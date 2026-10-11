# Plan: Tournament state in tables

Replaces the `tournament.state` JSON column (V52) with tables. Written against branch `new-tournament`, where the
newest migration is `V53__tournament_round_notification.sql`.

## Why

Today a tournament's progress is one `jsonb` document, read whole and rewritten whole under the tournament row's lock
by every change. Two needs make that the wrong shape:

- **A ladder that runs forever.** A ladder is one stage repeated until stopped. Every repetition appends a stage run
  (its pools, members, matches and placings) to the document, and nothing removes them. So every recorded match
  rewrites a document that holds the ladder's whole history, and every action reads and parses it. The cost of one
  change grows with the ladder's age, and the total cost grows with the square of its history.
- **Standings across tournaments.** A player's pools in progress, their place in each, and a ladder's current order
  are wanted on screens that span tournaments. Inside JSON, every such question means loading and parsing every
  tournament involved.

Unpacking into tables means a finished stage run is written once and never read again by the rules. The rules load
only what is still being played, and standings become plain SQL.

## What stays the same

- **The model and its rules.** `model/tournament` remains an in-memory tree of pure functions: `Tournament.start`,
  `nextRound`, `record`, `advance`, `lateEntry`, `requestStop`. The simulator and the model specs keep running without
  a database.
- **One lock per tournament.** Every change still takes `tournament` `FOR UPDATE` first. That row is the lock for
  the tournament's whole tree, as the `player` row is for a player's notification settings (`NotificationRepo.lockSettings`).
  Reads of the child tables under it are not separately locked, and each such read says so in a comment, as the
  repository rules require.
- **Entrants, invitations and the round's matches** stay in `entrant`, `tournament_invitation`, `tournament_match`
  and `tournament_seat`.

## Phase 1: The rules stop reading history

Before any table exists, make the rules work on a tree that holds only what is still being played. That is the
property that lets the repository load the live part alone, and it can be proven against the simulator.

What the rules read today, and the change for each:

| Rule | Reads today | Change |
| --- | --- | --- |
| `step`, `completePool`, `finishRun`, `reseed` | the open run of each unfinished node | none |
| `roundComplete`, `findMatch`, `isRecorded`, `matchesWhere` | every pool, open or not | none needed: a match of a completed run is always finished |
| `finishNode` → `Ranking.rankNode` | **every completed run** of the node | rank from a per-member summary kept up to date as runs finish (below) |
| `withdraw`, `reenter`, `state.withdrawn` | a set kept in the state | read from `entrant.withdrawn_at`, passed in through `Env` |

**Ranking a node from summaries.** `rankNode` sorts participants first by the last run they were placed in, most
recent first. Within that run it sorts by still-in or eliminated, then by stratum, then by pool rank. Ties are
broken by their keys in the tie-breaker runs. So all it needs per participant is:

- the index of their last run and their `RunPlacing` in it. `finishRun` sets these on the node's `Member`, and that
  includes the members the run left sitting out;
- their `RunPlacing` in each tie-breaker run. These are kept as a short list on the `Member`, since tie-breaker
  stages are few and the standard ladder has none.

`Ranking.rankNode` is rewritten over these summaries, so `finishNode` no longer reads completed runs.

**Tests.**

- **Pruning makes no difference.** The simulator plays every standard design twice: once as today, and once with
  completed runs deleted from the tree after every step. The final rankings and every round's matches must be
  identical. This is the test that keeps the rules from coming to rely on history again.
- **Summary ranking matches today's.** The old `rankNode`, kept in the spec, and the new one agree over the
  simulator's runs.

## Phase 2: Schema

### Tables

Pool ids are already unique within a tournament (`nextPoolId`), so a sub-tournament is keyed by the id of the pool
it is played in, and the root is node 0. No new id sequence is needed.

