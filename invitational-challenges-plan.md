# Plan: Invitational Challenges

A challenge is open or it is not (`challenge.is_open`), and an `invitation` row says who may
accept one that is not — optionally naming the role they are being asked to play. The two are
separate facts on purpose: whether anybody may join is the challenger's policy, and who has
been asked is a list that grows a row at a time.

Written against the code as of `59fa78a`.


## Phase 1 — The rename (mechanical, no behaviour change)

Best done and committed on its own: it touches around 35 files, and a real bug from the phases
after it would be invisible inside that diff.

### `V21__rename_challenge.sql`

```sql
ALTER TABLE open_challenge RENAME TO challenge;
ALTER TABLE character_open_challenge RENAME TO character_challenge;
```

Indexes, constraints and the `trg_open_challenge_update_date` trigger follow the table
automatically but keep their old *names*. Rename the trigger too — V1 builds it from the table
name, so a future re-run of that loop would otherwise make a second one — and leave the
auto-named indexes alone, with a comment saying why.

### Scala

| from | to |
| --- | --- |
| `OpenChallenge` | `Challenge` |
| `PlainOpenChallenge` | `PlainChallenge` |
| `CharacterOpenChallenge` | `CharacterChallenge` |
| `OpenChallengeSummary` | `ChallengeSummary` |
| `OpenChallengeRepo` | `ChallengeRepo` |
| `OpenChallengeService` | `ChallengeService` |

`Services.challenges` already reads right. Files: the model, `ChallengeRepo`,
`AcceptanceRepo`, four services, `Json.scala`, `Router`, `Store`/`Main`/`ApiClient`, and nine
specs.

One consequence worth stating: upickle's `ReadWriter.merge` discriminates on the **class
name**, so `$type` on the wire changes from `PlainOpenChallenge` to `PlainChallenge`. The UI
and the API ship together and nothing persists that JSON, so this is safe — but `JsonSpec` and
`WireFormatSpec` assert it and will need updating, and a browser holding an old bundle will get
400s until it reloads.

Nothing renames in the URL space: the routes are already `/challenges/...`.


## Phase 2 — `is_open` and the `invitation` table

### `V22__invitational_challenge.sql`

```sql
ALTER TABLE challenge
    ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT TRUE;

-- The browse query asks for one game's open challenges and nothing else, so the rows that are
-- not open do not belong in the index at all: a partial index is smaller, and it stays small
-- as invitational challenges accumulate.
CREATE INDEX challenge_open_by_game ON challenge (game_id) WHERE is_open;

CREATE TABLE invitation (
    game_id      INT    NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    player_id    BIGINT NOT NULL REFERENCES player,
    -- The role they are being asked to play, when the challenger cares which. NULL is "any seat
    -- that is still free", which is what an invitation to a game of equals means.
    game_role_id INT,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, player_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role
);

-- The primary key leads with game_id, so it does not serve "every invitation addressed to me",
-- which is what the home page asks on every sign-in.
CREATE INDEX invitation_player ON invitation (player_id);
```

Add `'invitation'` to V1's `update_date` trigger loop (a fresh `CREATE TRIGGER` in this
migration, since that loop has already run).

One invitation per player per challenge, which the composite key is: a second row would be a
second answer to one question. A player may be invited to any number of *different* challenges.

`DEFAULT TRUE` on `is_open` because that is what every challenge that already exists has been
doing.

### Model

- `isOpen: Boolean = true` on the `Challenge` trait and both cases.
- `case class Invitation(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId, gameRoleId: Option[GameRoleId])`.
- `case class ChallengeInvitation(invitation: Invitation, challengerNickname: String, gameName: String, roleName: Option[String], message: String)` — what the home-page section draws, since it has no per-game context to look names up in.

### `InvitationRepo`

A repo of its own, beside `AcceptanceRepo`, whose shape it mirrors: `create`, `read(gameId,
challengeId, playerId)`, `delete`, `deleteAllForChallenge`, `listForChallenge`,
`listForPlayer(playerId)` (joined to challenger nickname, game name and role name),
`reservedRoles(gameId, challengeId)` returning `(GameRoleId, PlayerId)` pairs.

`deleteAllForChallenge` is called from `ChallengeService.delete` alongside the acceptances —
the FK would refuse the delete otherwise, and a cascade would hide that the rows exist.

### `ChallengeRepo`

`is_open` into `insertChallenge`, `updateChallenge`, `selectChallenge` and
`selectChallengesByGame`, with the tuple types and `toChallenge` following. `listByGame` gains,
in its WHERE:

