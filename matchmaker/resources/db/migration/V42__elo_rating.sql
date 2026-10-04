-- Each player's Elo rating in each game, moved by every match of the game that is
-- not friendly (V36) as it completes, and set outright by an admin of the game.
--
-- A row exists once a player has one: their first rated match makes it at the starting rating and
-- moves it, and an admin setting a rating makes it at what they said. A player with no row has no
-- rating yet, which is not the same as having the starting one -- the list of a game's ratings is
-- its rated players, not everybody who has ever registered.
--
-- `matches` counts the rated matches that moved it, and an admin's setting leaves it alone: it is
-- how much play the number stands on. `set_by` is the admin who last set it outright, kept for the
-- same reason game_admin keeps `granted_by`, and left alone by play: once a rated match has moved
-- the number it is no longer only the admin's, but they are still who set where it started from.
--
-- Keyed by game first, which is the order a game's list is read in; a match's completion reads its
-- players' rows by the whole key.
CREATE TABLE elo_rating (
    game_id      INT    NOT NULL REFERENCES game,
    player_id    BIGINT NOT NULL REFERENCES player,
    rating       INT    NOT NULL,
    matches      INT    NOT NULL DEFAULT 0,
    set_by       BIGINT REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, player_id)
);

CREATE TRIGGER trg_elo_rating_update_date
    BEFORE UPDATE ON elo_rating
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
