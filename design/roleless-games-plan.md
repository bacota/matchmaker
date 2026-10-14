# Games without roles

Some multiplayer games are played by any number of players, none of whom has a role. Examples are a race, a quiz, a
battle royale, or rock-paper-scissors played by a whole room at once. Today matchmaker cannot express such a game.

- **A match has exactly one seat per role.** A game lists its roles, and each role is one seat (`game_role`, one row
  per seat).
- **R comes from the roles.** The game's R, its required and recommended roles, sizes everything a tournament does:
  - the default pool size;
  - how many matches a round robin plays, `C(N, R)`;
  - the standard formats' `pool_size: R + 2` and `demote: R`;
  - how many seats a challenge offers.
- **`has_unimportant_roles` is a workaround.** It says the roles exist but carry no advantage. They are still seats,
  and still bound the number of players.

This plan adds **roleless games**. Such a game has no roles, and the tournament creator chooses how many players a
match has. That choice can be "everyone in the pool". Combined with a pool size of everyone entered, it puts every
entrant into one match. Roleless games replace `has_unimportant_roles`, which goes away.

Every roleless game is a simultaneous-move game, so the order of its players never matters.

## The idea in one line

For a roleless game, **R is no longer a property of the game. It is the tournament's `players_per_match`**, and
everything that read R from the roles reads it from there.

## Decisions

### A roleless game still says how many players it can take

A roleless game has no `game_role` rows. It has two new columns:

- `min_players`: the fewest players a match can start with. At least 1; the default is 2.
- `max_players`: the most its engine can handle. NULL means no limit.

These are needed even though the number of players is "unspecified":

1. **Replacing unimportant roles requires it.** Rock-paper-scissors has unimportant roles, but it is still a
   two-player game. As a roleless game it is `min = max = 2`. Without a maximum, a tournament creator could set up
   five-player rps matches the engine cannot play.
2. **Most engines have a real limit.** A board has only so many starting squares. A pool or match bigger than the
   game's maximum is a design error to catch when the tournament is validated, not when the engine refuses to create
   the match.

A game either has roles or has `min_players`; it never has both. Seeing roles or none tells the two kinds apart, so no
flag is needed. The admin's game form offers the choice as **Players take roles** or **Any number of players**. The
second hides the roles editor and shows the two number fields.

### A roleless game is always simultaneous

All its players move at once, so none of them goes first, and the engine needs no seat order or seeds. In code:

- `GameService.createOrUpdate` refuses a roleless game whose `move_type` is not `sim`.
- The game form fixes the move type at Simultaneous when **Any number of players** is chosen.
- The existing rule for simultaneous games applies unchanged: if the game gives no `max_turns`, the tournament must.

### The tournament says how many players a match has

A new tournament column holds it:

```sql
-- For a game without roles: how many players each match seats. NULL seats the whole pool in one match.
-- Always NULL for a game with roles, whose roles decide.
players_per_match  INT CHECK (players_per_match > 0),
```

The creator picks one of two options:

- **N players per match.** N must lie between the game's `min_players` and `max_players`.
- **Everyone in the pool in one match.** The column is NULL.

The service refuses a `players_per_match` for a game with roles. A CHECK constraint cannot see the game, so this is
enforced the same way the other cross-table rules are. `TournamentSettings` gains `playersPerMatch: Option[Int]`, and the
tournament DTO and request carry it. Like the pool size, it can be changed until the tournament starts.

### "Everyone in one match" for the whole tournament

Seating the whole pool in one match is half of this option. The other half is having one pool. The owner can already
change the default pool size while the tournament is Draft or Open (`TournamentService.update`). So the missing piece is
a convenient value to set it to, not a new mechanism.

For a roleless game, the tournament form offers **One pool of everyone entered**. It sets the default pool size to
whichever is smaller:

- the number of entries so far, not counting withdrawals;
- the game's `max_players`, when it has one.

With "everyone in the pool" as the match size, that is one match of every entrant, as long as no more enter before the
start. Entries can arrive after the size was set, so two checks back it up:

- **The start screen warns.** It shows how many pools the entries will form when that is more than one. The owner can
  then set the size again, or start with an extra pool.
- **The start refuses an impossible pool.** A pool size above `max_players` cannot happen through the form, but the
  API also refuses one at start.

No stage flag or new format is needed. "One match" is the round-robin format played once, with the pool size set this
way.

A challenge is already one pool of its own seat count. A challenge in a roleless game is therefore one match of
everyone who enters it.

### R, required seats and seating, per kind of game

