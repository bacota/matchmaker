# Game sites: one game (or a few) on a domain of its own

## Goal

A *site* is the matchmaker UI served on a domain of its own — `capture-the-flag.vivi.com` — showing
only some games. It is the same deployment: the same CloudFront distribution and bucket, the same
`main.js`, API, user pool and database. A player signed in on a site has the same account, ratings
and matches as on the main domain; they just never see the other games.

Secure separation is **not** a goal. Anyone can open the main domain or edit a request and see
everything. The point is that people who care about one game are not shown, or confused by, the rest.

## Decisions already made — do not revisit

1. **The API filters, not the browser.** The UI does no game filtering at all. Every cross-game list
   comes back from the API already narrowed to the site's games.
2. **The API knows the site from the request's `Origin` header**, which the browser sets on every
   cross-origin call (the UI and the API are always on different origins). No origin, or one that is
   not a site, means the whole instance.
3. **The filter is in SQL.** Every repo query that lists rows from more than one game takes a
   required `GameScope` parameter — no default — so the compiler makes every caller decide. Not a
   filter applied to results in the router or a service.
4. **Sites are named by domain and list games by `Game.name`** — the game's fixed short name
   (`boxing`, `capture-the-flag`), which is the same in every environment. Never by numeric game id.
5. **One source of truth: the terraform variable `ui_sites`.** Terraform turns it into the UI's
   `config.js`, the distribution's aliases, Route 53 records, Cognito callback/logout URLs, the API's
   CORS origins, and the `UI_SITES` environment variable the API and mail read.
6. **Mail goes to the site its tournament was made on.** A game is expected to be on several sites,
   and a few players move between sites, so the site is neither chosen from the game nor stored on
   the player. It is recorded once, on the tournament, when it is created; challenges are
   tournaments and every match belongs to one, so every mail has a tournament, and everyone in it is
   sent to the same place. A mail links to that site and calls it by its heading **when that site
   lists the mail's game**; otherwise it links to `UI_BASE_URL` and says "matchmaker", as now.
   Tournaments made before this, or on the main domain, have no site, so their mail is unchanged.
7. Routes under `/games/{gameId}/...` are about one game already and are **not** changed. A site does
   not block them.

## Vocabulary

- `GameScope` — which games a query may return rows from: `All`, or `Only(games: Set[GameId])`.
  `Only(Set.empty)` returns nothing, and that is correct (a site whose games are not registered).
- `Site` — one entry of `UI_SITES`: its origin, heading and game names.

## Order of work

Do the steps in order; each builds on the one before. After each step, compile and run only the
tests for what it touched (see "Testing" at the end). Run everything once, at the end.

---

### Step 1 — `GameScope` and its SQL fragment

**New file** `matchmaker/src/com/vivi/matchmaker/model/GameScope.scala`:

```scala
package com.vivi.matchmaker.model

/** Which games a cross-game list may show. See design/game-sites-plan.md. */
enum GameScope {
    case All
    case Only(games: Set[GameId])

    /** Narrowed further to one game, where a screen asks about one: the game screen's own list. */
    def narrowedTo(game: Option[GameId]): GameScope = (this, game) match {
        case (_, None)              => this
        case (All, Some(g))         => Only(Set(g))
        case (Only(games), Some(g)) => Only(games.intersect(Set(g)))
    }

    def isShowing(game: GameId): Boolean = this match {
        case All         => true
        case Only(games) => games.contains(game)
    }
}

object GameScope {
    def only(game: GameId): GameScope = Only(Set(game))
}
```

**In** `matchmaker/src/com/vivi/matchmaker/persistence/SkunkCodecs.scala`, add one helper that every
scoped query uses, so the condition is written the same way everywhere:

```scala
/** `column` is in `scope`: `(TRUE OR …)` for every game, `(FALSE OR column = ANY(ids))` for some.
  * Interpolate it into a query as `AND ${inGameScope("p.game_id")}`. `column` is SQL text, never
  * user input — always a literal at the call site.
  */
def inGameScope(column: String): Fragment[GameScope] =
    sql"(${bool} OR #$column = ANY(${_int4}))".contramap[GameScope] {
        case GameScope.All         => (true, Arr.empty[Int])
        case GameScope.Only(games) => (false, Arr.fromFoldable(games.toList.map(_.value)))
    }
```

