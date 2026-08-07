# Matchmaker UI

Scala.js and Laminar, following the outline in `ui.txt`. It is an ordinary client of the HTTP API:
it holds a Cognito ID token and sends it as a bearer token, and has no privileges beyond what that
token gets it.

## Running it

```sh
# 1. Link the Scala to JavaScript. fastLinkJS for development, fullLinkJS to deploy.
mill matchmaker.ui.fastLinkJS

# 2. Put main.js next to index.html.
ln -sf ../../out/matchmaker/ui/fastLinkJS.dest/main.js matchmaker/ui/main.js

# 3. Fill in index.html's config block from the terraform outputs of the environment you want.
terraform -chdir=terraform/environments/dev output

# 4. Serve it. Any static server will do; the port must match a callback URL on the pool
#    and an allowed CORS origin.
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
