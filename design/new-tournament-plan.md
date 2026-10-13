# Plan: the relational tournament model

Implements the data model in `design/new-tournament.sql`, which replaces V52. It supersedes the schema half of
`tournament-tables-design.md` (Phases 2–4 there). That document's Phase 1, "the rules stop reading history", still
applies, and is a prerequisite here.

Written against `a4e2b36` on `new-tournament`. V50–V53 exist only on this branch: none of them is on `main`, so V52 can
be rewritten in place rather than followed by a corrective migration.

## What changes

1. **Designs become rows.** `tournament_model` and `stage_model` hold the designs a player chooses from, and
   `classification` groups them in the create form's drop-down. Creating a tournament copies a model into
   `tournament` and `stage`. Today a design is JSON in `tournament.design`, built from `StandardDesigns` by key.
2. **Running state becomes rows.** `pool`, `pool_member`, `round` and the new columns on `match` and `participant`
   replace `tournament.state` (JSONB), `tournament_match` and `tournament_seat`.
3. **A tournament match is a `match` from the moment its round starts.** Its row, and a `participant` row per seat,
   exist while seats choose roles (`C`) and while the match waits to be created in the engine (`D`). The engine game
   comes later, with the match id matchmaker already gave it (`P`).

### What item 3 really costs

Matchmaker already chooses the match id: `GameEngineService.started` and `launchTournamentMatch` generate a UUID,
write the `match` row, then send that id to the engine as `CreateGameRequest.matchId`. So the request and response
formats do not change.

What changes is the meaning of a `match` row. Until now, a row was a game the engine either had or was about to have
within the same call. Under the new model, a row can sit for a whole round with no engine game, no urls, no `start`,
and seats with no role. Every reader of `match` and `participant` was written assuming that never happens. That is
the bulk of Phase 2.

It also changes what a retry means. Today a failed engine call deletes the match (challenge) or puts the
`tournament_match` back to `due`, and the next attempt uses a **new** id. Under the new model the match keeps its id,
so a create that reached the engine but whose answer was lost gets sent again **with the same id**. Today the engine
answers that with `ConcurrentModification` from `MatchStore.create`. Phase 1 makes it safe.

## The SQL as it stands

`design/new-tournament.sql` runs cleanly on a database at V51: checked inside a transaction that is rolled back.
`design/new-tournament-compile-fixes.diff` shows the changes that made it compile. The `set_update_date` triggers at
the end of the file are separate from that diff. Each table with `create_date` and `update_date` has one, and the
existing function keeps both columns.

## Decisions taken

- **D1. Everything belongs to the root tournament, and groups are pools.**
  - A round is played in one atomic stage, which `round.stage_id` enforces. A pool of an atomic stage plays matches,
    by the rules of that stage and of the tournament.
  - An inner design is a chain of `stage` rows of the **root** tournament. It starts at
    `nesting_stage.inner_first_stage_id` and follows each stage's `current_stage_id`. There are no inner `tournament`
    rows, so `tournament_id` on every stage, pool, seed, match and seat is the root's. The creator's settings live once,
    on the root.
  - Pools are subtyped, as stages are:
    - a **`pool_group`** is a pool of a `nesting_stage`: a group playing the inner design. The pools of that inner
      design have the group as their `parent_pool_id`, and the group's `pool_member.final_rank` is its inner ranking;
    - an **`atomic_pool`** is a pool of an `atomic_stage`, and the only kind that plays matches.
      `match.pool_id` references `atomic_pool`;
    - each tournament has one **root pool**, a `pool` row with neither subtype: no parent, no stage and no round. It
      is the parent of the top-level pools, so every other pool has a parent. Its members are the tournament's
      entrants, so it has no `pool_member` rows.
  - A tournament's final ranking is `entrant.final_rank`, and only there. A group's ranking is its
    `pool_member.final_rank`.
  - SEED rows are scoped by pool: the root pool for the tournament's own seeds, a group for its inner design's seeds.
    So two groups can each have a seed 1 in the same round, and an entrant's outer and inner seeds don't collide.
  - All groups move in step, because a round is one atomic stage. A group that finishes early waits, and one that
    repeats a stage plays another run in a later round.
  - Models stay reusable: `nesting_stage_model.inner_tournament_model_id` points at a model. Copying a model into a
    tournament copies the inner model's stages in as `stage` rows of the root.
