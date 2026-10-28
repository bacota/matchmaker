-- A match completed without its results, its results callback having been lost: when matchmaker last had an answer from
-- its engine about them. The caller's completed list asks the engine once for each such match, so that a row is not
-- left with no result; set once the engine has answered, whether or not the answer had results, so that a match the
-- engine has nothing for is not asked about on every load.
ALTER TABLE match ADD COLUMN results_asked_at TIMESTAMPTZ;
