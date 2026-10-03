-- A completed match's archive in S3 (archiving-matches-plan.md), and what matchmaker has told the
-- engine about a cancelled one.
--
-- The archive is the engine's own stored JSON, uploaded by the engine to a url matchmaker signs.
-- Matchmaker keeps where it went and whether it arrived:
--
--   archive_key         the object's key, chosen by matchmaker when the engine asks to upload. The
--                       bucket is not stored: it follows from `friendly`, which a completed match can
--                       no longer change (MatchService.setFriendly), and bucket names are configuration.
--   archive_sha256      the checksum the upload was signed for, base64 as S3 reports it, so that the
--                       confirm can tell the object that arrived is the one that was asked for.
--   archive_requested   when the engine last asked to upload.
--   archived_at         when the upload was confirmed. From then on the engine has dropped its live
--                       copy and the archive is the only one.
--   archive_expired_at  when a friendly archive was found to be gone: its bucket expires objects after
--                       30 days. Once set, matchmaker stops offering the Review and Watch links.
ALTER TABLE match
    ADD COLUMN archive_key TEXT,
    ADD COLUMN archive_sha256 TEXT,
    ADD COLUMN archive_requested TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archive_expired_at TIMESTAMPTZ;

-- Where the engine is told a match has been cancelled, as the engine answered its create; null from
-- an engine that offers no such route, which is then simply not told. And when it acknowledged, so
-- that the sweep retries a cancel the engine missed.
ALTER TABLE match
    ADD COLUMN cancel_url TEXT,
    ADD COLUMN engine_released TIMESTAMPTZ;

-- The sweep's two questions: completed matches never archived, and cancelled matches whose engine
-- has not acknowledged the cancel. Both partial, so they hold only the rows that are still owed
-- something -- in steady state, almost none.
CREATE INDEX match_unarchived ON match (completed) WHERE completed IS NOT NULL AND archived_at IS NULL;
CREATE INDEX match_unreleased ON match (game_id)
    WHERE cancelled AND cancel_url IS NOT NULL AND engine_released IS NULL;