- **D2. Every match of a pool is written when the pool is formed,** with status `S` (scheduled). `match.round` is the
  round it is planned for, so no `slot` column is needed. `match.rubber` marks a rubber match.
- **D3.** A seat's `player_id` is written when the seat is created, and the character's current owner is re-read at
  launch. To be revisited: `player_id` may move to a `plain_participant` table.
- **D4. `match.status` is the source of truth.** `F` means completed or cancelled.
- **D5.** Settled in the SQL:
  - `rubber_match` on `stage_model` replaces the tiebreaker choice;
  - `concurrent_rounds` is on `tournament` and `stage`;
  - `round_duration` is set on the tournament, and can be overridden when a round is started by hand;
  - `ties_allowed` is derived rather than designed (below).
- **D6. A seat's ratings are taken at launch.** `participant.elo_start` drops `NOT NULL` (`elo_role_start` already
  allows NULL), so a seat written when its pool forms has no rating yet. Only a tournament seat may lack `elo_start`:
  `participant_rated_or_tournament`. Both ratings are written when the match is created in the engine (`D` → `P`),
  as a challenge's are when it starts, so they come from the same moment, just before play. Neither is updated after.

The other rules that came with these:

- **Ties:** `match.ties_allowed` is decided by the size of the match's `atomic_pool`. It's true for a round-robin pool
  of three or more members, where a draw earns draw points, and false for a pool of exactly two, which is an
  elimination match that needs a winner. A rubber match is always false, whatever its pool, since it exists to break
  a tie. `false` is what's sent to the engine as `CreateGameRequest.tiesAllowed`.
- **SEED:** holds seeds and strata. A row is added only when seeds are recomputed or strata change.
- **Repetition:** a stage's `repetitions` or `remaining_participants` says how often it is played. With neither set, it
  repeats until the owner stops the tournament (`tournament.stop_requested`).
- **Renamed:** `next_stage` is now `current_stage`.

## Changes the SQL still needs

These all compile as written now. They are about what the code needs.

### Keep what the code relies on

- **`match.completed` is renamed `completed_at`, not dropped.** Status says that a match is over, and `completed_at`
  says when. The time is what the completed-match listings sort and page by, what the archive sweep's
  `match_unarchived` index is on (the index follows the rename), and what the API returns as `completedAt`. A check
  holds the two together: `CHECK ((status = 'F') = (completed_at IS NOT NULL OR cancelled))`.
- **`match_game_id_challenge_id_idx` is kept** (no longer dropped). It's the unique index that makes a challenge
  produce at most one match, which `GameEngineService.started` relies on against a double Start. The new unique index
  on `(game_id, challenge_id, match_id)` sits beside it, for `participant`'s foreign key.

### Columns the code needs (added)

These are now in the SQL:
- **`tournament.owner`**, with a foreign key to `player` and the index `tournament_owner`;
- **`tournament.status`** (`draft`, `open`, `running`, `finished`, `cancelled`);
- **`tournament.tournament_model_id`**, `NOT NULL`: every tournament is made from a model, since a player can't
  create a custom design. It has a foreign key to `tournament_model` (below);
- **`stage.rubber_match`**, matching `stage_model`;
- **`pool.round`** (NULL only for the root pool), with a foreign key to `round` and an index: the round a pool began in, which gives its members'
  SEED rows and groups the pools of one playing of a stage;
- **`pool_member.total_score`, `score_against` and `pool_points` as `double precision`**, since a points bonus makes
  1.5 and scores needn't be whole. `final_rank` stays `int`.
- **No `pool_member.stratum_change`:** seeds and strata are kept only in SEED. A member's stratum after a pool is
  their SEED row for the next round.

`tournament.tournament_model_id` has a foreign key to `tournament_model`, with an index so that deleting a model doesn't scan every tournament. A model can't be
deleted while a tournament made from it exists.

