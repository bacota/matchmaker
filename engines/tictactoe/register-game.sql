-- Registers the tic-tac-toe engine as a game in matchmaker.
--
-- Matchmaker has no route that creates a game — a game is an administrative fact, not something a
-- player does — so this is how the engine becomes reachable. Run it against the same database
-- matchmaker is using, after editing the two values at the top.
--
--   psql "$DATABASE_URL" -v url="http://localhost:8090/games" -v external_id="tictactoe-dev" \
--        -f engines/tictactoe/register-game.sql
--
-- url          where matchmaker POSTs the create-game request. Locally the engine's /games;
--              deployed, the tictactoe module's create_game_url output.
-- external_id  who matchmaker will accept the callbacks from. Locally whatever GAME_EXTERNAL_ID
--              the engine is started with; deployed, the engine's lambda_role_arn output, since
--              the deployed matchmaker identifies a caller by its verified IAM principal.

\set url :url
\set external_id :external_id

WITH game AS (
  INSERT INTO game (game_type, name, description, url, active, external_id, min_players, max_players)
  VALUES (
    -- 'P' — plain: a seat in tic-tac-toe is a player, not a character. The engine accepts a
    -- character-carrying seat too (it ignores the character), but nothing here needs one.
    'P',
    'Tic-tac-toe',
    'Two players, three in a row. A test engine for matchmaker''s game interaction.',
    :'url',
    true,
    :'external_id',
    2,
    2
  )
  RETURNING game_id
)
-- X and O, the two sides of the game. Neither is optional: a seat in tic-tac-toe is one side or
-- the other, so a match cannot start until both are taken.
INSERT INTO game_role (game_id, name, optional)
SELECT game_id, role, false FROM game, (VALUES ('X'), ('O')) AS roles(role);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Tic-tac-toe' ORDER BY game_id DESC LIMIT 1;
