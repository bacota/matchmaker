-- Replaces signature verification with a simple external_id shared secret on the game
-- itself: requests that used to be authorized by a signature over the game's private
-- key now instead pass the game's external_id, which is compared against this column.
ALTER TABLE game ADD COLUMN external_id TEXT;
UPDATE game SET external_id = '' WHERE external_id IS NULL;
ALTER TABLE game ALTER COLUMN external_id SET NOT NULL;
create index on game(external_id);

ALTER TABLE character_game DROP COLUMN signing_key;
