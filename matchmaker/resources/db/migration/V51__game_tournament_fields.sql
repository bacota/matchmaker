-- What a tournament needs to know about a game, and about each of its roles.
--
-- game_role.type replaces game_role.optional. A `recommended` role is one a match can go without,
-- as an optional one can, but which counts toward the size of a tournament pool: a game's "R" is
-- its required and recommended roles together. Every role keeps the meaning it had: an optional one
-- stays optional, and the rest are required.
--
-- game_role.preference_level ranks the roles a tournament hands out by seed: the highest level goes
-- to the highest seed. NULL is no preference.
--
-- game.move_type says whether players move in turn (`seq`) or all at once (`sim`), which decides how
-- a tournament divides a round's time: a chess clock per seat, or a limit per turn. game.max_turns is
-- how many turns a simultaneous game lasts, where the game can say.
ALTER TABLE game_role ADD COLUMN type TEXT;
UPDATE game_role SET type = CASE WHEN optional THEN 'optional' ELSE 'required' END;
ALTER TABLE game_role ALTER COLUMN type SET NOT NULL;
ALTER TABLE game_role ALTER COLUMN type SET DEFAULT 'required';
ALTER TABLE game_role ADD CONSTRAINT game_role_type_check CHECK (type IN ('required', 'optional', 'recommended'));
ALTER TABLE game_role DROP COLUMN optional;

ALTER TABLE game_role ADD COLUMN preference_level INT;

ALTER TABLE game ADD COLUMN move_type TEXT NOT NULL DEFAULT 'seq'
    CONSTRAINT game_move_type_check CHECK (move_type IN ('seq', 'sim'));
ALTER TABLE game ADD COLUMN max_turns INT CONSTRAINT game_max_turns_check CHECK (max_turns > 0);

-- Both engines' players throw or plan at the same time. Found by the name their registration
-- scripts give them, which an admin cannot change (display_name is what they rename).
UPDATE game SET move_type = 'sim' WHERE name IN ('Boxing', 'Rock-paper-scissors');
