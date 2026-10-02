-- Registers the boxing engine as a game in matchmaker.
--
-- Matchmaker has no route that creates a game — a game is an administrative fact, not something a
-- player does — so this is how the engine becomes reachable. Run it against the same database
-- matchmaker is using.
--
--   psql "$DATABASE_URL" -v url="http://localhost:8092/games" -v external_id="boxing-dev" \
--        -f engines/boxing/register-game.sql
--
-- url          where matchmaker POSTs the create-game request. Locally the engine's /games;
--              deployed, the engine's own create-game url. The page a player builds and renames
--              fighters on is /fighters beside it, and is recorded as the game's character_url.
-- external_id  who matchmaker will accept the callbacks and fighter writes from. Locally whatever
--              GAME_EXTERNAL_ID the engine is started with; deployed, "boxing" — the name
--              matchmaker files this engine's API key under.
--
-- One transaction, so a failure part-way leaves no game without its corners or its rounds.

\set ON_ERROR_STOP on
\set url :url
\set external_id :external_id

BEGIN;

INSERT INTO game (game_type, name, description, url, character_url, active, external_id)
VALUES (
  -- 'C' — a character game: every corner is a character, which this game calls a fighter. Its
  -- characteristics are the character's state. A fighter is built on the engine's own page, which
  -- reports it to matchmaker; a bout refuses a character that is not a built fighter.
  'C',
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
INSERT INTO game_role (game_id, name, optional)
VALUES (:game_id, 'Red', false), (:game_id, 'Blue', false);

-- How many rounds a bout is scheduled for. The challenger picks one of these values on the
-- challenge form; the default is what a challenge that picked nothing gets. The engine accepts 3
-- to 25 and falls back to 10.
--
-- The default is set last: game_parameter.default_value is a foreign key to the value rows.
INSERT INTO game_parameter (game_id, name) VALUES (:game_id, 'rounds')
RETURNING game_parameter_id AS rounds_id \gset

INSERT INTO game_parameter_value (game_id, game_parameter_id, value)
SELECT :game_id, :rounds_id, n::text FROM generate_series(3, 25) AS n;

UPDATE game_parameter SET default_value = '10'
 WHERE game_id = :game_id AND game_parameter_id = :rounds_id;

COMMIT;

SELECT game_id, name, url, character_url, external_id FROM game WHERE game_id = :game_id;
