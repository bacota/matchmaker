-- What each seat's Elo rating (V42) was when its match began, and what the match did to it.
--
-- `elo_start` is the player's rating in the game as the match is started -- the starting rating
-- for a player who has none yet -- and is written for every seat, friendly match or not: it is what
-- the player was rated when they sat down, which is worth knowing whether or not the match counts.
--
-- `elo_delta` is the change the match made, worked out from the seats' `elo_start`s rather than
-- from the ratings as they stand when it ends: a player's other matches finishing in the meantime
-- do not change what this one was played at. Written when a match that is not friendly completes,
-- for each seat with a result; a player's rating moves by the sum of their seats' deltas.
--
-- Both null for seats written before these columns existed. A match already running when this is
-- applied rates such a seat from the player's rating when it ends, which is all there is to go on.
ALTER TABLE participant ADD COLUMN elo_start INT;
ALTER TABLE participant ADD COLUMN elo_delta INT;
