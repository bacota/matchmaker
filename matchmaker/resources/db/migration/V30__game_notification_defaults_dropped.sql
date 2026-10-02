-- A game no longer says what its players hear about.
--
-- V13 ended the notification chain at the game -- participant, player_game, player, game -- so that
-- an admin could ship quieter defaults for a game played over weeks than for one finished in an
-- evening. V23 added three more kinds to it, and V24 narrowed the seat to the four it can answer.
--
-- The chain now ends at a constant instead: what a player has not said anything about is sent. That
-- is `NotificationLevels.unsaid` on the Scala side, and the TRUE that closes the COALESCE in
-- `ParticipantRepo.create` and `NotificationRepo.applyToMatches`. A player who wants less mail says
-- so on their own settings, for every game or for one.
--
-- Nothing is carried over. A seat already in a match keeps the answers it was stamped with -- V14
-- made those the whole answer -- so dropping these changes only what future seats and challenge
-- mail resolve to.
ALTER TABLE game
    DROP COLUMN notify_challenge_accepted,
    DROP COLUMN notify_challenge_ready,
    DROP COLUMN notify_acceptance_changed,
    DROP COLUMN notify_accepted_challenge_ready,
    DROP COLUMN notify_invitation_received,
    DROP COLUMN notify_invitation_accepted,
    DROP COLUMN notify_invitation_rejected,
    DROP COLUMN notify_match_started,
    DROP COLUMN notify_turn_taken,
    DROP COLUMN notify_your_turn,
    DROP COLUMN notify_match_ended;
