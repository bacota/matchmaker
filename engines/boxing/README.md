# boxing

A two-player game engine implementing the game API of `interaction-design.txt`, playing the game
described in `boxing.txt`. It is the third engine in `engines/` and the first **character game**:
every corner is a matchmaker character, which this game calls a **fighter**.

Like `rps`, it is a separate system reached over HTTP. Its wire types are restated in `Protocol`
rather than imported, and `ProtocolSpec` is the one place the two sides are compared.

## The game

A fighter has five characteristics: **strength, speed, agility, workrate, chin**. A bout is
scheduled for 3–25 rounds. Each round, both players secretly split their fighter's workrate across
**offense, defense and power**, and the round resolves when the second plan arrives:

- offense = plan + 2 × speed, defense = plan + 2 × agility, power = plan + 2 × strength
- effective chin = defense + 3 × chin

A player sees their own fighter's characteristics and nobody else's, and the public board shows
neither corner's. In place of the numbers, a corner is described by its highest and lowest
characteristic (`Fighter.impression`):

| | Highest | Lowest |
|---|---|---|
| Strength | powerful | not very muscular |
| Speed | fast | sluggish |
| Agility | tall | short |
| Workrate | fit | flabby |
| Chin | — | — |

Only strength, speed, agility and workrate are compared: chin is left out, so it neither gets a word
nor stops another characteristic getting one. When two tie at an end, both are described; when
three or more do, nothing is said for that end.

Once a round resolves, both corners' total offense, defense and power (`redNumbers` / `blueNumbers`)
are sent to everyone. A corner's totals less its plan are its characteristics, so each corner is sent
only its own plans (`red` / `blue`), and the public board neither. Effective chin is never sent.

Each test below is only looked at if nothing above it decided the round. "Both landed" is settled
by higher offense, then higher defense. If that still ties, the round drops to the next test down.

| test | result |
|---|---|
| power > opponent's effective chin | **knockout**: the bout ends |
| power > opponent's defense + chin | **knockdown**: 10–8 |
| power > opponent's defense | **telling blow**: 10–9 |
| higher offense, then higher defense | 10–9 |
| nothing separates them | 10–10 |

Without a knockout, the fighter with more points after the last round wins. Equal points is a
draw. All of this is `Rules.resolve` in `Fighter.scala`, and `RulesSpec` covers it clause by
clause.

Two readings of `boxing.txt` worth knowing about:

- "if there has not been a knockdown" in its last paragraph is read as *knockout*. A knockdown is
  scored 10–8 and the bout goes on.
- "If there is no tie there is no knockdown" is read as "if there is *still* a tie". Both fighters
  landing a knockdown that nothing separates means neither one scores it.

### Fighters

A fighter is a matchmaker character, and its characteristics are the character's state:

- **25 points** across the five characteristics, each **1–10**. The constants are `Fighter.Budget`,
  `Min` and `Max`.
- Every fighter arrives at a bout already built, its characteristics in the create request's
  `characterState`. Building a fighter is not part of a bout: a corner whose state is empty, or
  isn't one this engine could have built (for example, over budget), refuses the bout.

A fighter is built here, not in matchmaker. A signed-in player builds one on this engine's
`/fighters` page, which posts to `POST /fighters`. The engine checks the fighter against the
rules above and then reports it to matchmaker with `POST {MATCHMAKER_URL}/characters`. That call
carries the engine's API key, the player's `sub` as owner, and the characteristics as the
character's state. Matchmaker takes the game from the key, records the character, and answers with
its id. The engine keeps nothing itself, so the fighter's only record is matchmaker's.

Matchmaker's UI has no character form of its own. A player with no fighter is sent to the game's
`character_url`, which `register-game.sql` sets to `/fighters` beside the engine's `/games`.

Every other change to a fighter is made here too, on the same page. It lists the player's fighters,
and each one can be renamed, given a new description, or given to another player by their
matchmaker nickname. The engine keeps no fighters itself, so it gets the list from matchmaker with
`GET {MATCHMAKER_URL}/characters?owner=<sub>`. Edits go to `PUT {MATCHMAKER_URL}/characters/{id}`
and gifts to `PUT {MATCHMAKER_URL}/characters/{id}/owner`, each naming the signed-in player as
owner. Matchmaker applies them only if that player owns the fighter. Players cannot change
characters in matchmaker at all.

### Rounds

The challenger picks it. Matchmaker's challenge form offers a dropdown for every game parameter,
stores the pick in the challenge's `settings` (`{"rounds":"12"}`), refuses a value the game doesn't
allow, and at start sends the engine the chosen value in `parameters` in place of the default. A
challenge that chose nothing gets the game's default, 10 as registered.

The engine reads `rounds` from the challenge's `settings`, then the `rounds` parameter, then falls
back to 10. Anything outside 3–25 refuses the create.

## The four exchanges

