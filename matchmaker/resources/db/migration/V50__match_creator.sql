-- A match's creator, on the match itself.
--
-- Until now a match's creator -- the player who may cancel it, and whose match the listings say it
-- is -- was its challenge's challenger, reached by joining `challenge` through `challenge_id`. That
-- ties every match to a challenge, and a tournament's matches have none: they are created by the
-- tournament, for its owner. So the creator is copied onto the match when it is started, and the
-- challenge becomes optional.
--
-- Existing matches take their challenge's challenger, which is what they were reported as until now.
--
-- `match_game_id_challenge_id_idx` stays unique: nulls are distinct in a unique index, so it still
-- allows one match per challenge and any number with none.
ALTER TABLE match ADD COLUMN creator BIGINT REFERENCES player;

UPDATE match m SET creator = ch.challenger
  FROM challenge ch
 WHERE ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id;

ALTER TABLE match ALTER COLUMN creator SET NOT NULL;
ALTER TABLE match ALTER COLUMN challenge_id DROP NOT NULL;

-- For the foreign key: without it, deleting a player would scan every match.
CREATE INDEX match_creator ON match (creator);
