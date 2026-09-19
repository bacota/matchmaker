-- Renames `open_challenge` to `challenge`, and its character sibling with it.
--
-- The name was accurate when every challenge was an offer to whoever turned up. It stops being
-- accurate as soon as a challenge can be addressed to particular players, and "an open
-- open_challenge" is not a sentence anybody should have to write. Openness becomes a property of
-- a challenge (`is_open`) rather than part of its name, which is the migration after this one;
-- this one is the rename alone, so that the diff that changes behaviour is small enough to read.
--
-- Indexes, constraints and the update_date trigger follow the table automatically. Their *names*
-- do not: `open_challenge_pkey`, the foreign keys, and the index V1 created on (challenger) still
-- say open_challenge. They are left alone deliberately -- renaming them changes nothing any query
-- names, and a migration that renames a dozen constraints is a dozen more chances to name one
-- wrong than it is worth.
ALTER TABLE open_challenge RENAME TO challenge;
ALTER TABLE character_open_challenge RENAME TO character_challenge;

-- The trigger is the one exception, and not for tidiness: V1 builds these by formatting the table
-- name into `trg_%1$s_update_date`, so a table named `challenge` carrying a trigger named
-- `trg_open_challenge_update_date` is a table that gets a *second*, identical trigger the day
-- anything runs that loop again. Renaming it now is what makes that a no-op instead.
ALTER TRIGGER trg_open_challenge_update_date ON challenge RENAME TO trg_challenge_update_date;