Imports: `skunk.*`, `skunk.implicits.*`, `skunk.codec.all.{bool, _int4}`, `skunk.data.Arr`. If the
`contramap` over a two-parameter fragment does not compile as written, adjust it (the shape is
`Fragment[(Boolean, Arr[Int])]` contramapped to `Fragment[GameScope]`); `_int4` / `Arr[Int]` are
already used in `MatchRepo.selectClocksFor` and `CharacterInvitationRepo` as working examples.

**Test**: `GameScopeSpec` in `matchmaker/test/src/com/vivi/matchmaker/model/` — `narrowedTo` and
`isShowing` for each case. No database.

---

### Step 2 — scope every cross-game query

Each query below gets the condition `AND ${inGameScope("<alias>.game_id")}` in its `WHERE`, and its
repo method gets a required `games: GameScope` parameter (no default). Use whichever table alias
the query already uses for the row's game (`p.game_id`, `m.game_id`, `t.game_id`, `mine.game_id`,
`g.game_id`, or the bare `game_id`). Change each query's Skunk type to take the scope.

| Repo method (file) | Query val | Notes |
|---|---|---|
| `MatchRepo.listDueForPlayer` | `selectDueForPlayer` | |
| `MatchRepo.listActiveForPlayer` | `selectActiveForPlayer` | |
| `MatchRepo.listCompletedForPlayer` | `selectOverForPlayer` | replaces its `COALESCE(${gameId.opt}, p.game_id)` |
| `MatchRepo.listPublicActiveForPlayer` | `selectPublicActiveForPlayer` | |
| `MatchRepo.listPublicCompletedForPlayer` | `selectPublicOverForPlayer` | replaces its `COALESCE` |
| `MatchRepo.hasCompletedBefore` | `selectCompletedBefore` | parameter `gameId: Option[GameId]` becomes `games: GameScope` |
| `MatchRepo.hasPublicCompletedBefore` | `selectPublicCompletedBefore` | same |
| `ResultRepo.listForPlayer` | `selectResultsForPlayer` | replaces `mine.game_id = COALESCE(...)` |
| `ResultRepo.timeTakenForPlayer` | `selectTimeTakenForPlayer` | same |
| `TournamentRepo.summariesForPlayer` | `selectNotOverForPlayer` | |
| `TournamentRepo.endedOwnedSince` | `selectEndedOwnedSince` | |
| `TournamentRepo.standingsForPlayer` | `selectStandings` | |
| `TournamentMatchRepo.choosingFor` | `selectChoosingFor` | the second query, seats of those matches, needs no scope |
| `NotificationRepo.listForPlayer` | `selectPlayerGames` | the per-game notification answers |
| `GameRepo.list` | `selectGameAggregate` | the game list itself |

`MatchRepo.CompletedSpan(from, until, gameId: Option[GameId])` becomes
`CompletedSpan(from, until, games: GameScope)`; the completed and results queries read `span.games`.

**Leave alone:**
- The character queries (`…ForCharacter`): they are about one character in one game.
- `NotificationRepo`'s re-stamp statement with `COALESCE($gameId.opt, g.game_id)`: it updates a
  player's own seats after they change settings; it is not a list anybody sees.
- Every query that takes one specific game id.

**Tests**: in the existing repo specs (`MatchRepoSpec`, `ResultRepoSpec`, `TournamentRepoSpec`,
`GameRepoSpec`, and the notification repo's spec if there is one — otherwise
`NotificationServiceSpec`), add for each method above one case with rows in **two** games asserting:
`All` returns both games' rows, `GameScope.only(first)` returns only the first's, and
`Only(Set.empty)` returns none. Follow each spec's existing fixture style.

---

### Step 3 — thread the scope through the services

Each service method behind a cross-game route takes a required `games: GameScope` and passes it to
its repo calls. Calls with three or more arguments pass them **by name** (CLAUDE.md).

