-- A role belongs to its game, so its key is (game_id, game_role_id), as game_parameter's is. V1
-- keyed it by game_role_id alone, and V2 added a unique index on (game_id, game_role_id) so that
-- the composite foreign keys from acceptance, participant, invitation and character_invitation had
-- something to reference. That index becomes the primary key here, keeping its dependents, and the
-- single-column key goes, with the plain index V1 also put on game_role_id.
ALTER TABLE game_role DROP CONSTRAINT game_role_pkey;
ALTER TABLE game_role ADD CONSTRAINT game_role_pkey PRIMARY KEY USING INDEX game_role_game_id_game_role_id_idx;
DROP INDEX game_role_game_role_id_idx;
