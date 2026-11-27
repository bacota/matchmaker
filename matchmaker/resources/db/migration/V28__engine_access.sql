-- Engines only listed players may play (EngineAccessService). An engine is the games sharing a `game.external_id`;
-- there is no engine table, so these are keyed by that identity.
--
-- A row here is the restriction: an engine with none is open to everybody. Its players and codes are kept while it is
-- open, so that restricting it again does not lose the list.
CREATE TABLE restricted_engine (
    external_id   TEXT PRIMARY KEY,
    restricted_by BIGINT REFERENCES player ON DELETE SET NULL,
    restricted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Codes a game's admins hand out, each letting whoever redeems it play the engine. Only the code's SHA-256 is
-- stored, as with sign_in_handoff: a copy of the table lets nobody in. The code itself is shown once, when it is made.
CREATE TABLE engine_access_code (
    code_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id TEXT NOT NULL,
    code_hash   BYTEA NOT NULL UNIQUE,
    -- What the admins see the code as in their list.
    label       TEXT NOT NULL,
    -- NULL: as many as redeem it.
    max_uses    INT,
    uses        INT NOT NULL DEFAULT 0,
    -- NULL: never.
    expires_at  TIMESTAMPTZ,
    is_revoked  BOOLEAN NOT NULL DEFAULT false,
    created_by  BIGINT REFERENCES player ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON engine_access_code (external_id);

-- The players who may play a restricted engine: let in by an admin (`granted_by`), or by a code (`code_id`).
CREATE TABLE engine_player (
    external_id TEXT NOT NULL,
    player_id   BIGINT NOT NULL REFERENCES player ON DELETE CASCADE,
    granted_by  BIGINT REFERENCES player ON DELETE SET NULL,
    code_id     BIGINT REFERENCES engine_access_code ON DELETE SET NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (external_id, player_id)
);
CREATE INDEX ON engine_player (player_id);
