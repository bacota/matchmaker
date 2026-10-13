# Plan: Tournaments (second design)

Implements `tournament-design.md`. Written against `a23d6f9` (the revert of #143), where the newest migration is
`V49__elo_by_role.sql`.

The first design gave each tournament type its own scheduling, advancing and ranking rules. This one has a single
recursive model: a tournament is a list of stages, a stage splits its participants into pools, and each pool either
plays matches directly or plays a smaller tournament. Every standard format is a configuration of that model. So
this plan builds the model once, as pure code that a simulator can run through whole tournaments before any of it
touches the database, and then adds persistence, rounds and UI around it. The standard formats come after that as
data, not as phases.

Order of work:

1. **Preliminaries.** Catalogue changes (role type, preference level, move type, max turns), `ties_allowed` in the
   engines, `match.creator` restored, and a numeric score in results.
2. **The model and the pure core.** The design types, validation, the standard designs, and the algorithms: pools,
   schedules, ranking, advancement, seeds and clocks. A simulator plays complete tournaments with made-up results.
3. **Schema and repositories.**
4. **Lifecycle.** Create, edit, invite, enter, withdraw, start.
5. **Rounds.** Starting a round, choosing roles, creating matches from the queue, detecting the end of a round, and
   advancing.
6. **UI.**


## Open questions in the design

Fourth review of `tournament-design.md`. Question numbers are kept from the earlier reviews so the references below
stay valid.

**Settled:**

| | how |
| --- | --- |
| **Q1 Brackets** | `p = (n/a)^k` per stratum, with `a` dividing `n`. If `r < n`, there's at most one pool, and only if it can fill the required roles. Consolation stage 3 is now `brackets: false`. |
| **Q2 Nested round robins** | The indented blocks are gone. `tournament_design: round_robin` is read as unset, so NCAA has one set of settings. |
| **Q3 Match count** | `C(N, R)`, or one match when `required ≤ N < R`, or none. "Every role exactly once" is read as once per subset. |
| **Q4 `concurrent`** | Per-participant limit, default 1. M is capped at 1000 per pool. |
| **Q8 Field-dependent repeats** | Zero Elimination is deferred ("a future enhancement"), so the three repetition criteria stand. |
| **Q9 Heats** | The creator sets the pool size. |
| **Q10 Duration** | Inherited from the nearest stage that sets one, then the tournament. |
| **Earlier** | `unimportant_roles` is reused. `points_for_draw` is an integer. Stratego scores by strength. |

**Nothing is still open.** Q5 (stratum after the stage), Q6 (ties go to the weaker seed) and Q7 (rubber only in
two-player games) were answered in review.

### Q6. Ties go to the weaker seed *(resolved)*

When ties aren't allowed and no tiebreaker resolves one, the win goes to the **weaker** seed: the larger seed
number. An 8 seed tied with a 1 seed advances.

How the plan applies it:
- **When.** Only when ranking a pool in a stage with `allow_ties: false`, and only after the stage's tiebreaker
  (scores, or a rubber) has failed. Applying it to the match result instead would skip `Rubber`.
- **Which seed.** The seed each member held entering the pool (`pool_member.seed_in`), so a seed that changes
  later can't reorder a finished pool. Seeds are unique within a (sub)tournament, so this always breaks the tie.
- **Default tiebreaker.** The design still gives none. The plan uses `scores`.
- **No endless eliminations.** Elimination Match sets `allow_ties: false`, so a tic-tac-toe single elimination
  that keeps drawing still finishes, with the underdog advancing.

A consequence worth knowing: in a game that often draws, such as tic-tac-toe, the weaker seed advances from every
drawn match. A strong player can therefore lose a tournament without losing a game. That follows from the rule, and
it's in the simulator tests so it's kept on purpose.

### Q5. The stratum used for ranking *(resolved)*

A participant's stratum for ranking a stage is its stratum **after** the stage's promotions and demotions.
- That's `pool_member.stratum_out`. It's written when the pool completes, so ranking reads it and never recomputes
  it.
- Every standard design ranks correctly under this rule. Double Elimination's winner ranks first even when they
  came from stratum −1, because `promote: 1` lifts them to 0. Consolation's finalists stay at 0 above the
  third-place match at −1, because stage 3 has `demote: null`.

Two rules follow, and the plan applies them:
- **Elimination.** Anyone whose stratum after the stage is below `min_stratum` is eliminated. That covers Double
  Elimination's final, where a player from −1 is already below the default minimum of 0.
- **Ranking the eliminated.** A participant eliminated in the stage being ranked has no stratum after it. They rank
  by pool rank, below everyone still in it.

### Q7. Rubber is for two-player games *(resolved)*

`Rubber` only applies to games with exactly two `game_role` rows. In any other game it's ignored, and the stage
breaks ties as though `scores` were set.

How the plan applies it:
- **The rubber.** One extra match between the two tied participants, played in the next round with
  `ties_allowed = false` and roles chosen by seed as usual. Their pool isn't complete until it's played.
- **Ties of three or more** in a two-player game can't be settled by one match, so they also fall back to `scores`.
  Raise it if a rubber per pair is wanted instead.
- **After the rubber or the scores:** a tie that remains goes to the weaker seed where ties aren't allowed (Q6),
  and otherwise stands.
- **Validation.** A design that sets `Rubber` for a game with more than two roles is accepted, with a warning that
  scores will be used. A design can be written for one game and used with another, so this isn't an error.

### Plan decisions, not design gaps

These are settled in the plan unless overruled:
- **Q11.** For the clock, boxing's `t` is `rounds + 1` when ties are disallowed. A sim game gets `d/(t+1)` per turn
  when roles are chosen.
- **Q12.** Rating limits use overall `elo_rating`. A character entry uses its owner's rating. Equal ratings are
  broken by time of entry.
- **Q13.** Tournament matches aren't live.
- **"Until k remain"** is read as "until at most k remain".

### Small items in the text

- **Bracket formula:** it's still missing a closing parenthesis.
- **`a < n`:** validation needs it as well as `a | n`. With `a = n`, nobody is demoted, and the formula divides by
  zero.
- **Ranking:** it still says `Stage.Draw` rather than `points_for_draw`, and has a stray line containing only `r`.
- **Consolation stage 1:** it still has the stray `tournament_design: elimination round` line.
- **Non-strict pooling:** "from highest strat to lowest seed" presumably means "from highest stratum to lowest".


## Decisions carried over from the first plan

These were settled in review of `tournament-plan.md` and are independent of the redesign.

- **D4. Tournament matches have no challenge.** `match.creator` comes back (`e26ac80`, reverted with #143), and
  `match.challenge_id` becomes nullable. A tournament match's creator is the tournament owner at the time it's
  created. Creating a match is pulled out of `GameEngineService.started` into a shared core with two front ends:
  from a challenge, and from a tournament. The tournament front end claims through a unique key on the match.
- **D5. A round's matches are created from the queue, in parallel.** One `MatchDue` message per match goes on the
  `MatchEndings` queue. The listener uses `parTraverseN` with a bound on engine calls, and a connection is borrowed per
  transaction rather than held across the engine call.
- **D6. A round's end is detected in `EndingService.settle`.** One transaction locks `tournament_round` `FOR
  UPDATE`, re-derives "every match done", and stamps it once.
- **D7, without live.** No scheduled sweep. The owner's **Check round** enqueues a `MatchCheck` for every unfinished
  match that could be overdue, rate-limited by `checked_at`.
- **D12. The owner may rank a match's seats when cancelling it.** `result.manual`. Refused on challenge matches.
  Corrections are allowed until the round completes.

These first-plan decisions are **replaced**:

| first plan | replaced by |
| --- | --- |
| D1 fixtures and `fixture_slot` | pools and pool members (Phase 3) |
| D3 slot filled by seed or by previous fixture | re-pooling by seed and stratum each stage (Q1) |
| D8 `i² − 1` points | the design's `points` / `points_for_draw` / bonus |
| D9 choosing roles inside the engine | choosing in matchmaker before the match is created (Phase 5). That's what the design's "turn time limits need to be adjusted down" describes, and it needs no engine change. |
| D13 `no_tie` | `ties_allowed` |
| D14 `preferred` role | `preference_level` |

The six-type enum, the type-specific phases (7–10) and the old SQL drafts (`tournament*.sql`) are dropped.


## Phase 1: Preliminaries

Four independent PRs, none of which depends on the tournament tables.

### 1a. `match.creator` and a nullable `challenge_id`

Re-apply `e26ac80` with `git cherry-pick e26ac80`, as `V50__match_creator.sql`. The local database never ran V50.
Before merging, check `flyway_schema_history` on RDS too: if the reverted V50 was ever applied there, the file must
come back byte-identical or be renumbered.

### 1b. The game catalogue: `V51__game_tournament_fields.sql`

- `game_role.type TEXT NOT NULL CHECK (type IN ('required','optional','recommended'))`, backfilled from `optional`,
  then `optional` is dropped.
  - The design says every existing role is `required`, but V4 created optional roles for some games, so the
    backfill copies what's there rather than assuming.
  - The Scala field is `roleType` (`type` is a keyword), with an enum `RoleType`.
- `game_role.preference_level INT NULL`. Higher means more preferred. Several roles may share a level.
- `game.move_type TEXT NOT NULL DEFAULT 'seq' CHECK (move_type IN ('seq','sim'))`, set to `'sim'` for boxing and
  rps by name.
- `game.max_turns INT NULL CHECK (max_turns > 0)`.
- No `significant_roles` column: the design now reads `game.unimportant_roles` (V49).

Code:
- Every reader of `optional` (`GameRepo`, `AcceptanceRepo` readiness at :285 and :339, `GameEngineService` :264,
  `Game.scala`, the `Json` DTO and the game editor) reads `roleType != Required`.
- The four `engines/*/register-game.sql` scripts insert `type`, and boxing and rps set `move_type`.
- The game editor gets a type select, a preference level, a move type and max turns, each with a `withTip`
  explanation, at mobile sizes.
- Tests: `GameRepoSpec` round-trips the new fields, `RouterSpec` is unchanged, and the readiness specs cover a
  `recommended` role (one a start doesn't wait for).

### 1c. `ties_allowed`

- **Protocol.** `CreateGameRequest.tiesAllowed: Option[Boolean] = None` in both copies (`engines/common/Protocol`,
  `matchmaker/engine/GameEngineClient`), sent only when false. An engine that predates it gets what it always got.
  The design calls it a "parameter". A typed field is recommended over a reserved key in `parameters`, which is
  built from the game's own `game_parameter` rows and the challenger's settings, and shouldn't hold a key the
  catalogue doesn't list.
- **Matchmaker.** `match.ties_allowed BOOLEAN NOT NULL DEFAULT true` (in V51 or its own migration), so a `refresh`
  that re-creates the request sends what the original did. Challenges don't get it. Nothing in the design asks for a
  checkbox on the challenge form.
- **Engines** (common decodes it and exposes it on the match state):
  - **Boxing.** If the scheduled rounds end level, one more round is fought. If that round is level too, its winner
    is drawn at random, and the summary says so.
  - **Stratego.** Where it would declare a draw (move cap, neither side can move), it scores each captured piece by its `strength`, and a bomb or spy by 1. The
    higher score wins, and equal scores are still a draw. The summary gives both scores.
  - **RPS.** A tied throw starts another throw, until somebody wins or the clock runs out.
  - **Tic-tac-toe** decodes it and ignores it. The design allows that.
- **Tests.** Per-engine `MatchState` specs for each rule, plus a protocol round-trip for the absent and false cases.

### 1d. A numeric score in results

The `scores` tiebreaker needs a number per seat, and `result.scores` is a free-form map.

**Recommendation:** a convention, not a column. A seat's score is `scores.score` when the engine reports a number
there. Boxing already keeps points (KO = 25, from `scratch`, goes in here). Stratego reports its capture points
always, not only when ties are disallowed. RPS reports throws won. Tic-tac-toe reports nothing. With no score, the
tiebreaker uses rank differentials: opponents ranked below minus opponents ranked above.

The common `resultsOf` keeps ranks 1 and 2. Placings for more than two seats wait for a game that has more than two.


## Phase 2: The model and the pure core

No database and no IO. This lives in `shared/` so the UI can validate a design and preview its pools. It is the
largest phase and carries most of the design's rules, so it's built and tested in full before anything persists.

### Types

```scala
case class TournamentDesign(stages: List[StageDesign])

case class StageDesign(
    repeat: Repetition,                 // Fixed(k) | UntilRemaining(k) | UntilStopped
    allowTies: Boolean = true,
    brackets: Boolean = true,
    demote: Option[Int] = Some(2),      // > 1
    promote: Option[Int] = None,
    maxStratum: Option[Int] = Some(0),  // None = unbounded
    minStratum: Option[Int] = Some(0),  // None = nobody is eliminated
    points: Int = 2,
    pointsForDraw: Int = 1,
    pointsBonus: Boolean = false,
    poolSize: Option[Int] = None,       // None = R
    roundDuration: Option[Duration] = None,  // None = inherited from the enclosing stage, then the tournament (Q10)
    design: Option[TournamentDesign] = None, // None = round robin
    rotateRoles: Boolean = false,
    concurrent: Int = 1,                // Q4: matches per participant per round
    strictStrata: Boolean = true,
    tieBreakerStage: Boolean = false,
    seedPolicy: SeedPolicy = WinnerTakesSeed,
    tiebreaker: Tiebreaker = Scores     // the design gives no default (Q6)
)
```

A design is a value, and the tournament stores a copy as JSONB (Phase 3). The design's recursion is through
`design`, so a sub-design written inline, like the consolation final in *Single Elimination with
Consolation*, is just a nested value. Nothing needs a table of designs.

### Validation

`TournamentDesign.validate(game): List[Problem]` covers:
- the constraints the design names: `demote > 1`, `a` dividing `n` under brackets, a pool no larger than the game's
  seats when `C(N, R)` would be 1;
- a scoring setting given on a stage with a sub-design (a warning: it's ignored, Q2);
- "only for games where required roles = 2", which is a property of the standard design and becomes a check;
- `minStratum ≤ maxStratum`;
- a sim game with no `max_turns` and none supplied;
- `Rubber` in a game with more than two roles (a warning: scores are used, Q7);
- no round duration at any level, so some match would have none to inherit (Q10);
- a cap on matches per pool (1000, Q4), so a pool of 10 in a 4-seat game can't schedule 210 matches;
- brackets requires `demote` to be set, `a < n` and `n mod a = 0` (Q1);
- a `Fixed(0)` repeat;
- a nesting depth cap.

### Standard designs

`StandardDesigns(r: Int)` holds every format in the design document as a function of `R`: Round Robin, Elimination
Match, Single Elimination, with Consolation, Modified Double, Double, Heats (pool size supplied by the creator),
Ladder, Brazilian Repechage, World Cup, Fencing and NCAA. Zero Elimination is deferred (Q8). Each one is also a test case.

Transcribe them with `tournament_design: round_robin` read as unset (Q2). Heats takes its pool size as a parameter (Q9).

### Algorithms

Each algorithm is a pure function, with property tests.

| function | rule |
| --- | --- |
| `poolCount(r, n, a, brackets)` | Q1, per stratum, with `k` floored at 0 |
| `snake(position, npools)` | the design's code, applied to a participant's position among its stratum's seeds (Q1) |
| `formPools(members, stage)` | strict strata pool each stratum alone. Non-strict strata go from the highest stratum down, filling a short pool from the next stratum, lowest seed first. |
| `schedule(pool, stage, game)` | the list of matches by round: `C(N, R)` subsets (Q3), times `R` under `rotate_roles`, packed so that nobody plays more than `concurrent` in a round: the circle method for `R = 2` and `concurrent = 1`, greedy otherwise (Q4). |
| `assignRoles(match, seeds, game, pass)` | `rotate_roles`: shifted cyclically per pass. Otherwise, with roles that matter, the roles with the highest `preference_level` go to the highest seeds, and the rest are chosen in seed order. With `unimportant_roles`: random. |
| `points(matchRanks, stage)` | the design's rule, with bonus |
| `rankPool(pool, results, stage)` | points, or match ranks, or the sub-tournament's ranking, then the tiebreaker, then, in a stage with `allow_ties: false`, the weaker seed (Q6), returning ties and pending rubbers (Q7) |
| `advance(stage, poolRankings)` | promote and demote with caps, eliminate anyone whose new stratum is below `minStratum` (Q5), then the seed policy |
| `rankTournament(stageRuns)` | final stage first, by `stratum_out`, the stratum after the stage, then pool rank (Q5), then tie-breaker stages most recent first, then earlier stages for the eliminated |
| `clock(game, duration, seats, tiesAllowed)`, with `duration` resolved by inheritance (Q10) | `seq`: `Total`, `d/n` per seat. `sim`: `PerTurn`, `d/(t+1)` when roles are chosen (one turn's time for the choice), `d/t` otherwise. |
| `entrantForfeits(match)` | multiple entries from one player in one match: all but the highest seed forfeit |

### The simulator

`TournamentSim` runs a whole tournament from a design, a field and a result oracle (random, seed order, or upset).
It drives the same state machine Phase 5 drives, as a pure fold, so that machine is tested here rather than through
the database.

```
State = (tree of node instances, entrants' seeds/strata/eliminated, pending matches)
step: State × results of the current round → State × next round's matches | Finished(ranking)
```

Property tests over every standard design and field sizes 2–64:
- Single Elimination of `N` takes `ceil(log₂ N)` repetitions, has one winner, and ranks `2^k` players tied at each
  depth.
- Nobody is in two matches in a round, and nobody eliminated plays again.
- Byes go to the top seeds.
- The round-one bracket is 1–N, 2–(N−1)…
- Double elimination's loser plays from stratum −1 and leaves on a second loss.
- Consolation ranks the final's loser second and the third-place winner third. Double elimination's winner ranks
  first whichever stratum they came from (Q5).
- A stratum smaller than a pool forms one pool if it can fill the required roles, and none otherwise (Q1).
- "Until k remain" stops at the first repetition that leaves at most k.
- An elimination that only ever ties still finishes, and the weaker seed advances every time (Q6).
- Ladder never terminates on its own, and moves the winner up and the last place down.
- A random oracle and a seed-order oracle both finish.
- A rubber settles a two-way tie in a two-player game. A three-way tie, or `Rubber` in a game with more roles,
  falls back to scores (Q7).

This is where most of the bugs in this design will be found, cheaply.


## Phase 3: Schema and repositories: `V52__tournament.sql`

Follows the house conventions: `GENERATED ALWAYS AS IDENTITY`, `game_id INT`, `TIMESTAMPTZ NOT NULL DEFAULT now()`
with explicit `trg_<table>_update_date` triggers, composite keys that carry `game_id`, and `game_type` discriminators
for the character siblings.

**Naming:** `participant` is already the table of seats in a match. The design's "participant" is called an
**entrant** in the schema and code, and "participant" in the UI only.

| table | holds |
| --- | --- |
| `tournament` | `game_id`, id, name, description, `owner`, `design JSONB`, `status` (`draft`, `open`, `running`, `finished`, `cancelled`), `auto_rounds`, `friendly`, `invitational`, `late_entries`, `min_rating`/`max_rating` with a CHECK, `entries_per_player`, `round_duration INTERVAL`, `max_turns`, `started_at`, `ended_at` |
| `tournament_invitation` + `character_tournament_invitation` | as V25 |
| `entrant` + `character_entrant` | `entrant_id`, `player_id` (the entering player), `entered_at`, `withdrawn_at`, `rating_at_entry`. A unique index on (tournament, player) while `entries_per_player = 1` is enforced by the service, so the limit is a service check, not a constraint. |
| `tournament_node` | an instance of a (sub)tournament: `parent_pool_id` (null at the root), `design_path` (`"2/0"`, the stage indices from the root, for reading the design node), `completed_at` |
| `node_member` | an entrant's state within a node: `seed`, `stratum`, `eliminated_at`. Seeds are updated in place (first plan D2). A deferrable unique (node, seed) allows swaps. |
| `stage_run` | node, `stage_index`, `repetition`, `started_round`, `completed_at`. Unique on (node, stage, repetition). |
| `pool` | stage run, `pool_no`, `stratum`, `completed_at` |
| `pool_member` | pool, entrant, `seed_in`, `stratum_in`, then on completion: `rank`, `points NUMERIC`, `score_diff`, `total_score`, `seed_out`, `stratum_out`, `eliminated`. This is the history that standings, brackets and "Previous" seeding read. |
| `tournament_round` | `round_no`, `started_at`, `deadline`, `completed_at`, `checked_at` |
| `pool_match` | pool, `match_no`, `round_no`, `rubber`, `ties_allowed`, `status` (`choosing`, `due`, `started`, `done`), `match_id` null until created. Unique (pool, match_no) is the claim that makes `MatchDue` idempotent (D4). |
| `pool_match_seat` | pool match, entrant, `game_role_id` (null while choosing), `choose_order`, `choose_started_at`, `chosen_at`, `forfeit` (multiple entries) |

Column changes on existing tables:
- `match` gets `(tournament_id, pool_match_id)` with a foreign key, and a CHECK that a match has exactly one origin,
  challenge or pool match.
- `result` gets `manual` (D12).

Repositories follow `repo-methods-return-case-classes`, with `lockForUpdate` / `requireXForUpdate` on `tournament`,
`tournament_round`, `pool_match` and `stage_run`. `tournament.design` is read plainly on the way to writing play,
the same exemption `game` has, with the comment at the read. Repo specs use the existing generators.


## Phase 4: Lifecycle

Routes. Each one is three changes: `Router.scala`, `local.routes` in `terraform/modules/api/main.tf`, and `routed`
in `RouterSpec`.

| route | does |
| --- | --- |
| `POST /games/{g}/tournaments` | create a draft: a design (a standard one with parameters, or custom) plus settings, validated by Phase 2 |
| `PUT /games/{g}/tournaments/{t}` | the owner edits. The design is editable until the start. After it, only settings that apply to future rounds (`auto_rounds`, `round_duration`, `late_entries`) and the owner. |
| `GET /games/{g}/tournaments`, `GET …/{t}` | list, and detail with standings |
| `POST …/{t}/open` | accept entries |
| `POST …/{t}/invitations` and character invitations | as V25 |
| `POST …/{t}/entries`, `DELETE …/entries/{e}` | enter, withdraw |
| `POST …/{t}/start` | freeze entries (late entries excepted), seed by rating (Q12), create the root node and its first stage run |
| `POST …/{t}/cancel` | the owner abandons it |

Entering checks the rating limits, the invitation, the entry count and the status under a lock on the tournament.
A **late entry** joins stratum 0 of every running top-level node, seeded by rating against that stratum, and plays
from the next stage run. A **withdrawal** marks `withdrawn_at`. The entrant isn't pooled again, and a match already
created for them is theirs to finish or forfeit. **Re-entering** clears `withdrawn_at` and keeps the stratum, as the
design says.


## Phase 5: Rounds

The round loop. Every step is one transaction with `FOR UPDATE` on the rows that decide it. No transaction is held
across an engine call.

### Start a round

`POST …/{t}/rounds` from the owner, or automatically when the previous round completes and `auto_rounds` is set:

1. Lock the tournament.
2. Load the instance tree.
3. Run Phase 2's step to get this round's matches across every active leaf pool, forming pools for any stage run
   that's starting.
4. Write `tournament_round`, the new `stage_run`/`pool`/`pool_member` rows, `pool_match` and `pool_match_seat`.
5. Commit.

Then a `MatchDue` is enqueued for each match whose roles are all set. Matches with roles to choose notify their
first chooser.

### Choose roles, in matchmaker, before the match exists

`POST …/pool-matches/{pm}/role` from the seat whose turn it is:
- The chooser's deadline is the choice's share of the clock: one per-turn limit for sim, or the seat's whole
  `Total` budget for seq.
- Time spent choosing is charged against that budget. When the match is created, each seat's choice is written as
  a `turn` row with its real start and end times, so `TurnRepo.timeUsed` and `dueFor` count it with no new clock
  logic.
- The last choice, or a seat with one role left, enqueues the `MatchDue`.
- A chooser who runs out of time forfeits, caught by **Check round**.

A UI for choosing roles before a match is created was asked for in `scratch`, and this is it.

### Create the match from the queue

The listener handles `MatchDue(game, poolMatch)` with the D4 core:
1. Transaction A claims the `pool_match` (status `due` → `started`) and writes `match` + `participant` with
   `creator` set to the owner and `ties_allowed`, the clock from Phase 2, and `friendly` from the tournament.
2. The engine call, with no connection held.
3. Transaction B re-reads under lock and records the urls.

A failure undoes the claim. The multiple-entries rule (Phase 2's `entrantForfeits`) is applied at claim time by
writing forfeit results.

### End of a match → end of round → advance

`EndingService.settle` gains the D6 step:
1. Lock the round and mark the `pool_match` done.
2. If every pool match in the round is done, stamp `completed_at`.
3. In the same transaction, run Phase 2's step to complete pools (write `pool_member` outcomes, or schedule
   rubbers), stage runs (promote, demote, eliminate, re-seed into `node_member`), repetitions, and nodes, which
   feeds the parent pool's ranking.
4. When the root finishes, write the final ranking and set `status = finished`.
5. After the commit, mail the owner and, if `auto_rounds` is set, start the next round, through the queue so settle
   stays short.

### Owner tools

- **Check round** (D7): `POST …/rounds/{n}/check`.
- **Cancel with ranks** (D12): the existing cancel route takes the optional body for a tournament match.
  `PUT …/matches/{m}/ranks` corrects until the round completes. The owner is read from `tournament` under lock, not
  from `match.creator`.
- **Stop** for `UntilStopped` stages: `POST …/{t}/stop` ends the running stage run after the current round and
  proceeds.

### Tests

The DB-backed specs follow the generous `caseTimeout` rule:
- a property spec that plays a small Single Elimination and a Round Robin end to end against a fake engine;
- idempotency of a duplicated `MatchDue` and a duplicated settle;
- a race between Check round and a result;
- a withdrawal mid-stage;
- a late entry;
- a role choice that times out;
- a player with two entries in one match.


## Phase 6: UI

Mobile and accessible throughout: 16px fields, 44px targets, labels, live regions for standings that refresh, and
explanations as `withTip`. No internal ids are shown. Every fetch goes through `Store.load`/`reload`.

- **Tournaments list** per game, plus "my tournaments" and invitations on the home page.
- **Create/edit.** Pick a standard design, then the settings. "Custom" opens a stage editor: a list of stages, each
  a form of the stage properties, with "play" as round robin, one match, or a nested design picked from the
  standards. Phase 2 validation runs live as the form changes.
- **Detail.**
  - Entrants and their seeds.
  - The current round with its matches and deadlines.
  - Per stage run: pools with standings (points, differential, rank, and promoted, demoted or eliminated).
  - A bracket view for bracket stages, drawn from `pool` history (Q1).
  - The final ranking.
- **Choose a role.** For the seat whose turn it is: the remaining roles, a countdown, and the choices already made.
  Linked from "matches waiting on me".
- **Owner controls.** Start round, check round, stop stage, cancel a match with ranks, and edit settings.


## Later

- Live tournaments (Q13, first plan D7).
- The scheduled sweep that enforces timeouts without the owner pressing Check.
- Placings for engines with more than two seats.
- Editing a running tournament's future stages.
- Tournament results feeding Elo differently from ordinary matches. Today they rate as ordinary matches unless
  friendly.


## How this splits into PRs

Each one is reviewable on its own, and no PR is opened while its predecessor has outstanding work.

1. `match.creator` (1a): a cherry-pick.
2. Catalogue fields (1b), with game editor UI.
3. `ties_allowed` in matchmaker and every engine (1c). It deploys the engines.
4. Numeric scores (1d), small, possibly folded into 3.
5. Design types, validation and standard designs (Phase 2, first half).
6. Pools, schedule, ranking, advancement and the simulator (Phase 2, second half).
7. Schema and repositories (Phase 3).
8. Lifecycle routes (Phase 4), with terraform.
9. Rounds: start, choose, create, settle and advance (Phase 5). This is the big one. If it gets too large, split
   role choice into its own PR, after a version where every role is assigned.
10. Owner tools: check round, cancel with ranks, stop (end of Phase 5).
11. UI (Phase 6), possibly split into read-only views first, then forms and owner controls.


## As built (branch `new-tournament`)

Where the implementation differs from the plan above:

- **State as JSON.** A tournament's progress (stage runs, pools, standings, sub-tournaments) is one JSONB column,
  `tournament.state`, read and written whole under the tournament row's lock, rather than the `tournament_node`,
  `node_member`, `stage_run`, `pool` and `pool_member` tables. Entrants, invitations and each round's matches
  (`tournament_match`, `tournament_seat`) are relational.
- **No `match.ties_allowed`.** Only tournament matches disallow ties, and the flag lives on `tournament_match`.
- **`match.tournament_id` is not on the `Match` model.** It is written by `MatchRepo.createForTournament`; adding a
  field to `Match` overflowed the Scala.js compiler's stack while it derived the codec.
- **Phase 1d is folded into 1c's commit.**
- **Check round is one queued message per check**, `TournamentRoundCheck`, rather than one `MatchCheck` per match. The
  request records the check and queues it; the ending function looks at the round's matches one at a time, each under
  its own lock.
- **D12 is a separate owner action**, `POST .../matches/{pool}/{no}/result`, rather than an optional body on the
  match cancel route. It cancels a running match and writes `manual` results. A recorded result stands; there is no
  correction route.
- **Boxing's knockout score.** A knockout winner's `scores.score` is their card points plus 25.
- **Heats are not brackets.** The standard Heats design's round-robin stage sets `brackets: false`.
- **A finished round mails its owner** (V53, `notify_tournament_round_done`), when the tournament goes on and its
  rounds are started by hand. The kind is a twelfth notification type, settable per player and per game like the
  others. Home lists such tournaments under "Rounds to Start" with a Start Next Round button.
