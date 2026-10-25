-- A tournament is open for entries from the moment it is made: there is no draft any longer. Any left are opened,
-- and the two indexes whose predicates named drafts are made again with the queries' predicates as they are now
-- written (TournamentRepo.isNotStarted and playersLiveTournaments), so that the planner can still match them.
UPDATE tournament SET status = 'open' WHERE status = 'draft';
ALTER TABLE tournament ALTER COLUMN status SET DEFAULT 'open';
ALTER TABLE tournament DROP CONSTRAINT tournament_status_check;
ALTER TABLE tournament ADD CONSTRAINT tournament_status_check
    CHECK (status IN ('open', 'running', 'finished', 'cancelled'));

DROP INDEX tournament_not_started;
CREATE INDEX tournament_not_started ON tournament (game_id, create_date DESC, tournament_id DESC)
    WHERE status = 'open';
DROP INDEX tournament_owner_not_over;
CREATE INDEX tournament_owner_not_over ON tournament (owner) WHERE status IN ('open', 'running');
