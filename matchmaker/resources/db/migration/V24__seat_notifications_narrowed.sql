-- A seat answers only about the match it is in.
--
-- V13 gave `participant` a column per notification kind, matching the three levels behind it, and
-- V14 made them NOT NULL so that a seat's row *is* the answer with no chain to resolve. The
-- uniformity was the point: one codec binds one type positionally against all four tables, so a list
-- out of step with `NotificationType` is wrong in one place rather than four.
--
-- What it also gave us is seven columns that nothing can ever read. A seat exists because a match
-- exists, and a match exists because a challenge was started -- after which that challenge cannot be
-- accepted, cannot fill up, and cannot be invited to. So the four challenge kinds have been dead
-- since V13, and the three invitation kinds would have been dead the moment V23 added them (it does
-- not). The dead columns were written at creation, re-stamped by every cascade, and read by nothing.
--
-- They were not harmless. A mangled COALESCE in `ParticipantRepo.insertParticipant` wrote the wrong
-- resolution into notify_accepted_challenge_ready for every seat created, and no test could see it,
-- because nothing reads that column. Dead data hides its own corruption.
--
-- What is left is `NotificationType.onSeat`: the three kinds that can happen to a match under way,
-- plus match_started, which fires while the seat is new and reads the seat's own answer. The Scala
-- side calls that `SeatNotifications` -- a type of its own rather than `NotificationDefaults`, which
-- is what makes the narrowing checkable instead of a convention.
--
-- Note what is NOT narrowed: `player`, `player_game` and `game` keep all eleven. Those are levels a
-- player states an opinion at, and every kind is a thing they can have an opinion about.
ALTER TABLE participant
    DROP COLUMN notify_challenge_accepted,
    DROP COLUMN notify_challenge_ready,
    DROP COLUMN notify_acceptance_changed,
    DROP COLUMN notify_accepted_challenge_ready;
