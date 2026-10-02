-- What a player is shown for a role, where that need not be the name the engine is sent.
-- `name` stays what the engine is told each seat is; `display_name` is what pickers, invitations,
-- results and notifications say. Every role that exists has been shown by its name until now, so
-- that is what each one starts with.
ALTER TABLE game_role ADD COLUMN display_name TEXT;
UPDATE game_role SET display_name = name;
ALTER TABLE game_role
    ALTER COLUMN display_name SET NOT NULL,
    ADD CHECK (btrim(display_name) <> '');
