-- Each rated player's place on the game's leaderboard: one more than how many are rated higher, so
-- equal ratings share a place and the places after them are skipped (1, 2, 2, 4). Null for a
-- player not on it yet.
--
-- Kept by the listener that settles the ends of matches (RankingService), not by the call that
-- moves the rating: a match's completion moves its players' ratings in its own transaction, and
-- their places follow a moment later. So the two can disagree for that moment, and `ranked_rating`
-- is the rating a player's place was worked out from. Places are compared by it, never by
-- `rating`: the leaderboard is always in order by what it was ranked from, even while ratings that
-- have moved since wait for their turn. A player is placed again when the two differ.
--
-- On the leaderboard is a player a rated match has moved or an admin has set: what the ratings list
-- shows (EloRatingRepo). A row a match's start made, which nothing has rated, has no place.
ALTER TABLE elo_rating ADD COLUMN rank INT;
ALTER TABLE elo_rating ADD COLUMN ranked_rating INT;

-- A game's leaderboard in order, and a place's neighbours: every step of placing a player reads
-- one place, and every shift moves a run of them.
CREATE INDEX elo_rating_rank ON elo_rating (game_id, rank);

-- The players of a game who are waiting to be placed, or to be taken off: placed on a rating that
-- has moved since, not placed at all, or placed and no longer rated. Only these are in it, so
-- finding them costs what there is to find. The predicate is EloRatingRepo's `selectUnplaced`
-- word for word, which is what lets the planner use it.
CREATE INDEX elo_rating_unplaced ON elo_rating (game_id)
    WHERE ((matches > 0 OR set_by IS NOT NULL) AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating))
       OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL);

UPDATE elo_rating r
SET rank = placed.rank, ranked_rating = r.rating
FROM (
    SELECT game_id, player_id, rank() OVER (PARTITION BY game_id ORDER BY rating DESC) AS rank
    FROM elo_rating
    WHERE matches > 0 OR set_by IS NOT NULL
) placed
WHERE placed.game_id = r.game_id AND placed.player_id = r.player_id;
