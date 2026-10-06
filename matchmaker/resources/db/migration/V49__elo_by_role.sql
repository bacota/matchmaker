-- Elo ratings by role, as well as overall, for a game whose roles matter to who wins.
--
-- `game.unimportant_roles` is the admin saying that no role of the game has any advantage in
-- winning it. Unticked -- the default, and so every game there is now -- a rated match moves each
-- player's rating in the role they played as well as their overall one (elo_rating, V42).
--
-- A role's ratings are a pool of their own: a seat's rating in its role is played against the
-- other seats' ratings in theirs, by the same arithmetic as the overall ones (EloRating.deltas). So
-- a role that wins more than its players' ratings say it should draws points from the others, and
-- the gap between the roles' ratings is what the role is worth. Nothing is carried over from the
-- overall rating: a player's first rated match in a role starts them at the starting rating in it,
-- and matches rated before this are in no role's.
--
-- Each row is kept, placed on its role's own leaderboard (V45) and counted (V46) as elo_rating's
-- are, with the same columns, but for `set_by`: a role's rating is only ever moved by play, and an
-- admin sets the overall one.
ALTER TABLE game ADD COLUMN unimportant_roles BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE elo_role_rating (
    game_id         INT    NOT NULL,
    game_role_id    INT    NOT NULL,
    player_id       BIGINT NOT NULL REFERENCES player,
    rating          INT    NOT NULL,
    matches         INT    NOT NULL DEFAULT 0,
    rank            INT,
    ranked_rating   INT,
    ranked_matches  INT,
    wins            INT    NOT NULL DEFAULT 0,
    losses          INT    NOT NULL DEFAULT 0,
    draws           INT    NOT NULL DEFAULT 0,
    forfeit_wins    INT    NOT NULL DEFAULT 0,
    forfeit_losses  INT    NOT NULL DEFAULT 0,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_role_id, player_id),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

-- A player's ratings across games and roles, and a player's deletion, as elo_rating_player.
CREATE INDEX elo_role_rating_player ON elo_role_rating (player_id);

-- A role's leaderboard in order, as elo_rating_rank is the game's.
CREATE INDEX elo_role_rating_rank ON elo_role_rating (game_id, game_role_id, rank);

-- The players waiting to be placed on, or taken off, a role's leaderboard, as elo_rating_unplaced.
-- Rated is a match having moved the rating, there being no admin's setting here. The predicate is
-- EloRatingRepo's word for word, which is what lets the planner use it.
CREATE INDEX elo_role_rating_unplaced ON elo_role_rating (game_id)
    WHERE (matches > 0
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
       OR (NOT matches > 0 AND rank IS NOT NULL);

CREATE TRIGGER trg_elo_role_rating_update_date
    BEFORE UPDATE ON elo_role_rating
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- Each seat's rating in its role as the match began, beside `elo_start` (V43), and what the match
-- did to it, beside `elo_delta`. Null for a seat of a game whose roles are unimportant -- decided as
-- the match starts -- and for every seat from before ratings were kept by role: a match any of whose
-- seats has none is rated overall only.
ALTER TABLE participant ADD COLUMN elo_role_start INT;
ALTER TABLE result ADD COLUMN elo_role_delta INT;
