-- Everything the game-engine interaction needs that the original model did not carry.
--
-- Three additions, one per gap in the flow described in interaction-design.txt:
--
--   * a challenge says whether the match it becomes is public, because that is decided when the
--     challenge is offered and is sent to the game engine when the game is created;
--   * an acceptance says which role the accepting player will play, because the engine is told
--     "the cognito ids of the players and the roles they will be playing", and a participant
--     carries that role forward once the match exists;
--   * a match holds the three urls the engine hands back, since they are how matchmaker checks
--     status afterwards and how a player (or the public) watches the game.

-- game_role's primary key is game_role_id alone, so a plain foreign key to it could point at a
-- role belonging to some other game. This unique index is what lets the composite foreign keys
-- below require that a role and the row referencing it are in the same game — the same trick the
-- schema already uses for (game_type, game_id).
CREATE UNIQUE INDEX ON game_role (game_id, game_role_id);

-- game_role_id on open_challenge is the role the challenger will play: creating a challenge is
-- also accepting it, so the challenger needs the same say in their role as everybody else.
ALTER TABLE open_challenge
    ADD COLUMN public BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN game_role_id INT,
    ADD FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id);

ALTER TABLE acceptance
    ADD COLUMN game_role_id INT,
    ADD FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id);

ALTER TABLE participant
    ADD COLUMN game_role_id INT,
    ADD FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id);

-- The urls are nullable because a match row exists before the engine has answered, and
-- public_url stays null for a match that is not public — the engine only issues one when it is.
ALTER TABLE match
    ADD COLUMN public BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN status_url TEXT,
    ADD COLUMN play_url TEXT,
    ADD COLUMN public_url TEXT;
