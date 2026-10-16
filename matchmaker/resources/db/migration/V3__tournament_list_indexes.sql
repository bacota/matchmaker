-- The tournament lists leave out the challenges that are over (TournamentRepo.isListed), and the challenge lists read
-- only the open ones (TournamentRepo.isOpenChallenge). Each predicate here is written as the queries write it, so that
-- the planner can match it, and each index is in the lists' order: a game's list then reads only the rows it shows,
-- not every challenge the game has ever had.
CREATE INDEX tournament_listed ON tournament (game_id, create_date DESC, tournament_id DESC)
    WHERE tournament_model_id <> 'challenge' OR status IN ('draft', 'open', 'running');
CREATE INDEX tournament_open_challenge ON tournament (game_id, create_date DESC, tournament_id DESC)
    WHERE tournament_model_id = 'challenge' AND status = 'open';
