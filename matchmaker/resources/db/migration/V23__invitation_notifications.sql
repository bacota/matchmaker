-- Who wants to hear about invitations.
--
-- Three more kinds, and so three more columns on each of the four tables V13 put the other eight on.
-- The chain and the reasoning are V13's, unchanged; only the list is longer:
--
--   notify_invitation_received  -- somebody has invited you to a challenge of theirs
--   notify_invitation_accepted  -- somebody you invited has joined your challenge
--   notify_invitation_rejected  -- somebody you invited has turned it down
--
-- They sit after accepted_challenge_ready and before match_started, because that is where they sit
-- in `NotificationType` -- which is the order the Scala side binds these columns in
-- (`SkunkCodecs.notificationPreferences`), so their position is part of the contract and not a
-- matter of taste. `GameRepoSpec` writes eleven alternating answers and reads them back for exactly
-- this reason: a list out of step with the enum stores every answer under the wrong heading, and
-- would pass any test that set them all alike.
--
-- Why a kind of its own for a rejection, when nothing else here reports a refusal: rejecting an
-- invitation deletes it and leaves the challenge untouched, so without this mail the challenger is
-- never told that a seat they were holding open is not going to be taken. The other two are the
-- ordinary pair -- one to the invitee, one back to the challenger.

-- NULL on the three specific levels means "I have not said", which is what makes the chain a chain.
ALTER TABLE player
    ADD COLUMN notify_invitation_received BOOLEAN,
    ADD COLUMN notify_invitation_accepted BOOLEAN,
    ADD COLUMN notify_invitation_rejected BOOLEAN;

-- Existing seats get NULL... except they cannot: since V14 a seat's notify_ columns are NOT NULL and
-- are the answer, with no chain behind them. So these three are NOT NULL DEFAULT TRUE, which stamps
-- every match already being played with "yes" -- the same answer V13's game-level default gives, and
-- the one a seat created from today's chain would get for a game that has said nothing else.
--
-- Not a decision about those matches so much as an admission about them: an invitation cannot be
-- made to a match, so these three kinds will never fire for a seat that already exists. The value
-- matters only for a seat whose player later opens that match's settings and saves them.
ALTER TABLE participant
    ADD COLUMN notify_invitation_received BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_invitation_accepted BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_invitation_rejected BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE player_game
    ADD COLUMN notify_invitation_received BOOLEAN,
    ADD COLUMN notify_invitation_accepted BOOLEAN,
    ADD COLUMN notify_invitation_rejected BOOLEAN;

-- DEFAULT TRUE for V13's reason: the chain has to end somewhere, and a game registered before today
-- has no opinion to preserve -- "send it" is what every other kind already defaults to here, and
-- `GameService` is what refuses an API caller who leaves one unsaid.
ALTER TABLE game
    ADD COLUMN notify_invitation_received BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_invitation_accepted BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_invitation_rejected BOOLEAN NOT NULL DEFAULT TRUE;
