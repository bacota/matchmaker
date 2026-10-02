-- Registers the rock-paper-scissors engine as a game in matchmaker.
--
-- In the local database only — the one the unit tests and local servers use. A deployed matchmaker's
-- games are added on its admin page with the values the deploy scripts print, and this script
-- refuses to run against anything but a local server.
--
--   psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8091/games" -v external_id="rps-dev" \
--        -f engines/rps/register-game.sql
--
-- url          where matchmaker POSTs the create-game request: the local engine's /games.
-- external_id  who matchmaker will accept the callbacks from: whatever GAME_EXTERNAL_ID the
--              local engine is started with.

\set url :url
\set external_id :external_id

-- Local only. A deployed matchmaker's games are added on its admin page, by an admin, with the
-- values the deploy scripts print — never by running this against a deployed database. This is for
-- the local database the unit tests and local servers run against, and it refuses any other: the
-- host is the one psql connected to, as psql sees it, so a socket or a loopback address is local
-- and an RDS endpoint is not.
\set ON_ERROR_STOP on
SELECT :'HOST' LIKE '/%' OR :'HOST' IN ('localhost', '127.0.0.1', '::1') AS is_local \gset
\if :is_local
\else
  \echo 'register-game.sql registers games in a local database only; add a deployed game on matchmaker''s admin page'
  DO $$ BEGIN RAISE EXCEPTION 'refusing to register a game outside the local database'; END $$;
\endif

WITH game AS (
  INSERT INTO game (game_type, name, display_name, description, url, active, external_id)
  VALUES (
    -- 'P' — plain: a seat here is a player, not a character. The engine accepts a
    -- character-carrying seat too (it ignores the character), but nothing here needs one.
    'P',
    'Rock-paper-scissors',
    -- What players see; an admin may change it later. The name above is how this script, and
    -- anything else, finds the game again, so that stays as it is.
    'Rock-paper-scissors',
    'Two players throw at once. A test engine for matchmaker''s simultaneous-turn handling.',
    :'url',
    true,
    :'external_id'
  )
  RETURNING game_id
)
-- One and Two. Both required, because the game needs two players — but unlike tic-tac-toe's X and
-- O, neither side is anything in particular: both throw at the same time and neither moves first.
-- The names exist because matchmaker seats a player by accepting *into* a role, and because the
-- play page has to call the two seats something.
INSERT INTO game_role (game_id, name, optional, display_name)
SELECT game_id, role, false, role FROM game, (VALUES ('One'), ('Two')) AS roles(role);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Rock-paper-scissors' ORDER BY game_id DESC LIMIT 1;
