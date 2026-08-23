-- Makes starting a challenge idempotent against concurrent attempts.
--
-- GameEngineService.start cannot hold one transaction across the whole operation: the game
-- engine call sits in the middle of it, and no database transaction can roll the engine's game
-- back anyway. So the challenge row's FOR UPDATE lock is released when the first transaction
-- commits, long before the challenge is deleted in the last one -- which left a window (as wide
-- as the engine call) where a second Start on the same challenge re-read a challenge that still
-- looked startable, passed every check again, and produced a second match and a second engine
-- game.
--
-- This column is the state that lock was missing. The first attempt claims the challenge by
-- writing the match id it is starting as, under the lock; a second attempt sees a non-null value
-- and is refused. It is cleared again if the engine call fails, which is what keeps the
-- documented "the challenger can simply try again" behaviour working.
--
-- Deliberately not a foreign key to match: the claim is written in the same transaction as the
-- match row today, but its purpose is to mark the challenge spoken for, and it must survive
-- being cleared by the undo path that deletes that match.
ALTER TABLE open_challenge
    ADD COLUMN started_match_id TEXT;
