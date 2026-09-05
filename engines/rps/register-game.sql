-- Registers the rock-paper-scissors engine as a game in matchmaker.
--
-- Matchmaker has no route that creates a game — a game is an administrative fact, not something a
-- player does — so this is how the engine becomes reachable. Run it against the same database
-- matchmaker is using, after editing the two values at the top.
--
--   psql "$DATABASE_URL" -v url="http://localhost:8091/games" -v external_id="rps-dev" \
--        -f engines/rps/register-game.sql
--
-- url          where matchmaker POSTs the create-game request. Locally the engine's /games;
--              deployed, the engine's own create-game url.
-- external_id  who matchmaker will accept the callbacks from. Locally whatever GAME_EXTERNAL_ID
--              the engine is started with; deployed, the name matchmaker files this engine's API
--              key under.

\set url :url
\set external_id :external_id

WITH game AS (
  INSERT INTO game (game_type, name, description, url, active, external_id)
  VALUES (
    -- 'P' — plain: a seat here is a player, not a character. The engine accepts a
    -- character-carrying seat too (it ignores the character), but nothing here needs one.
    'P',
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
INSERT INTO game_role (game_id, name, optional)
SELECT game_id, role, false FROM game, (VALUES ('One'), ('Two')) AS roles(role);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Rock-paper-scissors' ORDER BY game_id DESC LIMIT 1;
