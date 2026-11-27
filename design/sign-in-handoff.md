# Handing a sign-in to an engine's page

When a player signed in on matchmaker's UI opens an engine's page (a board, a spectator's view, a character page),
the page opens signed in as them. The engines sign in against the same Cognito pool and app client, so the tokens
matchmaker holds are good there too. The page is on another origin and in a new tab, and `sessionStorage` is per
origin and per tab, so the tokens have to be handed over.

## No token in a url

The url carries a one-time code, never a token. A browser records every url it visits in its history, fragment and
all, before the page can clear it, and keeps it for good if the page never loads. History can be synced to other
devices and read by an extension with the `history` permission. A token found there would sign anyone in as the
player until it expired, which for a refresh token is 30 days. A code found there is spent, or expired a minute after
it was made.

The tokens travel only in request and response bodies, over TLS:

```
matchmaker UI ──POST /handoffs (JWT: the player) {gameId, idToken, refreshToken?}──▶ matchmaker API
              ◀── {code, expiresAt} ─────────────────────────────────────────────── stores sha256(code) → tokens, 60 s
matchmaker UI opens  PLAY_URL#handoff=CODE
engine page   ──POST /api/handoff {code}──▶ engine server
engine server ──POST /handoffs/redeem {code} (x-api-key: the game's key)──▶ matchmaker API
                                             deletes the row, checks the game and the expiry
engine server ◀── {idToken, refreshToken?} ──────────────────────────────────────────
engine page   ◀── {idToken, refreshToken?} ── stored as a sign-in, as one made on the page is
```

## The code

- 32 bytes from `SecureRandom`, base64url: 43 characters (`HandOffService.newCode`).
- Only its SHA-256 is stored, in `sign_in_handoff`, with the game, who made it (for logs), the tokens and the expiry.
- It can be redeemed for 60 seconds (`HandOffService.Lifetime`). Making a code deletes every row already expired, so
  the table holds only the last minute's rows and needs no scheduled job.
- Redeeming it deletes its row in the same statement that reads it (`HandOffRepo.take`, `DELETE … RETURNING`), so a
  code is redeemed at most once however many calls race for it. A refused redeem deletes it all the same, so a wrong
  engine trying a code leaves nothing for the right one.
- Every refusal is the same 404 (`handOff.unknown`): a spent code, an expired one, another game's and a made-up one
  look alike to the caller.
- Nothing logs a code or a token: only the game, who made the code, and what became of it.

## Who can make one, and who can redeem it

- `POST /handoffs` is a player's route, under the JWT authorizer. The ID token handed over must be the caller's own:
  its `sub` is the caller's. Otherwise a player could make a code that signs someone else in. Its signature is not
  checked there; the engine verifies it on every request.
- The refresh token is kept only for a trusted game (`game.is_trusted`); an untrusted one gets the ID token alone, and
  the page asks the player to sign in again once it expires. With the token never in a url, a trusted engine holding
  it is no worse off than the player signing in on its own page, which stores it the same way.
- `POST /handoffs/redeem` is an engine's route (`local.engine_routes`), authorized by the game's API key like the
  archive routes. The code is redeemed only if its game's `external_id` is the identity the key names. That is why the
  engine's server redeems the code and not its page: the key ties the code to the one engine it was made for, so a
  code copied from one engine's url can't be spent by another engine, or by anyone holding just the code. It also
  needs no CORS between an engine's site and matchmaker's API.
- Both answers carry `cache-control: no-store`.

## The pages

- Matchmaker's UI (`Views.openSignedIn`, `Auth.handOff`) opens a blank tab within the click, since a browser blocks a
  popup opened later and the code takes a request, clears the tab's `opener` by hand (`noopener` would make
  `window.open` answer `null`), and sends the tab to `PLAY_URL#handoff=CODE` once the code is made. With no session, or
  a code that could not be made, it sends the tab to the bare url, and the engine's page asks the player to sign in.
- An engine's page (`SignIn.takeHandOff`, the script `engines/common` writes) reads `#handoff=`, clears the fragment at
  once with `history.replaceState`, and posts the code to its engine's `POST /api/handoff` (`EngineRoutes`, through
  `HandOffs` and `Matchmaker.redeemHandOff`). Until the answer comes the tab counts as signed in and asking for a token
  waits for it, so nothing asks for a seat too early. On a failure the tab stays as it was and the sign-in form says
  why (`signIn.handOffFailed`).
- Anyone can make a code for their own account and send the link (login CSRF). So a tab already signed in as another
  account asks before switching (`signIn.switchAccount`), and the line saying who is signed in stays.
- An engine with no `MATCHMAKER_URL`, or offline, answers every code 503, and its page asks the player to sign in.
  `RecordingMatchmaker.handOffs` redeems codes a test puts there.

## Deploying

Matchmaker's API and table first (Terraform for the routes, then Flyway), then its UI, then the engines with the new
`engines-common`. Until an engine updates, a page opened with `#handoff=` from the new UI asks the player to sign in.
