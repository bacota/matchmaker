-- When each seat was finished with, rather than only whether it was: the expand half of replacing
-- participant.completed with participant.completed_at.
--
-- Every list of completed matches is a window of time -- the last day, week or month, and the
-- equally long windows before it -- and each window is a range of this column for one player. A seat
-- is completed exactly when the time is there.
--
-- Expand only. Flyway runs before the new function is live (deploy.sh, step 3), so the code still
-- deployed when this runs reads and writes `completed`; dropping it here would break every request
-- until the apply finished. So `completed` stays, still written by the code, and a trigger keeps
-- `completed_at` in step with it for old code and new alike. The contract half -- dropping the
-- trigger, `completed` and its index once nothing reads them -- is a later migration in a later
-- deploy.
ALTER TABLE participant ADD COLUMN completed_at TIMESTAMPTZ;

-- Backfilled from the match's own completion time, which is when its seats were finished with. A
-- cancelled match has none (`match.completed` is set only for a match played to an end), and nor has
-- a seat finished in a match still running; for those the seat's own `update_date` is the nearest
-- thing on record. An approximation, and harmless: cancelled matches are not listed, and it applies
-- only to seats finished before this column existed.
UPDATE participant p
   SET completed_at = COALESCE(m.completed, p.update_date)
  FROM match m
 WHERE m.game_id = p.game_id
   AND m.match_id = p.match_id
   AND p.completed;

-- The time follows the flag. Stamped with now() when a seat is first finished; kept, when a finished
-- seat is written again without naming it -- an UPDATE leaves NEW.completed_at as the row had it --
-- so completing is sticky and a repeated callback is not a second ending; and cleared for a seat that
-- is not finished. A write that names a time for a finished seat keeps the time it names.
CREATE FUNCTION participant_completed_at() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.completed THEN
        NEW.completed_at := COALESCE(NEW.completed_at, now());
    ELSE
        NEW.completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER participant_completed_at
    BEFORE INSERT OR UPDATE ON participant
    FOR EACH ROW EXECUTE FUNCTION participant_completed_at();

-- What the windows and the split between running and finished are read by. V19's index on
-- (player_id, completed) goes with the column, in the contract half.
CREATE INDEX participant_player_completed_at ON participant (player_id, completed_at);
