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
- **D7. Pool sizes.** `pool_size` is on `stage` and `stage_model`, nullable. A stage with none takes
  `tournament.default_pool_size`, which is `NOT NULL`. `tournament_model.pool_size` is the default the create form
  suggests, which the creator may change; NULL suggests the game's R. So a standard format that took a pool size from
  its creator (heats) leaves its first stage unsized and suggests a size, and sizes its knockout stages explicitly so
  they don't inherit the heat size.
- **D8. Only admins make models.** An admin of the game, or an overall admin. A player can't create a custom design,
  so every tournament is made from a model and `tournament.tournament_model_id` is `NOT NULL`.
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
- **Models are every game's** (Phase 8). `tournament_model` and `stage_model` have no `game_id`; their ids are
  descriptive text (`world-cup`, `stage-2`, and `world-cup/stage-2` for an inner design). The models are the
  standard formats the code defines (`StandardModels`, each object with an `id` and a `version`), written by a site
  admin's sync. `tournament_model_game` says which models each game offers, as its admins choose; a new game offers
  every model it can play. Nobody makes a model by hand.
- **Removing a model:** `tournament_model.active` and `stage_model.active` (default true). A sync that finds a stored
  model the code no longer defines locks the tournaments made from it -- entering one takes the same lock. If none
  has an `entrant`, it deletes them, invitations, stages and root pools included, and then the model. Otherwise it
  sets `active = false` on the model, its stages and its inner models. A deactivated model, like one the game no
  longer offers, can't be chosen for a new or changed tournament. A tournament already made from it keeps it, edits
  included. Making a tournament, and offering a model in a game, take the model `FOR SHARE`, so a sync can't delete
  it from under them.

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

### Phase 1: An engine create is idempotent on the match id (done)

`engines/common`, independent of everything else, and mergeable first.

- **Repeat create of an existing match** (done): `GameEngine.createGame` catches the `ConcurrentModification` that
  `MatchStore.create` raises for a stored id, and reads the stored match. If it seats the same players (the same
  participant ids and subjects), the answer is that match's `CreateGameResponse`, and nothing changes. Otherwise it's
  `Refusal.Conflict` (409).
- **No store or route changes:** both stores already refuse a second create of an id. The repeat is answered `201`
  like the first, which `HttpGameEngineClient` accepts as it accepts any 2xx.
- **A cancelled match:** cancelling deletes it from the store, so a create sent after a cancel would make it again.
  Matchmaker never sends one: a cancelled match is `F`, and only a `D` match is created.
- **Tests:** in tic-tac-toe's `EngineSpec`, since the common library has no game of its own. A create sent twice
  returns the same urls and leaves a move made in between standing, and a create naming other players is refused.

Every engine (boxing, rps, stratego, tictactoe) picks this up through the common library, so no engine code changes.

### Phase 2: A match that exists before its engine game (done)

Matchmaker only, on today's tournament code. Challenges keep working throughout.

1. **Migration**, appended to today's V52, which Phase 3 rewrites whole:
   - `match.status` (`S`, `C`, `D`, `P`, `F`), backfilled `F` for completed or cancelled matches and `P` otherwise;
   - `match.completed` renamed `completed_at`;
   - `start` nullable;
   - `match_finished`, `match_waits_in_tournament` and `match_not_yet_created`;
   - `participant.challenge_id`, backfilled, with its foreign key to `match (game_id, challenge_id, match_id)`;
   - `participant.game_role_id` and `elo_start` nullable, with `participant_challenge_seated` keeping both required on
     a challenge seat. Phase 3 restates that check through `tournament_id`.
2. **`Match`** has `status: MatchStatus` and `start: Option[Instant]`. `MatchRepo` writes `Finished` whenever a match is
   completed or cancelled (`statusOf`), so a caller that cancels by copying with `cancelled = true` keeps the row
   consistent, and `complete` sets `F` with `completed_at`. The status is on the wire as its code.
