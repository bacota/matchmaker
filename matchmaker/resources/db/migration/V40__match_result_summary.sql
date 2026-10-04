-- How the match came out, as its game engine said it with the results: a line of HTML -- "alice
-- knocked out bob in round 4" -- that the finished match is shown with in place of its result table.
--
-- Stored as `SummaryHtml.accept` cleaned it: a few formatting tags without attributes, and
-- everything else escaped. Null for a match whose engine sent none, and for every match completed
-- before engines could send one; those are shown with their result table as before.
ALTER TABLE match ADD COLUMN result_summary TEXT;
