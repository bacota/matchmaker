-- Which bucket a match's archive is in: true for the friendly one, which expires it after 30 days,
-- false for the permanent one.
--
-- V38 left this to follow from `friendly`, which a completed match could not change. It can now: a
-- game's admin may say a finished match was or was not friendly (MatchService.setFriendly). Made
-- not friendly, its archive is moved to the permanent bucket, to be kept -- copied there before the
-- change, as part of the same request, which fails if the copy does. Made friendly, it is left in
-- the permanent bucket, where it was safe, rather than moved to one that would expire it. So the two
-- can disagree for good, and where the archive is has to be written down rather than worked out.
--
-- Set when an upload is signed, to the bucket the url was signed for, and changed when a move has
-- copied the archive -- or, for an upload asked for and not yet made, when it is to go to the
-- permanent bucket instead. Null for a match whose engine has never asked to upload, which has no
-- archive to be anywhere.
ALTER TABLE match ADD COLUMN archive_friendly BOOLEAN;

UPDATE match SET archive_friendly = friendly WHERE archive_key IS NOT NULL;
