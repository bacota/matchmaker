-- Where a player makes a character for a character game.
--
-- Characters are made in their game engine, not in matchmaker: the engine builds one with the
-- player -- the boxing engine builds fighters -- and then reports it to matchmaker with
-- `POST /characters`, authorized as the game. Matchmaker's UI no longer offers a
-- form of its own, so a player with no character in a game needs to be sent to the engine's page
-- instead, and this is where that page is.
--
-- Nullable: a plain game has no characters, and a character game whose engine offers no page has
-- nothing to send its players to until an admin sets one.
ALTER TABLE game ADD COLUMN character_url TEXT;

-- Boxing, as engines/boxing/register-game.sql registers it, builds fighters at /fighters/new beside
-- the /games matchmaker creates its bouts at. Any other row is left for its admin.
UPDATE game
   SET character_url = regexp_replace(url, '/games$', '/fighters/new')
 WHERE game_type = 'C' AND name = 'Boxing' AND url ~ '/games$';
