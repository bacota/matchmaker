-- A seat answers for itself.
--
-- V13 made every notification a walk down a chain: participant, then player_game, then player, then
-- game, with NULL meaning "I have not said". That is the right shape for the three levels a player
-- edits, and the wrong shape for the one the mailer reads. Deciding whether to write to a seat meant
-- joining two more tables per seat, and -- worse -- the answer was not fixed: a player who muted
-- their tic-tac-toe in March changed what every match they were already playing would send them,
-- including matches they had deliberately left noisy.
--
-- So the participant level stops being an override and becomes the answer. Its eight columns are NOT
-- NULL from here on, filled when the seat is created from whatever the chain said at that moment
-- (player_game, then player, then game -- see `ParticipantRepo.create`), and changed afterwards only
-- by the player saying so about that match, or by their asking for a change to be carried into the
-- matches they are already in.
--
-- The other three levels keep their meaning exactly. They are what a new seat inherits from, and
-- NULL there is still "I have not said".
--
-- Existing rows are backfilled by resolving the chain as it stands, which is what those matches have
-- been sending all along: the change is that the answer is now recorded rather than recomputed.
UPDATE participant p SET
    notify_challenge_accepted = COALESCE(p.notify_challenge_accepted, r.notify_challenge_accepted),
    notify_challenge_ready = COALESCE(p.notify_challenge_ready, r.notify_challenge_ready),
    notify_acceptance_changed = COALESCE(p.notify_acceptance_changed, r.notify_acceptance_changed),
    notify_accepted_challenge_ready = COALESCE(p.notify_accepted_challenge_ready, r.notify_accepted_challenge_ready),
    notify_match_started = COALESCE(p.notify_match_started, r.notify_match_started),
    notify_turn_taken = COALESCE(p.notify_turn_taken, r.notify_turn_taken),
    notify_your_turn = COALESCE(p.notify_your_turn, r.notify_your_turn),
    notify_match_ended = COALESCE(p.notify_match_ended, r.notify_match_ended)
FROM (
    SELECT pl.player_id, g.game_id,
           COALESCE(pg.notify_challenge_accepted, pl.notify_challenge_accepted, g.notify_challenge_accepted) AS notify_challenge_accepted,
           COALESCE(pg.notify_challenge_ready, pl.notify_challenge_ready, g.notify_challenge_ready) AS notify_challenge_ready,
           COALESCE(pg.notify_acceptance_changed, pl.notify_acceptance_changed, g.notify_acceptance_changed) AS notify_acceptance_changed,
           COALESCE(pg.notify_accepted_challenge_ready, pl.notify_accepted_challenge_ready, g.notify_accepted_challenge_ready) AS notify_accepted_challenge_ready,
           COALESCE(pg.notify_match_started, pl.notify_match_started, g.notify_match_started) AS notify_match_started,
           COALESCE(pg.notify_turn_taken, pl.notify_turn_taken, g.notify_turn_taken) AS notify_turn_taken,
           COALESCE(pg.notify_your_turn, pl.notify_your_turn, g.notify_your_turn) AS notify_your_turn,
           COALESCE(pg.notify_match_ended, pl.notify_match_ended, g.notify_match_ended) AS notify_match_ended
    FROM player pl
        CROSS JOIN game g
        LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = g.game_id
) r
WHERE r.player_id = p.player_id AND r.game_id = p.game_id;

-- The chain is resolved in a subquery and joined, rather than joined to directly, because the row
-- being updated may not be referenced from the join conditions of an UPDATE's own FROM clause --
-- Postgres says so plainly, and it is the same shape `ParticipantRepo.restampParticipants` uses for
-- the same reason. The cross join is over players and games, not over seats, so it is small.

-- The COALESCE above ends at `game`, whose eight columns V13 made NOT NULL for exactly this reason:
-- the chain has to finish somewhere, so there is no row the backfill can have left unanswered and
-- these eight statements cannot fail on existing data.
ALTER TABLE participant
    ALTER COLUMN notify_challenge_accepted       SET NOT NULL,
    ALTER COLUMN notify_challenge_ready          SET NOT NULL,
    ALTER COLUMN notify_acceptance_changed       SET NOT NULL,
    ALTER COLUMN notify_accepted_challenge_ready SET NOT NULL,
    ALTER COLUMN notify_match_started            SET NOT NULL,
    ALTER COLUMN notify_turn_taken               SET NOT NULL,
    ALTER COLUMN notify_your_turn                SET NOT NULL,
    ALTER COLUMN notify_match_ended              SET NOT NULL;
