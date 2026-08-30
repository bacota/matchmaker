-- When a match was completed, not merely that it was.
--
-- The flag could say a match was over but not when, which is the one thing a history needs: a
-- list of finished matches has no order of its own, and "how long did that take" has no answer
-- at all. A nullable timestamp carries both — NULL is the old false, and any value is the old
-- true — so nothing that asked whether a match is finished has to start asking two columns.
--
-- Existing completed rows are backdated to their last update, which for a completed match is
-- the write that completed it: recordResults completes the match and touches nothing after.
ALTER TABLE match ALTER COLUMN completed DROP DEFAULT;
ALTER TABLE match ALTER COLUMN completed DROP NOT NULL;
ALTER TABLE match
    ALTER COLUMN completed TYPE TIMESTAMPTZ
    USING (CASE WHEN completed THEN update_date END);
