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

## Playing locally

Two processes and one insert. Matchmaker in header-auth mode, the engine pointed at it:

```bash
# 1. matchmaker on 8080
AUTH_MODE=header mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 2. the engine on 8090, calling back as the game 'tictactoe-dev'
GAME_EXTERNAL_ID=tictactoe-dev mill -j 4 --ticker false engines.tictactoe.runMain com.vivi.tictactoe.LocalServer

# 3. register it as a game
psql "$DATABASE_URL" -v url="http://localhost:8090/games" -v external_id="tictactoe-dev" \
     -f engines/tictactoe/register-game.sql
```

Then the ordinary matchmaker flow: two players register, one creates a challenge on that game id,
the other accepts, the challenger starts it. Starting it makes matchmaker call `POST /games` here;
the `playUrl` on the match is what a player opens.

`MATCHMAKER_OFFLINE=true` runs the engine with nothing to call back to — the callbacks are printed
instead. Useful for working on the board itself.

## Who a player is

The board page signs in with **matchmaker's own user pool, app client and hosted-login flow** —
authorization code with PKCE, ID token in `sessionStorage`, the password only ever typed into
Cognito's pages. The engine then matches the token's `sub` against the `cognitoId` matchmaker sent
for each seat. Signing in to the board is signing in as the same player as in matchmaker.

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
| `HOSTED_LOGIN_URL` | Base url of the hosted login the page sends players to. |
| `PLAY_AUTH` | `gateway`, `verify` or `trusted`, overriding the choice above. |
| `MATCHMAKER_API_KEY` | The secret shared with matchmaker: required on `POST /games` and `GET /matches/{id}/status`, and sent on the callbacks. Optional locally, required in Lambda. |

## Deployed

`terraform/modules/tictactoe` puts it behind an API Gateway HTTP API with matches in DynamoDB and
three kinds of route: the matchmaker-facing ones (`POST /games`, `GET /matches/{id}/status`),
which require the API key matchmaker and this engine share, the player's (`state`, `moves`) under
a JWT authorizer on matchmaker's user pool, and the page shells open. The root module generates
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

Then register the game with the outputs — `create_game_url` as `url`, and `tictactoe_external_id`
(that is, `tictactoe`) as `external_id`. That name is what matchmaker files this engine's API key
under, and so is how it tells which engine a callback came from; a row whose `external_id` says
anything else has its callbacks refused. `./deploy-tictactoe.sh dev` prints the exact command.

## What this engine is not

One shortcut, deliberate:

- **Callbacks are best-effort.** They are sent after the move is committed and are not retried. A
  crash in between leaves matchmaker a move behind — which is exactly what its `refresh` (step 4)
  exists to repair, and is a state worth being able to produce on purpose.

The character fields of a seat (`characterId`, `characterState`) are accepted and ignored:
tic-tac-toe has nothing to carry in them. A character game's engine would read the state, and
write it back through matchmaker's character-state route.
