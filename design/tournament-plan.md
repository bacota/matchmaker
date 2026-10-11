# Plan: Tournaments

Implements `tournament-design.md` with the data model in `tournament.sql`. Written against the code as of
`3ffa5a8`.

The design describes six tournament types in three classes, and gives each its own rules for scheduling, advancing
and ranking. Building them all at once would produce one huge PR. This plan does three things in order:

1. Three changes outside tournaments that the tournament work depends on:
   - a match's creator moves onto `match`;
   - engines learn a "no tie" option;
   - engines learn to let players choose their roles at the start of a match.
2. The shared tournament machinery, then one type that goes all the way from sign-up to final ranking (SingleElim,
   with RoundRobin).
3. The other types, one PR at a time.


## Decisions

Each item below settles a gap or a conflict between the design, `tournament.sql` and the code. Items marked
*(settled)* were decided in review. The rest are recommendations the plan follows unless they're overruled before
the phase that depends on them.

### D1. A fixture is a pool, and its matches are created straight from the schedule *(fixture_slot settled)*

A fixture is a pool. Its slots (`fixture_slot`, now numbered by `slot_id` rather than keyed by role) are the places
in the pool. A pool can hold more players than one match does: a pool of 4 in a two-player game plays 6 matches.

The matches a pool plays are worked out from its slots by the Phase 2 schedule when the round starts, and written
directly as `match` and `participant` rows. No table for scheduled matches is needed, because roles are chosen
inside the match (D9). `match` carries `(tournament_id, fixture_id, match_no)`, and a unique index on those columns
makes creating match k of a fixture idempotent (D4).

