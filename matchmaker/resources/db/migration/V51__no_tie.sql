-- "No tie": a match asked to end with somebody ahead.
--
-- The challenger's choice, carried from the challenge to its match like `friendly`, and sent to the
-- engine as `noTie` in its create request. What it means is the engine's business -- Stratego plays
-- on past its move cap, boxing goes to extra rounds -- and an engine with no way to break a level
-- position may ignore it. Tournaments will set it on the matches that break a tie.
ALTER TABLE challenge ADD COLUMN no_tie BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE match ADD COLUMN no_tie BOOLEAN NOT NULL DEFAULT false;
