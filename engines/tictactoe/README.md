# tic-tac-toe

A two-player game engine implementing the game API of `interaction-design.txt`, so that
matchmaker's engine interaction can be developed and tested against something that actually plays
a game.

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

Step 1 answers with three urls: `statusUrl` for step 4, `playUrl` for the players, and — only for a
public game — `publicUrl` for anyone. Every move produces step 2; the move that ends the match
produces step 2 and then step 3.

One `playUrl` serves both players. It names the match and nobody in particular: the engine works
out whose seat it is from who signed in, so matchmaker can hand the same url to everyone in the
match and a url that leaks is not a seat that leaks.

The engine sends step 2 for the last move as well, with nobody in `next`. Matchmaker clears the
mover's pending flag from it, and the results that follow complete every seat.

Step 2 carries both times a move costs: `takenAt`, when it was made, and `startedAt`, when the
mover's own clock started — the move before it, in this game. Matchmaker used to infer that second
one, and no longer does: only an engine knows whether the inference holds, and in `engines/rps`,
where both players move at once, it does not.

## Playing locally

Two processes and one insert. Matchmaker in header-auth mode, the engine pointed at it:

```bash
# 1. matchmaker on 8080
AUTH_MODE=header mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 2. the engine on 8090, calling back as the game 'tictactoe-dev'
GAME_EXTERNAL_ID=tictactoe-dev mill -j 4 --ticker false engines.tictactoe.runMain com.vivi.tictactoe.LocalServer

# 3. register it as a game
psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8090/games" -v external_id="tictactoe-dev" \
     -f engines/tictactoe/register-game.sql
```

Then the ordinary matchmaker flow: two players register, one creates a challenge on that game id,
the other accepts, the challenger starts it. Starting it makes matchmaker call `POST /games` here;
the `playUrl` on the match is what a player opens.

`MATCHMAKER_OFFLINE=true` runs the engine with nothing to call back to — the callbacks are printed
instead. Useful for working on the board itself.

## Who a player is

The board page signs in with **matchmaker's own user pool and app client, running the same flow
matchmaker's UI runs** — email and password on the page itself, against Cognito's `InitiateAuth`
with `PREFERRED_CHALLENGE = PASSWORD`, an emailed code offered as the alternative, and sign-up and
password reset redirecting to Cognito's hosted pages and back through `/auth/callback`. Tokens live
in `sessionStorage`. The engine then matches the token's `sub` against the `cognitoId` matchmaker
sent for each seat. Signing in to the board is signing in as the same player as in matchmaker.

The code is copied from `SignIn.scala`, `Auth.scala` and `CognitoIdp.scala` in `matchmaker/ui`
rather than shared with them: the board is a self-contained document served by a Lambda with no
static hosting, and the UI is Scala.js. A change to the sign-in on either side belongs on both.

Three ways that identity is established, chosen by the environment and overridable with
`PLAY_AUTH`:

| mode | when | how |
|---|---|---|
| `gateway` | deployed | API Gateway's JWT authorizer verifies the token; the engine reads the `sub` claim. Chosen automatically inside Lambda. |
| `verify` | local, with a pool configured | The engine verifies the token itself against the pool's public JWKS — real tokens, really checked. Chosen when `COGNITO_ISSUER` is set. |
| `trusted` | local, zero setup | The caller names themselves with `?as=<sub>` or an `X-Player-Id` header. The local server prints a warning saying so. |

Two routes stay open in every mode: the play page and the sign-in callback. A browser navigation
cannot carry an `Authorization` header, so requiring a token there would make the board
unreachable rather than protected — the page is a shell that carries no game state for a caller
with no seat, signs the player in, and then fetches the board with their token.

Signing in locally needs the same three values matchmaker's UI is configured with, from its
terraform outputs:

```bash
COGNITO_ISSUER=$(cd terraform && ./tf.sh dev output -raw jwt_issuer) \
COGNITO_CLIENT_ID=$(cd terraform && ./tf.sh dev output -raw user_pool_client_id) \
HOSTED_LOGIN_URL=$(cd terraform && ./tf.sh dev output -raw hosted_login_url) \
GAME_EXTERNAL_ID=tictactoe-dev \
mill -j 4 --ticker false engines.tictactoe.runMain com.vivi.tictactoe.LocalServer
```

`http://localhost:8090/auth/callback` has to be a registered callback url on that app client for
the redirect to come back — deployed, the terraform adds the engine's own callback url for you.

### Environment