3. **The challenge path** writes `P`, through the default. Its `undo` still deletes.
4. **Every reader of `match`:**
   - all twelve player and admin listings in `MatchRepo`, and the results in `ResultRepo`, begin
     `WHERE m.start IS NOT NULL`: a match is listed once it has begun. That also leaves out a tournament match cancelled
     before it began, which is over but has seats with no role and no ratings;
   - `GameEngineService.read` and `refresh` answer a match that hasn't begun as not found (`requireBegunMatch`), before
     reading its seats;
   - `MatchService.cancel` and `setFriendly` refuse it as a conflict: its tournament decides what becomes of it;
   - timeout enforcement, the archive sweep and ratings key on `due`, `completed_at` and finished matches, so they
     were renamed and need nothing else.
5. **Test:** `MatchServiceSpec` takes two tournament matches back to before they began, one due and one cancelled.
   Neither appears in the active, due, completed or results lists. Reading the due one is not found, and cancelling
   it is a conflict.

Left for Phase 6, when seats without a role are first written:
- `Participant.gameRoleId` is still a plain `GameRoleId` in the model. Every path that reads seats either filters to
  begun matches or refuses an unbegun one first, so no NULL role is decoded yet.
- `ParticipantRepo.selectLaterSeats` and `selectLaterCompleted` compare a seat's `create_date` with when another match
  completed, as a stand-in for "rated after that match". Once a tournament seat is created at pool formation and rated
  at launch, they must compare the time its rating was taken instead.

### Phase 3: Schema (done)

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
  in `design/reset-tournament-schema.sql` rather than as a migration. `TestMigration.ensure()` then migrates the test database the same way. No
  deployed database has V52.
- **Tests:** a migration spec that creates a model and copies it into a tournament. It checks that the constraints
  refuse:
  - a match with both origins;
  - a `C` match with urls;
  - an `F` match with neither `completed_at` nor `cancelled`;
  - a non-tournament seat without a role.

As built:
- `V52__tournament.sql` is `design/new-tournament.sql` with a header, and with `result.manual` (D12) carried over from
  the old V52.
- `ParticipantRepo.create` now writes a seat's `challenge_id`, taken from its match. The new V52 requires every seat to
  name its origin, and Phase 2 had added the column without writing it.
- `design/reset-tournament-schema.sql` takes the local database back to V51 before the new V52 is migrated.
- `TournamentSchemaSpec` copies a one-stage model into a tournament (root pool, round, atomic pool, entrant) in plain
  SQL. It checks that the constraints accept a scheduled match and a tournament seat with no role. It checks that they
  refuse a match with both origins, a `C` match with urls, an `F` match neither completed nor cancelled, a rubber match
  allowing ties, a challenge seat with no role, and a stage missing its subtype row or carrying the other kind's.
- **Known failures until Phase 6:** today's tournament code still reads `tournament.design`, `tournament.state`,
  `tournament_match` and `tournament_seat`. `TournamentRoundsSpec`, `TournamentServiceSpec`, `TournamentRepoSpec` and
  the tournament cases of `MatchServiceSpec` fail. So do two cases of `CancelReleaseSpec`, because settling any match's
  ending asks the tournament code whether the match was one of its own. Every other spec passes.

### Phase 4: Designs as rows (done)

**Schema** (in V52):
- `pool_size` moved from the atomic subtypes to `stage` and `stage_model`, nullable (D7);
- `tournament.default_pool_size INT NOT NULL` and `tournament_model.pool_size`;
- `stage_model.concurrent_rounds`, which `stage` already had, so that a model can carry it into the stages copied from
  it.

**Design types.** `StageDesign` follows the columns:
- `rubberMatch` replaces `tiebreaker`;
- `concurrentRounds` replaces `concurrent: Int`. True lets a round-robin pool play all of its matches in one round;
  false is one match per member per round. The tournament's own `concurrent_rounds`, when set, applies to every stage;
- `allowTies` is gone: `Ties.allowed` says a match may be drawn in a pool of three or more, never in a pool of two,
  and never in a rubber match;
- the per-stage `roundDuration` is gone: a round lasts the tournament's `round_duration`, and `Clock.duration` went;
- `name` and `description`, as the stage rows have them;
- seed policies take the SQL's codes (`CONST`, `ELO`, `PREV`, `WINNER`).

`Env` carries the tournament's default pool size and concurrent rounds. `Validation.validate` checks a tournament's
design with its default pool size; `Validation.designProblems` checks a model's alone, with the game's R for an
unsized stage. Neither checks round durations any more: the tournament carries one, and a round started by hand may
give another.

