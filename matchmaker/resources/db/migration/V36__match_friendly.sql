-- Whether a match is friendly. Every match is unless it is classified otherwise, the ones already
-- played included, so the default is what the existing rows are given too.
ALTER TABLE match ADD COLUMN friendly BOOLEAN NOT NULL DEFAULT true;
