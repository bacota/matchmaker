-- The site's admins (PlayerRepo.listAdmins), read without reading every player there is.
CREATE INDEX player_admin ON player (player_id) WHERE is_admin;

-- Whether a game has a tournament not yet over (TournamentRepo.hasUnfinished), written with the query's predicate so
-- that the planner can match it, and so without reading every tournament the game has ever had.
CREATE INDEX tournament_unfinished ON tournament (game_id) WHERE status IN ('open', 'running');
