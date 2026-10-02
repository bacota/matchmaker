-- What a player is shown for a game parameter, where that need not be the name the engine is sent.
-- `name` stays the key of the challenge's settings and of what the engine is handed at a start;
-- `display_name` only captions it. Every parameter that exists has been shown by its name until
-- now, so that is what each one starts with.
ALTER TABLE game_parameter ADD COLUMN display_name TEXT;
UPDATE game_parameter SET display_name = name;
ALTER TABLE game_parameter
    ALTER COLUMN display_name SET NOT NULL,
    ADD CHECK (btrim(display_name) <> '');
