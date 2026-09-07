# rock-paper-scissors

A two-player game engine implementing the game API of `interaction-design.txt`, and the second one
in `engines/`. Where `tictactoe` is a game of alternating turns, this one has no turn order at
all: **both seats are on the clock from the moment the match is created, either player may throw
first, and the match resolves the instant the second throw lands.**

That is the whole reason it exists. Matchmaker has always modelled simultaneous play — its move
callback takes a *list* of seats to make pending, its status answer may report several, and
`MatchSummary.whoseTurn` is a list of names — and nothing exercised any of it. This engine does.

It is not part of matchmaker. Nothing in `engines/` is on matchmaker's classpath and nothing in
matchmaker is on this module's — an engine is a separate system reached over HTTP, and the wire
types are restated here rather than imported precisely so that a rename on one side fails a test
instead of quietly compiling. That test is `ProtocolSpec`, the only place the two are compared.

## The four exchanges

| step | who calls whom | here |
|---|---|---|
| 1. create a game | matchmaker → engine | `POST /games` |
| 2. a player moved | engine → matchmaker | posts `MoveNotification` to the url matchmaker sent |
| 3. the match ended | engine → matchmaker | posts `MatchResults` to the url matchmaker sent |
| 4. how is it going | matchmaker → engine | `GET /matches/{id}/status` |

Step 1 answers with three urls: `statusUrl` for step 4, `playUrl` for the players, and — only for
a public game — `publicUrl` for anyone. One `playUrl` serves both players: it names the match and
nobody in particular, and the engine works out whose seat it is from who signed in.

### What a simultaneous game does differently

- **`next` is always empty.** Both seats are pending from creation. Matchmaker clears the seat
  that moved and leaves alone any participant it was told nothing about — so the seat still to
  throw stays pending by *not* being named. Naming it would re-stamp its clock as starting at the
  other player's throw, and here nobody is waiting for anybody.
- **Status reports both seats pending**, and each seat's `prevMoveAt` is the match's creation
  however late either player throws. A player who takes an hour spends an hour of their own
  budget; the other player's deadline does not move.
- **The results follow the second throw**, whichever throw that turns out to be.
- **The move callback's `startedAt` is the match's creation**, for both seats. Matchmaker takes
  it as stated rather than inferring it from the move before — an inference that would bill the
  second thrower only for the time since the first threw. It is what makes a chess-clock limit
  chargeable in a game where nobody waits for a turn.

## Not seeing the other throw

The rule that makes this a game rather than a formality: **no seat is told what the other threw
until both have thrown.** It is enforced in `Engine.stateOf`, not in the page — a page that
filtered what it rendered would be hiding a throw from the one person able to open the network
tab.

Until a match resolves, a seat view carries `thrown` (that a player has moved) and nothing else.
The viewer's own throw comes back as `yourThrow`, because a player may certainly see their own.
Both throws appear, to players and to the public board alike, only once `completed` is true.

## Playing locally

Two processes and one insert — matchmaker in header-auth mode, the engine pointed at it. The port
is 8091 here so this engine and `tictactoe` can be run side by side.

```bash
# 1. matchmaker on 8080
AUTH_MODE=header mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 2. the engine on 8091, calling back as the game 'rps-dev'
PORT=8091 GAME_EXTERNAL_ID=rps-dev mill -j 4 --ticker false engines.rps.runMain com.vivi.rps.LocalServer

# 3. register it as a game
psql "$DATABASE_URL" -v url="http://localhost:8091/games" -v external_id="rps-dev" \
     -f engines/rps/register-game.sql
```

Then the ordinary matchmaker flow: two players register, one creates a challenge on that game id,
the other accepts, the challenger starts it. Starting it makes matchmaker call `POST /games` here;
the `playUrl` on the match is what a player opens. Both players will find the match in their
**due** list at once, which is the thing to look at.

`MATCHMAKER_OFFLINE=true` runs the engine with nothing to call back to — the callbacks are printed
instead. Useful for working on the play page.