`TournamentGame` is what the tournament model knows about the game. It loses `hasSignificantRoles`, since roles are
significant exactly when the game has them, and gains a description of open seating:

```scala
case class OpenSeats(minPlayers: Int, maxPlayers: Option[Int], playersPerMatch: Option[Int])
// TournamentGame(roles, moveType, maxTurns, hasRoleChoice, open: Option[OpenSeats])
```

`TournamentGame.of(game, playersPerMatch)` builds it, and every caller that has a tournament passes the tournament's
setting. Its existing methods keep their names and answer for both kinds, with `wholePoolSeats(n)` saying when a pool
plays as one match:

| | Game with roles | Roleless, N per match | Roleless, whole pool |
|---|---|---|---|
| `poolSeats` (R) | required + recommended roles | N | the pool's size: one match per pool |
| `requiredSeats` | required roles | `max(min_players, 1)` | `max(min_players, 1)` |
| largest match | `roles.size` | N | the pool |
| `seatingRoles` | as today | empty: every seat has `role = None` and no `chooseOrder` | same |
| roles are significant (`!isRoleless`) | always | false | false |
| `isRubberApplicable` | exactly two roles | N == 2 | false |

Under whole pool, R depends on the pool, so a whole-pool match size never feeds a default pool size. The form
therefore requires a default pool size for a whole-pool tournament: typed in, or set by **One pool of everyone
entered**.

`Schedule.matchCount` and `Schedule.roundRobin` already have the rule "a pool no bigger than the largest match plays
once, all together; a bigger one plays every R-subset". The rule stays, with "largest match" taken from the table
above. Whole pool always plays one match per pool, and N per match plays `C(n, N)` matches. Rotation and role choice
are skipped, as they already are when roles are not significant.

### What validation adds

These go in `Validation.validate` and `designProblems`:

- A roleless game with N per match, where N is outside `[min_players, max_players]`.
- A whole-pool tournament with a pool size, the default or a stage's, above `max_players`.
- The existing "the game has no required role" problem becomes "a match could never start" and asks `requiredSeats`,
  which is never 0 for a roleless game.
- Which formats a game offers is decided when the game is created, before any tournament says how many play a match.
  A roleless game is judged at its smallest matches (`players_per_match = min_players`), on the server and in the
  admin's format list alike. A format that does not suit a tournament's own choice is refused when that tournament is
  created.

### Seats with no role, end to end

The schema already allows this. `participant.game_role_id` is nullable, and `EnginePlayer.role` is an `Option` on the
wire. The work is in the code that assumes every seat has a role by the time a match launches:

- **Settling roles.** A roleless seat is planned with no role and no `chooseOrder`, so it is never a chooser, and
  `statusOf` makes its match due at once. No change was needed.
- **Launch.** `TournamentRounds` used to count only seats with a role as able to play, and called `s.gameRoleId.get`
  for the starting ratings and the engine's role name. A roleless seat now counts, the role is an `Option` throughout,
  and `byRole` is false for a roleless game.
- **Reading seats back.** `Participant.gameRoleId` is now an `Option`. The seat and result queries in
  `ParticipantRepo` and `ResultRepo` that inner-joined `game_role` now outer-join it, and a result's `roleName` is
  optional, so the results table leaves the role out for such a seat.
- **Ratings.** `EloRatingService.startingRatings`, `roleDeltas` and `elo_role_start` already allow "no role". They are
  reached with `byRole = false`, so the match is rated overall only. `EloRating.deltas` is pairwise over every seat,
  normalised by the number of opponents, so a 40-player match rates correctly.
- **The engine.** It receives every player with `role: None`. The order of the list means nothing, since every move
  is simultaneous. Engines with roles are unaffected.
- **Showing a match.** The tournament page names a seat's role only when it has one, so it needed no change.

### Ranking and points

Round-robin points are already scored pairwise within a match: each opponent ranked below you earns `Points`, each
tie earns `Draw`, and the winner can earn a bonus. They need no change for big matches. Tie handling is the same:
`Ties.isAllowed(members)` already allows draws in pools bigger than two.

### Challenges

The challenge form's seat chooser runs from `least = max(2, requiredSeats)` to `most = roles.size`. For a roleless game
it runs from `max(2, min_players)` to `max_players`, with no upper bound when the game has none. A challenge's pool size
is its seat count, and it can be edited before the start like any other tournament's.

`TournamentService.startIn` and the UI's `leastToStart` both use `requiredSeats`, and the `Open` case keeps them
correct.

