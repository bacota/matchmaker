-- What a player is shown for a game parameter, where that is not the name the engine is sent.
-- `name` stays the key of the challenge's settings and of what the engine is handed at a start;
-- `display_name` only captions it. NULL means the name is shown as it is, which is what every
-- parameter that exists already has been doing.
ALTER TABLE game_parameter ADD COLUMN display_name TEXT;