| variable | meaning |
|---|---|
| `BASE_URL` | The url the outside world reaches this engine on; every url handed to matchmaker is built from it. Defaults to `http://localhost:$PORT` locally, and is set by terraform when deployed. |
| `PORT` | Local server port. Default 8090. |
| `MATCH_TABLE` | DynamoDB table for matches. Unset means keep them in memory, which is right for the local server and wrong for Lambda. |
| `GAME_EXTERNAL_ID` | Sent as `X-External-Id` on the callbacks, which only a header-auth matchmaker reads. |
| `MATCHMAKER_OFFLINE` | `true` prints the callbacks instead of sending them. |
| `COGNITO_ISSUER` | Token issuer of the user pool players sign in to (matchmaker's `jwt_issuer`). Unset means the trusted local mode. |
| `COGNITO_CLIENT_ID` | App client the board page signs in with, and the audience a token must carry. |
| `HOSTED_LOGIN_URL` | Base url of the hosted login, used for sign-up and password reset. |
| `PLAY_AUTH` | `gateway`, `verify` or `trusted`, overriding the choice above. |
| `MATCHMAKER_API_KEY` | The secret shared with matchmaker: required on `POST /games` and `GET /matches/{id}/status`, and sent on the callbacks. Optional locally, required in Lambda. |
| `LIVE_URL`, `LIVE_ENDPOINT`, `LIVE_TABLE` | Play Live, deployed: the WebSocket url the page connects to, the stage url the engine pushes through, and the table of open connections. Set by the terraform; without `LIVE_URL` the page offers no switch. |
| `LIVE_PORT` | Play Live's port when run locally. Defaults to the engine's port plus 100. |

## Play Live

Every engine's play page and public board carry a **Play Live** switch. It is off by default, the
player turns it on, and the choice is remembered in the browser under the game's name. All of it is
in `engines.common` (`Live`, `PlayLive`, `LocalLiveServer`); a game gives it nothing.

- **Off**, the page polls its state every two seconds, as it always has.
- **On**, the page opens a WebSocket and is sent `{"changed":"<matchId>"}` whenever a successful
  player `POST` lands on the match — a move, or a route only one game has, like boxing's
  fighter. It answers by fetching its state through the ordinary `state` route, so nothing a seat
  would hide ever travels down the connection. It still checks once a minute in case a push was
  lost, sends a keep-alive every five minutes, and falls back to two-second polling while the
  connection is down, reconnecting with a growing pause.

A connection is admitted on the state route's terms: a seat in the match (`?match=…&token=…`, the
ID token in the url because a browser cannot put a header on a WebSocket), or a public match's
board (`?match=…&board=1`). Deployed there is no JWT authorizer on a WebSocket API, so the engine
verifies the token itself on connect.

Locally the engine serves Play Live on its own port, its HTTP port plus 100 — `ws://localhost:8190/live`
for this engine — since the JDK's HTTP server cannot upgrade a connection. The startup banner prints it.

## Deployed

`terraform/modules/engine` — the module every bundled engine is deployed with — puts it behind an API Gateway HTTP API with matches in DynamoDB and
three kinds of route: the matchmaker-facing ones (`POST /games`, `GET /matches/{id}/status`),
which require the API key matchmaker and this engine share, the player's (`state`, `moves`) under
a JWT authorizer on matchmaker's user pool, and the page shells open. Beside it, a WebSocket API for Play
Live, with its connections in a second table; the gateway answers the page's keep-alive itself, so
only a connect, a disconnect and a push cost a Lambda invocation. The root module generates
that key and gives it to both sides, so there is nothing to copy; it also adds the engine's
`/auth/callback` to the user pool client's callback urls.

Enable it from the root configuration:

```hcl
# environments/dev.settings.tfvars
deploy_tictactoe = true
```

```bash
mill -j 4 --ticker false engines.tictactoe.assembly
./terraform/tf.sh dev apply
```

`./deploy-tictactoe.sh dev` does that in one step, and `./deploy-all.sh dev` deploys matchmaker
and every enabled engine together in a single plan and apply.

Then add the game on matchmaker's admin page ("Add a Game"), or edit it if it is already there.
A deploy never creates a game, and `register-game.sql` is for the local database only — it refuses
any other. The deploy scripts print the urls; the form wants:

- **Name**: Tic-tac-toe
- **Game engine url**: the `tictactoe_create_game_url` output
- **Engine identity**: `tictactoe` — the name matchmaker files this engine's API key under, and so
  how it tells which engine a callback came from. A game naming anything else has its callbacks
  refused.
- **API key**: the key the engine was deployed with, which
  `./terraform/tf.sh <env> output -raw tictactoe_api_key` prints. Matchmaker stores it with the game and
  never shows it again; entering a new one on the game's edit form replaces it.
- **Type**: plain (leave "Requires characters" unticked)
- **Roles**: `X` and `O`, neither optional
- **Parameters**: none

## What this engine is not

One shortcut, deliberate:

- **Callbacks are best-effort.** They are sent after the move is committed and are not retried. A
  crash in between leaves matchmaker a move behind — which is exactly what its `refresh` (step 4)
  exists to repair, and is a state worth being able to produce on purpose.

The character fields of a seat (`characterId`, `characterState`) are accepted and ignored:
tic-tac-toe has nothing to carry in them. A character game's engine would read the state, and
write it back through matchmaker's character-state route.
