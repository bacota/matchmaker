-- Collapse the Player/Character variant split: Game is now a single class backed by the
-- single "game" table (no more player_game/character_game marker tables), and every
-- Match/Participant/OpenChallenge/Acceptance is now the "character" variant, so their
-- per-variant marker tables collapse into the base tables.

ALTER TABLE character DROP CONSTRAINT character_game_id_fkey;
ALTER TABLE character ADD FOREIGN KEY (game_id) REFERENCES game;

ALTER TABLE character_match DROP CONSTRAINT character_match_game_id_fkey;
ALTER TABLE character_match ADD FOREIGN KEY (game_id) REFERENCES game;

ALTER TABLE character_open_challenge DROP CONSTRAINT character_open_challenge_game_id_fkey;
ALTER TABLE character_open_challenge ADD FOREIGN KEY (game_id) REFERENCES game;

DROP TABLE player_acceptance;
DROP TABLE player_open_challenge;
DROP TABLE player_participant;
DROP TABLE player_match;
DROP TABLE player_game;
DROP TABLE character_game;

-- Rows that were PlayerParticipant (no matching character_participant row) have no
-- character_id equivalent under the new model and are dropped rather than preserved.
-- result.participant_id FKs to participant, so its rows must be pruned first.
DELETE FROM result r
  WHERE NOT EXISTS (SELECT 1 FROM character_participant cp WHERE cp.participant_id = r.participant_id);
DELETE FROM participant p
  WHERE NOT EXISTS (SELECT 1 FROM character_participant cp WHERE cp.participant_id = p.participant_id);
ALTER TABLE participant ADD COLUMN character_id BIGINT REFERENCES character;
UPDATE participant p SET character_id = cp.character_id
  FROM character_participant cp WHERE cp.participant_id = p.participant_id;
ALTER TABLE participant ALTER COLUMN character_id SET NOT NULL;
create index on participant(character_id);
DROP TABLE character_participant;

DROP TABLE character_match;

-- acceptance.challenge_id FKs to open_challenge, so acceptances for PlayerOpenChallenge
-- rows must be pruned before those open challenges are removed below.
DELETE FROM acceptance a
  WHERE NOT EXISTS (SELECT 1 FROM character_open_challenge coc WHERE coc.challenge_id = a.challenge_id);

-- Rows that were PlayerOpenChallenge (no matching character_open_challenge row) have no
-- game_id/character_id equivalent under the new model and are dropped rather than preserved.
DELETE FROM open_challenge o
  WHERE NOT EXISTS (SELECT 1 FROM character_open_challenge coc WHERE coc.challenge_id = o.challenge_id);
ALTER TABLE open_challenge ADD COLUMN game_id INT REFERENCES game;
ALTER TABLE open_challenge ADD COLUMN character_id BIGINT REFERENCES character;
UPDATE open_challenge o SET game_id = coc.game_id, character_id = coc.character_id
  FROM character_open_challenge coc WHERE coc.challenge_id = o.challenge_id;
ALTER TABLE open_challenge ALTER COLUMN game_id SET NOT NULL;
ALTER TABLE open_challenge ALTER COLUMN character_id SET NOT NULL;
create index on open_challenge(character_id);

-- character_acceptance FKs to character_open_challenge, so it must be merged and dropped
-- before character_open_challenge itself is dropped below.
ALTER TABLE acceptance ADD COLUMN character_id BIGINT REFERENCES character;
UPDATE acceptance a SET character_id = ca.character_id
  FROM character_acceptance ca WHERE ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id;
ALTER TABLE acceptance ALTER COLUMN character_id SET NOT NULL;
create index on acceptance(character_id);
DROP TABLE character_acceptance;

DROP TABLE character_open_challenge;