**Repositories:**
- `StageRows` maps a stage row to and from a `StageDesign`, the same for `stage` and `stage_model`, and follows a
  chain of stages with a guard against cycles;
- `TournamentModelRepo` lists, reads, creates, edits and deletes models. It reads every model of a game and all their
  stages in two queries. An inner design is written as an unclassified model of its own; editing a model rewrites its
  stages and inner models, and deleting it takes them with it;
- `StageRepo` copies a model's design into a tournament, with each inner design as a chain of the same tournament's
  stages, and reads a tournament's design back. Each chain is written last stage first;
- `PoolRepo.createRoot` writes the tournament's root pool;
- `TournamentRepo` is rewritten for the new columns. The round being played is read from `round` through
  `current_round`. `readState` and `writeState` raise until Phase 5.

**Services:**
- `TournamentService.create` and `update` take a model and a default pool size (the request's, else the model's, else
  the game's R) and copy the model's stages; a tournament changed to another model before it starts is re-copied.
  `detail` returns the design, read from the stages.
- `TournamentModelService` is for admins: list for anyone; create, update, delete and `addStandard` for admins of
  the game, through `GameAdmins.require`. Deleting a model a tournament was made from is a conflict.
- A new game is given the standard formats it can play as models (`TournamentModelService.seed`, called from
  `GameService.createOrUpdate`). An older game gets them from its admin's Add the Standard Formats.

**API.** `GET` and `POST /games/{gameId}/tournament-models`, `POST .../tournament-models/standard`, and `PUT` and
`DELETE .../tournament-models/{modelId}`, in Router, terraform and RouterSpec. A tournament request names its
`tournamentModelId` and `defaultPoolSize`. RouterSpec has no database, so non-admins being refused is checked in
`TournamentModelServiceSpec`.

**UI.**
- The create form chooses a format from the game's models, grouped by classification. Choosing one fills in its
  suggested pool size and late entries. Concurrent rounds and Public are added to the settings.
- The game's screen has a Tournament Formats section for its admins: each format with Edit and Remove, Add a
  Format (name, kind, suggested pool size, late entries, and the stage editor), and Add the Standard Formats. Every
  control is labelled, its explanation is behind a tip, and what a save did is announced in a live region.

**Tests:**
- `TournamentModelServiceSpec`: an admin adds, edits and removes; a non-admin is refused; a design that can't be
  played is refused; the standard formats are added once; a new game comes with them.
- `TournamentRepoSpec`: every standard model is read back as written; edit and delete, refused while a tournament
  uses the model; a tournament's columns and its copied stages.
- The rules' specs, for the pool-size rule, the ties rule and the rubber match.

**Known failures:**
- until Phase 5: the four cases of `TournamentServiceSpec` that start a tournament, since starting writes the state,
  which `TournamentRepo` no longer holds;
- until Phase 6: `TournamentRoundsSpec`, the tournament-match cases of `MatchServiceSpec`, and two cases of
  `CancelReleaseSpec`.

Everything else passes: 768 tests.

### Phase 5: Running state as rows (done)

The rules stay pure. `TournamentStateRepo.load` assembles a `TournamentState` from the rows, the rules work out the
next one, and `save` writes the difference. `write` is load-then-save, which is what `TournamentRepo.writeState` now
does; `readState` loads, and answers `None` before a tournament has started.

How the state maps onto the rows:
- **Nodes.** A node is a pool that scopes seeds: the root pool for the tournament, and a group for each
  sub-tournament. Its members are the entrants with SEED rows in it: the latest row gives the seed, stratum and
  elimination, and the first row gives the starting seed. A group that never seeded a sub-tournament (a nesting
  stage's pool too small to play) has none.
- **Runs.** A node's runs are its child pools grouped by stage and starting round. Its stage and repetition come from
  its open run, which every unfinished node has between calls, so they need no column. Who sat a run out is derived
  (open question 5).
- **Pools.** A pool's members are `pool_member` rows. The seed and stratum each came in with are read from SEED as of
  the pool's round, and the stratum each left with as of the next run's round.
- **Matches.** A match is a `match` row, and its seats are `participant` rows in seat order. A match has its round
  once it's no longer `S`, and an outcome once it's `F` with results, so recording a result is reading it.
- **Match numbers** aren't stored: a match's number is its place among its pool's matches by round and seats, which is
  stable from read to read. Matches are found by id; `Loaded.matchIds` maps each number to its id.
- **Who has withdrawn** is `entrant.withdrawn_at`, and the final ranking is `entrant.final_rank`.

What a save writes, each from the round after the one being played when the call began (`effective`):
- **New pools,** parents first, with their subtype row, their members, and their matches. A match is written `S` with
  the round its slot is planned for, and its seats with their planned role and turn to choose. The rules number new
  pools from `NewPoolIds` down (`Progress.start` takes the number to begin from), and the save gives each its real id.
- **Changes to pools:** results, and completion.
- **SEED rows** for members whose seed, stratum or elimination changed, replacing any written earlier in the same
  round.
- **A match given its round:** it moves to `C` while a seat has a role to choose, else `D`.
- **The tournament's** `current_round` and `stop_requested`, and the final ranks once it's over.
- **Round rows,** as they're needed. Each names the atomic stage played in it: that of a new or open pool, failing
  that the last one. A finished tournament's last seeds are written against the round after its last, whose row is
  written but never begun.

Two changes to the rules came with this:
- **Seeds stay a permutation of a node's members.** A renumbering (the Previous and Re-initialize policies, a late
  entry) numbers active members first and the eliminated after them, since SEED's key allows one member per seed per
  round.
- **A nested design must have exactly one stage** (`Validation`). Every match of a round is in one atomic stage
  (`round.stage_id`), and the groups of a nesting stage play their inner designs side by side. With more than one inner
  stage, one group could be playing a different stage from another in the same round. Every standard format already
  complies.

**Test.** `TournamentStateRepoSpec` plays whole tournaments through the database: single elimination with and without
byes, with a consolation match, double elimination, group round robins then a knockout, and heats with the
tournament's pool size. It saves every state, reads it back, and compares it with what the rules made, with results
written as an engine's report leaves them. A finished tournament's ranking is read back from `entrant.final_rank`.

**Known failures until Phase 6:** the rounds still went through `tournament_match` (fixed in Phase 6).

### Phase 6: Rounds on `match` rows (done)

`TournamentRounds` works on `match` and `participant` rows through a rewritten `TournamentMatchRepo`, and on the
state through `TournamentStateRepo`. A tournament match is identified by its `MatchId` everywhere: the routes, the
queue, the views.

- **Beginning a round** loads the state, gives the next slot of each pool its round (`nextRound`, then a save that
  moves those matches from `S` to `C` or `D`), and sets the round row's `start_date` and `deadline`. The deadline uses
  the owner's duration for this round if given (`POST .../rounds` takes `roundDurationSeconds`), else the tournament's.
  Then each match of the round is seated: who forfeits before play (withdrawn, a second entry of a player already
  seated, a character with nobody to play it), and the first chooser's clock started.
- **Choosing a role** is unchanged in its rules, on `participant` under the match's lock.
- **Creating a match** (`createMatch`, from the queue's `TournamentMatchDue`, which now carries the match id) locks the
  match, checks it is `D`, and launches it in one statement: status `P`, its start, clock and description. In the same
  transaction:
  - each playing seat gets its ratings, taken then, and its current player (D3, D6);
  - each forfeited seat gets a role nobody plays, so no seat of a begun match lacks one, a last-place result marked
    manual, and its seat finished with.

  Then the engine's game is made under the same id. If the engine can't be reached, the match goes back to `D` under
  that id (`backToDue`); Phase 1 makes the retry safe.
- **Too few seats to play.** Such a match is decided without being played: manual results, and the match called off.
- **A match's end** (`recordEnding`) loads the tournament, whose results are now its outcome. If the round is complete,
  it advances and saves (or finishes). `round.complete` is set once, which is what keeps a twice-delivered ending from
  mailing the owner twice or beginning two rounds.
- **The owner deciding a match** writes manual results for seats that have none and calls the match off; a begun
  match's engine is told through the ending.
- **The round check and stop** work as before, by match id. Stopping saves `stop_requested`.
- **Rating** leaves forfeited seats out (`eloSeatsForMatch`), since the engine reports only the seats it was told of.
- **`MatchRepo.createForTournament` is gone:** only `TournamentStateRepo` writes tournament matches.
- **Load's outcome rule:** a cancelled match counts as over with whatever ranks it has, as the old recording did.
- **Load's numbering:** matches are numbered within a pool by participant id rather than by role, so a choice of role
  doesn't renumber them.
- **Routes:** `.../matches/{matchId}/role` and `.../matches/{matchId}/result` replace the pool-and-number paths, in
  Router, terraform and RouterSpec. The queue message `TournamentMatchDue` carries `matchId`. Nothing deployed sends the
  old shape, so it isn't kept.
- **UI:** the chooser and the decider address a match by id, and the owner's Start Next Round has a "This round's
  length (hours)" field, blank for the tournament's.
- **Tests:** `TournamentRoundsSpec` runs on the new tables. `MatchServiceSpec`'s tournament matches are written with
  their pool, round and stage, and their seats with an entrant (`TournamentFixtures.begunMatch` and `begunSeat`).

Every matchmaker suite passes again: 794 tests, with no known failures left.

### Phase 7: Reads and the UI (done)

- **Live standings.** `Standings.provisional` gives each round-robin pool still being played the ranks and points its
  finished matches make (`Ranking.rankPool`, rubbers aside). The tournament's page applies it to the state it shows,
  and nothing saves it. Completed pools keep the standings their completion recorded.
- **Matches by id.** The tournament page's chooser and decider address a match by its id (Phase 6). Pools are still
  labelled A, B, C, and no id is shown as text.
- **Across tournaments.** `GET /me/tournament-standings` (Router, terraform, RouterSpec) lists every tournament the
  caller entered, those under way first: the entry (by its character in a character game), its seed and stratum from
  its latest SEED row in the root pool, whether it's out, how many are still in, and its final place once it's over. A
  ladder that runs for ever is always one of these.
- **UI.** The main page has a "Where You Stand" table: a column header for each column, a caption for screen readers,
  inside the scrolling wrapper the other standings tables use, and each tournament a link to its page.
- **Cleanup.** `tournament-tables-design.md` is marked superseded.
- **Tests.** `StandingsSpec` covers provisional standings. `TournamentServiceSpec` checks that a player's standings
  give an open tournament no seed, and a started one their seed among the entrants.

### Phase 8: Models shared by every game, synced from the code (done)

Replaces the admin-made, per-game models of Phase 4.

**Schema (V52, rewritten in place).**
- `tournament_model` and `stage_model` lose `game_id`. `tournament_model_id` and `stage_model_id` become text.
- `tournament_model` gains `version`.
- `tournament_model_game (game_id, tournament_model_id)` is new.
- `tournament.tournament_model_id` references the model alone.

**Code.**
- Each standard format is an object in a file of its own in `model/tournament/standard`, built for two players and
  spelling out its whole design, sharing nothing with another, so that no edit changes a model without its version.
  `StandardModels.all` lists them. Each has an `id` that is never reused and a `version` bumped whenever its file
  changes. Where a pool size follows the
  game, the stage leaves it unset, so it takes the tournament's default pool size.
- `TournamentModelService.sync` adds what is missing, rewrites a model whose code version is newer (or that was
  deactivated), and deletes or deactivates what the code dropped. It runs under a transaction-scoped advisory lock,
  since a row lock can't hold a model that isn't there yet.
- A game's admin offers and withdraws models. `GameService.createOrUpdate` offers a new game every active model with
  no design errors for it, in place of seeding per-game copies.
- `TournamentService` takes a model only if the game offers it and it is active, unless it is the model the
  tournament being edited was made from.
- The admin create, edit and remove routes, the Add the Standard Formats route, and the model and stage editors in
  the UI are gone.

**API.** `GET /tournament-models` (every active model), `POST /tournament-models/sync` (site admins), `GET
/games/{gameId}/tournament-models` (what the game offers), and `PUT` and `DELETE
/games/{gameId}/tournament-models/{modelId}` (offer and withdraw, for the game's admins). The route count is
unchanged.

**UI.** A game's screen has a Tournament Formats section for its admins. It lists every format with a checkbox for
whether the game offers it; a format the game can't play may be unticked but not ticked, and says why. Site admins
also see Sync Formats, which reports what it did in a live region.

**Tests.** The specs share every game's models with each other, so a model a spec makes up has an id of its own and
no classification (so a sync never removes it). `TournamentFixtures.model` adds a missing standard model under the
sync's lock and offers it in the game.

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
