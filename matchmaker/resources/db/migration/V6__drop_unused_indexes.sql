-- Indexes that serve no query. Every index on participant is written on each move that is not a HOT update, so one
-- that carries no weight there is paid for on every turn.

-- A lookup by player alone is served by participant_player_completed_at (player_id, completed_at), which leads with
-- the same column -- and so does player's foreign key from participant.
DROP INDEX participant_player_id_idx;

-- The primary key (game_id, tournament_id, entrant_id) leads with the same columns, and serves the foreign key to
-- tournament as well.
DROP INDEX entrant_tournament;

-- Meant for sweeps of what is still owed something -- completed matches never archived, cancelled matches whose engine
-- has not acknowledged the cancel -- that were never written. Archiving and releasing look up one match by its key.
-- Bring them back with a sweep that needs them, its predicate written as its query writes it.
DROP INDEX match_unarchived;
DROP INDEX match_unreleased;