| Service method | Change |
|---|---|
| `GameService.list(caller, activeOnly)` | + `games` → `GameRepo.list` |
| `MatchService.due(caller)` | + `games` |
| `MatchService.active(caller)` | + `games`. It reads the list, may settle ended matches, and **reads again**: both reads use `games`. |
| `MatchService.completed(caller, query)` | + `games`. Compute `games.narrowedTo(query.gameId)` once and pass that to both `completedPage` (for the `CompletedSpan`) and `hasCompletedBefore`. |
| `MatchService.publicActive(caller, playerId)` | + `games` |
| `MatchService.publicCompleted(caller, playerId, query)` | + `games`, narrowed as for `completed` |
| `MatchService.results(caller, query)` | + `games`, narrowed; builds `CompletedSpan(games = …)` |
| `TournamentService.mine(caller)` | + `games` |
| `TournamentService.endedOwned(caller, frame)` | + `games` |
| `TournamentService.standings(caller)` | + `games` |
| `TournamentRounds.choicesFor(caller)` | + `games` |
| `NotificationService.mine(caller)` | + `games` → `listForPlayer` only; the player's overall settings are not per game |

`MatchService.completedPage` takes the already-narrowed scope instead of reading `query.gameId`.
The character's completed list (`MatchService` near the `query.copy(gameId = Some(gameId))` call)
passes `GameScope.only(gameId)`.

`CompletedQuery.gameId` **stays** as it is — it is the game screen asking for one game, it is on the
wire and in the UI. The server narrows the site's scope by it.

Fix every other caller the compiler reports (tests included) by passing `GameScope.All`, unless the
caller is one of the routes in step 5.

**Tests**: the existing service specs must still pass. New coverage is in step 8.

---

### Step 4 — reading the sites, and resolving names to ids

**New file** `matchmaker/src/com/vivi/matchmaker/util/Sites.scala`. It parses the `UI_SITES`
environment variable, which is JSON keyed by origin (scheme and host, no path, no trailing slash):

```json
{
  "https://capture-the-flag.vivi.com": { "heading": "Capture the Flag", "games": ["capture-the-flag"] }
}
```

```scala
case class Site(origin: String, heading: String, games: List[String])

case class Sites(byOrigin: Map[String, Site]) {
    /** The site a request came from, by its `Origin` header. None: the whole instance. */
    def forOrigin(origin: Option[String]): Option[Site]

    /** The site at a stored origin, if it is still configured. */
    def at(origin: String): Option[Site]
}

object Sites {
    val none: Sites = Sites(Map.empty)
    def parse(json: String): Sites                // throws on malformed JSON: terraform writes it with jsonencode, so a parse failure is a real bug
    def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): Sites
    lazy val current: Sites = fromEnvironment()   // unset or blank UI_SITES → none
}
```

Use `ujson`, which `matchmaker` already depends on. Trim a trailing `/` from origins on both sides of
the comparison.

**In `GameRepo`**: `def idsNamed(names: List[String]): IO[List[GameId]]` —
`SELECT game_id FROM game WHERE name = ANY(${_text})`. Inactive games included; `GameRepo.list`
decides activeness as it does now.

**In `GameService`**: `def scopeFor(site: Site): IO[GameScope]` — `GameScope.Only(ids.toSet)` from
`idsNamed(site.games)`. A name that matches no game is skipped; if none match, the scope is empty
and the site shows nothing, which is right.

**Tests**: `SitesSpec` (no database): parse, `forOrigin` with and without a trailing slash, an
unknown origin, `None`; `at` for a configured and an unconfigured origin. `GameRepoSpec`: `idsNamed`.

---

### Step 5 — the router

In `matchmaker/api/src/com/vivi/matchmaker/api/Router.scala`, add:

```scala
/** The games this request may see: its site's, by the Origin header, or every game. */
private def gameScope(services: Services[String], request: Request): IO[GameScope] =
    Sites.current.forOrigin(request.header("origin")) match {
        case None       => IO.pure(GameScope.All)
        case Some(site) => services.games.scopeFor(site)
    }
```

and use it in exactly these routes, passing the scope to the service:

- `GET /games`
- `GET /me/notifications`
- `GET /me/matches`, `GET /me/matches/due`, `GET /me/matches/completed`
- `GET /me/results`
- `GET /players/{playerId}/matches`, `GET /players/{playerId}/matches/completed`
- `GET /me/tournaments`, `GET /me/tournaments/ended`
- `GET /me/tournament-standings`
- `GET /me/tournament-choices`

