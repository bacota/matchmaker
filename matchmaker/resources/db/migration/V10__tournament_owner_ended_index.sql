-- A player's ended tournaments (TournamentRepo.endedOwnedSince): those they own that finished or were cancelled since
-- a time, the most recently ended first. The predicate and the expression are written as the query writes them, so
-- that the planner can match them, and the index is in the list's order: it reads only the window asked for, not
-- every tournament the player has ever owned.
CREATE INDEX tournament_owner_ended ON tournament (owner, (coalesce(ended_at, update_date)) DESC, tournament_id DESC)
    WHERE status IN ('finished', 'cancelled');
