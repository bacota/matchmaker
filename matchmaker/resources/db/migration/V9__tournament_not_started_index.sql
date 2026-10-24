-- A game's screen lists its tournaments not yet started (TournamentRepo.isNotStarted), in place of its open challenges
-- and its other tournaments, which the two V3 indexes served. The predicate is written as the query writes it, so that
-- the planner can match it, and the index is in the list's order.
DROP INDEX tournament_listed;
DROP INDEX tournament_open_challenge;
CREATE INDEX tournament_not_started ON tournament (game_id, create_date DESC, tournament_id DESC)
    WHERE status IN ('draft', 'open');
