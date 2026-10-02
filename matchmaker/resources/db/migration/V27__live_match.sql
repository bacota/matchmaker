-- Live matches: matches whose turns, and the clock on each turn, the game engine runs by itself.
--
-- The challenger decides, as they decide the time limit, and the match is created on the terms of
-- its challenge -- so the flag is on both, copied from one to the other at the start like the rest
-- of those terms. In a live match the engine is told the turn timeout when the game is created,
-- sends no move callbacks, and ends a match whose turn has run out by forfeit itself, reporting the
-- result as any other. Matchmaker neither tracks whose turn it is nor enforces the limit.
ALTER TABLE challenge ADD COLUMN live BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE match ADD COLUMN live BOOLEAN NOT NULL DEFAULT false;

-- What the engine is told is a turn timeout, so a live match has a time limit and it is per turn.
-- A total budget is a chess clock that matchmaker keeps across the turns it is told about, and in a
-- live match it is told about none.
ALTER TABLE challenge
    ADD CONSTRAINT challenge_live_turn_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit_kind = 'PER_TURN'));
ALTER TABLE match
    ADD CONSTRAINT match_live_turn_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit_kind = 'PER_TURN'));

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
