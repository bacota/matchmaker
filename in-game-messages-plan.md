# Plan: In-Game Messages over Play Live

Players, and the people watching a public match, can write to each other while the match is being
played. **Messages travel over the Play Live websocket and are not stored.** A page sees what is
said while it is connected and nothing from before. [Persisting them](#extension-persisting-messages-for-replay)
is written up below as an extension that leaves the delivery path as it is.

Written against the code as of `4bcbaf0`. Not scheduled: this records the design so it is not
re-derived.

"Live" here means **Play Live**, the websocket push (`Live`, `PlayLive`, `LocalLiveServer`). It does
not mean the live-match turn clock (V27, `TurnClock`, `LiveTerms`). Any match whose page has a Play
Live connection gets the boards, clock or no clock. Name the code after Play Live.


## What it reuses

The branch `message-boards` (`4d929ce`, never merged) built persisted boards: a DynamoDB messages
table, `GET`/`POST /matches/{id}/messages`, and an open `GET /matches/{id}/board/messages`.

- **Keep:** `MessageBoards`, the page side. The panels, the three-column layout, the CSS (16px
  textarea, 44px buttons, visible focus), `role="log"` lists that are appended to rather than
  redrawn, and messages drawn with `textContent`, never as markup. Also keep `MessageBoard`,
  `Viewer` and `MessageRules`.
- **Drop:** `MessageStore`, `InMemoryMessageStore`, `DynamoDbMessageStore`, the messages table, all
  three HTTP routes, and the page's `refreshMessages` fetch.

That branch's page re-read every board on every change: `keepCurrent` calls `refreshMessages()`
for each viewer on each move, each message and each minute's check. So its read cost grew with
watchers × board size. Nothing in this design re-reads a board.


## The boards and who may use them

Two boards per match, as on the branch:

| Board | Written by | Read by |
|---|---|---|
| Players' | the players | the players, and anyone watching if the match is public |
| Observers' | signed-in non-players, public matches only | non-players only |

On the branch, players could read the observers' board once the match was over, so that nobody
could be coached mid-match. With nothing stored, nothing is left to read afterwards, so the rule
becomes **players never see the observers' board**. `MessageRules.canRead` loses its `over`
argument.

Both boards close when the match completes: a `say` is refused once the match is over.

Anonymous watchers of the public board read the players' board but cannot write.


## How a message gets from one player to another

Nothing is stored. The message exists in the engine function's memory for the one invocation that
fans it out. The only state is the list of connections that Play Live already keeps.

1. **The browser sends it up its websocket.** Its frame is
   `{"action":"say","board":"players","text":"good move"}`. Being connected is the only way to
   speak, so "only while connected" is how it works, not a rule the UI has to enforce.
2. **API Gateway routes it to the engine function.** The live API already selects routes by
   `$request.body.action`. A new `say` route goes to the same integration as `$connect` and
   `$disconnect`. The invocation carries the text and the sender's connection id.
3. **The function finds out who is speaking and who may hear them.**
   - It looks up the sender's connection row (`GetItem` by connection id), which has their match,
     viewer kind, subject and name (see [Identity is settled at connect](#identity-is-settled-at-connect)).
   - It checks `MessageRules` and the length cap, and that the match is not over.
   - It finds the match's connections through the `byMatch` index, which is the same lookup a move
     makes to push "changed".
4. **It pushes the message to every connection allowed to read that board.** It uses
   `ApiGatewayChannel.send` (`POST @connections/{id}`), with `Live`'s existing fan-out, `fanOut`
   limit and deadline. A 410 means the connection is gone; it is skipped.
5. **The function returns.** No copy is left on the server. A page that was not connected at that
   moment never sees the message, and a failed send is not retried, since there is nothing to retry
   from.

Locally it is the same shape in one process: `LocalLiveServer` holds the sockets,
`InMemorySubscriptions` the connections, and `send` writes to the socket.


## Identity is settled at connect

Today `connect` checks the seat and then forgets it: `Subscription` is `(connectionId, matchId)`.
It gains what a `say` needs, so that a message costs one `GetItem` rather than a token check and a
matchmaker call:

```scala
case class Subscription(
    connectionId: String,
    matchId: String,
    viewer: Viewer,               // Player, Observer or Anonymous
    subject: Option[String],      // never sent to a page; marks "mine"
    name: Option[String]          // the matchmaker nickname, looked up once, at connect
)
```

- **A player** connects as now, with `?token=` checked against their seat.
- **A signed-in watcher** of a public match connects with `?board=1&token=…`. Today `board=1`
  ignores any token; it should verify one when given, and record an Observer.
- **Without a token**, `?board=1` records Anonymous, as now.

The token is checked once, when connecting. A connection lasts at most two hours (API Gateway
closes it after that), and a connection admitted for two hours is already how the gateway treats
it.

`DynamoDbSubscriptions` writes the new attributes. Rows written before the change lack them;
treat a missing viewer as Anonymous, which can read but not write.


## The wire

Up (page → function):

```json
{"action":"say","board":"players","text":"good move"}
```

Down (function → each permitted page), one push per connection:

```json
{"message":{"id":"…","board":"players","at":"2026-10-03T21:04:05Z","name":"Ann","text":"good move","mine":false}}
```

- `mine` is worked out for each connection by comparing the stored subjects, so a subject never
  reaches a page.
- `id` and `at` are the server's. The fan-out sends side by side, so pushes can arrive out of
  order. The page inserts by `at` and drops an `id` it already has.

To the sender's own connection, on a refusal:

```json
{"refused":{"board":"players","error":"the match is over; its message boards are read-only"}}
```

The page shows it in the form's `role="alert"` line. The sender's own message comes back to them
by the ordinary fan-out, which is how the page knows it was sent.

### This changes a Play Live rule

`Live`'s doc says nothing goes down a connection except `{"changed":"<matchId>"}`, so that a push
can never leak what a seat hides. A message is content. The rule's purpose still holds, because
who receives a message is decided by `MessageRules` at fan-out, from the viewer kind recorded at
connect. Rewrite the doc to say that: a push is either the fact of a change, or a message that
`MessageRules` lets that connection read.


## Presence (optional)

An unstored message is wasted on an empty room. The connections table already knows who is there,
so on connect and disconnect the function can push this to the match's connections:

```json
{"present":{"players":["Ann"],"watchers":3}}
```

The players' panel then says "Your opponent is here", or "Your opponent isn't connected; they
won't see messages sent now". The cost is one more fan-out per connect and disconnect, with
nothing stored.


## Limits and cost

- **Length:** 500 characters, checked by the function. This also keeps a push well inside API
  Gateway's 32 KB billing unit.
- **Rate:** the live stage's `default_route_settings` throttle (burst 100, rate 50) covers the
  whole stage, not one client. Give `say` its own route throttle. Without storage there is nowhere
  shared to count one sender's messages. If a per-sender cap is ever wanted, it is the counter row
  in the extension below.
- **Cost per message:** one `GetItem` on the sender's row, one `byMatch` query (about 100 bytes per
  watcher), and **one API Gateway send per recipient**. The sends are by far the largest part. A
  message on a crowded public board costs about as much as a move does today. Consider a cap on how
  many watchers the observers' board fans out to.


## Changes by place

**`engines/common`**
- `Live.scala`: `Subscription` gains viewer, subject and name, and `DynamoDbSubscriptions` reads and
  writes them. Add `Subscriptions.get(connectionId)`, and a `Live.say` that fans a message out per
  board. Rewrite the doc about what may be pushed.
- `EngineRoutes.scala`: `connect` records the viewer, verifies a token given with `?board=1`, and
  looks up the name. The `MESSAGE` case, which answers `{}` today, dispatches on the body's
  `action`: `say`, and anything else ignored.
- `LocalLiveServer.scala`: `read` drops text frames (`case _ => ()`). Hand opcode `0x1` to the
  routes as `MESSAGE` with the payload as the body, and send the answer, if any, back down the
  socket. `maxFrame` (64 KB) is already big enough.
- `MessageBoards.scala` from the branch: replace the fetch and the POST with the socket. The form
  shows only while the page's Live switch is on and connected. A note under the list says
  "Messages aren't saved: you'll only see what's said while you're here."
- `PlayLive.scala`: route an incoming push by its key (`changed`, `message`, `refused`, `present`).
- `MessageRules`: drop `over` from `canRead`.

**Each engine's `Html`/`Routes`:** wrap `<main>` in the board layout, as the branch did for all
four games.

**`terraform/modules/engine/main.tf`**
- A `say` route on `aws_apigatewayv2_api.live`, to the existing integration, with its own
  throttle.
- `dynamodb:GetItem` on the connections table, in the Play Live policy.
- No messages table, and none of the branch's HTTP routes.

**Matchmaker:** only the name lookup the branch added to `PlayerService`, if names come from
matchmaker. There is no new public route, so `Router` and `local.routes` are unchanged.


## Tests

- `MessageRules`: the read and write table above, every viewer against every board, public and
  private, over and not over.
- `Live.say` with `InMemorySubscriptions` and a recording channel: who receives each board, `mine`
  per connection, refusals sent only to the sender, a 410 skipped, and the deadline honoured.
- `connect`: a token with `?board=1` records an Observer, one without records Anonymous, and an
  invalid token is refused.
- `LocalLiveServer`: a text frame reaches the routes, and the reply comes back down the socket.
- A refusal test that makes something fail on purpose is tagged `Quiet`.


## Extension: persisting messages for replay

If messages are wanted in replay, they can be kept without adding a measurable cost to any game,
however chatty its players. **The delivery path above does not change.** Persistence is a write on
the side and a read at connect and at archive time.

1. **One small row per message, during the match.** A messages table keyed by match id, sorted by
   time plus message id. One `PutItem` under 1 KB is one write unit. The push still carries the
   message, so nobody reads the table to see it.
2. **History is read only when a page connects.** One eventually-consistent `Query` with `Limit`
   (the last 50). That is a couple of read units however long the match has run, and it closes the
   reconnect gap.
3. **A hard cap per player.** A counter row per (match, player). Each `say` makes a conditional
   `UpdateItem` (`ADD sent 1` if `sent < cap`, e.g. 300), which is one write unit, and is refused at
   the cap. It sits in the table rather than on the connection row, so reconnecting does not reset
   it. A match's chat is then at most about 2 × cap × 2 write units, around a tenth of a cent with
   a cap of 300.
4. **Archived with the match.** When the engine builds the archive (`ArchivingMatchStore`, which
   does `write(m)` today), it reads the messages once with a consistent `Query`, so a message sent
   just before the last move is included. It uploads `{match, messages}`, with `formatVersion`
   bumped and the reader accepting both shapes. The match JSON inside is unchanged byte for byte,
   as the engine refactor requires. The archive keeps its single upload and confirm, its expiry and
   its bucket choice by `friendly`, so a friendly match's chat expires with its archive at 30 days.
5. **Cleaned up for free.** Each row carries a TTL (say 90 days). TTL deletes cost nothing, and a
   capped match's chat is about 300 KB, which is negligible storage.
6. **Anchored to moves for replay.** Stored moves have no timestamps, so each message records the
   move number its sender's page was showing (`MoveState.sequence`). Replay shows the message when
   it reaches that move. The page supplies the number and the server checks only that it is a
   number, because checking it properly would mean reading the match item on every message. A wrong
   number only misplaces the sender's own message.
7. **Who sees what in replay.** A finished match hides what play hid (see the replay design). The
   observers' board was kept from players only to stop coaching, so in replay of a finished match
   players can see it as well.

Ruled out:
- **Messages inside the match item:** every move would rewrite a growing item, so a chatty match
  would make every move cost more. Items are also capped at 400 KB.
- **S3 while the match is live:** there is no append. Each message would mean reading the whole
  object and writing it back, and two people sending at once would overwrite each other. A PUT
  also costs about 12 times a GET.
- **A whole-board read on every change, as the branch did:** this is what made the cost grow with
  watchers × board size.
