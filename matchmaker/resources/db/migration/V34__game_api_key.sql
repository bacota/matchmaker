-- The key matchmaker and a game's engine authenticate each other with, moved out of matchmaker's
-- ENGINE_API_KEYS and GAME_ENGINE_API_KEYS environment variables and into the database, where an
-- admin sets it on the game's form.
--
-- A table of its own rather than a column of `game`, so that nothing reading a game can carry its
-- key along by accident: the key is written by a game save and read only by the two places that
-- authenticate with it, and no API answer ever includes it. Stored as it is, because matchmaker has
-- to present it to the engine on every call it makes, not just check one presented to it.
--
-- One per game. A game with none can still be played against an engine that asks for no key —
-- a local one, registered by its register-game.sql — and a deployed engine refuses it.
CREATE TABLE game_api_key (
    game_id      INT PRIMARY KEY REFERENCES game,
    api_key      TEXT NOT NULL CHECK (btrim(api_key) <> ''),
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