### Constraints (added)

These are now in the SQL:
- **`match`:**
  - the origin check also requires `(tournament_id IS NULL) = (round IS NULL)`. Under D2 a scheduled match has its
    round too, so a planned round's row exists before the round starts, with `start_date` NULL;
  - `match.stage_id`, the atomic stage the match is played in, set exactly when `tournament_id` is. The foreign keys
    `(…, round, stage_id)` to `round` and `(…, pool_id, stage_id)` to `atomic_pool` (`match_tournament_fk`) hold a
    match's round and pool to the same stage. Each references a new `UNIQUE (…, stage_id)` on its target;
  - `match_waits_in_tournament`: only a tournament match can be `S`, `C` or `D`;
  - `match_not_yet_created`: an `S`, `C` or `D` match has no `status_url`, `play_url` or `start`;
  - `match_finished`: status is `F` exactly when `completed_at` is set or the match is cancelled;
  - `match_rubber_decided`: a rubber match never allows ties;
  - `UNIQUE (game_id, tournament_id, match_id)`, for the participant foreign key below.
- **`participant`:**
  - a foreign key `(game_id, tournament_id, match_id)` to `match`, mirroring the challenge one;
  - `participant_role_or_tournament`: only a tournament seat may lack a role.
- **`tournament`:** `min_rating <= max_rating`, `entries_per_player >= 1` and `max_turns > 0`.

Pools are subtyped (D1), and `match.pool_id` references `atomic_pool`, so a match can only be played in a pool of
an atomic stage.

**Kinds keep the subtypes exclusive** (added). `stage_model`, `stage` and `pool` each have a `kind` column:
- `stage_model` and `stage`: `A` for atomic, `N` for nesting;
- `pool`: `R` for the root pool, `G` for a group, `A` for an atomic pool.

Each subtype has a `kind` column fixed by a check (`DEFAULT 'A' CHECK (kind = 'A')`, for example), and its foreign key
to the supertype includes `kind`, against a `UNIQUE (…, kind)` on the supertype. So a stage of kind `A` can't also
have a `nesting_stage` row, and the root pool can't have an `atomic_pool` row. On `pool`, `kind = 'R'` exactly when
there is no parent and no round, and `pool_root` is unique on `kind = 'R'`.

**Deferred foreign keys make the subtype row exist** (added; no triggers). Each supertype has a stored generated
column per subtype that holds the kind only when that subtype applies, and NULL otherwise:
- `stage_model` and `stage`: `atomic_kind`, `nesting_kind`;
- `pool`: `group_kind`, `atomic_kind`.

A `DEFERRABLE INITIALLY DEFERRED` foreign key on the key plus that column references the subtype's
`UNIQUE (…, kind)`. A foreign key with a NULL column isn't checked, so the subtype row is required exactly when the
kind calls for it, and the root pool needs none. Deleting a subtype row while its supertype remains fails at commit as
well. Code that creates a stage or pool writes both rows in one transaction, in either order.

### Contradictions to resolve

- **SEED is unique per pool, entrant and round** (resolved): `(game_id, tournament_id, pool_id, entrant_id, round)`.
  An entrant's seed in a pool is their row with the latest round at or before the one asked about, and the index
  serves that lookup. The primary key `(game_id, tournament_id, pool_id, round, seed)` stops two entrants sharing a
  seed number within one pool in one round. So when seeds change, every entrant whose number moved needs a row for
  that round. Otherwise, reading each entrant's latest row could show two entrants with the same seed.
- **The legacy local rows.** On the local database, today's V52 tournament matches and seats fail the new `match` and
  `participant` checks. No deployed database has them, so the local reset in Phase 3 deletes them before migrating.

### Open questions

1. **Resolved: stage order.** `stage.current_stage_id` (and `stage_model.current_stage_model_id`) points at the stage
   played after this one, as `next_stage_id` did. The stages are in order starting from `tournament.first_stage_id`
   (`tournament_model.first_stage_model_id`) and following each stage's pointer until it is NULL. The stage being
   played now is not a column on `stage`: it's `tournament.current_round` → `round.stage_id`.
