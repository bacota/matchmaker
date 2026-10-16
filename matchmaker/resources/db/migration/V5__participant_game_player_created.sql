-- A match's later seats: ParticipantRepo's lockLaterSeats and laterCompleted, which look up its players' seats in
-- the same game made after it began or finished. participant (player_id) served those only by reading every seat the
-- players have had in every game.
CREATE INDEX participant_game_player_created ON participant (game_id, player_id, create_date);
