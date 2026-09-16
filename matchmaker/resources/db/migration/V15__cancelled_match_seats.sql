-- A cancelled match's seats did not know the match was over.
--
-- Cancelling has always marked the match and nothing else: `cancelled` on `match`, no write to
-- `participant`. Completion is the other way round -- it retires every seat as it records what each
-- player scored -- so the two endings left the seats in different states, and "is this seat still in
-- play" could only be answered by joining `match` to find out which ending it was.
--
-- That join is what this removes the need for. From here `MatchService.cancel` retires the seats in
-- the same transaction as the match, exactly as the results and forfeit paths do, and a seat with
-- `completed = false` means a match that is genuinely still being played.
--
-- The three columns are the ones those paths write (see `withTurn`): the turn is given up, the clock
-- is stopped, the seat is finished. Nobody is waiting on anybody in a match that was called off.
UPDATE participant p SET
    pending = FALSE,
    completed = TRUE,
    due = NULL
FROM match m
WHERE m.game_id = p.game_id
  AND m.match_id = p.match_id
  AND m.cancelled
  AND NOT p.completed;

-- Referencing the target from the FROM clause's own join condition is what Postgres refuses (see
-- V14); a plain `FROM match m` with the correlation in WHERE is the same statement without that
-- problem, and needs no subquery because there is nothing here to resolve.