2. **Resolved: inner designs and entrants.** Every row's `tournament_id` is the root's (D1), so `pool_member`, SEED
   and `participant` reach `entrant` through the tournament the entrants belong to.
3. **Resolved: each group's progress.** A group is a pool of its nesting stage, its inner pools name it as their
   parent, and its seeds are SEED rows scoped to it (D1). Groups move through the inner stages in step, one atomic
   stage per round.
4. **Resolved: the round-duration override isn't stored.** A round started by hand with another duration records only
   its `deadline`, which is all that's needed: the duration is `deadline - start_date`.
5. **Resolved: who sat a stage run out is derived, not stored.** Members sit out a run when `Pools.form` puts them in
   no pool, because their stratum is too small to fill a match. Only the final ranking (`finishNode` →
   `Ranking.rankNode`) needs to know: it counts them as placed in that run, level with the pool winners of their own
   stratum, rather than ranking them by an earlier run with those eliminated. When the tournament finishes, the
   members who sat out a run are those active when it began (not eliminated before its round, per SEED's
   `eliminated`, and not withdrawn) who have no `pool_member` in its pools: the stage's pools begun in that round
   (`pool.round`). The accepted gap: `entrant.withdrawn_at` is cleared on re-entry, so an entrant who withdrew before a
   run and came back later is taken as having sat it out.
6. **Resolved: ties.** Decided by the `atomic_pool`'s size: allowed with three or more members, not with two.
7. **Resolved: seats of a scheduled match.** A pool's matches and their seats are written together when the pool forms
   (D2). Each seat has its planned role and choose order, and its ratings are NULL until they're taken (D6). Who plays
   whom is stored, not recomputed.
8. **Resolved: when the ratings are taken.** Both at launch (D6).

## Phases

### Phase 1: An engine create is idempotent on the match id

`engines/common`, independent of everything else, and mergeable first.

- **Repeat create of an existing match:** `GameEngine.createGame` answers it with that match's `CreateGameResponse`,
  instead of letting `MatchStore.create` throw `ConcurrentModification`. The same match is one that exists, isn't
  cancelled, and has the same players and participant ids. A create that disagrees is a `409`.
- **Store:** `MatchStore` needs a `read` before `create`, or a create that returns what was already there. For
  DynamoDB that means catching the `ConditionalCheckFailed` from `attribute_not_exists(matchId)` and reading.
- **Response code:** `EngineRoutes` returns `200` for the repeat and `201` for the first create. Matchmaker's
  `EngineClient` accepts both.
- **Tests:** a common-library spec run by every engine's suite. A create sent twice returns the same urls. A conflicting
  second create is refused. A cancelled id isn't revived.

Every engine (boxing, rps, stratego, tictactoe) picks this up through the common library, so no engine code changes.

### Phase 2: A match that exists before its engine game

Matchmaker only, still with today's tournament code, so it is testable on its own. Challenges keep working throughout.

1. **Migration (in the rewritten V52):**
   - `match.status` (`S`, `C`, `D`, `P`, `F`) with the D4 backfill and checks;
   - `match.completed` renamed `completed_at`. About 30 SQL statements in the repos name it (`MatchRepo`,
     `ParticipantRepo`, `ResultRepo`, `ArchiveRepo`, `CharacterRepo`), and each is renamed with it.
   - `start` nullable;
   - `participant.game_role_id` and `elo_start` nullable, with their checks (D6);
   - `participant.challenge_id` with its backfill, the composite foreign key and the origin check.
2. **`Match`, `Participant` and their repos:** `status: MatchStatus`, `start: Option[Instant]`,
   `gameRoleId: Option[GameRoleId]`, `eloStart: Option[Int]`. `EloRatingService` rates a finished match from its seats'
   `elo_start`, which every played seat has by then. `MatchRepo.complete` and the cancels set `F` with `completed_at` and
   `cancelled`, in the same statement.
3. **The challenge path** writes `P` directly. Its `undo` still deletes, because a challenge match has nothing to
   return to: it was never `D`.
