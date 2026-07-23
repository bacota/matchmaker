-- The signing key used to verify signatures on character-creation requests for this game
-- (character state + player external id must be signed by the game's private key).
ALTER TABLE character_game ADD COLUMN signing_key TEXT NOT NULL;
