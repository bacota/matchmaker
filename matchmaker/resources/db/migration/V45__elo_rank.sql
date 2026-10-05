-- Each rated player's place on the game's leaderboard. Players are ranked by rating, and of two
-- rated the same, the one more rated matches stand behind is ranked higher -- together, their
-- standing. A place is one more than how many stand higher, so only players equal in both share
-- one, and the places after a tie are skipped (1, 2, 2, 4). Null for a player not on it yet.
--
-- Kept by the listener that settles the ends of matches (RankingService), not by the call that
-- moves the rating: a match's completion moves its players' ratings and matches in its own
-- transaction, and their places follow a moment later. So the two can disagree for that moment,
-- and `ranked_rating` and `ranked_matches` are the standing a player's place was worked out from.
-- Places are compared by them, never by `rating` and `matches`: the leaderboard is always in order
-- by what it was ranked from, even while standings that have moved since wait for their turn. A
-- player is placed again when the two differ -- including after a match that moved their rating by
-- nothing, which still adds a match, and can move them past somebody rated the same.
--
-- On the leaderboard is a player a rated match has moved or an admin has set: what the ratings list
-- shows (EloRatingRepo). A row a match's start made, which nothing has rated, has no place.
ALTER TABLE elo_rating ADD COLUMN rank INT;
ALTER TABLE elo_rating ADD COLUMN ranked_rating INT;
ALTER TABLE elo_rating ADD COLUMN ranked_matches INT;

-- A game's leaderboard in order, and a place's neighbours: every step of placing a player reads
-- one place, every shift moves a run of them, and a page of the leaderboard is a range of them.
CREATE INDEX elo_rating_rank ON elo_rating (game_id, rank);

-- The players of a game who are waiting to be placed, or to be taken off: placed on a standing
-- that has moved since, not placed at all, or placed and no longer rated. Only these are in it, so
-- finding them costs what there is to find. The predicate is EloRatingRepo's `selectUnplaced`
-- word for word, which is what lets the planner use it.
CREATE INDEX elo_rating_unplaced ON elo_rating (game_id)
    WHERE ((matches > 0 OR set_by IS NOT NULL)
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
       OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL);

UPDATE elo_rating r
SET rank = placed.rank, ranked_rating = r.rating, ranked_matches = r.matches
FROM (
    SELECT game_id, player_id, rank() OVER (PARTITION BY game_id ORDER BY rating DESC, matches DESC) AS rank
    FROM elo_rating
    WHERE matches > 0 OR set_by IS NOT NULL
) placed
WHERE placed.game_id = r.game_id AND placed.player_id = r.player_id;