4. **Audit every reader of `match`.** The SQL lives in `MatchRepo`, `ParticipantRepo`, `ResultRepo`, `ArchiveRepo` and
   `CharacterRepo`; the services that read through them are `GameEngineService`, `EloRatingService` and
   `ArchiveService`. Each query that lists or acts on matches either takes `status IN ('P', 'F')` or says why it
   doesn't need to. The known cases:
   - player and character listings (`listActiveForPlayer`, `listDueForPlayer`, `clocksForPlayer`, the public
     variants, `listForGame`): exclude `S`/`C`/`D`. Choices are already shown through `TournamentRounds.choicesFor`.
   - `enforceTimeouts` and `listOverdueForMatch`: `due` is NULL before launch, so they're safe, but say so.
   - `refresh` and `read`: an `S`/`C`/`D` match has no `status_url`. Answer with the match rather than "no status url".
   - cancel: an `S`/`C`/`D` match has no engine game and no `cancel_url`, so nothing to release.
   - archive sweep and ratings: keyed on `completed_at`, so unaffected apart from the rename.
5. **Tests:** existing suites pass unchanged. A new spec inserts an `S` and a `D` match with seats and no role, and
   shows that every listing and the timeout enforcer ignore them.

### Phase 3: Schema

Rewrite `V52__tournament.sql` from `design/new-tournament.sql`, with the changes and the answers to the open questions
above. V53 (the
round-done notification) is unaffected and keeps its number.

- **Tables, in dependency order:**
  - `tournament_model`, `stage_model` and their atomic and nesting tables;
  - `tournament`, `stage`, `atomic_stage`, `nesting_stage`, `round`;
  - `tournament_invitation`, `tournament_character_invitation`, `entrant`, `character_entrant`;
  - `pool`, `pool_group`, `atomic_pool`, `pool_member`, `seed`;
  - then the `match`, `participant` and `character_participant` changes;
  - then the `set_update_date` triggers.
  - The circular foreign keys (`first_stage_id`, `current_round`) go in trailing `ALTER`s, as the SQL already has
    them.
- **Dropped compared with today's V52:** `tournament.design`, `standard_design`, `state`, `round_started_at` and
  `round_deadline` (they move to `round`), and the tables `tournament_match` and `tournament_seat`.
- **The local database:** V52 changes shape, so:
  - delete today's tournament matches and their seats, turns and results, which fail the new checks;
  - drop the tournament tables, and revert the `match`, `participant` and `result` changes;
  - then delete the V52 and V53 rows from `flyway_schema_history`, and re-migrate. Write this as a script
  in `scratch/` rather than as a migration. `TestMigration.ensure()` then migrates the test database the same way. No
  deployed database has V52.
- **Tests:** a migration spec that creates a model and copies it into a tournament. It checks that the constraints
  refuse:
  - a match with both origins;
  - a `C` match with urls;
  - an `F` match with neither `completed_at` nor `cancelled`;
  - a non-tournament seat without a role.

### Phase 4: Designs as rows

1. **`TournamentModelRepo` and `StageRepo`:**
   - read a model, or a tournament's stages, as the `TournamentDesign` the rules already take: from
     `first_stage_id` along each stage's `current_stage_id` and, for a nesting stage, recursing into its inner model;
   - write a `TournamentDesign` back as model rows, for an admin's new or edited model. A player can't create a
     custom design, so tournaments are only ever copied from models;
   - copy a model into a tournament in one transaction: its stages, the stages of every inner model as stages of the
     same tournament (D1), and the root pool. A stage's `current_stage_id` must reference a stage that's already
     there, so each chain is inserted last stage first;
   - guard the walk along the stages against cycles, which the schema can't rule out.
2. **Seed the models** from `StandardDesigns`. `tournament_model` is per game, so seed when a game is registered, and
   with a backfill for existing games. `classification` takes the standard design's grouping: single elimination,
   round robin, ladder, and so on.
3. **Who makes models: admins only.** A game's models are created, edited and deleted by an admin of that game
   (`game_admin`) or an overall admin (`player.is_admin`), the check `ChallengeService.requireGameAdmin` already makes (moved somewhere both services can share). Letting any
   player make one is left for later. No creator column is needed. A model that a tournament was made from can't be
   deleted, because `tournament.tournament_model_id`'s foreign key refuses it. Editing a model doesn't change
   tournaments already made from it, since creating a tournament copied its stages.
