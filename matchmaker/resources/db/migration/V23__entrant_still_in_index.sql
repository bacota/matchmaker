-- How many entries are still in a tournament (TournamentRepo's summaryColumns, selectStandings and the max_entrants
-- check on what a player may enter), counted from the index alone rather than from every entrant the tournament has
-- had, withdrawn ones included. Written with the queries' predicate so that the planner can match it.
CREATE INDEX entrant_still_in ON entrant (game_id, tournament_id) WHERE withdrawn_at IS NULL;
