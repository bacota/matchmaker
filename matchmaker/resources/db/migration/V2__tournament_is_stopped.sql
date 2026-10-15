-- A running tournament its owner has stopped: no further round is begun, and it finishes once the round being
-- played is over. Not is_stop_requested, which ends only a stage that repeats until stopped.
ALTER TABLE tournament ADD COLUMN is_stopped BOOLEAN NOT NULL DEFAULT false;
