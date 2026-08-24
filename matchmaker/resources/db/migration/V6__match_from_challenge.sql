-- A match keeps the challenge it came from, and a challenge is never deleted by starting it.
--
-- Until now a start destroyed its own origin: the challenge and its acceptances were deleted in
-- the last transaction, and `started_match_id` existed only to stop a second start in the window
-- before that happened. The consequence was that a match had no creator -- the challenger who
-- started it was the one fact the delete threw away -- and there is no way to answer "may this
-- caller cancel this match?" without it.
--
-- So the arrow is reversed and made permanent: the match points at its challenge, mandatorily,
-- and the challenge stays. The challenger is the match's creator, by definition rather than by a
-- copied column, so the two cannot disagree.
--
-- No backfill: this is applied to a database with no matches in it. A NOT NULL column with no
-- default would otherwise be unaddable, and there is no honest value to invent for a match whose
-- challenge was deleted months ago.
ALTER TABLE match ADD COLUMN challenge_id BIGINT NOT NULL;

ALTER TABLE match
    ADD FOREIGN KEY (game_id, challenge_id) REFERENCES open_challenge;

-- One match per challenge, enforced here rather than only by `started_match_id`. The claim
-- column serializes two concurrent starts; this says the invariant outright, and survives any
-- future path that writes a match without going through the claim.
CREATE UNIQUE INDEX ON match (game_id, challenge_id);

-- Cancelled by its creator: not completed (nobody won, and the engine may never report), and not
-- deleted (participants are referenced by `result`, and a match that was played and abandoned is
-- history worth keeping). A third state rather than an overload of `completed`, because the two
-- answer different questions -- "is it over" and "how did it end".
ALTER TABLE match ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE;
