-- Registers the Hilo engine as a game in matchmaker.
--
-- In the local database only — the one the unit tests and local servers use. A deployed matchmaker's
-- games are added on its admin page with the values the deploy scripts print, and this script
-- refuses to run against anything but a local server.
--
--   psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8095/games" -v external_id="hilo-dev" \
--        -f engines/hilo/register-game.sql
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
  -- Plain: a player, not a character.
  'P',
  'Hilo',
  'Hilo',
  'Everyone picks a number at once; the highest number nobody else picked wins.',
  :'url',
  true,
  :'external_id',
  -- Everyone picks at once, and a game without roles always does.
  'sim',
  -- What matchmaker divides a round's time among: a typical match, not a limit the engine keeps.
  3,
  -- No roles: two players or more a match, as the tournament or challenge says, with no upper limit.
  2,
  NULL
);

-- Challenges, at least, so that the game can be played as soon as it is registered: a game offers
-- only the formats it has been given, which the admin page does when a game is created there. The
-- other formats can be ticked on the game's admin page.
INSERT INTO tournament_model_game (game_id, tournament_model_id)
SELECT g.game_id, m.tournament_model_id
FROM game g JOIN tournament_model m ON m.tournament_model_id = 'challenge' AND m.is_active
WHERE g.name = 'Hilo' AND g.game_id = (SELECT max(game_id) FROM game WHERE name = 'Hilo');

SELECT game_id, name, url, external_id FROM game WHERE name = 'Hilo' ORDER BY game_id DESC LIMIT 1;
