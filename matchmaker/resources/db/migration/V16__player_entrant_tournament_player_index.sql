-- A player's entries in one tournament (TournamentRepo's open list, whether the viewer has an entry left in each, and
-- whether they have entered an invitational one): read from this rather than from every entry in the tournament, or
-- from every entry the player has ever made.
CREATE INDEX player_entrant_tournament_player ON player_entrant (game_id, tournament_id, player_id);
