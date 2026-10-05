-- Equal ratings are told apart by rated matches: of two players rated the same, the one more matches
-- stand behind is placed higher, and only players equal in both share a place. Places are still one
-- more than how many are placed higher, and skipped after a tie (V45).
--
-- So a place is worked out from the rating and the matches together, and `ranked_matches` is the
-- matches it was worked out from, as `ranked_rating` is the rating: a completion moves both before
-- the listener places the player again, and a match whose rating moves by nothing still adds a
-- match, which can move the player past somebody rated the same.
ALTER TABLE elo_rating ADD COLUMN ranked_matches INT;

-- The players waiting to be placed now include those whose matches have moved since. The predicate
-- is EloRatingRepo's `selectUnplaced` word for word, which is what lets the planner use it.
DROP INDEX elo_rating_unplaced;
CREATE INDEX elo_rating_unplaced ON elo_rating (game_id)
    WHERE ((matches > 0 OR set_by IS NOT NULL)
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
       OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL);

-- Every place worked out again under the new order, from the ratings and matches as they stand. All
-- of them taken off first, so that nobody is left holding a place from the old order.
UPDATE elo_rating SET rank = NULL, ranked_rating = NULL, ranked_matches = NULL WHERE rank IS NOT NULL;

UPDATE elo_rating r
SET rank = placed.rank, ranked_rating = r.rating, ranked_matches = r.matches
FROM (
    SELECT game_id, player_id, rank() OVER (PARTITION BY game_id ORDER BY rating DESC, matches DESC) AS rank
    FROM elo_rating
    WHERE matches > 0 OR set_by IS NOT NULL
) placed
WHERE placed.game_id = r.game_id AND placed.player_id = r.player_id;
