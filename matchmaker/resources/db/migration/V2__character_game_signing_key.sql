-- The signing key used to verify signatures on character-creation requests for this game
-- (character state + player external id must be signed by the game's private key).
ALTER TABLE character_game ADD COLUMN signing_key TEXT;
UPDATE character_game SET signing_key = '' WHERE signing_key IS NULL;
ALTER TABLE character_game ALTER COLUMN signing_key SET NOT NULL;
