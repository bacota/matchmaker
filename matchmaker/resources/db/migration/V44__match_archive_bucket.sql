-- Which bucket a match's archive is in: true for the friendly one, which expires it after 30 days,
-- false for the permanent one.
--
-- V38 left this to follow from `friendly`, which a completed match could not change. It can now: a
-- game's admin may say a finished match was or was not friendly (MatchService.setFriendly), and the
-- archive is then moved to the other bucket to match. A move is a copy to S3 and a delete, made
-- outside any transaction, and until it has finished the archive is still where it was -- so where
-- it is has to be written down rather than worked out. Reading it from `friendly` would look for an
-- archive in the bucket it is on its way to, and find nothing, for as long as a move takes, or for
-- good if one failed half way.
--
-- Set when an upload is signed, to the bucket the url was signed for, and changed only by a move
-- that has finished copying. Null for a match whose engine has never asked to upload, which has no
-- archive to be anywhere. A match whose `archive_friendly` differs from its `friendly` is one whose
-- move is owed, and the sweep finishes it.
ALTER TABLE match ADD COLUMN archive_friendly BOOLEAN;

UPDATE match SET archive_friendly = friendly WHERE archive_key IS NOT NULL;

-- The sweep's third question: archives in the wrong bucket. Partial, like V38's two, so it holds only
-- the rows that are owed something -- in steady state, none.
CREATE INDEX match_misplaced_archive ON match (game_id)
    WHERE archived_at IS NOT NULL AND archive_expired_at IS NULL AND archive_friendly <> friendly;
