-- The five notifications about a challenge's entries become two: an owner hears that somebody has entered (or
-- withdrawn from) a competition of theirs, full or not, and a player hears that they have been invited to one. The
-- other entrants are no longer written to about each other's entries.
--
-- Renamed rather than added, so that what a player said about "someone enters my challenge" carries over.
ALTER TABLE player RENAME COLUMN is_notified_of_challenge_accepted TO is_notified_of_competition_entered;
ALTER TABLE player
    DROP COLUMN is_notified_of_challenge_ready,
    DROP COLUMN is_notified_of_acceptance_changed,
    DROP COLUMN is_notified_of_accepted_challenge_ready;

ALTER TABLE player_game RENAME COLUMN is_notified_of_challenge_accepted TO is_notified_of_competition_entered;
ALTER TABLE player_game
    DROP COLUMN is_notified_of_challenge_ready,
    DROP COLUMN is_notified_of_acceptance_changed,
    DROP COLUMN is_notified_of_accepted_challenge_ready;
