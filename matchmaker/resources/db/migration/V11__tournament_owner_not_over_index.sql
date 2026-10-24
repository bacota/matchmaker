-- A player's tournaments that are not over (TournamentRepo.playersLiveTournaments): the ones they own are read from
-- this, written with the query's predicate so that the planner can match it, and so without reading every tournament
-- they have ever owned.
CREATE INDEX tournament_owner_not_over ON tournament (owner) WHERE status IN ('draft', 'open', 'running');