A throw is posted as `{"shape":"rock"}`; `paper`, `scissors`, and the initials `r`, `p`, `s` are
accepted too, in any case. Anything else is a 400 that says what a throw is called.

```bash
curl -s -X POST "http://localhost:8091/matches/$MATCH/moves?as=$SUB" \
     -H 'content-type: application/json' -d '{"shape":"r"}'
```

## Who a player is

Exactly as in `tictactoe`, and deliberately unchanged: the play page signs in with **matchmaker's
own user pool, app client and hosted-login flow** — authorization code with PKCE, ID token in
`sessionStorage` — and the engine matches the token's `sub` against the `cognitoId` matchmaker
sent for each seat. The three modes (`gateway`, `verify`, `trusted`), what chooses between them,
and the two routes that stay open in all of them are described in `engines/tictactoe/README.md`
under "Who a player is"; `PLAY_AUTH` overrides the choice here in the same way.

Signing in locally needs the same three values matchmaker's UI is configured with:

```bash
COGNITO_ISSUER=$(cd terraform && ./tf.sh dev output -raw jwt_issuer) \
COGNITO_CLIENT_ID=$(cd terraform && ./tf.sh dev output -raw user_pool_client_id) \
HOSTED_LOGIN_URL=$(cd terraform && ./tf.sh dev output -raw hosted_login_url) \
PORT=8091 GAME_EXTERNAL_ID=rps-dev \
mill -j 4 --ticker false engines.rps.runMain com.vivi.rps.LocalServer
```

`http://localhost:8091/auth/callback` has to be a registered callback url on that app client for
the redirect to come back.

### Environment

The same variables as `tictactoe`, with the same meanings: `BASE_URL`, `PORT` (default 8091 when
unset here), `MATCH_TABLE`, `GAME_EXTERNAL_ID`, `MATCHMAKER_OFFLINE`, `COGNITO_ISSUER`,
`COGNITO_CLIENT_ID`, `HOSTED_LOGIN_URL`, `PLAY_AUTH`, `MATCHMAKER_API_KEY`.

## Deployed

`terraform/modules/rps` puts it behind an API Gateway HTTP API with matches in DynamoDB and three
kinds of route: the matchmaker-facing ones (`POST /games`, `GET /matches/{id}/status`), which
require the API key matchmaker and this engine share, the player's (`state`, `moves`) under a JWT
authorizer on matchmaker's user pool, and the page shells open. The root module generates that key
and gives it to both sides, so there is nothing to copy; it also adds this engine's
`/auth/callback` to the user pool client's callback urls.

Independent of the tic-tac-toe engine in every way that matters — its own function, table, api and
key — so either may be deployed without the other.

Enable it from the root configuration:

```hcl
# environments/dev.settings.tfvars
deploy_rps = true
```

```bash
./deploy-rps.sh dev
```

That builds the jar (running this engine's tests first), plans just `module.rps` plus the Cognito
app client whose callback urls the engine changes, and applies that plan. `--full` plans the whole
environment instead, `--skip-build` reuses the jar already in `out/`, and `--yes` skips the
confirmation.

Then register the game with the outputs — `rps_create_game_url` as `url`, and `rps_external_id`
(that is, `rps`) as `external_id`. That name is what matchmaker files this engine's API key under,
and so is how it tells which engine a callback came from; a row whose `external_id` says anything
else has its callbacks refused. `./deploy-rps.sh` prints the exact command at the end.

## What this engine is not

The same deliberate shortcut as `tictactoe`:

- **Callbacks are best-effort.** They are sent after the throw is committed and are not retried. A
  crash in between leaves matchmaker a move behind — which is exactly what its `refresh` (step 4)
  exists to repair.

And one of its own:

- **One throw each, no best-of-three.** The match is a single pair of throws, and a draw is a
  result rather than a replay. Rounds would be the natural thing to add — `parameters` is where
  matchmaker would send how many — but a game whose whole point is the simultaneous turn does not
  need three of them to make it.

The character fields of a seat (`characterId`, `characterState`) are accepted and ignored.
