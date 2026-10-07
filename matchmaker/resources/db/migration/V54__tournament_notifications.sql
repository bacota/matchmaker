-- Two kinds of notification for tournaments: a round of a tournament you run is over, and you (or a
-- character of yours) have been invited to one.
--
-- Answered at the two levels the challenge kinds are (V14, V23): a player's defaults, and one game's,
-- both NULL until said. Neither is about a match, so a seat has no column for them.
ALTER TABLE player ADD COLUMN notify_tournament_round_complete BOOLEAN;
ALTER TABLE player ADD COLUMN notify_tournament_invitation BOOLEAN;
ALTER TABLE player_game ADD COLUMN notify_tournament_round_complete BOOLEAN;
ALTER TABLE player_game ADD COLUMN notify_tournament_invitation BOOLEAN;
