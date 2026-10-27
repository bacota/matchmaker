-- A tournament match its engine would not create: how many tries in a row have failed, when the last one did, and
-- what the engine said. Shown to the tournament's owner and admins on its page, so that a match left due is not
-- silently stuck. Cleared once the engine makes the game; after a few failures the queue stops trying, and the
-- owner's Check round tries again.
ALTER TABLE match ADD COLUMN create_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE match ADD COLUMN create_failed_at TIMESTAMPTZ;
ALTER TABLE match ADD COLUMN create_error TEXT;