The same as `rps` (`POST /games`, the move and results callbacks, `GET /matches/{id}/status`), with
simultaneous rounds:

- Both corners are pending from the start of each round, and each clock starts when the round did.
- The **first plan of a round** is reported with `next` empty. The other corner stays pending by
  not being named.
- The **plan that resolves a round** names *both* corners in `next`, the mover included. That
  starts the next round's clocks at `takenAt`. Matchmaker clears the mover before applying `next`,
  so this works.
- The plan that **ends the bout** names nobody, and the results follow. Each corner's `scores`
  contains `outcome`, `method` (`knockout`/`points`), `rounds` (fought), `points`,
  `knockdowns` and `corner`. The winner is rank 1 and the loser rank 2. A draw is rank 1 for both.

Every move callback also carries `state`: the plan's number (how many plans the bout holds) and
every corner that is to plan now. Callbacks are sent after each commit, from whichever request made
the plan, so two can reach matchmaker in the opposite order to the plans. Matchmaker uses the number
to ignore the late one's effect on whose turn it is (V26), and status answers carry the same number.

Nobody sees a plan for the current round except the corner that made it (`yourPlan`). Everyone else
sees only `planned: true`. That holds after the round resolves too: `rounds` carries each viewer's own
plans and nobody else's. This is enforced in `Engine.stateOf`, not in the page.

## Playing locally

```bash
# 1. matchmaker on 8080
AUTH_MODE=header mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 2. the engine on 8092, calling back as the game 'boxing-dev'
PORT=8092 GAME_EXTERNAL_ID=boxing-dev mill -j 4 --ticker false engines.boxing.runMain com.vivi.boxing.LocalServer

# 3. register it as a game
psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8092/games" -v external_id="boxing-dev" \
     -f engines/boxing/register-game.sql
```

Then run the normal character-game flow. Each player builds a fighter at
`http://localhost:8092/fighters?as=<sub>`, which registers it with the matchmaker at
`MATCHMAKER_URL` (by default `http://localhost:8080`). A challenge is then made and accepted with a
fighter each, and the challenger starts it. The match's `playUrl` is the page.

`MATCHMAKER_OFFLINE=true` runs with nothing to call back to. Callbacks are
printed instead.

With the trusted local sign-in, `?as=<sub>` names the player:

```bash
curl -s -X POST "http://localhost:8092/matches/$MATCH/moves?as=$SUB" -H 'content-type: application/json' \
     -d '{"offense":2,"defense":2,"power":1}'
```

Sign-in, the three `PLAY_AUTH` modes and the environment variables are exactly as in `rps` and
`tictactoe`. See `engines/tictactoe/README.md` under "Who a player is". The default port here is
8092.

Play Live is the same too (`engines/tictactoe/README.md`, "Play Live"), and served locally on 8192.

## Deployed

The boxing engine uses `terraform/modules/engine`, the module shared by every bundled engine. Enable it
with `deploy_boxing = true` in
`environments/<env>.settings.tfvars`.

**The first deployment must be `./deploy-all.sh <env>`** (or `./deploy-boxing.sh <env> --full`).
This engine needs a change on matchmaker's side that the targeted per-engine plan doesn't touch —
its API key does not count, since matchmaker learns that from the game's admin form:

- `POST /characters`, `GET /characters`, `PUT /characters/{characterId}` and
  `PUT /characters/{characterId}/owner` (and `PUT /characters/{characterId}/state`) have to be
  engine routes
  (`local.engine_routes` in `terraform/modules/api/main.tf`). Behind the JWT authorizer, no game
  could ever report a fighter.

After that, `./deploy-boxing.sh <env>` redeploys the engine alone.

Then add the game on matchmaker's admin page ("Add a Game"), or edit it if it is already there.
A deploy never creates a game, and `register-game.sql` is for the local database only — it refuses
any other. The deploy scripts print the urls; the form wants:

- **Name**: Boxing
- **Game engine url**: the `boxing_create_game_url` output
- **Engine identity**: `boxing` — the name matchmaker files this engine's API key under, and so
  how it tells which engine a callback came from. A game naming anything else has its callbacks
  refused.
- **API key**: the key the engine was deployed with, which
  `./terraform/tf.sh <env> output -raw boxing_api_key` prints. Matchmaker stores it with the game and
  never shows it again; entering a new one on the game's edit form replaces it.
- **Type**: requires characters
- **Roles**: `Red` and `Blue`, neither optional
- **Character page url**: the `boxing_character_url` output — where a player builds a fighter
- **Parameters**: `rounds`, display name `Rounds`, values `3, 4, … 25`, default `10`

## Known limits

- **Callbacks are best-effort**, as in the other engines. A lost one is what matchmaker's
  `refresh` repairs.
- **Matchmaker's UI still says "character".** The game calls them fighters, and so do this engine's
  pages, but matchmaker has no per-game name for its characters.