In the common case (pool size equals the game's player count, no rotations) a fixture is one match.

### D2. Seeds are updated in place *(settled)*

A new seed is written over `tournament_participant.seed`. The old seed isn't kept, except that `participant.seed`
records the seed a player held when they sat down in each match, which is what a past round's bracket is drawn
from.

Seeds are swapped in place at the end of every round. `UNIQUE (game_id, tournament_id, seed)` is therefore declared
`DEFERRABLE INITIALLY DEFERRED`, or the first half of a swap would violate it.

### D3. A slot is filled either from a previous pool or from a seed

`prev_fixture_id` plus `rank` can't describe the first round or a reseed round, because those are scheduled by seed.

**Recommendation:** `fixture_slot` gets a nullable `seed int`, with a CHECK that exactly one of these holds: `bye`,
`seed IS NOT NULL`, or `prev_fixture_id IS NOT NULL AND rank IS NOT NULL`. A slot with none of them can't be
resolved, and without the CHECK a slot with two of them would be resolved by whichever the code looks at first.

### D4. Tournament matches have no challenge *(settled)*

`match` gets a `creator` column (Phase 0), so nothing needs `challenge` to say whose a match is, and
`match.challenge_id` becomes nullable. A tournament match's creator is the tournament owner at the moment it's
created.

Creating a match is pulled out of `GameEngineService.started` into a shared core with two front ends:

- **The core.** Transaction A writes the match and its participants. Then comes the engine call, with an undo if
  it fails. Then the urls are recorded, followed by `applyEngineStatus` and the `matchStarted` mail. This is the
  existing code moved, not rewritten. It keeps the rule that nothing read before the gap is trusted after it.
- **From a challenge.** What `started` does today: lock the challenge, claim it, build the roster from the
  acceptances. Undoing deletes the match and releases the claim.
- **From a fixture.** Lock the fixture's round. Build the roster for match k from the schedule: each seat gets a
  role, or no role if it's to be chosen (D9). Insert the match. The unique `(tournament_id, fixture_id, match_no)` is
  the claim, so a second attempt fails on it rather than making another match. Undoing deletes the match, so a retry
  starts clean.

A failure after the engine has made its game leaves the claimed, urlless match that `refresh` already repairs, as it
does today.

A CHECK on `match` requires exactly one origin: `challenge_id IS NOT NULL` or `fixture_id IS NOT NULL`.

### D5. A round's matches are created in parallel, from the queue *(settled)*

A round of 32 matches is 32 engine calls, around a second or two each and longer from a cold start. In one API
request they'd run up against the 29-second gateway limit, and a timeout would leave the round half started. So
"Start round" commits the round's state and enqueues the work on the queue `MatchEndings` already uses. The work is
then run in parallel in three ways:

- **One message per match.** `MatchEnded | RatingsChanged` gains `MatchDue(gameId, tournamentId, fixtureId,
  matchNo)`. A pool's schedule is fixed by its slots, so the listener can work out match k from the message alone.
  A per-fixture message would create a pool of 4's six matches one after another.
- **The listener handles its batch in parallel.** `ending/Handler.scala` handles its up-to-10 records one at a time
  today. It moves to `IO.parTraverseN`, as `ArchiveService` already uses, with a bound on engine calls in flight
  (start at 8). The existing deadline check still stops a copy from starting what it can't finish. A message not
  reached, or one that failed, is reported as a batch item failure and delivered again.
- **More copies at once.** `ending_max_concurrency` goes from 2 to 10 (Phase 4).

None of this would help if every start kept a database connection through its engine call, because the listener's
pool is 2 (`DB_POOL_SIZE` in `ending.tf`). `started` does keep its connection today: it runs both transactions and
the engine call between them on one session. So the fixture front end of D4 borrows a connection for transaction A,
returns it during the engine call, and borrows one again for transaction B. Nothing crosses the gap anyway, since
the second half already re-reads everything under lock. Connections are then held only for the length of a
transaction, and the engine is the only limit on parallelism. The challenge path keeps running on its caller's
session in `startIfReady`, because borrowing a second connection there is the pool deadlock its comment describes.

Creation is idempotent (D4), so a redelivered message or an owner pressing **Resume** can't make a second match.
Locally, `MatchEndings.inline` already runs the work in the background.

Round starts share the queue with match endings, so a large round start delays end-of-match settling (leaderboard
placement, archiving) by a few seconds. Nobody waits on that.

### D6. A round's end is detected in `EndingService.settle`

`settle` is already called for every way a match can end: results, a completion found by refresh, a forfeit, a
cancel. It's idempotent, and it runs outside the request.

It gains a third step: if the match belongs to a tournament, re-check its round. The check is one transaction that
locks the `tournament_round` row `FOR UPDATE`, re-derives "every fixture done", and stamps `completed_at` once.
Bye-only fixtures, standings, reseeding and the owner's notification all follow from that stamp. The notification is
sent after the commit.

### D7. Timeouts, Check round, and live tournaments *(settled: no scheduled sweep in this plan)*

`enforceTimeouts` runs only when someone reads a match. A round where a player has stopped looking would never end.
Phase 4 gives the owner **Check round**, which runs the timeout enforcement on every unfinished match in the round.
A role choice is an engine turn (D9), so an overdue choice is caught the same way.

**The check runs from the queue, in parallel.** Checking a match that looks overdue means a status call to its
engine before anything is enforced, and a forfeit can follow it. A round of dozens of matches checked in one
request would hit the 29-second gateway limit, exactly as starting them would (D5). So:

- **The request only enqueues.** **Check round** enqueues one `MatchCheck(gameId, matchId)` per unfinished match
  and answers at once. The page says how many matches are being checked.
- **No separate report.** A forfeit is settled like any other ending, so the round completes and the owner is
  notified by the usual path (D6).
- **The listener runs checks with D5's bounded parallelism.** It handles `MatchCheck` alongside `MatchDue`.
- **Only matches that could be overdue are enqueued.** The request skips any non-live match where no seat's `due`
  has passed. A check of a healthy round that isn't live therefore costs one query and no engine calls. Live
  matches are always enqueued; see below.
- **No connection is held during the engine call.** `enforceTimeouts` and `applyEngineStatus` take no transaction
  across the status call, but they keep the caller's session through it. With the listener's pool of 2 that would
  cap a copy at two checks at a time. So the narrow `GameEngineService.enforce(gameId, matchId)` that the listener
  calls borrows a connection per step: the overdue query, then the engine call with none held, then the
  transaction that writes the answer. That's the same change as D5's fixture start. The paths players use (`read`,
  `refresh`) keep their single session.
- **A failed check isn't retried.** An engine that can't be reached means nothing is enforced, which is already
  `enforceTimeouts`' rule. The owner can press again, and the next time anyone reads the match enforces it too. The
  listener logs the failure and reports the message done.
- **Repeated presses are cheap.** Enforcement is idempotent. To keep a burst of presses from flooding the queue,
  the round records `checked_at`, and a check within a minute of the last one is answered "already checking"
  rather than enqueued again.

The chess clock (`TimeLimitKind.Total`, half the round's duration per player) already exists, and so does turn
tracking (`turn`, V10/V26). The design's "turns must be tracked" needs nothing new.

**Whether matches are live is the tournament's setting** *(settled)*. `tournament.live BOOLEAN NOT NULL DEFAULT
false` governs every match in the tournament.

- **Edits apply only to future rounds.** When a round starts, `tournament.live` is copied onto
  `tournament_round.live`, and every match of that round takes the round's value. The round's matches are created
  by queued messages after the round starts (D5), so reading the tournament's own column at creation would let an
  edit mid-round split a round between the two. The owner may change `live` at any time, and the change takes
  effect at the next **Start round**.
- **The clock is the same either way.** The time limit is `duration / 2` as a chess clock (`TOTAL`). In a live
  match it's sent to the engine as `LiveTerms(limit, "TOTAL")`, and the engine enforces it.
- **What differs for a live match.**
  - The engine runs the turns and the clock, sends no move callbacks, and reports the result, including a forfeit,
    when the match ends.
  - Matchmaker doesn't record its turns or deadlines. `enforceTimeouts` already skips live matches.
  - Choosing a role (D9) runs on the engine's clock, which the choosing phase already supports.
- **Check round still works on live matches.** A live engine notices a turn that has run out on the first request
  that looks at the match, and matchmaker's status call is such a request. So for a live match, the check is a
  status call (`applyEngineStatus`), and its existing `liveEnded` path records a match the engine has just ended on
  its clock. Check round queues the round's unfinished live matches as well as its overdue non-live ones. Matchmaker
  holds no deadlines for live matches, so it can't pick out the overdue ones and queues them all.
- **A player who never opens the board has to be able to lose** *(settled)*. `TurnClock` starts a player's clock
  only once they've opened the board. Its own comment says the cost is that "a player who never turns up holds the
  match up indefinitely". In a tournament, that would hold up the round. So `LiveTerms` gains
  `startOnOpen: Option[Boolean] = None`, where absent means true, today's behavior. Tournaments send `false`, and
  the engine then starts a player's clock when their turn starts, opened or not. This is a small change in
  `engines/common` (`TurnClock` and `GameEngine.opened`), so every engine gets it. It goes in Phase 0c, since both
  change the same code. The owner can still cancel and rank a match by hand (D12), but a no-show forfeits without
  anyone having to.

### D8. Pool standings are points from each match's ranks, then the tiebreaker *(points settled)*

Win, draw and loss don't describe a game with more than two players, so points come from `result.rank`. In a match
of n players:

1. **Invert each rank.** The inverted rank is `i = n − rank + 1`, so first place becomes n and last place becomes
   1.
2. **A seat placed alone scores `i² − 1`.**
3. **A tie scores `(r − m + 1)²` for each tied seat.** Here m is the number of seats tied and r is their inverted
   rank, meaning the highest place the tied group covers, so `r − m + 1` is the lowest. Each tied seat scores one
   more than a lone seat in the lowest place covered.

   Places come from the order of the reported ranks, not their values. Seats are sorted by rank, equal ranks form
   a tied group, and each group covers the next m places. So 1, 1, 3 and 1, 1, 2 both mean "two tied first, one
   third". The rule doesn't depend on which tie convention an engine uses.

Worked through:

| match | ranks | points |
| --- | --- | --- |
| 2 players, a win | 1, 2 | 3, 0 |
| 2 players, a draw | 1, 1 | 1, 1 |
| 3 players | 1, 2, 3 | 8, 3, 0 |
| 3 players, two tied first | 1, 1, 3 | 4, 4, 0 |
| 3 players, two tied second | 1, 2, 2 | 8, 1, 1 |
| 3 players, all tied | 1, 1, 1 | 1, 1, 1 |

For two players this is win 3, draw 1, loss 0, as before.

- **A seat with no reported rank scores 0.** An engine reports what it chooses, and a seat it said nothing about
  is still a seat.
- **A forfeit scores by the ranks the forfeit produced.**
- **A cancelled match scores by the owner's manual `result` rows, if any, and otherwise 0 for every seat (D12).**

Engines with more than two players are expected to report full placings: 1, 2, 3 and so on. Matchmaker already
takes any ranks: `EloRating` rates every pair of seats by rank, and `MatchRecord.of` compares each seat against the
best rank. The only thing reducing ranks to 1 and 2 is `GameEngine.resultsOf` in `engines/common`, which derives
the rank from win/draw/loss. Phase 0b lets a game report its own placings.

The tiebreaker only applies between players level on points.

**Recommendation:** the design's "win-loss record" for a reseed round, and "highest finish" in the fill rule, are
also measured in these points. Win-loss doesn't extend to more than two players either, and for two it gives the
same order.

Ladder ranks don't use points: they move by +1 for a win and −1 for a loss, as the design says.

`SCORE`, the default tiebreaker, needs a numeric score, and `result.scores` is a free-form map.

**Recommendation:** add a nullable column `game.score_key`, set by the game admin, naming the numeric score. For
boxing that's `score` (Phase 0b).

- A player's differential for a match is their score minus the mean of their opponents' scores.
- With no key set, ranks stand in for scores: the differential is the number of opponents ranked below the player
  minus the number ranked above. Stratego has no numeric score, so it would use this.

### D9. Players choose roles inside the match, in the engine *(settled)*

The design: with `rotations = 0`, roles are chosen in seed order, and the time spent choosing counts against the
player's clock. With `rotations > 0`, roles are assigned so each player covers every role. With `unimportant_roles`,
they're assigned at random. A `REMATCH` tie-break is chosen in seed order too.

Matchmaker handles assignment: rotations, random, and preferred roles (D14). Choosing is done in the engine. The
match is created at once, with the seats still to choose sent without a role and an order to choose in. The
engine's first turns are the choices. That means:

- **Time counts without anything new.** A choice is an ordinary engine turn. The engine reports the chooser as
  pending, and the move callback records the turn. `TurnRepo.timeUsed` charges it to the chess clock, and `dueFor`
  works out the deadline.
- **Running out of time is the ordinary forfeit.** `enforceTimeouts` asks the engine, finds the chooser overdue,
  and forfeits the match.
- **Notifications are the existing ones.** "Matches waiting on me" and the your-turn mail show a choice like any
  other turn. No new notification type is needed.
- **Live matches work too.** The engine's own clock runs the choosing turns. In a live tournament (D7), running out
  while choosing is the engine's forfeit.

Built once, in `engines/common`, as a phase in front of any game, so no individual engine has to implement it
(Phase 0c). An engine opts in by being deployed with that version, and the admin marks the game `chooses_roles`.
For a game without the flag, matchmaker falls back to assigning by seed, highest first, after preferred roles.

### D10. Character entries

- A character entry stores the character, not a player. The owner is resolved when a match is created, the way V25
  resolves character invitations, so a character handed to a new player between rounds is played by its new owner.
  Whoever owns the character when the match is created makes its role choice in that match.
- Characters have no Elo rating. A character entry is seeded by its owner's overall rating at the moment the
  tournament starts.

### D11. `tournament_participant` references its entry

Rather than repeating `player_id` and adding `character_tournament_participant`, `tournament_participant` holds
`entry_id`. Who or what it is comes from the entry. Entries and participants stay separate as the design has them
(sign-ups and the seeded field), but there's only one source for who an entrant is.

### D12. The owner may rank a match's seats when cancelling it *(settled)*

The owner can cancel a tournament match and, while doing so, set its seats' ranks by hand. The case this is for is
a player who doesn't turn up to a live match. Ranks can't be set on a match that hasn't been cancelled.

- **Who can cancel.** The tournament's *current* owner, read from `tournament` under lock, can cancel a tournament
  match. The match's `creator` isn't used for this: it's the owner when the match was made, and ownership may have
  changed since. Ordinary matches keep today's rule, read from `match.creator` (Phase 0a).
- **Cancelling.** `POST /games/{g}/matches/{m}/cancel` (the existing route) takes an optional body,
  `{ ranks: { participantId: rank } }`, for a tournament match. The ranks are written as `result` rows (below). In
  the cancel's own transaction, under the match's lock:
  - every seat must be given a rank of at least 1;
  - ties are allowed, and D8 reads places from the order of the ranks, so a tie means what it does in a reported
    result;
  - a body naming a seat outside the match, or leaving one out, is refused;
  - ranks on a match started from a challenge are refused with a `ValidationError` *(settled)*. Its creator may
    still cancel it, as today, but can never set its results. The test is `match.challenge_id IS NOT NULL`, which
    the origin CHECK of D4 makes the same as "not a tournament match".

  Everything else about the cancel is unchanged: the seats are completed, players are mailed, and the engine is
  told through `endings.ended`.
- **Correcting.** `PUT /games/{g}/matches/{m}/ranks` sets or replaces the ranks of a tournament match that's
  already cancelled, until its round completes. A challenge match is refused here too, cancelled or not. On a
  tournament match that isn't cancelled it's refused with a `ConflictError` saying the match has to be cancelled
  first. That's the "not permitted" rule, enforced under the match's lock, so a
  cancel and a result can't race it.
- **Storage: ordinary `result` rows, marked manual.** `result` gains `manual BOOLEAN NOT NULL DEFAULT false`. The
  owner's ranks are written as one row per seat:
  - `rank`, as given;
  - `is_winner` for a seat alone at the best rank;
  - `forfeit = false`, `scores = '{}'`;
  - `elo_delta` and `elo_role_delta` null;
  - `manual = true`.

  A correction replaces the match's rows in one transaction, under the match's lock.
- **Why this is safe.** Nothing treats these rows as a played match:
  - Ratings move only in `recordResults`, which refuses a cancelled match.
  - `reclassify` runs only on a completed match, and a cancelled one never is.
  - `CharacterRepo.ratedRecord` already excludes cancelled matches.

  The one rule kept, noted on the column, is that `manual` rows exist only on cancelled tournament matches.
- **Showing them.** The match listings and `ResultRepo.listForPlayer` already LEFT JOIN `result` and include
  cancelled matches, so they pick these rows up. Each read model gains `manual`, and the UI labels the result "Ranked
  by the tournament owner". `MatchSummary`'s doc ("a cancelled match is over but has no result") is updated.
- **Scoring.** D8 reads `result.rank` for every match, so a ranked cancellation scores exactly as if the engine had
  reported those ranks. One without rows scores 0 for every seat. Either way, it's done for round completion (D6).
  Elo is never moved by a cancelled match, ranked or not: the ranks decide the tournament, not anybody's rating.
- **After the round.** Once the round has completed, the ranks are fixed: its standings and reseeding were derived
  from them.

### D13. "No tie" is a match option every engine is sent *(settled)*

`challenge` and `match` get `no_tie BOOLEAN NOT NULL DEFAULT false`. The challenge carries it forward to the match,
as it does `friendly`. The engine is sent it as `CreateGameRequest.noTie: Option[Boolean] = None`, which is absent
from a matchmaker that predates it.

- Every engine decodes it. Whether an engine acts on it is up to the engine.
- Tournaments set it on `REMATCH` tie-break matches.
- The design's "No Tie option when creating a match" also puts a checkbox on the challenge form.

Engine behavior is in Phase 0b.

### D14. Preferred roles go to the high seeds automatically *(settled)*

`game_role` gets `preferred BOOLEAN NOT NULL DEFAULT false`, set by the game admin in the game editor. An example is
White in chess: the role a higher seed should simply be given.

When roles are to be chosen (`rotations = 0`, roles important), matchmaker works through the seats in seed order,
highest first. While a preferred role is still free, the seat takes the first one in `game_role` order without
choosing. The remaining seats then choose in seed order (D9). If only one seat is left by then, it gets the last
role and no choosing happens at all.

This runs in matchmaker before the match is created, in the Phase 2 core. The engine just receives some seats with
roles already set. For a game without `chooses_roles`, the same rule comes first, then the remaining roles are
assigned by seed.

Preferred roles don't apply to rotations (which assign roles so everyone covers each one), to random assignment, or
to ordinary challenges, where an acceptor picks a role by accepting it.


## Problems in `tournament.sql` (re-reviewed after the `fixture_slot` change)

To be fixed when it becomes `V53__tournament.sql`.

### `fixture_slot`

- `bigitnt` is a typo.
- Use `GENERATED ALWAYS AS IDENTITY`, not `serial`. Because the identity is unique on its own, the primary key could
  be `(game_id, tournament_id, slot_id)`. Keep `fixture_id` in the key anyway, as written: the foreign key from
  `participant` needs it.
- It still needs D3's `seed` column and CHECK. Without them a first-round slot has nothing that says who fills it.
- `unique (game_id, tournament_id, prev_fixture_id, rank)` is right. Rows with a null `prev_fixture_id` don't
  collide, because nulls are distinct in a unique constraint.
- It has no `create_date` or `update_date`.
- It needs a foreign key `(game_id, tournament_id, fixture_id) REFERENCES fixture`. As written, a slot can name a
  fixture that doesn't exist.

### The new unique index on `participant` is wrong for pools

`create unique index on participant (game_id, tournament_id, fixture_id, slot_id)` allows a slot only one
participant row in the whole fixture. But a player in a pool of 4 plays 3 matches from the same slot. Make it
`UNIQUE (game_id, match_id, slot_id)`: one seat per slot per match.

The participant's foreign key to `fixture_slot` stays as written.

### Everything else, unchanged from the first review

- `game_id` is `INT` everywhere else, not `bigint`. Use `GENERATED ALWAYS AS IDENTITY` (the convention since V1), not
  `serial`. `bigint serial` and `bigint not null serial` don't parse.
- Timestamps are `TIMESTAMPTZ NOT NULL DEFAULT now()`. The `trg_<table>_update_date` triggers are created
  explicitly, calling `set_update_date()` (V1).
- Syntax errors:
  - `check (x in 'A','B')` needs parentheses.
  - `tournament _type` contains a stray space.
  - Commas are missing after `max_rating int` and before several `primary key` lines.
  - `owner` has no type.
  - There's a trailing comma before `)`.
- `CYCLICC` is misspelled. `ELIM` and `CYCLIC` have an `elimination_tournament` row, and `LADDER` doesn't. A CHECK
  can't span tables, so the service enforces it, with a comment.
- `round_duration date` should be `INTERVAL`, as `challenge.time_limit` is. `start_date` and the round dates should
  be `TIMESTAMPTZ`.
- `references round` should be `tournament_round`.
- Character tables follow the existing convention: a `game_type CHAR(1) CHECK (game_type = 'C')` column with
  `FOREIGN KEY (game_id, game_type) REFERENCES game (game_id, game_type)`, as on `character_participant`. Not
  `references character_game`, which doesn't exist.
- `character_tournament_entry.entry_id` is a reference, not `serial`.
- `participant.tournament_participant_id` is dropped. A seat's entrant is its slot's
  (`fixture_slot.tournament_participant_id`), reached through the seat's `(fixture_id, slot_id)`. Keeping both would
  be two copies of one fact, free to disagree. So a slot's occupant, once settled, is never changed or cleared:
  withdrawal is `tournament_participant.withdrawn`, and only slots in rounds not yet started become byes.
- The foreign key from `participant` to `match (game_id, match_id, tournament_id, fixture_id)` needs a matching
  `UNIQUE (game_id, match_id, tournament_id, fixture_id)` on `match`. Keep that foreign key and add the constraint:
  it's what guarantees a seat's fixture is its match's fixture.
- `tournament_participant.player_id` and `character_tournament_participant` are replaced by `entry_id` (D11).
- `tournament_participant.seed` gets the deferrable unique constraint (D2).
- Missing tables:
  - `tournament_invitation` and `character_tournament_invitation`, for invitational tournaments.
- Missing columns:
  - `tournament`: `started_at`, `ended_at`, `public`, `live` (D7).
  - `tournament_round`: `started_at`, `completed_at`, `checked_at` (D7), `live` (D7, copied from the tournament at
    the round's start), `duration INTERVAL` (the per-round override), `cycle INT` (for Cyclic).
  - `tournament_participant`: `ladder_rank INT` (Ladder), and `initial_seed` (Cyclic, Phase 9), written at the
    start and never updated.
  - `fixture`: `position`, its place in the round, for "Pool A" and "Match 3" in the UI.
  - `fixture_slot`: `tournament_participant_id`, who fills it, settled when its round starts. The round's matches
    are created afterwards from the queue (D5), so this has to be stored.
  - `result`: `manual BOOLEAN NOT NULL DEFAULT false` (D12).
  - `match`: `match_no`, with `UNIQUE (game_id, tournament_id, fixture_id, match_no)` (D1).
  - `game`: `score_key` (D8).
- Missing constraints:
  - A named unique index on `tournament_entry (game_id, tournament_id, player_id)` while `max_entries_per_player`
    must be 1, so lifting the limit later is a single `DROP INDEX`.
  - `CHECK (max_entries_per_player = 1)` for this pass, with a comment pointing at "For later" in the design.
  - `CHECK (min_rating IS NULL OR max_rating IS NULL OR min_rating <= max_rating)`.
- Missing index: `tournament_invitation (player_id)`, for the home page.


## Phase 0a: `match.creator`, and `match.challenge_id` nullable

A refactor outside tournaments, done first and on its own so the tournament PRs don't carry it.

### `V50__match_creator.sql`

```sql
ALTER TABLE match ADD COLUMN creator BIGINT REFERENCES player;
UPDATE match m SET creator = ch.challenger
  FROM challenge ch
 WHERE ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id;
ALTER TABLE match ALTER COLUMN creator SET NOT NULL;
ALTER TABLE match ALTER COLUMN challenge_id DROP NOT NULL;
CREATE INDEX match_creator ON match (creator);
```

`match_game_id_challenge_id_idx` stays unique. Nulls don't collide, so it still allows one match per challenge.

### Scala

- `Match` gets `creator: PlayerId`, and `challengeId` becomes `Option[ChallengeId]`. Its doc comment ("where the
  match's creator comes from") is rewritten.
- `GameEngineService.started` sets `creator = challenge.challenger`.
- `MatchRepo` replaces `JOIN challenge ch ... ch.challenger = p.player_id` with `m.creator = p.player_id` in its
  listing queries (about ten).
- `MatchService.cancel` reads `existing.creator` instead of calling `challengeRepo.challengerOf`.
- `NotificationRepo` and `ArchiveRepo` are checked for the same join.
- The wire format of `Match` changes. Update `JsonSpec` and `WireFormatSpec`, and the UI's uses of `challengeId`.
- Specs: the existing suites cover the listings and cancel unchanged. Add a `MatchRepoSpec` case for a match with no
  challenge, listed and cancellable by its creator. That's the case the change exists for, and Phase 4 is its first
  real user.

A schema change, so it goes on its own PR.


## Phase 0b: "No tie" in the engines, boxing's score, and placings

### Matchmaker

- `V51__no_tie.sql`: `challenge.no_tie` and `match.no_tie`.
- The challenge model, `ChallengeService` validation, and the copy into the match in `started`.
- `GameEngineClient.CreateGameRequest.noTie`, and `createRequest`.
- A **No tie** checkbox on the challenge form. Its explanation goes in a `withTip` tip, like the form's other
  settings.

### `engines/common`

`Protocol.CreateGameRequest.noTie: Option[Boolean] = None`. Every engine that decodes the request now recognizes
it. Each `Game.create` already receives the request, so an engine that ignores it needs no change. Tic-tac-toe and
RPS ignore it.

### Stratego

With `noTie`, the move cap doesn't apply. `StrategoMatch` gets `noTie: Boolean = false`. `ending` skips
`Ending.MoveCap` when it's set, and the page doesn't show the cap. The game is decided by flag, by a side that can't
move, by concession, or by the clock.

The default value matters: stored match JSON must keep decoding (memory: engine common-library refactor), so the
field is defaulted and its name is new.

Even with `noTie`, "neither side could move" stays a draw: no rule decides that position. It's rare, and the
tournament's tiebreaker handles it.

### Boxing

- **No tie.** With `noTie`, a bout that's level on points after its scheduled rounds keeps going, one round at a
  time, until a corner is ahead on points or scores a knockout.
  - `isOver` becomes `ranOut || knockout.isDefined || (rounds.size >= scheduledRounds && !(noTie && level))`.
  - `rounds` is derived from `1 to scheduledRounds`, so it has to extend to the rounds actually planned.
  - The page's "Round n of m" says "Extra round" past m.
- **Running out of time.** A bout that isn't live is ended by matchmaker's chess clock, through `enforceTimeouts`
  → `forfeit`. A live bout is ended by the engine's own clock (`ranOut`). Both already work with extra rounds.
- **`Bout` storage.** `Bout` gets `noTie: Boolean = false`, defaulted for the same reason as Stratego's field.
- **A `score` for each corner.** `scores` gains a `score` key: 2 for a knockout win, 1 for a win on a decision, and 0
  for anything else (a loss, a draw, and a win by forfeit, which isn't a decision). It's a new key, so `points` stays
  the card total. Nothing that decides a bout or writes its summary changes.
- Specs for each engine: the request decodes with and without `noTie`; a capped Stratego match runs past its cap; a
  level boxing bout goes to an extra round; a bout reports `score` 2, 1 or 0 by how it ended.

### Placings (`engines/common`)

`Game` gains `def placing(m: M, seat: S): Int`. Its default is the current derivation from `outcome`: 1 for a win
or a draw, 2 for a loss. Every existing engine therefore reports exactly what it does now. `GameEngine.resultsOf`
uses `game.placing` for `ResultEntry.rank`, in place of the hard-coded expression.

A game with more than two players overrides `placing` to give each seat its place, with tied seats sharing a rank.
`outcome` still says win, draw or loss for the page and the `outcome` score, and `isWinner` still comes from it.

- Specs: a stub three-player `Game` overriding `placing` reports ranks 1, 2 and 3. The existing engines' results are
  unchanged.
- Nothing in matchmaker changes, beyond a `GameEngineServiceSpec` case recording a three-placing result and rating
  it.

Several engines plus a schema change, so this goes on its own PR. Each engine deploys separately; the field is
optional both ways, so the order doesn't matter.


## Phase 0c: Choosing roles in the engines

This lets any match start with roles still to be chosen. Tournaments are the first user, but none of it is
tournament-specific.

### Protocol (`engines/common` and `GameEngineClient`)

- `CreateGameRequest.roleChoice: Option[RoleChoice] = None`, where
  `RoleChoice(order: List[Long], roles: List[String])`:
  - `order` is the participant ids still to choose, in choosing order.
  - `roles` is every role name in the game.

  The seats in `order` are sent with `role = None`, which `EnginePlayer.role` already allows. Seats with a role keep
  it.
- The engine reports roles back as they're chosen:
  - `MoveState` gains `roles: Map[Long, String] = Map.empty`, every seat's role so far.
  - `EngineParticipantStatus` gains `role: Option[String] = None`.
  - `MatchResults` carries the roles too, for a match whose move callbacks were lost.

  Every field is optional both ways, so engines and matchmaker deploy in any order.

### The choosing phase (`engines/common`)

- **Storage.** A new match with a `roleChoice` is stored as a choosing state: the request, the choices so far, and
  when the current chooser was offered. Nothing game-specific exists yet. Matches without a `roleChoice`, and every
  match already stored, decode and run exactly as now (memory: stored match JSON must not change).
- **Pending and turns.** While choosing, the common layer reports the next chooser as pending. Each choice is a turn
  with `takenAt` and `startedAt`, and it advances `sequence`.
  - The game's own sequence is offset by the number of choices, so the numbering stays one sequence across the
    whole match.
  - The first game move's clock starts at the last choice.
- **The last role.** When one seat is left it's given the remaining role. Then the common layer calls the game's
  own `create` with every seat roled. From there the match is the game's as it is today.
- **Live matches.** `TurnClock` runs the choosing turns, so running out while choosing is a forfeit like any other.
- **`LiveTerms.startOnOpen`** (D7). With `false`, `TurnClock.startedFor` uses the turn's start for a player who
  hasn't opened the board, instead of returning `None`. Absent or `true` keeps today's behavior. Specs: a live match
  created with `startOnOpen = false`, whose first player never opens the board, is forfeited once their budget
  passes.
- **Concession.** A seat may concede while choosing, if the game allows concession.
- **The page.** While choosing, the play page shows a shared fragment from `engines/common`:
  - the free roles, as 44px buttons labelled with their display names;
  - who has chosen what;
  - whose turn it is, in a live region;
  - the chooser's remaining time.

  Each engine includes the fragment in its own page. A viewer who isn't choosing sees the same page without
  buttons. The route is `POST …/role` on the engine's existing play routes, authorized like a move.

### Matchmaker

- **`V52__role_choice.sql`.**
  - `participant.game_role_id` becomes nullable.
  - `game.chooses_roles BOOLEAN NOT NULL DEFAULT false`.
  - `game_role.preferred BOOLEAN NOT NULL DEFAULT false` (D14).
- **Recording roles as they arrive.** `recordMove`, `applyEngineStatus` and `recordResults` write each newly
  reported role onto its participant, under the match lock they already take. At the same time they write the seat's
  `elo_role_start` from the player's rating in that role.
  - A report that would change a role already set is refused and logged, not applied.
- **A seat that never got a role.** A match forfeited while roles are still being chosen ends with some seats
  roleless. `EloRatingService.rate` skips the role delta for those seats and still moves the overall rating.
- **Showing a seat with no role.** Listings and the results table show it as "Choosing role", and
  `ParticipantResult`'s `roleName` becomes optional. Every query that inner-joins `game_role` on a participant's role
  becomes a LEFT JOIN, or it silently drops roleless seats. `ResultRepo.selectResultsForPlayer` is one; grep for
  `JOIN game_role` to find the rest.
- **The game editor** gains `chooses_roles` and, per role, `preferred`. Each gets a `withTip` explanation.
- **Specs:**
  - Protocol round-trips with and without the new fields.
  - A tic-tac-toe match created with a `roleChoice`: two choices, then play.
  - The time spent choosing appears in `timeUsed`.
  - A chooser who runs out of time is forfeited by `enforceTimeouts`.
  - A role change is refused.
  - Matches stored before the change still decode.

Schema plus engines, so this goes on its own PR. It doesn't depend on Phases 0a or 0b.


## Phase 1: Tournament schema, models and repositories (no behavior change)

- `V53__tournament.sql`: everything in the review above. One migration, because nothing reads any of it yet.
- Models in `model/`, as named case classes (memory: repo methods return case classes) with opaque ids in `Ids.scala`
  (`TournamentId`, `EntryId`, `TournamentParticipantId`, `FixtureId`, `SlotId`):
  - `Tournament`
  - `EliminationSettings`
  - `TournamentClass`, `TournamentType` and `Tiebreaker`, as enums with `code`, like `TimeLimitKind`
  - `TournamentEntry`
  - `TournamentParticipant`
  - `TournamentRound`
  - `Fixture`
  - `FixtureSlot` (`SlotSource = Bye | Seed(n) | Winner(prevFixture, rank)`)
- Repos: `TournamentRepo`, `EntryRepo`, `FixtureRepo` (fixtures and slots), and
  `TournamentParticipantRepo`. Any read that a later write decides from gets a `...ForUpdate` version, per
  CLAUDE.md. `MatchRepo` and `ParticipantRepo` gain the new columns.
- Specs: one `...RepoSpec` per repo, against the local Postgres. One case swaps two seeds inside a transaction, to
  show the deferred constraint works.


## Phase 2: The scheduling core (pure, no database)

A `tournament` package of pure functions. It holds every rule in the design that can be stated without a database,
property-tested with ScalaCheck in the style of `EloRatingSpec`. It's the part most likely to be subtly wrong and the
cheapest to test thoroughly, so it comes before any service that uses it.

- **Seeding.** Order by overall rating, with ties broken by a seeded `Random` that's passed in, so tests can
  reproduce a draw.
- **Byes to a power of two.** For a round with pool size 2, the top seeds get byes until the number of pools is a
  power of two.
  - Properties: the number of pools is a power of two; byes go to the top seeds; nobody gets two byes in one pool.
- **Pool construction balanced by seed sum.** Elimination fixtures for every round, generated assuming the higher
  seed always wins.
  - Property: the spread of seed sums is no worse than a snake draft.
  - Rounds after the first are wired by `Winner(prevFixture, rank)` slots.
- **The matches within a pool.** A list of planned matches, each a list of seats, and each seat either a role or
  "to choose":
  - With `rotations = 0` and important roles, a round-robin schedule. Preferred roles go to the high seeds (D14),
    and the rest choose in seed order (D9). For a game without `chooses_roles`, the rest are assigned by seed.
  - With `rotations = n`, roles are assigned so every player plays every role at least n times. RoundRobin with
    rotations keeps pairs from meeting in consecutive matches where that's possible.
  - With `unimportant_roles`, roles are assigned at random.
- **Pool standings.** Rank by points worked out from each match's ranks (D8). Ties go to the tiebreaker: `SCORE`
  uses the differential; `REMATCH` emits tie-break matches with `no_tie` set, with roles handled as in the previous
  item.
  - Properties: the worked table in D8 holds; points never go up as rank gets worse; for two players it's win 3,
    draw 1, loss 0; 1, 1, 3 and 1, 1, 2 score alike.
- **Advancement.** Take the top `min_pool_advance` from each pool. Then apply the fill rule: if the next round's
  pools can't all be filled, advance more players, ordered by highest finish in their pool, then score differential,
  then lowest seed.
  - Properties: every pool in the next round is filled; nobody who advanced on their own finish is displaced.
- **Reseeding**, producing the new value of every `tournament_participant.seed` (D2):
  - Within a pool, the pool's own seeds are reassigned by finishing order.
  - For a `reseed` round, seeds are recomputed globally by total points across the tournament (D8), then
    differential, then Elo.
- **Final ranks** for SingleElim (rounds advanced; the final ranks above the consolation round) and for RoundRobin.
  Later phases add the other types' rules here.
- **Ladder pairing.** Pair rank by rank from the top, lowest Elo against highest. Borrow from the next rank down,
  lowest total Elo first.

Not a schema change or a route, but it's a feature's worth of logic, so it gets its own PR.


## Phase 3: Tournament lifecycle (create, enter, start)

`TournamentService`, plus routes. Every updating call uses one `session.transaction.use`, and any read that decides
a write uses `FOR UPDATE`.

- **Create and edit.**
  - Validation: pool size is at least the game's mandatory roles; Cyclic only for character games (`game_type = 'C'`).
  - Only a game admin may set `friendly = false` (`GameAdminRepo.isAdminForShare`, so a revoke can't race the
    create).
  - Edits lock the tournament `FOR UPDATE`.
  - `friendly` is refused once `started_at` is set.
  - `live` can be edited at any time. It takes effect from the next round started (D7).
  - `game` and `game_role` are read plainly, under the reference-table exception, with the comment at the read.
- **Owner transfer** by a game admin.
- **Invitations**: create, withdraw, decline. These mirror `InvitationService`, including character invitations.
- **Entries.**
  - Entering an invitational tournament requires an invitation.
  - Entering an open tournament checks the min/max rating. A player with no `elo_rating` row counts as
    `EloRating.initial`.
  - For a character entry, the character must be owned by the caller, read `FOR SHARE`, as `CharacterService.create`
    does.
  - A ladder accepts entries after the start; the other classes don't.
- **Withdrawal**, at any time. Sets `withdrawn`. Any later slot the player would have filled is treated as a bye.
  That's resolved when the slot is filled, not by rewriting slots.
  - A withdrawn player's matches already under way are theirs to concede or let run out. Matches of the round that
    haven't been created yet treat the player's seat as a bye.
- **Start**, ELIM class only in this phase.
  - In one transaction: lock the tournament; refuse if it's already started; read the entries; seed them (Phase 2);
    write the `tournament_participant` rows, every round's `tournament_round` row, and the fixtures and slots for
    every round.
  - Starting a tournament doesn't start round 1. The owner presses **Start round**, as for every later round.

Routes, each added in three places: `Router.scala`, `local.routes` in `terraform/modules/api/main.tf`, and `routed`
in `RouterSpec`.

| method | path |
| --- | --- |
| GET  | `/games/{g}/tournaments` |
| POST | `/tournaments` |
| GET  | `/tournaments/{g}/{t}` |
| PUT  | `/tournaments/{g}/{t}` |
| PUT  | `/tournaments/{g}/{t}/owner` |
| POST | `/tournaments/{g}/{t}/invitations` |
| DELETE | `/tournaments/{g}/{t}/invitations/{playerId}` |
| POST | `/tournaments/{g}/{t}/entries` |
| DELETE | `/tournaments/{g}/{t}/entries/{entryId}` (withdraw) |
| POST | `/tournaments/{g}/{t}/start` |
| GET  | `/me/tournaments` (entered, owned, invited to) |

Codecs go in the shared `Json.scala`. Specs: a `TournamentServiceSpec`, including concurrent-entry and double-start
cases like the existing race specs. Use generous `caseTimeout`s.


## Phase 4: Playing a round

- **Start round** (`POST /tournaments/{g}/{t}/rounds/{n}/start`).
  - Owner only.
  - Takes the round's optional overrides: `duration`, `pool_size`, `min_pool_advance`, `rotations`, `tiebreaker`.
  - Under the round's lock, it refuses if the previous round isn't complete. It copies `tournament.live` onto the
    round. It then resolves each slot to a
    tournament participant:
    - a `Seed` slot from `tournament_participant.seed`;
    - a `Winner` slot from the previous pool's standings;
    - a withdrawn player as a bye.
  - It applies the fill rule, writes `started_at`, and enqueues one `MatchDue` per scheduled match after the
    commit (D5).
  - A pool-size override on a pre-generated round regenerates the fixtures of that round and every later round
    before resolving.
- **Creating a match** (listener, one `MatchDue` at a time, many in parallel). Works out match k from the pool's
  schedule (Phase 2) and runs D4's fixture front end, unless the match already exists. Connections are borrowed per
  transaction, not across the engine call (D5).
  - Seats get their roles, or are sent to choose, with `roleChoice` giving the seed order (D9, D14).
  - For a character seat, the owner is resolved now (D10).
  - `time_limit` is `duration / 2`, with `time_limit_kind = TOTAL`, and `live` comes from `tournament_round.live`.
    A live match is sent `LiveTerms(limit, "TOTAL", startOnOpen = false)` (D7).
  - `friendly` is the tournament's setting, or true if one player holds two seats.
  - `creator` is the tournament owner.
  - A fixture where every slot but one is a bye creates nothing. It's complete at once, and its lone player
    advances.
- **Round completion** (D6). When `settle` finds a tournament match, it calls `TournamentService.matchSettled`. That
  stamps the round complete once every fixture is complete or bye-only, writes the standings and the new seeds, and
  notifies the owner after the commit. If `REMATCH` leaves a tie, it enqueues the tie-break matches (match numbers
  after the pool's last) as `MatchDue` messages instead, and leaves the round open.
- **Check round** (`POST …/rounds/{n}/check`, D7). Owner only. Under the round's lock, it refuses a check within a
  minute of `checked_at`, stamps `checked_at`, and reads the round's unfinished matches: the non-live ones with a
  seat past `due`, and every live one. After the commit, it enqueues a `MatchCheck` for each of those matches and
  answers with their count.
  - For a live match, the listener makes the status call that lets the engine notice its own timeout.
  - For other matches it calls a new, narrow `GameEngineService.enforce(gameId, matchId)`.

  Either way, a connection is borrowed for each step rather than held across the engine call (D7).
- **Cancelling with ranks** (D12). The existing cancel route takes the optional ranks, and `MatchService.cancel`
  checks the tournament's current owner for a tournament match. Plus the new `PUT /games/{g}/matches/{m}/ranks`
  for correcting a cancelled match's ranks before its round completes.
- **Resume** (`POST …/rounds/{n}/resume`). Re-enqueues `MatchDue` for every scheduled match of the round that
  doesn't exist yet, for a start that failed partway.
- **Notifications.** New `NotificationType` cases:
  - `TournamentRoundComplete`, to the owner.
  - `TournamentInvitationReceived`.

  Each is a column on `player` and `player_game` (a migration of its own), plus mail text in `MailTemplates`. A
  player's own matches, role choices included, are already covered by `matchStarted` and the your-turn mail.

- **The ending listener (`ending/Handler.scala`).** Handles `MatchDue`, and runs its batch with
  `IO.parTraverseN` (D5). Settling a match ending is idempotent and touches only its own match's rows, so endings
  can run in parallel with each other too.

Terraform:

- The four new routes (start round, check round, resume, and the ranks route of D12). The cancel route already
  exists.
- **`ending_max_concurrency` goes from 2 to 10.** Its description and the comment on the event source mapping in
  `ending.tf` both state the cost: at most `ending_max_concurrency × 2` database connections, so 20 at worst,
  alongside the API's. That needs checking against the RDS instance's `max_connections` before it's applied. The
  instance isn't in this module, so its limit can't be read here; the bounce consumer's variable says the same. Add
  a `validation` block (between 2 and 1000) like `bounce_max_concurrency`'s.
- `MatchDue` and `MatchCheck` ride the existing ending queue, so there's no new queue.
- **A failed `MatchDue` is retried in 30 minutes, not an hour and a half** *(settled)*. The queue's visibility
  timeout is six times the listener's 900-second timeout, so that's how long an ordinary failure waits. Before it
  reports a `MatchDue` as failed, the listener sets that message's visibility to 1,800 seconds with
  `ChangeMessageVisibility`. Resending with a delay won't do, because SQS caps a send delay at 15 minutes. This
  needs:
  - the record's `receiptHandle`, which `Handler.records` doesn't read today;
  - the queue's url in the function's environment;
  - `sqs:ChangeMessageVisibility` added to the `match_ended` role policy in `ending.tf`.

  The queue's `ending_max_receive_count` (10) still applies, so a match that keeps failing gets about 5 hours of
  tries before the dead-letter queue. **Resume** stays as the owner's way to try again at once. If the visibility
  change itself fails, the message falls back to the ordinary retry, which is slower but still correct. Endings keep
  their existing hour and a half.


## Phase 5: Advancing and finishing, for SingleElim and RoundRobin

- After the last round, compute final ranks into `tournament_participant.final_rank`, set `tournament.ended_at`, and
  notify everyone entered. That's another notification type, or the owner's mail only; decide when this phase
  starts.
- SingleElim's consolation match is generated as an extra fixture in the final round, fed by the semi-final pools at
  rank 2.
- RoundRobin is one round with one pool. Phase 2's schedule does the work.
- End-to-end spec: an 8-player SingleElim of tic-tac-toe against a fake engine, from creation to final ranks. The
  fake engine reports role choices as turns. It also covers:
  - a 5-player field (3 byes);
  - a withdrawal mid-tournament;
  - a cancelled match with no ranks (0 points for everyone), and one ranked by the owner, scored as reported ranks
    would be (D12);
  - ranks refused on a match that isn't cancelled;
  - ranks refused on a challenge match, both when cancelling it and through the ranks route, and no `result` row
    written;
  - a draw scored at 1 point each, and a three-player match scored by the D8 table, against a fake engine that
    reports placings;
  - a preferred role taken by the higher seed;
  - a role choice that runs out of time.


## Phase 6: UI

A new `Tournaments.scala` in `ui/`, not more of `Main.scala`. Every fetch goes through `Store.load` or
`Store.reload`, so a late answer can't cross a sign-out.

- **Game page:** a Tournaments list, and **Create tournament** for any signed-in player. Explanations go in
  `withTip` tips, not paragraphs. The `friendly` control is shown only to game admins.
- **Tournament page:**
  - Entrants, and an Enter or Withdraw button.
  - Rounds and pools, with points and standings per pool, and each match linked to its match page.
  - Owner controls: Start, Start round (with the override fields), Check round, Resume, Invite.
  - **Cancelling a match** (owner only, from the tournament page or the match page). This opens a dialog listing the
    seats, each with a labeled rank select (16px, 44px tall). It has two actions: **Cancel and rank** and **Cancel
    without ranking**. A cancelled match whose round hasn't completed shows **Edit ranks**, which opens the same
    dialog. The confirmation is announced in a live region. Only a tournament match gets this dialog: cancelling a
    challenge match keeps today's plain confirmation, with no ranks offered.
  - Owner transfer, for game admins.
- **Matches still choosing roles** show "Choosing roles" on the tournament page. The choice itself is made on the
  engine's play page (Phase 0c).
- **Home page:** "My tournaments", plus tournament invitations alongside challenge invitations.
- **Mobile and accessible by default:**
  - The bracket renders as one stacked list per round on narrow screens, and as columns only when wide.
  - Form fields are 16px, touch targets 44px, every control has a label, and focus is visible.
  - The round's status sits in a live region.
  - Pools and matches are labeled "Pool A" and "Round 2, match 3", never by fixture or tournament ids (memory: no
    internal ids in UI).


## Phase 7: Playoff

A round-robin pool round, then SingleElim, with the first elimination round marked `reseed`. Most of it is already
built by this point. The new parts are fixture generation for "pools of size > 2 then pairs", and the
elimination-only `elimination_rotations`.


## Phase 8: Ladder

- Entries are accepted at any time. A new entrant joins at `ladder_rank = 0`. Re-entering after a withdrawal clears
  `withdrawn` on the same row, so the old rank carries over.
- One round's fixtures at a time, from Phase 2's ladder pairing.
- Ranks move at match settlement: a win is +1, a loss is −1. Two cases still need deciding:
  - In a game with more than two players, is everyone but the winner −1?
  - Is a draw 0?
- Owner flow: as for the other classes. Start round, then notification when complete. No final ranks.


## Phase 9: Cyclic

Character games only. When the final completes, generate the next cycle: new `tournament_round` rows with
`cycle + 1`, and round 1's pools copied from cycle 1 with the same seeds. Later rounds are generated as in Playoff.
The cycle repeats until the owner presses **End tournament**. Character hand-offs between cycles need nothing extra,
because of D10.

Seeds are overwritten in place (D2), so "the same seeds" means cycle 1's round-1 slots are kept and refilled from
the original seeding order. That order is `tournament_participant.initial_seed` (in V53), set once at the start and
never updated. This is the one place an old seed is still needed.


## Phase 10: DoubleElim, Repechage, ZeroElim

One PR each. Each one adds fixture generation and final ranking in the Phase 2 core, plus an end-to-end spec.

- **DoubleElim:** pool size 2 or `min_pool_advance = 1` (validated at create). A losers' bracket is fed by
  `Winner(prev, rank = 2)` slots. Then a final between the winners' champion and the top of the losers' bracket.
- **Repechage:** pool size 2. After the final pair is known, the players who lost to each finalist play a chain of
  repechage matches for third place.
- **ZeroElim:** pool size 2, every player plays every round. Each pool splits into winners and losers. The final rank
  is the binary string of results.


## Later (not in this plan)

- **Multiple entries per player.** Drop the unique index and the CHECK. Add the hold-back rule from the design, so
  a pool never contains two entries from the same player.
- **Replaying a cancelled fixture match (D12).** An owner action that creates a new match for the same seats, with
  the next `match_no`.


## How this splits into PRs

| PR | phases | kind |
| --- | --- | --- |
| 1 | 0a | schema refactor (`match.creator`) |
| 2 | 0b | schema and engines (no tie, boxing score, placings) |
| 3 | 0c | schema and engines (role choice, preferred roles) |
| 4 | 1 | tournament schema |
| 5 | 2 | core logic, no I/O |
| 6 | 3 | service and routes |
| 7 | 4 | service, routes, notifications schema, ending listener and its concurrency |
| 8 | 5 | service |
| 9 | 6 | UI |
| 10–14 | 7–10 | one per tournament type |

PRs 1–3 are independent of each other and of the tournament work, and can land in any order. PRs 1–9
together give a usable SingleElim and RoundRobin tournament. Each PR is finished and tested before it's opened
(memory: no PR with outstanding work).