```sql
AND (c.is_open
     OR c.challenger = $playerId
     OR EXISTS (SELECT 1 FROM invitation i
                 WHERE i.game_id = c.game_id AND i.challenge_id = c.challenge_id
                   AND i.player_id = $playerId))
```

A challenge nobody may accept is nobody else's business — the same reasoning already written
there for a challenge that is full. It is worth checking with `EXPLAIN` whether this keeps the
partial index usable for the common case; if the planner will not use it behind the `OR`, the
honest fix is two queries unioned rather than an index that looks used and is not.

`ChallengeSummary` gains `invitations: Seq[Invitation]` so a row can say who has been asked and
which seats are spoken for, the way `takenRoles` already says which are gone.

### `ChallengeService.create`

`create` accepts the challenge together with its initial invitations, and writes all of it in
the one transaction it already opens: creating a challenge for somebody in particular is one
act, and two calls would leave a window where an invitational challenge exists that nobody may
accept. Each invited player is read `readForShare` (the insert references the row) and each
named role must be one of the game's, checked here so a wrong one is a 400 rather than a
constraint violation surfacing as a 500.

A challenge with `isOpen = false` and no invitations is refused: nobody could ever accept it.

### `ChallengeService.invite`

`invite(gameId, challengeId, invitee, gameRoleId, callerExternalId)` — the challenger's own
transaction, taking the challenge's `FOR UPDATE` lock first, like every other write to it, and
refusing one already being started. Only the challenger may invite. A role named here must be
free: reserved for nobody else and not already accepted.

### `ChallengeService.accept` — the two new checks

Both under the challenge's existing `FOR UPDATE` lock, which is what makes them race-free
against a concurrent accept, invite or reject:

1. **May this player accept at all.** If `NOT is_open`, an invitation for the acceptor must
   exist, or `UnauthorizedError`. Resolved against the acceptance's `playerId`, so a character
   game's invitation is answered by the character's **owner**: the invitation is to a person,
   not to a character.
2. **Is this the right seat.** If their invitation names a role, the acceptance must name that
   role — a `ValidationError` naming the role they were asked for. If it names none, they pick
   from what is free, which is the check that is already there.

And one addition to the existing free-role check, which applies to open challenges too: a role
reserved by an invitation to *somebody else* is not free. Without this, `role_id` on an
invitation means something on a closed challenge and nothing on an open one — and a challenger
who asked their friend to play the defender and left the rest open would have the seat taken by
a passer-by.

### `ChallengeService.reject`

`reject(gameId, challengeId, callerExternalId)` deletes the caller's own invitation and nothing
else. The challenge survives: its other invitees may still accept, and the challenger may
invite somebody else. A challenge whose last invitation is rejected is left there for its
challenger to deal with — they are told (see phase 3), and deleting their challenge out from
under them on somebody else's decision is not ours to do.

Same shape as `delete`: locked read first, a started challenge is a 409.

`revoke(gameId, challengeId, playerId, callerExternalId)` is the challenger's mirror of it —
worth having, since a challenger who mis-typed an invitation otherwise cannot take it back, and
it is the same delete with the other authorization.


## Phase 3 — Notifications

Three new kinds, in `NotificationType` after `AcceptedChallengeReady` — the order is the order
the columns bind in and the order the forms list, so a new kind goes where a player meets it:

- `InvitationReceived` — you have been invited to a challenge
- `InvitationAccepted` — somebody accepted your invitation
- `InvitationRejected` — somebody turned your invitation down

`InvitationReceived` goes to the invitee; the other two to the challenger.

### `V23__invitation_notifications.sql`

Three columns on each of four tables — `player`, `participant`, `player_game` nullable;
`game` `NOT NULL DEFAULT TRUE` — following V13's comments and its reasoning.

### Then, positionally

`NotificationPreferences.apply`/`updated`/`complete`, `NotificationDefaults.apply`,
`SkunkCodecs.notificationPreferences` (8 → 11 positions), and every column list in
`NotificationRepo` (79 mentions), `GameRepo` (13) and `ParticipantRepo` (20).

The UI forms and `differences`/`unsaid` derive from `NotificationType.values` and need no
change at all. That is the payoff of how this was built.

### Mail

`ChallengeMail` gains the three kinds; `messages.properties` gains three subject/body pairs.
`ChallengeNews` needs the invitee's and the challenger's nicknames and the role, when the
invitation named one — "Ada has asked you to play the defender" is the whole of what that mail
is for. `InvitationReceived` is the one of the three that wants a link, and it points at the
home page: there is nothing to play yet.

### `Notifications`

`invitationMade`, `invitationAccepted`, `invitationRejected`, each reading what it needs for
itself and called after the commit, per the contract at the top of that class. An invitation
created *with* its challenge sends one `InvitationReceived` per invitee.

