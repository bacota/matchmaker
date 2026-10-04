-- When each seat was finished with, rather than only whether it was.
--
-- Every list of completed matches is now a window of time -- the last day, week or month, and the
-- equally long windows before it -- and each window is a range of this column for one player. So
-- the flag becomes the time, and a seat is completed exactly when the time is there.
--
-- Backfilled from the match's own completion time, which is when its seats were finished with. A
-- cancelled match has none (`match.completed` is set only for a match played to an end), and nor has
-- a seat finished in a match still running; for those the seat's own `update_date` is the nearest
-- thing on record. That is an approximation, and harmless: cancelled matches are not listed, and it
-- applies only to seats finished before this column existed.
ALTER TABLE participant ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE participant p
   SET completed_at = COALESCE(m.completed, p.update_date)
  FROM match m
 WHERE m.game_id = p.game_id
   AND m.match_id = p.match_id
   AND p.completed;

-- V19's index served the split on the flag; this serves that split (null or not) and the windows,
-- which are a range within one player's rows.
DROP INDEX participant_player_completed;
CREATE INDEX participant_player_completed_at ON participant (player_id, completed_at);

ALTER TABLE participant DROP COLUMN completed;
