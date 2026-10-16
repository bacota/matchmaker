-- A player's lists of their own seats each look up only the seats they ask about, not every seat the player has had.
-- Each predicate is written as its query writes it, so that the planner can match the two.

-- The seats whose turn it is: MatchRepo's due list. Led by the player, where participant_pending was led by the
-- match and so could serve no lookup by player; the lookups by match it might have served are made on
-- participant (game_id, match_id), so it goes.
CREATE INDEX participant_player_pending ON participant (player_id) WHERE is_pending;
DROP INDEX participant_pending;

-- The seats with a role still to choose: TournamentMatchRepo's choosing list.
CREATE INDEX participant_player_choosing ON participant (player_id) WHERE game_role_id IS NULL AND NOT is_forfeit;
