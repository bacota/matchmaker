-- Registers the tic-tac-toe engine as a game in matchmaker.
--
-- In the local database only — the one the unit tests and local servers use. A deployed matchmaker's
-- games are added on its admin page with the values the deploy scripts print, and this script
-- refuses to run against anything but a local server.
--
--   psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8090/games" -v external_id="tictactoe-dev" \
--        -f engines/tictactoe/register-game.sql
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
    -- 'P' — plain: a seat in tic-tac-toe is a player, not a character. The engine accepts a
    -- character-carrying seat too (it ignores the character), but nothing here needs one.
    'P',
    'Tic-tac-toe',
    -- What players see; an admin may change it later. The name above is how this script, and
    -- anything else, finds the game again, so that stays as it is.
    'Tic-tac-toe',
    'Two players, three in a row. A test engine for matchmaker''s game interaction.',
    :'url',
    true,
    :'external_id'
  )
  RETURNING game_id
)
-- X and O, the two sides of the game. Neither is optional: a seat in tic-tac-toe is one side or
-- the other, so a match cannot start until both are taken.
INSERT INTO game_role (game_id, name, optional, display_name)
SELECT game_id, role, false, role FROM game, (VALUES ('X'), ('O')) AS roles(role);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Tic-tac-toe' ORDER BY game_id DESC LIMIT 1;
