-- When the sweep last asked about a match it found still owed something: a completed match never
-- archived, or a cancelled one whose engine never acknowledged the cancel (V38).
--
-- So that each is asked about at most once a day. Without it a match that can never be archived --
-- one whose engine has lost it, or has gone -- would be asked about on every run, forever, and
-- would crowd the matches that can out of each run's batch.
ALTER TABLE match ADD COLUMN swept_at TIMESTAMPTZ;
