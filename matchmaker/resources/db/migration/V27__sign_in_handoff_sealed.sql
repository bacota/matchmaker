-- The tokens of a sign-in hand-off (V26), sealed under a key derived from the one-time code (AES-GCM, see
-- `HandOffService.seal`) rather than stored as they are. Only the code's hash is stored, so a copy of the table, a
-- snapshot or a dump holds nothing that signs anyone in: the tokens cannot be read without the code, which is never
-- stored.
--
-- The rows here live a minute and are each a code nobody can redeem after this change, so they are dropped rather
-- than converted.
DELETE FROM sign_in_handoff;

ALTER TABLE sign_in_handoff
    DROP COLUMN id_token,
    DROP COLUMN refresh_token,
    -- A 12-byte nonce, then the ciphertext and its 16-byte tag.
    ADD COLUMN id_token_sealed      BYTEA NOT NULL,
    -- Only for a trusted game (`game.is_trusted`).
    ADD COLUMN refresh_token_sealed BYTEA;