Pattern: `gameScope(services, request).flatMap(games => ok(services.matches.due(caller, games)))`.

`PUT /me/notifications` and the other writes are unchanged. Check whether any **write** route answers
with one of the lists above (e.g. a PUT that returns the whole `NotificationSettings`); if so, scope
its answer the same way so the screen does not briefly show other games after a save.

The `RouterSpec` cases for these routes still pass with no `origin` header (they never reach the
database). One existing write route also reads the site, in step 6a; no route is added.

---

### Step 6a — recording the site a tournament was made on

Mail sends its reader to **the site the tournament it is about was made on**: not a site chosen from
the game (a game is expected to be on several sites), and not one stored on the player (a few
players move between sites, and mail is sent when no session is open). Challenges are tournaments
and every match belongs to one (`Match.tournamentId`), so this one value covers every mail, and
everyone in a tournament is sent to the same place.

- **Migration** `matchmaker/resources/db/migration/V25__tournament_site_origin.sql` (use the next
  free number if 25 is taken):

  ```sql
  -- The game site a tournament was made on: the origin of the request that created it, when that was
  -- a configured site (UI_SITES). NULL is the main domain, and every tournament made before this.
  -- Read only to choose the site its mail links to. See design/game-sites-plan.md.
  ALTER TABLE tournament ADD COLUMN site_origin text;
  ```

- **Writing it.** A tournament is made in exactly one place: `TournamentRepo.create` (its
  `INSERT INTO tournament`) takes `siteOrigin: Option[String]`, and `TournamentService.create`
  (which calls it) takes `site: Option[Site]` and passes `site.map(_.origin)`. Arguments by name.
  Every other caller the compiler finds (tests, internal callers) passes `None`.
- **The route** `POST /games/{gameId}/tournaments` in `Router.scala` passes
  `site = Sites.current.forOrigin(request.header("origin"))`. Only an origin that is a configured
  site is ever stored; any other origin, or none, stores NULL. Never store the raw header.
- **Reading it**, for mail only: `TournamentRepo.siteOrigin(gameId: GameId, tournamentId:
  TournamentId): IO[Option[String]]` — one row by its key. Do **not** add the column to the
  `Tournament` model or its existing selects.

No route is added, so `local.routes` and `RouterSpec.routed` do not change, and the UI does nothing
new.

Accepted consequence: a player who enters a tournament from a different site than the one it was
made on (or from the main domain) gets that tournament's mail with the tournament's site. That site
lists the game, and the player's account works there.

**Tests**: in the tournament service spec (`TournamentServiceSpec`, or the nearest one that creates
tournaments) — a tournament created with a site stores its origin, one created with `None` stores
NULL. `TournamentRepoSpec` — `siteOrigin` answers both.

---

### Step 6b — mail: the tournament's site name and URL

A mail must send its reader to the tournament's site **and call it by that site's name**. Today the
mail says "matchmaker" in its links and in three bodies, and links to `UI_BASE_URL`. So every mail
is given a *mail site*: a name and a URL, chosen **once per event** from the tournament it is about,
and used for both the link and the wording.

The rule, for a mail about game *g* in a tournament with stored origin *o*:

1. if *o* is set, `Sites.at(o)` still knows it, **and** that site's `games` include *g*'s name →
   `MailSite(name = site.heading, url = site.origin)`;
2. otherwise — no origin, a site no longer configured, or a site that does not list *g* — the main
   site: `MailSite(name = "matchmaker", url = UI_BASE_URL)`.

Every recipient of the event gets that site — players, owner and invitees alike — **except the
admins** told about a match the engine would not create (`matchNotCreated`) who are not the
tournament's owner: they get the main site, where the admin screens are.

In `matchmaker/src/com/vivi/matchmaker/notify/`:

- **New** `case class MailSite(name: String, url: String)` — what a mail calls the place it sends its
  reader, and where that is. `url` has no trailing `/`, as `uiBaseUrl` has none now.
- `MailSettings` gains `sites: Sites`, filled in `fromEnvironment` from `Sites.fromEnvironment(env)`.
  `MailSettings.none` has `Sites.none`. Add `def mainSite: Option[MailSite]`:
  `uiBaseUrl.map(url => MailSite(name = MailText.mainSiteName, url = url))`.