4. **The API:**
   - `GET /games/{id}/tournament-models` lists them, for any signed-in player, for the drop-down.
   - `POST /games/{id}/tournament-models`, `PUT` and `DELETE .../tournament-models/{modelId}` are for admins. A
     write validates the design (`Validation.validate`) before saving it.
   - `TournamentRequest` names a `tournamentModelId` instead of `standardDesign`.

   Each new route needs Router, terraform `local.routes` and RouterSpec `routed`. The admin routes also need
   RouterSpec cases refusing a player who isn't an admin.
5. **The UI:**
   - The create form's design drop-down groups by `classification`, with each model's description in its `withTip`.
   - The game's admin page gets a models section to list, add, edit and remove them. It's mobile-first, with 16px
     fields, a real label on each control, and live-region messages for saves and refusals.

`Validation.validate` is unchanged: it validates the `TournamentDesign` read from the rows. `StageDesign` follows the
columns:
- `rubber_match` replaces `tiebreaker`;
- `concurrent_rounds` replaces `concurrent: Int`;
- `allowTies` and the per-stage `roundDuration` go;
- seed policies take the SQL's codes (`CONST`, `ELO`, `PREV`, `WINNER`).

### Phase 5: Running state as rows

Keep the rules pure. `Tournament.advance` stays a function from one state to the next. What changes is how that state
is loaded and saved.

1. **Prerequisite:** `tournament-tables-design.md` Phase 1. The rules must not read completed runs, beyond a member's
   last result and the tie-breaker placings, or loading the whole history on every call is the cost of this.
2. **`TournamentStateRepo.loadLive(root)`:** reads the tournament (D1), assembling a `TournamentState` from:
   - each entrant's current SEED row (latest round) in each pool it's scoped by: the root pool, and the groups still
     playing. The rules' `Node` tree maps onto the pool tree: the root pool is the root node, and each group is a
     sub-tournament node;
   - the pools not yet complete, with their `pool_member`s;
   - the matches still to be played, with their seats.

   About six queries, all under the outer tournament's `FOR UPDATE`.
3. **`save(before, after)`:**
   - diffs the trees and writes only what changed: new pools and their scheduled matches, `pool_member` results,
     and `completed_at`;
   - writes a SEED row only for an entrant whose seed or stratum changed, as the table intends;
   - writes nothing for `StageRun.sittingOut`. When a tournament finishes, the ranking works out who sat each run out
     (open question 5) and passes it to `Ranking.rankNode` as today.
   - writes several rows per statement (multi-row `UNNEST` inserts) rather than one statement per row.
4. **Pool ids:** `pool.pool_id` is an identity column, so new pools are inserted before their matches, and the returned ids are put back into the tree before those are written. The rules number new pools
   with negative placeholders (`-1`, `-2`, …), in place of `nextPoolId`, and `save` replaces each placeholder with the
   real id. This is the remapping the earlier discussion said an identity column costs. It sits in one function and
   is tested against the simulator.
5. **Simulator test:** play whole tournaments of every standard design through load, advance and save against the
   database. Check that each round matches what the in-memory simulator produces from the same results.

### Phase 6: Rounds on `match` rows

`TournamentRounds` moves from `tournament_match`/`tournament_seat` to `match`/`participant`. The flow is unchanged.

- **Forming a pool** writes all its matches as `S` (D2), each with a fresh UUID and the round it is planned for, and
  with `ties_allowed` true for an atomic pool of three or more members and false for one of two (a rubber match is
  always false). Its seats are written with them, each with its
  planned role and its choose order. Its ratings stay NULL for now (D6).
- **Starting a round** sets the `round` row's `start_date` and `deadline`, and sets `tournament.current_round`. The
  round's matches move from `S` to `C` (or `D` when no seat chooses). It's all one transaction.
