-- The admins' list of an engine's codes (EngineAccessRepo), for an engine with thousands of them: its live codes a
-- page at a time, and its newest few ended ones. Both are in code_id order, newest first, and a page is asked for by
-- the code_id it follows. Revoked codes are kept, so the ended only grow.

-- The ended: walked newest first, past the live, stopping once enough are found. It leads with external_id, so it also
-- serves every lookup the single-column index did.
CREATE INDEX engine_access_code_newest ON engine_access_code (external_id, code_id DESC);
DROP INDEX engine_access_code_external_id_idx;

-- The live: only codes neither revoked nor used up, so the ended are not in it to be read past. Its predicate is
-- EngineAccessCode.isRedeemable's less the expiry, which depends on when it is asked and is checked as each row is
-- read. The queries state the predicate as written here, so that the planner can use it.
CREATE INDEX engine_access_code_live ON engine_access_code (external_id, code_id DESC)
    WHERE NOT is_revoked AND (max_uses IS NULL OR uses < max_uses);
