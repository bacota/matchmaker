-- A game's admin match list (MatchRepo.selectForGame) shows the running matches first, then the newest. No other index
-- on match gives that order, so every match the game has ever had was read and sorted for each page. The expression is
-- written as MatchRepo.gameMatchOrder writes it, so that the planner can match it, and `start DESC` sorts a match the
-- engine has not created yet first, as the query does: the list then reads only the rows it shows.
CREATE INDEX match_game_listing ON match (game_id, (completed_at IS NULL AND NOT is_cancelled) DESC, start DESC, match_id);
