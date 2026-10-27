-- Whether the game's engine is trusted with a player's refresh token. Opening an engine's page from matchmaker's UI
-- hands it the player's session; an untrusted engine is given only the short-lived ID token, a trusted one the
-- refresh token too, which keeps the player signed in there for as long as their session lasts. Every game here
-- before this column is one of ours, so it is trusted; a game added from now on is not until an admin says so.
ALTER TABLE game ADD COLUMN is_trusted BOOLEAN NOT NULL DEFAULT false;
UPDATE game SET is_trusted = true;
