-- A role a game no longer seats. A game with roles can become one without them once every role is optional, but its
-- roles cannot be deleted: played seats and role ratings name them. They are retired instead -- kept for the matches
-- that used them, and left out of the game as it is read.
ALTER TABLE game_role ADD COLUMN is_retired BOOLEAN NOT NULL DEFAULT false;
