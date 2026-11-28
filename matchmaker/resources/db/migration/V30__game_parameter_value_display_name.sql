-- What a player is shown for one of a parameter's values in the competition form's dropdown and in a match's
-- settings: "Small" for a board_size of `3x3`, say. `value` stays what the engine is sent. Usually the two are the
-- same, and matchmaker writes the value here when no other is given, as it does for game_parameter.display_name.
ALTER TABLE game_parameter_value ADD COLUMN display_name TEXT;

UPDATE game_parameter_value SET display_name = value;

ALTER TABLE game_parameter_value
    ALTER COLUMN display_name SET NOT NULL,
    ADD CONSTRAINT game_parameter_value_display_name_check CHECK (btrim(display_name) <> ''),
    -- A player choosing between two values shown alike could not tell them apart.
    ADD CONSTRAINT game_parameter_value_display_name_key UNIQUE (game_id, game_parameter_id, display_name);
