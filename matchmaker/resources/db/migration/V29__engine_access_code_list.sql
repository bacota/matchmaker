-- The admins' list of an engine's codes (EngineAccessRepo.listCodes), for an engine with thousands of them: every live
-- code, and the newest few dead ones. Revoked codes are kept, so the dead only grow.

-- The dead: walked newest first, stopping once enough are found. It leads with external_id, so it also serves every
-- lookup the single-column index did.
CREATE INDEX engine_access_code_newest ON engine_access_code (external_id, created_at DESC, code_id DESC);
DROP INDEX engine_access_code_external_id_idx;

-- The live: only codes neither revoked nor used up, so the dead are not in it to be read past. Its predicate is
-- EngineAccessCode.isRedeemable's less the expiry, which depends on when it is asked and is checked from the index's
-- second column. The query states the predicate as written here, so that the planner can use it.
CREATE INDEX engine_access_code_live ON engine_access_code (external_id, expires_at)
    WHERE NOT is_revoked AND (max_uses IS NULL OR uses < max_uses);
