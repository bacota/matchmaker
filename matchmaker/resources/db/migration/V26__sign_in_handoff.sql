-- A sign-in handed from matchmaker's UI to a game engine's page (design/sign-in-handoff.md). The UI asks for a
-- one-time code, opens the engine's page with the code in its url, and the engine's server redeems the code here,
-- with the game's API key, for the tokens. So no token is ever put in a url, where the browser's history would keep it.
--
-- Only the code's SHA-256 is stored, never the code. A row lives a minute at most: redeeming it deletes it, refused or
-- not, and making a code deletes every row past its expiry, so the table holds only the last minute's.
CREATE TABLE sign_in_handoff (
    code_hash     BYTEA PRIMARY KEY,
    game_id       INT NOT NULL REFERENCES game ON DELETE CASCADE,
    -- Who made it, for logs; never returned.
    cognito_id    TEXT NOT NULL,
    id_token      TEXT NOT NULL,
    -- Only for a trusted game (`game.is_trusted`).
    refresh_token TEXT,
    expires_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX sign_in_handoff_expires_at ON sign_in_handoff (expires_at);
