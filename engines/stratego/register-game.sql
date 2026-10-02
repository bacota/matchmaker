-- Registers the Stratego engine as a game in matchmaker.
--
-- Matchmaker has no route that creates a game — a game is an administrative fact, not something a
-- player does — so this is how the engine becomes reachable. Run it against the same database
-- matchmaker is using, after editing the two values at the top.
--
--   psql "$DATABASE_URL" -v url="http://localhost:8093/games" -v external_id="stratego-dev" \
--        -f engines/stratego/register-game.sql
--
-- url          where matchmaker POSTs the create-game request. Locally the engine's /games;
--              deployed, the stratego module's create_game_url output.
-- external_id  who matchmaker will accept the callbacks from. Locally whatever GAME_EXTERNAL_ID
--              the engine is started with; deployed, the name matchmaker files this engine's API
--              key under, i.e. "stratego".

\set url :url
\set external_id :external_id

WITH game AS (
  INSERT INTO game (game_type, name, display_name, description, url, active, external_id)
  VALUES (
    -- 'P' — plain: a seat in Stratego is a player, not a character. The engine accepts a
    -- character-carrying seat too (it ignores the character), but nothing here needs one.
    'P',
    'Stratego',
    -- What players see; an admin may change it later. The name above is how this script, and
    -- anything else, finds the game again, so that stays as it is.
    'Stratego',
    'Two armies of forty, ranks hidden from the other side. Deploy, then capture the enemy flag.',
    :'url',
    true,
    :'external_id'
  )
  RETURNING game_id
)
-- Red and Blue, the two armies. Neither is optional: a match cannot start until both are taken.
-- Red moves first once both have deployed.
INSERT INTO game_role (game_id, name, optional, display_name)
SELECT game_id, role, false, role FROM game, (VALUES ('Red'), ('Blue')) AS roles(role);

SELECT game_id, name, url, external_id FROM game WHERE name = 'Stratego' ORDER BY game_id DESC LIMIT 1;