```sql
ALTER TABLE tournament
    ADD COLUMN round          INT     NOT NULL DEFAULT 0,
    ADD COLUMN next_pool_id   INT     NOT NULL DEFAULT 1,
    ADD COLUMN stop_requested BOOLEAN NOT NULL DEFAULT false,
    DROP COLUMN state;

-- A (sub)tournament: the root (node 0), or one played in pool `node_id`.
CREATE TABLE tournament_node (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    node_id       INT    NOT NULL,
    path          INT[]  NOT NULL,          -- stage indices from the root design (designAt)
    stage         INT    NOT NULL DEFAULT 0,
    repetition    INT    NOT NULL DEFAULT 0,
    finished      BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, node_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament
);

-- A participant of a node: seed and stratum now, and the summary that ranks the node.
CREATE TABLE node_member (
    game_id          INT    NOT NULL,
    tournament_id    BIGINT NOT NULL,
    node_id          INT    NOT NULL,
    entrant_id       BIGINT NOT NULL,
    seed             INT    NOT NULL,
    stratum          INT    NOT NULL,
    initial_seed     INT    NOT NULL,
    eliminated       BOOLEAN NOT NULL DEFAULT false,
    last_run_no      INT,                   -- the RunPlacing of the last run they were placed in
    last_pool_stratum INT,
    last_rank        INT,
    last_stratum_out INT,
    last_eliminated  BOOLEAN,
    tie_breakers     JSONB,                 -- [RunPlacing], most recent first; usually absent
    final_rank       INT,                   -- once the node is finished
    PRIMARY KEY (game_id, tournament_id, node_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, node_id) REFERENCES tournament_node,
    FOREIGN KEY (game_id, entrant_id) REFERENCES entrant
);
CREATE INDEX node_member_entrant ON node_member (game_id, entrant_id);

CREATE TABLE stage_run (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    node_id       INT    NOT NULL,
    run_no        INT    NOT NULL,          -- position in the node's runs, from 0
    stage         INT    NOT NULL,
    repetition    INT    NOT NULL,
    completed     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, node_id, run_no),
    FOREIGN KEY (game_id, tournament_id, node_id) REFERENCES tournament_node
);
-- What the rules load: the runs still being played.
CREATE INDEX stage_run_open ON stage_run (game_id, tournament_id) WHERE NOT completed;

CREATE TABLE run_sitting_out (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    node_id       INT    NOT NULL,
    run_no        INT    NOT NULL,
    entrant_id    BIGINT NOT NULL,
    PRIMARY KEY (game_id, tournament_id, node_id, run_no, entrant_id),
    FOREIGN KEY (game_id, tournament_id, node_id, run_no) REFERENCES stage_run,
    FOREIGN KEY (game_id, entrant_id) REFERENCES entrant
);

CREATE TABLE pool (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       INT    NOT NULL,
    node_id       INT    NOT NULL,
    run_no        INT    NOT NULL,
    stratum       INT    NOT NULL,
    completed     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, pool_id),
    FOREIGN KEY (game_id, tournament_id, node_id, run_no) REFERENCES stage_run
);
CREATE INDEX pool_run ON pool (game_id, tournament_id, node_id, run_no);

-- A member of a pool. Points and rank are provisional while the pool is being played (Phase 5)
-- and final once `pool.completed`.
CREATE TABLE pool_member (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       INT    NOT NULL,
    entrant_id    BIGINT NOT NULL,
    seed_in       INT    NOT NULL,
    stratum_in    INT    NOT NULL,
    rank          INT,
    points        DOUBLE PRECISION,
    score_diff    DOUBLE PRECISION,
    total_score   DOUBLE PRECISION,
    stratum_out   INT,
    eliminated    BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, pool_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, pool_id) REFERENCES pool,
    FOREIGN KEY (game_id, entrant_id) REFERENCES entrant
);
CREATE INDEX pool_member_entrant ON pool_member (game_id, entrant_id);
```

### `tournament_match` holds every planned match

Today a round-robin pool's planned matches live in the JSON, and only the matches of the round being played are
copied into `tournament_match`, which makes two records of one match. Instead, `tournament_match` gets a row for every
planned match when its pool is formed, and the copy goes away:

- **New `tournament_match` columns:** `slot INT NOT NULL`, `rubber BOOLEAN NOT NULL DEFAULT false`, `round` nullable
  until the match is put in a round, and a new status `planned` before that. Its foreign key on
  `(game_id, tournament_id, pool_id)` goes to `pool`.
- **New `tournament_seat` columns:** `planned_role INT`, the seat's index into the game's seating roles from
  `SeatPlan.role`; and the outcome, `rank INT` and `score DOUBLE PRECISION`.
- **When a match is finished:** its status is `done`. That replaces `PoolMatch.outcome`, and the seats' `rank` and
  `score` are its placings.
- **Beginning a round** sets `round` and `status` on the rows of the slot being played, and fills in the seats as
  `TournamentRounds.seated` does today: role or choosing order, and forfeits.

### Migration

The branch is not merged, so no deployed database has V52 or V53 applied: check RDS's `flyway_schema_history` to
confirm. If that holds, I'd fold this schema into V52 and reset local tournament data, rather than ship a migration that
converts JSON documents nobody has. If any deployed database has tournaments, a Scala job reads each document and
writes its rows, between a migration that adds the tables and a later one that drops `state`.

## Phase 3: Repository

`TournamentStateRepo` with two operations, both inside the caller's transaction and under the tournament lock:

