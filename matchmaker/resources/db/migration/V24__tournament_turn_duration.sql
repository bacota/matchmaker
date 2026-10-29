-- How long each turn lasts in a game whose players move at once (`Clock.forMatch`). It replaces max_turns, which the
-- round duration used to be divided by: such a tournament made before this keeps the per-turn limit it had, as near as
-- the columns say, leaving out the extra turns once added for a tie-break or a choice of roles.
ALTER TABLE tournament ADD COLUMN turn_duration INTERVAL CHECK (turn_duration > INTERVAL '0');
UPDATE tournament t SET turn_duration = t.round_duration / COALESCE(g.max_turns, t.max_turns, 1)
  FROM game g
 WHERE g.game_id = t.game_id AND g.move_type = 'sim' AND t.round_duration IS NOT NULL;
ALTER TABLE tournament DROP COLUMN max_turns;