- `MailText.mainSiteName` is the text "matchmaker", from a new key `mail.site.main=matchmaker` in
  `resources/mail/messages.properties` (person-readable text lives there, not in code).
- `GameNotice` (in `NotificationRepo.scala`) gains `handle: String`, read from `game.name` in
  `selectGameNotice`. (Its existing `name` field is the display name — leave it.) Every composing
  path already reads the `GameNotice`, so the game's name costs no extra query.
- **New** `MailSites` (in `notify/`), which applies the rule. Pure — no database:

  ```scala
  /** Which site a mail sends its reader to. See design/game-sites-plan.md, step 6b. */
  final class MailSites(val main: MailSite, sites: Sites) {
      /** Whether any site is configured: when not, callers skip reading the tournament's origin. */
      def isEmpty: Boolean

      /** The site at `origin`, if it is still configured and lists the game; otherwise the main site. */
      def choose(origin: Option[String], gameHandle: String): MailSite
  }
  ```

- `Notifications.dispatch(repos, about)` keeps its signature. Its `compose` callback receives a
  `MailSites` where it now receives `uiBaseUrl: String`. `dispatch` sends nothing when there is no
  sender or no `mainSite`, exactly as it now does when `uiBaseUrl` is unset.
- Each composing path reads the tournament's origin **once per event, and only when
  `!mailSites.isEmpty`** (a deployment with no sites makes exactly the queries it makes today), then
  computes one `site = mailSites.choose(origin, notice.handle)` and uses it for every recipient:
  - `aboutMatch`: `repos.tournaments.siteOrigin(played.gameId, played.tournamentId)` in its `for`,
    next to `preferencesForMatch`, and pass `site` to the pure `compose` in place of `uiBaseUrl`.
  - The tournament paths (`aboutInvitation`, `tournamentRoundDone`, `matchNotCreated`, and the
    path near the `dispatch(repos, s"tournament …")` call):
    `repos.tournaments.siteOrigin(tournament.gameId, tournament.tournamentId)`. In
    `matchNotCreated`, admins who are not the owner get `mailSites.main`.
- Replace `uiBaseUrl: String` with `site: MailSite` all the way down: `NotificationMail.compose`,
  `MailText.letter` and `MailText.links`.
- `NotificationMail.lines(kind, news)` becomes `lines(kind, news, site: MailSite)`, so a body can
  name the site. Update its implementations (`MatchMail`, `ChallengeMail`, `TournamentMail`, and any
  other the compiler finds), each marked `override`.
- `MailTemplates.render` calls that produce the lines below pass `"siteName" -> site.name` and
  `"siteUrl" -> site.url`.

**`resources/mail/messages.properties`** — the four places that say "matchmaker" to the reader:

| Key | Now | Becomes |
|---|---|---|
| `mail.links` | `Open matchmaker: ${uiBaseUrl}` | `Open ${siteName}: ${siteUrl}` |
| `mail.links.play` | `…\nOpen matchmaker: ${uiBaseUrl}` | `…\nOpen ${siteName}: ${siteUrl}` |
| `mail.invitation.received.body` | `Open matchmaker to enter it, …` | `Open ${siteName} to enter it, …` |
| `mail.tournament.roundDone.body` | `…open the tournament in matchmaker…` | `…open the tournament in ${siteName}…` |
| `mail.tournament.matchNotCreated.body` | `Matchmaker tries again a few times. Open the tournament in matchmaker …` | `It is tried again a few times. Open the tournament in ${siteName} …` |

The last is reworded rather than given the site's name: "Capture the Flag tries again" would read as
the game doing it. Leave the comment lines at the top of the file (which describe matchmaker to a
developer) as they are. Search the file for any other "matchmaker" a reader sees and treat it the
same way.

On the main domain every mail reads exactly as it does today, since the main site's name is
"matchmaker".

**Tests**:
- `MailTemplatesSpec` / `MatchMailSpec` / `ChallengeMailSpec`: the existing assertions now pass a
  `MailSite("matchmaker", <url>)` and expect today's text unchanged. Add one case per changed key
  with `MailSite("Capture the Flag", "https://capture-the-flag.example")`, asserting the name and URL
  appear and "matchmaker" does not.
