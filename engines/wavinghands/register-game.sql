-- Registers the Waving Hands engine as a game in matchmaker.
--
-- In the local database only — the one the unit tests and local servers use. A deployed matchmaker's
-- games are added on its admin page with the values the deploy scripts print, and this script
-- refuses to run against anything but a local server.
--
--   psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8094/games" -v external_id="wavinghands-dev" \
--        -f engines/wavinghands/register-game.sql
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

INSERT INTO game (game_type, name, display_name, description, url, is_active, external_id, move_type, max_turns,
                  min_players, max_players)
VALUES (
  -- Plain: a wizard is a player, not a character.
  'P',
  'Waving Hands',
  'Waving Hands',
  'A duel of wizards: spells made of hand gestures, every order given in secret and carried out at once.',
  :'url',
  true,
  :'external_id',
  -- Every wizard gestures at once, and a game without roles always does.
  'sim',
  -- What matchmaker divides a round's time among: a typical duel, not a limit the engine keeps.
  20,
  -- No roles: two to eight wizards a match, as the tournament or challenge says.
  2,
  8
);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Waving Hands' ORDER BY game_id DESC LIMIT 1;
