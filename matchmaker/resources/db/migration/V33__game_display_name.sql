-- What players are shown for a game, where that need not be its name.
-- `name` stays the game's stable handle: the registration scripts, the deploy script's suggested
-- UPDATE and migrations V28 and V29 all find a game by it, so renaming a registered game for
-- players' sake would detach it from all of those. `display_name` is the part an admin may change.
-- Every game has been shown by its name until now, so that is what each one starts with.
ALTER TABLE game ADD COLUMN display_name TEXT;
UPDATE game SET display_name = name;
ALTER TABLE game
    ALTER COLUMN display_name SET NOT NULL,
    ADD CHECK (btrim(display_name) <> '');
