-- Each rated player's win-loss-draw record in the game, beside the rating the same matches moved.
--
-- Kept by the transaction that completes a match, as the rating is (EloRatingService.rate), and
-- over the same matches: rated ones. A friendly match moves neither. `forfeit_wins` and
-- `forfeit_losses` are the wins and losses a turn running out decided, and are counted in `wins`
-- and `losses` too, so the record reads 12 (2) - 5 (1) - 3.
--
-- Finishing first alone is a win, sharing first a draw, and anything below first a loss
-- (MatchRecord.of), over the seats that have a result.
ALTER TABLE elo_rating ADD COLUMN wins           INT NOT NULL DEFAULT 0;
ALTER TABLE elo_rating ADD COLUMN losses         INT NOT NULL DEFAULT 0;
ALTER TABLE elo_rating ADD COLUMN draws          INT NOT NULL DEFAULT 0;
ALTER TABLE elo_rating ADD COLUMN forfeit_wins   INT NOT NULL DEFAULT 0;
ALTER TABLE elo_rating ADD COLUMN forfeit_losses INT NOT NULL DEFAULT 0;

-- The matches already rated, read back from their results: a result carries what the match did to
-- the rating (V43) exactly when the match was rated, which is what makes it one to count.
WITH rated AS (
    SELECT p.game_id, p.match_id, p.player_id, res.rank, res.forfeit
    FROM result res
    JOIN participant p ON p.game_id = res.game_id AND p.participant_id = res.participant_id
    WHERE res.elo_delta IS NOT NULL
),
firsts AS (
    SELECT game_id, match_id, min(rank) AS best
    FROM rated
    GROUP BY game_id, match_id
),
sharing AS (
    SELECT r.game_id, r.match_id, count(*) AS at_best
    FROM rated r JOIN firsts f ON f.game_id = r.game_id AND f.match_id = r.match_id AND r.rank = f.best
    GROUP BY r.game_id, r.match_id
),
outcomes AS (
    SELECT r.game_id, r.player_id, r.forfeit,
           CASE WHEN r.rank = f.best AND s.at_best = 1 THEN 'W'
                WHEN r.rank = f.best THEN 'D'
                ELSE 'L' END AS outcome
    FROM rated r
    JOIN firsts f ON f.game_id = r.game_id AND f.match_id = r.match_id
    JOIN sharing s ON s.game_id = r.game_id AND s.match_id = r.match_id
),
records AS (
    SELECT game_id, player_id,
           count(*) FILTER (WHERE outcome = 'W')                AS wins,
           count(*) FILTER (WHERE outcome = 'L')                AS losses,
           count(*) FILTER (WHERE outcome = 'D')                AS draws,
           count(*) FILTER (WHERE outcome = 'W' AND forfeit)    AS forfeit_wins,
           count(*) FILTER (WHERE outcome = 'L' AND forfeit)    AS forfeit_losses
    FROM outcomes
    GROUP BY game_id, player_id
)
UPDATE elo_rating e
SET wins = r.wins, losses = r.losses, draws = r.draws,
    forfeit_wins = r.forfeit_wins, forfeit_losses = r.forfeit_losses
FROM records r
WHERE r.game_id = e.game_id AND r.player_id = e.player_id;
