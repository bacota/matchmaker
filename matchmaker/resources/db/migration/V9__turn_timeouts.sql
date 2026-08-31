-- What to do when a player takes too long over their turn, and the record of its having been done.
--
-- The action belongs to the game rather than to the match: it is a rule of how the game is
-- played, decided once by the admin who registers it, not something a challenger negotiates per
-- challenge the way the time limit is. The time limit says when a turn has run out; this says
-- what happens then, and the two are set by different people for different reasons.
--
-- Stored as text under a check constraint rather than a Postgres enum, because the set is
-- expected to grow: adding a value to a check constraint is one ALTER, and FORFEIT is only the
-- first of the actions the design calls for. Existing games get FORFEIT, which is the only
-- action there is, so the default is also the whole domain for now.
ALTER TABLE game
    ADD COLUMN timeout_action TEXT NOT NULL DEFAULT 'FORFEIT'
        CHECK (timeout_action IN ('FORFEIT'));

-- Whether the match this result belongs to was ended by a clock rather than by play.
--
-- On every row of the match, not only the row of the player who ran out: "won by forfeit" is a
-- statement about the winner's result as much as "forfeited" is about the loser's, and a list
-- showing one row at a time would otherwise have to fetch the rest of the table to know how the
-- match ended. `is_winner` already separates the two readings.
ALTER TABLE result
    ADD COLUMN forfeit BOOLEAN NOT NULL DEFAULT false;
