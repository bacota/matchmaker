# Matchmaker UI

Scala.js and Laminar, following the outline in `ui.txt`. It is an ordinary client of the HTTP API:
it holds a Cognito ID token and sends it as a bearer token, and has no privileges beyond what that
token gets it.

There are two ways to run it, and you need both: **local mode** for a fast click-through loop with
no AWS, and **dev mode** to exercise the one thing local mode cannot fake — hosted login itself.

## Local mode: no AWS, no Cognito

Everything below API Gateway is the real code; only the transport and the caller's identity are
replaced. Three terminals, or background the first two.

```sh
# 1. Migrations, once.
FLYWAY_URL=jdbc:postgresql://localhost:5432/matchmaker \
FLYWAY_USER=matchmaker FLYWAY_PASSWORD=matchmaker ./flyway.sh

# 2. The API on :8080, against local Postgres.
mill matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 3. The UI on :5173.
mill matchmaker.ui.fastLinkJS
ln -sf ../../out/matchmaker/ui/fastLinkJS.dest/main.js matchmaker/ui/main.js
python3 -m http.server 5173 --directory matchmaker/ui
```

Open <http://localhost:5173/index.local.html> — note the filename; `index.html` is the deployed
configuration and will try to reach Cognito. You should see a yellow banner reading *local mode —
signed in as local-dev-1, no authentication*, then the nickname form, then the app.

**To be a second player** — which accepting a challenge needs, since you cannot accept your own —
copy `index.local.html`, change `localExternalId`, and open it in a different browser profile.
Same browser, different profile: `sessionStorage` and `localStorage` are per-profile.

### What local mode actually is

`authMode: "header"` means **authentication is off**. The UI sends `X-External-Id` and
`LocalServer` believes it, so anything that can reach the port can be any player. Three things
keep that contained, and all three matter:

- `LocalServer` binds to `127.0.0.1`, not to every interface.
- Its CORS allows only loopback origins (`LOCAL_CORS_ORIGINS` to change them), and echoes back the
  one that matched rather than `*`.
- The deployed function will not accept the mode at all: `Handler` defaults to `AUTH_MODE=gateway`
  and the terraform sets it explicitly, so a stray `index.local.html` pointed at a real API gets
  401 from the gateway before any code runs.

### Two things to expect

- **`LocalServer` shares the database `mill matchmaker.test` uses.** `/games` comes back with
  hundreds of ScalaCheck-generated games. Harmless, but noisy; separating them means a different
  `DB_NAME` default and a second `flyway.sh` run.
- **Local mode never touches `Auth` or `Pkce`.** Sign-in, the code exchange, token expiry and the
  `Authorization` header are all skipped, so nothing here can tell you whether they work.

## Dev mode: the real pool

```sh
# 1. Deploy, if you have not.
mill matchmaker.api.assembly
cd terraform/environments/dev
cp terraform.tfvars.example terraform.tfvars   # RDS, subnets, SGs, secret, domain prefix
terraform init && terraform apply

# 2. Read off the three public values the UI needs.
terraform output    # api_endpoint, hosted_login_url, user_pool_client_id

# 3. Paste them into index.html's config block, then link and serve as above.
mill matchmaker.ui.fastLinkJS
ln -sf ../../out/matchmaker/ui/fastLinkJS.dest/main.js matchmaker/ui/main.js
python3 -m http.server 5173 --directory matchmaker/ui
```

Then open <http://localhost:5173/>. `http://localhost:5173/` is already in the dev pool's
`callback_urls` and `cors_allowed_origins`.

Two things must line up or sign-in fails with an unhelpful error:

- **The page's own URL must be a callback URL of the pool, exactly.** Cognito compares literally,
  so `http://localhost:5173` and `http://localhost:5173/` are different. `Config.redirectUri`
  defaults to the URL the page is served from; set `redirectUri` in the config block to override.
- **The page's origin must be in `cors_allowed_origins`**, or the browser refuses every API call
  and reports only that CORS failed.

`crypto.subtle`, which PKCE needs, exists only in a secure context: https, or http on localhost.
Serving this over plain http from any other host will not work, and `Pkce` says so rather than
failing obscurely.

## How it is put together

| File | |
| --- | --- |
| `Config.scala` | the three public values from terraform, read from `window.matchmakerConfig` |
| `Pkce.scala` | verifier, S256 challenge, state |
| `Auth.scala` | hosted-login redirect, code exchange, token storage, expiry |
| `ApiClient.scala` | one method per route, bearer token attached, non-2xx as a failed `Future` |
| `Store.scala` | the `Var`s the views read, and the reloads |
| `Main.scala` | entry point and every view |

The model and the wire codecs are **not** copied here. `build.mill` compiles
`shared/src/com/vivi/matchmaker/api/Json.scala` and `matchmaker/src/.../model/` into both this
module and the JVM `api` module, so a field renamed on one side fails to compile on the other
instead of turning into a missing key at runtime. Nothing in either directory may use anything
beyond the standard library and `java.time`.

## What `ui.txt` asks for, and where it is

| `ui.txt` | |
| --- | --- |
| login through hosted login using PKCE | `Auth`, `Pkce` |
| self registration triggers a player set up | `Views.registration`, shown when `GET /me` answers 403 |
| list of matches with a turn due | `Views.dueSection` |
| button opening matches the player is in | `Views.myMatchesSection` |
| ...also showing pending acceptances | the "awaiting other players" marker on `pending` rows |
| ...with option to back out | `Views.pendingAcceptances`, over `GET /me/acceptances` |
| list all games | `Views.gamesSection` |
| expand a game to see open challenges | `Views.gameDetail` |
| own challenges listed separately, deletable | `Views.myChallengeRow` |
| accept a challenge, which may create a match | `Views.openChallengeRow` |
| create an open challenge | `Views.newChallengeForm` |
| button to list completed | `Views.completedSection` |

The last line of `ui.txt` — the game calling back to update participants and create a result — is
server-side, and is `PUT /characters/{id}/state`. There is nothing for this UI to do about it.

## Characters

Both offering and accepting a challenge needs a character in that game. Expanding a game loads the
caller's characters for it from `GET /games/{gameId}/characters`; if there are none, the panel
offers to create one. A player with several characters in a game currently plays as the first —
choosing between them would need a picker, which nothing in `ui.txt` asks for.

## Tests

`mill matchmaker.ui.test` covers the shared wire format compiled for Scala.js — in particular that
`Instant` behaves the same under `scala-java-time` as under the JDK, which the server's own tests
cannot see. The views and `Auth` are not tested: they need a DOM, and there is no jsdom in this
build.
