-- Roles chosen in the match itself, by the players, in their engine.
--
-- A match may be created with seats still to choose a role (`CreateGameRequest.roleChoice`): the
-- engine offers the free roles to each chooser in turn, each choice a turn on that player's clock,
-- and reports each seat's role as it is chosen. Until then the seat has none, so
-- `participant.game_role_id` becomes nullable, and is written when the engine reports it. A match
-- forfeited before every seat chose ends with some seats roleless for good.
--
-- `game.chooses_roles` is the admin saying the game's engine is deployed with that choosing, so a
-- match of it may be created that way. `game_role.preferred` is a role a higher seed is simply
-- given when roles are chosen in a tournament -- White in chess.
ALTER TABLE participant ALTER COLUMN game_role_id DROP NOT NULL;
ALTER TABLE game ADD COLUMN chooses_roles BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE game_role ADD COLUMN preferred BOOLEAN NOT NULL DEFAULT false;