- `MailSitesSpec` (no database): with a site listing game *g*, `choose` for (a) that site's origin,
  (b) a configured site that does not list *g*, (c) an origin no longer configured, and (d) `None` —
  (a) gets the site, (b), (c) and (d) the main site. `isEmpty` with and without sites.
- `MatchNotificationSpec` (or wherever `RecordingNotifier` mail bodies are asserted, with
  `TestServices.servicesInline`): a match in a challenge created on a site that lists the game —
  every seat's queued mail carries the site's name and URL; the same in a challenge created with no
  site — "matchmaker" and `uiBaseUrl`.
- One tournament mail to the owner (round done is the simplest): a tournament created on a site
  sends its owner mail with that site's name and URL.
- `matchNotCreated` for a tournament made on a site: the owner's mail has the site, an admin's who
  is not the owner has the main site.
- A player in two challenges of the same game, one created on a site and one on the main domain,
  gets each challenge's match mail with that challenge's site. This is the case the design exists
  for: a player who moves between sites.

---

### Step 7 — the UI

The UI does **no** game filtering and sends nothing new: the API reads the site from the `Origin`
header the browser already sends. The UI shows the site's heading and hides the admin's "Add a Game".

- `matchmaker/ui/src/com/vivi/matchmaker/ui/Config.scala`: read an optional `sites` object from
  `window.matchmakerConfig`, keyed by origin, each value `{ heading }`. Add
  `site: Option[SiteHeading]` (or simply `siteHeading: Option[String]`) to `Config`, found by
  `dom.window.location.origin`. Absent or unmatched → `None`.
- `Main.main`: when there is a site heading, set `dom.document.title` to it.
- `Views.scala` (near line 243, `h1(Headings.app.title)`): show the site heading instead when there
  is one.
- `Views.scala` (near line 435, the `addGame` menu item): not shown on a site.
- `matchmaker/ui/config.local.js`: add a commented-out example:

  ```js
  // To try a game site locally: open http://127.0.0.1:5173 (not localhost) and start LocalServer
  // with UI_SITES='{"http://127.0.0.1:5173":{"heading":"Boxing","games":["boxing"]}}'.
  // sites: { "http://127.0.0.1:5173": { heading: "Boxing" } },
  ```

Nothing else in the UI changes: `/games` and the lists arrive already scoped.

Do **not** run `a11y-check.sh`; that is the UI reviewer's job.

---

### Step 8 — the end-to-end scope spec

**New file** `matchmaker/test/src/com/vivi/matchmaker/service/GameScopeSpec.scala`, against the real
test database. One fixture with **two** games, and one player who in both games has: a due match, an
active match, a completed match with results, a tournament they own (one not over, one ended), an
entry with a standing, a tournament match waiting on their role choice if the fixtures make that
practical, and per-game notification settings. Reuse the fixture helpers in `TestServices`,
`Challenges.scala` and `TestMatches.scala`, and copy the setup style of `MatchServiceSpec` and the
tournament service specs.

Then for **each** service method in the step 3 table: called with `GameScope.only(first)` it returns
rows of the first game only (and at least one — an empty answer must not pass); called with
`GameScope.All` it returns both games.

Put this comment at the top of the spec:

```scala
/* Every cross-game list, narrowed to one game. A new route that lists rows from more than one game
 * belongs here too: nothing else checks that a game site's players are not shown the other games.
 * See design/game-sites-plan.md. */
```

Remember `CLAUDE.md`'s note on timeouts: this is a DB-backed spec with a large fixture, so keep any
`.timeout(...)` generous.

---

### Step 9 — terraform

**`terraform/variables.tf`**: add

```hcl
variable "ui_sites" {
  description = <<-EOT
    Game sites: the UI on a domain of its own, showing only some games. Keyed by domain name
    (no scheme, no slash), e.g. "capture-the-flag.vivi.com". `games` are game names (Game.name),
    the same in every environment. Each domain must be covered by ui_certificate_arn and sit in
    hosted_zone_id. See design/game-sites-plan.md.
  EOT
  type = map(object({
    heading = string
    games   = list(string)
  }))
  default = {}
}
```