- **`loadLive(game, tournament): TournamentState`.** The tournament row (round, next pool id, stop requested), the
  unfinished nodes, their members, their open runs (`stage_run_open`), those runs' pools, members, sitting-out rows,
  matches and seats. That is one query per table, about seven round trips however old the tournament is, assembled
  into the in-memory tree.
- **`save(before, after)`.** Compares the loaded tree with the one the rules returned, keyed by the natural keys
  above, and writes only the differences: inserts for new nodes, runs, pools, members and matches, and updates for
  changed rows. Each table gets one multi-row statement (`UNNEST` of arrays) rather than one statement per row. Rows
  are never deleted.

**Tests.**

- **Round trip.** `save(empty, s)` then `loadLive` gives back `s` with completed runs pruned, for states taken at
  every step of simulator runs of each standard design.
- **Writes are bounded.** Recording a match in a ladder writes the same number of rows on its 200th repetition as on
  its 2nd.
- **Nothing outside the tournament changes.** `save` touches only rows of its own tournament.

## Phase 4: Switch over

- **The rules read and write through the repository.** `TournamentService.start`, the late-entry path,
  `TournamentRounds.begin`, `record`, `decide`, `stop` and `chooseRole` use `loadLive` and `save` in place of
  `readState` and `writeState`.
- **`begin` and the round's matches.** `begin` stops writing `tournament_match` rows itself: the rows exist from the
  pool's formation, and `save` writes the round they are put in.
- **Withdrawal.** `withdraw` and `reenter` stop touching the state. The rules see `entrant.withdrawn_at` through
  `Env`.
- **The JSON is removed.** `TournamentCodecs` keeps its design codecs; the state codecs go. `readState` and
  `writeState` go.

Every existing tournament spec keeps passing unchanged. They are the behavioural contract for this phase.

## Phase 5: Live standings

After a match is recorded, its pool's standings are recomputed from the pool's finished regular matches with
`Ranking.rankPool`, and written to `pool_member` as provisional points and ranks. The same function writes the final
ranks when the pool completes, so live and final standings can't disagree. A tie stays a tie until the pool ends,
because rubber matches are only scheduled once every regular match is played.

For a ladder, a player's position is their `node_member` row of the root node, meaning their stratum (rung) and
seed, which `finishRun` updates after every repetition.

## Phase 6: Reads and UI

`TournamentDetail` stops carrying a whole state. Every screen that shows a tournament reads from the tables:

- **The tournament page.** The live tree, plus the latest few completed runs of each node. Older runs come from a
  paged route, `GET /games/{g}/tournaments/{t}/runs?node={n}&before={run_no}`.
- **Standings across tournaments.** `GET /me/tournament-standings` returns the caller's pools still being played,
  each with their place in it, and their position in each ladder. It is one query joining `pool_member`, `pool`,
  `stage_run`, `tournament` and `entrant` on the caller's entrants, using the `(game_id, entrant_id)` indexes.
- **Home.** A "My Standings" section from that route.

Each new route is the three usual changes: `Router.scala`, `local.routes` in `terraform/modules/api/main.tf`, and
`routed` in `RouterSpec`.

## What one action costs afterwards

| Action | Today | With tables |
| --- | --- | --- |
| Record a match | read and rewrite the whole document (grows with history), plus the match row | about 7 reads of live rows, then 2–3 writes: the seats' placings, the match status, the pool's standings |
| Begin a stage run | rewrite the document | one multi-row insert each into `stage_run`, `pool`, `pool_member` and `tournament_match`/`tournament_seat` |
| Show a tournament page | read the whole document | the live tree plus a bounded page of history |
| A player's standings across tournaments | load every document involved | one query |

All of these stay independent of how long a tournament has run.

## How this splits into PRs

1. Phase 1 alone: a model-only change, provable by the simulator and safe to merge first.
2. Phases 2–4 together: schema, repository and switch-over cannot be merged apart without leaving two sources of truth.
3. Phases 5–6: live standings and the new reads, with their UI.

## Open questions

- **Folding into V52.** Is the branch still undeployed everywhere, so that this can replace V52 instead of adding a
  migration?
- **Joining and leaving a ladder.** Late entry joins stratum 0 at the next run. For a ladder that never ends, should a
  newcomer start at the bottom rung, or be placed by rating?
- **Players who stop playing.** Should a ladder drop players, or move them down a rung, after some number of
  repetitions without playing? Today a player who withdraws keeps their rung if they come back.
- **History kept.** Completed runs are kept forever. Should a ladder's old runs be archived after a time, with only
  each player's summary kept?
