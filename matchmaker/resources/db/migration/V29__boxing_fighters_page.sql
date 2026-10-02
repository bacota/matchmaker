-- Boxing's character page moves from /fighters/new to /fighters.
--
-- Fighters are now edited in the engine as well as built there, so the engine's one page for them
-- lists a player's fighters, each of which can be edited or given away, above the form that builds
-- a new one. That page is /fighters, and it is where matchmaker's UI should send a player, whether
-- they have a fighter yet or not. Only the url V28 or register-game.sql set is rewritten; anything
-- an admin chose instead is left alone.
UPDATE game
   SET character_url = regexp_replace(character_url, '/fighters/new$', '/fighters')
 WHERE game_type = 'C' AND character_url ~ '/fighters/new$';