- **The round's duration** is `tournament.round_duration`, unless the owner starting a round by hand gives another. So
  `POST .../rounds` takes an optional duration in its body, which goes only into `round.deadline`. The route already exists, so terraform doesn't change.
  RouterSpec's body for it does change, and the UI's Start round control gets a duration field (16px, labelled),
  defaulting to the tournament's.
- **Choosing a role** updates `participant.game_role_id` and `chosen_at` on the match locked `FOR UPDATE`. When the
  last seat chooses, the match becomes `D` and `endings.tournamentMatchDue` is enqueued, as today.
- **Creating the match** is `TournamentRounds.createMatch`, keyed by `match_id` instead of `(pool_id, match_no)`. It
  locks the match, checks it is `D`, and writes `start`, the current character owner (D3), and each seat's
  `elo_start` and `elo_role_start` (D6), taken by `EloRatingService.startingRatings` as a challenge's start takes
  them. Then it commits.
  Then `launchTournamentMatch` sends the existing id. On failure the match **stays `D`** under the same id, and the
  queue retries it; Phase 1 makes that safe. `undo` goes away for tournament matches. On success the match becomes `P`.
- **Ending** is unchanged in shape. `recordEnding` finds the pool through `match.pool_id` rather than through
  `TournamentMatchRepo.findByMatch`.
- **Forfeits and the round check:** `forfeit` moves from `tournament_seat` to `participant`. The deadline comes from
  `round.deadline`.
- **Queue messages:** `TournamentMatchDue` carries a `matchId` instead of `poolId` and `matchNo`. Accept the old shape
  in `parseAny` until the queue has drained. A message still in flight during deploy would otherwise be undeliverable.
- **Stopping:** a stage with neither `repetitions` nor `remaining_participants` repeats until the owner stops it.
  `stop` sets `tournament.stop_requested`, as today's `requestStop` sets the flag in the state.
- **Routes:** `.../matches/{pool}/{no}/role` and `.../result` become `.../matches/{matchId}/role` and `.../result`.
  Changing a route is the same three changes as adding one: Router, terraform and RouterSpec.
- **Delete:** `TournamentMatchRepo`, `TournamentMatch`, `TournamentSeat`, `TournamentRepo.readState`/`writeState` and
  the state JSON codecs in `TournamentCodecs`.

### Phase 7: Reads and the UI

- **Tournament detail:** reads the current round's matches and the open pools' standings from the tables. Standings
  are `pool_member` points while a pool plays, using `Ranking.rankPool` over its finished matches (earlier plan's
  Phase 5).
- **The match's place in the tournament:** the UI identifies a tournament match by `matchId` instead of pool and
  number. Still no ids are shown as text: pools are labelled A, B, C, as now.
- **Across tournaments:** `GET /me/tournament-standings` reads `pool_member` joined to `entrant` by player. The same
  query serves a forever-running ladder's current standing.
- **Cleanup:** `tournament-tables-design.md` gets a note that it is superseded here. The untracked `Bracket.scala`,
  `FixtureRepo.scala` and `TournamentLayout.scala` use an older design's `fixture` tables, and none of this plan uses
  them.

## How this splits into PRs

1. **Phase 1:** the engine's idempotent create. Small, independent, and safe to deploy before anything else.
2. **Phase 2:** a match that exists before its engine game. It touches every match reader, so review it on its own,
   before tournaments use it.
3. **Phases 3–6 together:** the new V52, designs as rows, state as rows, and rounds on matches. They can't be merged
   apart without the code reading tables that the migration has removed, or the reverse.
4. **Phase 7:** the reads and the UI.

PRs 1 and 2 can be written while the open questions are being answered. Only PR 3 depends on them.

## Risks

- **Phase 2's audit is the most likely place for a bug.** A missed query shows a player a match they can't open, or
  enforces a clock that hasn't started. Grep for `FROM match`, `JOIN match` and `participant p` in every repo, and
  give each one a test, rather than relying on the listings that happen to have specs.
- **Placeholder pool ids in `save`.** If one is left unmapped, it inserts a negative foreign key. The constraints
  catch that, but only at runtime, so the simulator test (Phase 5.5) is what proves it.