**`terraform/modules/ui/`**:
- `variables.tf`: a `sites` variable of the same type, default `{}`.
- `main.tf`:
  - `aliases = concat(local.custom_domain ? [var.domain_name] : [], keys(var.sites))`.
  - A new `precondition` on the distribution: `length(var.sites) == 0 || local.custom_domain` with
    the message that sites need the custom-domain certificate (the default `*.cloudfront.net`
    certificate cannot serve another name).
  - Route 53: `aws_route53_record` `site_ipv4` and `site_ipv6`, `for_each = var.sites`, the same
    alias blocks as the existing `ipv4` / `ipv6` records, `name = each.key`.
  - `config.js`: add `sites = { for domain, site in var.sites : "https://${domain}" => { heading = site.heading } }`
    to the `jsonencode` map.
- `outputs.tf`: `site_urls` (`[for d in keys(var.sites) : "https://${d}/"]`) and `site_origins`
  (`[for d in keys(var.sites) : "https://${d}"]`).

**`terraform/main.tf`**:
- `module "ui"`: `sites = var.ui_sites`.
- `module "api"` (around line 114): add `module.ui.site_urls` to `callback_urls` and `logout_urls`,
  and `module.ui.site_origins` to `cors_allowed_origins`.
- `module "api"`: a new input `ui_sites_json = jsonencode({ for domain, site in var.ui_sites : "https://${domain}" => site })`.

**`terraform/modules/api/`**: a `ui_sites_json` variable (string, default `"{}"`), and
`UI_SITES = var.ui_sites_json` next to `UI_BASE_URL` in **both** `main.tf` (the API function) and
`ending.tf` (the ending function, which also sends mail).

**`terraform/environments/*.tfvars`**: add nothing real. Add a commented example under the
`ui_domain_name` lines in `dev.tfvars` only.

Run `terraform fmt` on the changed files and `terraform validate` in `terraform/` (with
`terraform init -backend=false` first if needed). **Do not** run `terraform plan` against an
environment, `terraform apply`, or any `deploy*.sh`.

---

### Step 10 — the rule for later work

Add to `.claude/rules/backend.md`, as a short section:

> **A query that lists rows from more than one game takes a `GameScope`.** Required, never
> defaulted, and applied in SQL with `SkunkCodecs.inGameScope`. Its route reads the scope with
> `Router.gameScope`, and `GameScopeSpec` gets a case for it. Game sites
> (design/game-sites-plan.md) rely on it: a list that skips it shows a site's players every game.
>
> **A new way to make a tournament records its site.** `tournament.site_origin` comes from the
> request's `Origin` (`Sites.current.forOrigin`), and mail uses it to send everyone in the
> tournament to the site it was made on. A path that makes a tournament without it sends that
> tournament's mail to the main domain.

---

## Testing

Per step, run only what the step touched, e.g.

```
./test.sh matchmaker.test.testOnly com.vivi.matchmaker.model.GameScopeSpec
./test.sh matchmaker.test.testOnly com.vivi.matchmaker.persistence.MatchRepoSpec
```

Once at the end, everything the change touches:

```
./test.sh matchmaker.test matchmaker.api.test matchmaker.ui.test
```

Fix any failure. Before reporting green, confirm the summary shows tests ran in each module and
lists no stray traces (`./test.sh` checks both).

Run `scalafmt` on the changed `.scala` files only — never on `build.mill`.

## Manual check (optional, local)

Per `.claude/local-browser-testing.md`, with `boxing` registered locally (`./init-games.sh local`):
start `LocalServer` with `UI_SITES='{"http://127.0.0.1:5173":{"heading":"Boxing","games":["boxing"]}}'`,
copy `config.local.js` to `config.js` with the `sites` line uncommented, and compare
`http://127.0.0.1:5173` (Boxing only, headed "Boxing", no "Add a Game") with
`http://localhost:5173` (everything). Delete `config.js` afterwards.

## Out of scope

- Per-site Cognito branding for the hosted sign-up and password-reset pages (shared by every site).
- Blocking `/games/{id}/...` routes for games outside a site.
- Per-site anything beyond the heading: styles, logos, help text.
- Registering real sites in any environment's tfvars, issuing a certificate, or deploying.
- Choosing a site per recipient. Everyone in a tournament gets its site, including a player who
  entered from a different site or from the main domain.

## When done

Report what changed by step and the final test summary. Do not commit, push or open a PR.
