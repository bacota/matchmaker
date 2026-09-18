-- Whether this challenge starts itself the moment it can be started.
--
-- On the challenge rather than on the game, because it is the challenger's decision and not the
-- admin's: two challenges of the same game are offered on different terms -- one is "first two
-- people who turn up, and we play", the other is being held open for a particular friend, or for
-- an optional seat, or for an evening when everybody is about. `time_limit` is on this table for
-- the same reason, and `game.timeout_action` is on that one for the opposite one.
--
-- DEFAULT FALSE, which is what every challenge that already exists has been doing: it waits for
-- its challenger to press Start. Unlike V13's notification columns this is not an existing
-- behaviour becoming explicit -- it is a new one, and defaulting it on would start matches nobody
-- asked to have started.
--
-- "Can be started" is the readiness rule that was already there -- every non-optional role taken;
-- see `AcceptanceRepo.unclaimedRoles` and `GameEngineService.start`. A challenge with optional
-- roles still unclaimed is startable, so one of those offered with this set begins without them,
-- and a challenger who wants to wait for them leaves it off.
ALTER TABLE open_challenge
    ADD COLUMN auto_start BOOLEAN NOT NULL DEFAULT FALSE;
