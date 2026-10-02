-- Live matches: matches whose turns, and the clock they are played against, the game engine runs
-- by itself.
--
-- The challenger decides, as they decide the time limit, and the match is created on the terms of
-- its challenge -- so the flag is on both, copied from one to the other at the start like the rest
-- of those terms. In a live match the engine is told the time limit and its kind when the game is
-- created, sends no move callbacks, and ends a match whose clock has run out by forfeit itself,
-- reporting the result as any other. Matchmaker neither tracks whose turn it is nor enforces the
-- limit.
ALTER TABLE challenge ADD COLUMN live BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE match ADD COLUMN live BOOLEAN NOT NULL DEFAULT false;

-- A live match is played against a clock, so it has a time limit: per turn, or a chess clock
-- (TOTAL), which the engine keeps across the turns it runs just as matchmaker keeps one otherwise.
-- At least a second, which is what ChallengeService requires and what an engine accepts: anything
-- less is a clock that has run out before it starts.
ALTER TABLE challenge
    ADD CONSTRAINT challenge_live_time_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit >= INTERVAL '1 second'));
ALTER TABLE match
    ADD CONSTRAINT match_live_time_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit >= INTERVAL '1 second'));

-- Seconds, which is what a live turn is measured in. V11 created both checks unnamed, and the
-- challenge table's still carries the name it was given before V21 renamed the table.
ALTER TABLE challenge
    DROP CONSTRAINT open_challenge_time_limit_unit_check,
    ADD CONSTRAINT challenge_time_limit_unit_check
        CHECK (time_limit_unit IN ('SECONDS', 'MINUTES', 'HOURS', 'DAYS'));
ALTER TABLE match
    DROP CONSTRAINT match_time_limit_unit_check,
    ADD CONSTRAINT match_time_limit_unit_check
        CHECK (time_limit_unit IN ('SECONDS', 'MINUTES', 'HOURS', 'DAYS'));
