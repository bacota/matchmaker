-- The unit a time limit was offered in.
--
-- The limit itself is an interval and always was; this is only how it was said. Without it a
-- limit could be read back only by guessing — by folding it into the largest unit that divides
-- it evenly — which turns "2 days" into "2 days" but "48 hours" into "2 days" as well, and a
-- challenger who chose hours gets a challenge quoting days back at them. What was offered is a
-- fact about the offer, so it is kept rather than inferred.
--
-- On both tables for the same reason time_limit and time_limit_kind are on both: the challenger
-- decides it, and the match carries it forward so that editing the challenge afterwards cannot
-- change how a match already being played describes itself.
ALTER TABLE open_challenge
    ADD COLUMN time_limit_unit TEXT NOT NULL DEFAULT 'MINUTES'
        CHECK (time_limit_unit IN ('MINUTES', 'HOURS', 'DAYS'));

ALTER TABLE match
    ADD COLUMN time_limit_unit TEXT NOT NULL DEFAULT 'MINUTES'
        CHECK (time_limit_unit IN ('MINUTES', 'HOURS', 'DAYS'));

-- Existing rows are backfilled with the unit they were being displayed in until now: the
-- largest that divides the limit evenly. Nothing anybody is looking at changes, which is the
-- point — the guess was only ever wrong about which of several right answers to give, and for
-- rows offered before this column existed the guess is the only evidence there is.
UPDATE open_challenge
   SET time_limit_unit = CASE
       WHEN time_limit IS NULL THEN 'MINUTES'
       WHEN EXTRACT(EPOCH FROM time_limit)::bigint % 86400 = 0 THEN 'DAYS'
       WHEN EXTRACT(EPOCH FROM time_limit)::bigint % 3600 = 0 THEN 'HOURS'
       ELSE 'MINUTES'
   END;

UPDATE match
   SET time_limit_unit = CASE
       WHEN time_limit IS NULL THEN 'MINUTES'
       WHEN EXTRACT(EPOCH FROM time_limit)::bigint % 86400 = 0 THEN 'DAYS'
       WHEN EXTRACT(EPOCH FROM time_limit)::bigint % 3600 = 0 THEN 'HOURS'
       ELSE 'MINUTES'
   END;
