-- Which bucket a match's archive is in: true for the friendly one, which expires it after 30 days,
-- false for the permanent one.
--
-- V38 left this to follow from `friendly`, which a completed match could not change. It can now: a
-- game's admin may say a finished match was or was not friendly (MatchService.setFriendly). Made
-- not friendly, its archive is moved to the permanent bucket, to be kept; made friendly, it is left
-- in the permanent bucket, where it was safe, rather than moved to one that would expire it. So the
-- two can disagree for good, and where the archive is has to be written down rather than worked out.
-- A move is a copy in S3 and a delete, made outside any transaction, and until it has finished the
-- archive is still where it was -- another reason not to read it from `friendly`.
--
-- Set when an upload is signed, to the bucket the url was signed for, and changed only by a move
-- that has finished copying. Null for a match whose engine has never asked to upload, which has no
-- archive to be anywhere. An archive in the friendly bucket of a match that is no longer friendly is
-- one whose move is owed, and the sweep finishes it.
ALTER TABLE match ADD COLUMN archive_friendly BOOLEAN;

UPDATE match SET archive_friendly = friendly WHERE archive_key IS NOT NULL;

-- The sweep's third question: archives owed a move to the permanent bucket. Partial, like V38's two,
-- so it holds only the rows that are owed something -- in steady state, none.
CREATE INDEX match_misplaced_archive ON match (game_id)
    WHERE archived_at IS NOT NULL AND archive_expired_at IS NULL AND archive_friendly AND NOT friendly;
