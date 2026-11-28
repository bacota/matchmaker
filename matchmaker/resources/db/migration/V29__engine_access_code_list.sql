-- The admins' list of an engine's codes (EngineAccessRepo), for an engine with thousands of them: its live codes a
-- page at a time, and its newest few ended ones. Both are in code_id order, newest first, and a page is asked for by
-- the code_id it follows. Revoked codes are kept, so the ended only grow.

-- Whether the code's expiry has been seen to pass: set by EngineAccessRepo.markExpired, which each listing runs
-- first. Expiry is otherwise a matter of when it is asked, which no index predicate can state, so without this an
-- expired code that was never revoked or used up would stay among the live for good, to be read past every time.
-- Until the mark is made, an expires_at in the past still means expired.
ALTER TABLE engine_access_code ADD COLUMN is_expired BOOLEAN NOT NULL DEFAULT false;

-- The ended: walked newest first, past the live, stopping once enough are found. It leads with external_id, so it also
-- serves every lookup the single-column index did.
CREATE INDEX engine_access_code_newest ON engine_access_code (external_id, code_id DESC);
DROP INDEX engine_access_code_external_id_idx;

-- The live: only codes neither revoked, nor marked expired, nor used up, so the ended are not in it to be read past.
-- Its predicate is EngineAccessCode.isRedeemable's, with the mark for the expiry. The queries state it as written
-- here, so that the planner can use it.
CREATE INDEX engine_access_code_live ON engine_access_code (external_id, code_id DESC)
    WHERE NOT is_revoked AND NOT is_expired AND (max_uses IS NULL OR uses < max_uses);

-- The codes markExpired looks at: those with an expiry it has not yet marked, by when.
CREATE INDEX engine_access_code_expiring ON engine_access_code (external_id, expires_at)
    WHERE NOT is_expired AND expires_at IS NOT NULL;
