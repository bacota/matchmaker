-- What each seat's Elo rating (V42) was when its match began, and what the match did to it.
--
-- `elo_start` is the player's rating in the game as the match is started -- the starting rating
-- for a player who has none yet -- and is written for every seat, friendly match or not: it is what
-- the player was rated when they sat down, which is worth knowing whether or not the match counts.
-- Never null: every seat is written with one, and every seat from before ratings were kept is given
-- the starting rating, which is what everybody was rated then.
--
-- `result.elo_delta` is the change the match made, worked out from the seats' `elo_start`s rather
-- than from the ratings as they stand when it ends: a player's other matches finishing in the
-- meantime do not change what this one was played at. On the result rather than the seat because it
-- is part of how the match came out, beside the rank it was worked out from -- and so it exists
-- exactly where a result does. Written with the result of a match that is not friendly; null on a
-- friendly match's, which moves nobody, and on every result from before ratings were kept.
--
-- The default fills the existing rows with the starting rating (EloRating.initial) without
-- rewriting the table, and is dropped at once: a seat's starting rating is written by the start
-- that makes it, and a seat made without one is a mistake for the insert to refuse rather than a
-- rating for the default to invent.
ALTER TABLE participant ADD COLUMN elo_start INT NOT NULL DEFAULT 1500;
ALTER TABLE participant ALTER COLUMN elo_start DROP DEFAULT;
ALTER TABLE result ADD COLUMN elo_delta INT;
