-- Who wants to hear that a round of their tournament is over.
--
-- A twelfth kind, notify_tournament_round_done, mailed to a tournament's owner when the last match
-- of a round is recorded and the tournament waits on them to start the next. Only the two levels
-- a player states an opinion at: a seat is never asked about it (V24 narrowed `participant` to the
-- kinds about a match being played), and since V30 the chain ends at "send it" rather than at
-- the game.
--
-- It sits after notify_match_ended, as `TournamentRoundDone` sits after `MatchEnded` in
-- `NotificationType` -- the order `SkunkCodecs.notificationPreferences` binds these columns in.

-- NULL means "I have not said", which is what makes the chain a chain.
ALTER TABLE player ADD COLUMN notify_tournament_round_done BOOLEAN;

ALTER TABLE player_game ADD COLUMN notify_tournament_round_done BOOLEAN;
