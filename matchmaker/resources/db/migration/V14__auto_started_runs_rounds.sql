-- One flag for both: a tournament that starts itself begins each of its rounds as the last ends, and
-- has_automatic_rounds goes. It starts itself when it is full, if it has a most entries, and may be started by hand
-- before then; without one, its owner starts it. An owner who starts one by hand and then wants its rounds to run
-- themselves turns is_auto_started on once it is under way.
ALTER TABLE tournament DROP CONSTRAINT tournament_auto_start_full;
-- Under way or over, a tournament keeps the rounds it has. Not yet started, one whose rounds were to run themselves
-- keeps that, and starts itself if it is full as well.
UPDATE tournament SET is_auto_started = has_automatic_rounds WHERE status <> 'open';
UPDATE tournament SET is_auto_started = true WHERE status = 'open' AND has_automatic_rounds;
ALTER TABLE tournament DROP COLUMN has_automatic_rounds;