## Replacing `has_unimportant_roles`

Nothing is deployed separately yet, and redeploys may be destructive. So V1 is edited in place, as the boolean-naming
change did, with no compatibility shims.

- Drop `game.has_unimportant_roles`, `Game.hasUnimportantRoles`, the DTO field, the game form's checkbox and its tip,
  and `TournamentGame.hasSignificantRoles`. Roles are significant exactly when the game has roles.
- Code that asks "do roles matter?" now asks "does it have roles?". That is:
  - `byRole` in `TournamentRounds`;
  - role leaderboards in `Ratings.scala`, which already hide for fewer than two roles;
  - `Schedule.roles`;
  - the per-role rating tables.
- **Existing games with unimportant roles.** Before merging, check whether any registered game sets it. The
  registration scripts and engines don't, so it is probably only test fixtures. For any that do, re-register them as
  roleless with `min_players = max_players = ` their old role count. Their engine must then accept `role: None`.

## Schema changes (V1)

- `game`:
  - add `min_players INT CHECK (min_players >= 1)` and `max_players INT`, with `game_max_players_check` requiring a
    maximum to come with a minimum no greater than it;
  - add `game_roleless_simultaneous`: `CHECK (min_players IS NULL OR move_type = 'sim')`;
  - drop `has_unimportant_roles`.

  The rule that a game has either roles or `min_players` is enforced in `GameService.createOrUpdate`, because it spans
  two tables.
- `tournament`: add `players_per_match`.

`tournament_model` did not get a `players_per_match`. The models are written in code for every game, and none has a
match size worth suggesting, so it can wait until one does.

## API and UI

- **`Json`.** `GameDto` gains `minPlayers`/`maxPlayers` and loses `hasUnimportantRoles`. The tournament request and
  DTO gain `playersPerMatch`.
- **`GameForm.scala`.** A **Seats** choice, "Players take roles" or "Any number of players". The second hides the
  role editor, shows **Fewest players** and **Most players**, and fixes **Moves** at Simultaneous.
- **`Tournaments.scala`.** For a roleless game:
  - show a **Players per match** control: a number, or "Everyone in the pool";
  - show the **One pool of everyone entered** button beside the pool size, while the tournament is Draft or Open;
  - warn on the start screen when the entries will form more than one pool.

  The form's R comes from the chosen N, and **Players choose roles** is hidden, having nothing to choose.
- **`ChallengeForm.scala`.** Applies the seat range above: a list for a range of under 30, and a number to type for
  anything wider or unlimited.

## Steps

Built as one PR. Splitting it as first planned would have meant shipping roleless games with the match size fixed at
the minimum, only to replace that in the next PR.

## Tests

- **`ScheduleSpec`.** Roleless pools of every size, against N per match and whole pool: match counts equal `C(n, N)`
  and 1, every match is the right size, and no seat has a role or a choose order, even where seats always choose.
- **`GameServiceSpec`.** A roleless game is stored and read back with its range. A game with both roles and a range,
  neither, a backwards range, a minimum of 0, or turns in order is refused.
- **`TournamentServiceSpec`.**
  - `players_per_match` is refused for a game with roles, and outside a roleless game's range.
  - A whole-pool pool size above `max_players` is refused.
  - N per match becomes the default pool size.
  - A whole-pool tournament whose pool size is changed to the number entered before the start is one pool of one
    match, with no roles.
- **`TournamentRoundsSpec`.** The tests that used unimportant roles to skip role choosing now use a roleless
  two-player game, so they run roleless tournaments from start to finish. A new test plays a four-player match of
  everyone in a pool, and checks the engine was sent four players with no role.
- **`EloRatingServiceSpec`.** A roleless game's match moves overall ratings only, with no role rating and no role
  start on its seats.
- **`JsonSpec`.** A game's range of players round-trips, and a game with roles writes none.
- **By hand, in a browser at phone width.** I created a roleless game through the admin form, then a whole-pool
  tournament, and used **One pool of everyone entered**. A later entry raised the start warning, and the start then
  formed two pools.

No engine is roleless yet. The engine's side was checked against the test doubles, which record the request they are
sent.

## Decided

- **No fixed cap on players.** The owner can change the pool size until the start. For a roleless game they are
  offered the smaller of the entries and `max_players`.
- **The name is `players_per_match`** (`playersPerMatch` in code; "Players per match" in the UI).
- **Roleless games are simultaneous.** Player order never matters, and the engine gets no seeds.
- **Recommended roles are left as they are.**
