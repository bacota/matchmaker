-- Registers the boxing engine as a game in matchmaker.
--
-- In the local database only — the one the unit tests and local servers use. A deployed matchmaker's
-- games are added on its admin page with the values the deploy scripts print, and this script
-- refuses to run against anything but a local server.
--
--   psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8092/games" -v external_id="boxing-dev" \
--        -f engines/boxing/register-game.sql
--
-- url          where matchmaker POSTs the create-game request: the local engine's /games.
-- external_id  who matchmaker will accept the callbacks from: whatever GAME_EXTERNAL_ID the
--              local engine is started with.
--
-- One transaction, so a failure part-way leaves no game without its corners or its rounds.

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

BEGIN;

INSERT INTO game (game_type, name, display_name, description, url, character_url, active, external_id)
VALUES (
  -- 'C' — a character game: every corner is a character, which this game calls a fighter. Its
  -- characteristics are the character's state. A fighter is built on the engine's own page, which
  -- reports it to matchmaker; a bout refuses a character that is not a built fighter.
  'C',
  'Boxing',
  -- What players see; an admin may change it later. The name above is how this script, and
  -- anything else, finds the game again, so that stays as it is.
  'Boxing',
  'Two fighters trade punches over 3 to 25 rounds. Build a fighter from strength, speed, agility, '
    'workrate and chin, then plan each round''s offense, defense and power.',
  :'url',
  -- Where matchmaker's UI sends a player who has no fighter yet.
  regexp_replace(:'url', '/games$', '/fighters'),
  true,
  :'external_id'
)
RETURNING game_id \gset

-- Red and Blue, both required: a bout needs two fighters. Neither corner has any advantage — both
-- plan every round at the same time.
INSERT INTO game_role (game_id, name, optional, display_name)
VALUES (:game_id, 'Red', false, 'Red'), (:game_id, 'Blue', false, 'Blue');

-- How many rounds a bout is scheduled for. The challenger picks one of these values on the
-- challenge form; the default is what a challenge that picked nothing gets. The engine accepts 3
-- to 25 and falls back to 10.
--
-- The default is set last: game_parameter.default_value is a foreign key to the value rows.
-- `rounds` is the name the engine reads; 'Rounds' is what a challenger is shown.
INSERT INTO game_parameter (game_id, name, display_name) VALUES (:game_id, 'rounds', 'Rounds')
RETURNING game_parameter_id AS rounds_id \gset

INSERT INTO game_parameter_value (game_id, game_parameter_id, value)
SELECT :game_id, :rounds_id, n::text FROM generate_series(3, 25) AS n;

UPDATE game_parameter SET default_value = '10'
 WHERE game_id = :game_id AND game_parameter_id = :rounds_id;

COMMIT;

SELECT game_id, name, url, character_url, external_id FROM game WHERE game_id = :game_id;
