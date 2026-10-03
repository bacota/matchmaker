-- The players who administer a game: who may manage its matches beyond what a player of them can.
--
-- One row per player per game, so the pair is the key. Made by an overall admin (`player.is_admin`)
-- or by another admin of the same game, and `granted_by` is who: an overall admin may take any of
-- them away, and a game's admin only those they made — see GameAdminService. Making an admin of a
-- player who already is one keeps the row as it was, and so who made them.
--
-- An overall admin is not listed here for every game: what they may do they may do anywhere, and a
-- row would only be a second answer to the same question.
CREATE TABLE game_admin (
    player_id    BIGINT NOT NULL REFERENCES player,
    game_id      INT NOT NULL REFERENCES game,
    granted_by   BIGINT NOT NULL REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, game_id)
);

-- The key leads with the player, which answers "what does this player administer"; listing a game's
-- admins goes the other way.
CREATE INDEX ON game_admin (game_id);