`accept` then chooses: an acceptance by an invitee sends `InvitationAccepted` to the challenger
in place of `ChallengeAccepted`, and still suppresses it when the acceptance auto-started the
match — the existing `isMatch` guard already covers that. Everyone else in the challenge gets
the ordinary `AcceptanceChanged` they get today; being invited is between the challenger and
the invitee.


## Phase 4 — API

Each route in all three places (`Router`, `local.routes` in `terraform/modules/api/main.tf`,
and `routed` in `RouterSpec` with its count):

- `GET /me/invitations` → `challenges.invitationsFor(caller)`
- `POST /challenges/{gameId}/{challengeId}/invitations` → `invite` (body: player id, optional role)
- `DELETE /challenges/{gameId}/{challengeId}/invitations/{playerId}` → `reject` when the caller is that player, `revoke` when they are the challenger. One route because it is one fact being removed; the service picks the authorization from who is asking, and refuses anybody else.

Creating a challenge with invitations needs no new route: the invitations ride on the existing
`POST /challenges` body. That means a request wrapper — `CreateChallenge(challenge,
invitations)` — rather than a bare `Challenge`, which changes the body shape of an existing
route and so touches `JsonSpec`, `WireFormatSpec` and the UI together.


## Phase 5 — UI

**`Store`** — `invitations: Var[Seq[ChallengeInvitation]]`, `Fetch.Invitations`, loaded with
the other home-page lists on sign-in, cleared in `sessionExpired`, plus `reloadInvitations()`.

**Home page** — an `invitationsSection` above `readyToStartSection`, absent entirely when
empty, like "Ready to Start" and for the same reason given there. A row carries the game name,
the challenger's nickname, the message, the role if the invitation named one, and Accept /
Reject. Accept reuses `ApiClient.accept` — with the named role when there is one, and with a
role picker when there is not. Both buttons reload the invitations and the acceptance sections.

**Player page** — an "Invite to a challenge" control per game row, which needs the caller's own
unstarted challenges in that game. Those are what `listByGame` already returns, so the player
page fetches `challengesByGame` for the game being invited in, on the button press rather than
on load, and offers:

- each of the caller's own challenges in that game, with an optional role from what is free — one `POST .../invitations`;
- "New challenge" — the existing `newChallengeForm`, which gains an `invitee: Option[PublicPlayer]`. With one set it shows the name as a heading line rather than a field (the invitee is not a thing to edit on a form you reached by pressing Invite on their page), offers a role for them beside the challenger's own, and defaults `isOpen` to false and `autoStart` to on, so a two-player invitation starts the moment it is accepted.

Character games need the caller's characters for that game, which the same button press fetches.

Mobile and accessible as always: 44px targets on Accept/Reject, a live region on the
invitations list, a real label on every control the form adds.

`app.css` needs nothing new — the invitation rows use the existing `.row` and `.detail`.


## What this design does that the per-challenge `opponent` column did not

- A four-player game works: invite three people, or invite one and leave the rest open.
- "You play the defender" and "take whatever seat you like" are both sayable, per invitee.
- Inviting somebody to a challenge that already exists is the same row as inviting them to a
  new one, so the player page's two offers share their whole implementation below the form.
- `is_open` is one column that answers "may a stranger join", independent of who has been
  asked — so an open challenge can carry invitations (a nudge to a friend, seat reserved) and a
  closed one can be re-opened without touching the invitation list.


## Tests

- **`InvitationRepoSpec`** — round-trip with and without a role; the composite key refuses a second invitation for one player; `listForPlayer` spans games and carries the names; `deleteAllForChallenge`; a FK refuses an unknown role.
- **`ChallengeRepoSpec`** — `is_open` round-trips; `listByGame` hides a closed challenge from a stranger and shows it to the challenger and to an invitee; the partial index is used (`EXPLAIN`, `enable_seqscan = off`).
- **`ChallengeServiceSpec`** — a stranger cannot accept a closed challenge; an invitee can; an invitee whose invitation names a role is refused any other role and accepted into that one; an invitee with no role named picks a free one; a role reserved for somebody else is not free, on an open challenge as well as a closed one; only the challenger may invite; invite on a started challenge is 409; reject deletes only the invitation, and the challenge and its other invitations survive; a non-invitee's reject is 404; revoke works for the challenger and for nobody else; `isOpen = false` with no invitations is refused at create.
- **`ChallengeNotificationSpec`** — the three new kinds, each suppressed by each of the four preference levels; an acceptance by an invitee sends `InvitationAccepted` and not `ChallengeAccepted`; a challenge created with three invitations sends three mails.
- **`RouterSpec`**, **`JsonSpec`**, **`WireFormatSpec`**.
