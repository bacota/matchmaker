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

### Building a fighter

Matchmaker creates every character with empty state, and only the character's game may write it.
So a fighter is built on the engine's play page, **the first time it is in a bout**:

- **25 points** across the five characteristics, each **1–10**. The constants are `Fighter.Budget`,
  `Min` and `Max`.
- The engine writes the fighter to matchmaker's `PUT /characters/{id}/state` using its API key,
  then puts it in the bout. If matchmaker can't be reached, the build fails with a 502 and nothing
  changes, so the player can simply retry.
- A fighter is built once. At every later bout it arrives with its characteristics in the create
  request's `characterState`. If that state isn't one this engine could have built (for example,
  over budget), the fighter is treated as unbuilt.
- Until a corner has built its fighter it can't plan round 1, and the round can't resolve. It stays
  pending on its own clock the whole time.

Matchmaker's base url for that write is taken from the move callback url it sends
(`{base}/games/{g}/matches/{m}/moves`). A bout created without callback urls, like the offline
local mode, keeps its fighters for that bout only.

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
  contains `outcome`, `method` (`knockout`/`points`), `rounds`, `scheduledRounds`, `points`,
  `knockdowns` and `corner`. The winner is rank 1 and the loser rank 2. A draw is rank 1 for both.

Every move callback also carries `state`: the plan's number (how many plans the bout holds) and
every corner that is to plan now. Callbacks are sent after each commit, from whichever request made
the plan, so two can reach matchmaker in the opposite order to the plans. Matchmaker uses the number
to ignore the late one's effect on whose turn it is (V26), and status answers carry the same number.

Nobody sees a plan for the current round except the corner that made it (`yourPlan`). Everyone else
sees only `planned: true`. Once the round resolves, both plans are in `rounds` for everyone. This is
enforced in `Engine.stateOf`, not in the page.

## Playing locally

```bash
# 1. matchmaker on 8080
AUTH_MODE=header mill -j 4 --ticker false matchmaker.api.runMain com.vivi.matchmaker.api.LocalServer

# 2. the engine on 8092, calling back as the game 'boxing-dev'
PORT=8092 GAME_EXTERNAL_ID=boxing-dev mill -j 4 --ticker false engines.boxing.runMain com.vivi.boxing.LocalServer

# 3. register it as a game
psql "$DATABASE_URL" -v url="http://localhost:8092/games" -v external_id="boxing-dev" \
     -f engines/boxing/register-game.sql
```

Then run the normal character-game flow. Each player creates a fighter (a character) in
matchmaker, a challenge is made and accepted with a fighter each, and the challenger starts it. The
match's `playUrl` is the page. A fighter's first bout opens on the build form.

`MATCHMAKER_OFFLINE=true` runs with nothing to call back to. Callbacks and fighter writes are
printed instead.

With the trusted local sign-in, `?as=<sub>` names the player:

```bash
curl -s -X POST "http://localhost:8092/matches/$MATCH/fighter?as=$SUB" -H 'content-type: application/json' \
     -d '{"strength":5,"speed":5,"agility":5,"workrate":5,"chin":5}'
curl -s -X POST "http://localhost:8092/matches/$MATCH/moves?as=$SUB" -H 'content-type: application/json' \
     -d '{"offense":2,"defense":2,"power":1}'
```

Sign-in, the three `PLAY_AUTH` modes and the environment variables are exactly as in `rps` and
`tictactoe`. See `engines/tictactoe/README.md` under "Who a player is". The default port here is
8092.

## Deployed

`terraform/modules/boxing` is the `rps` module with one more player route
(`POST /matches/{matchId}/fighter`). Enable it with `deploy_boxing = true` in
`environments/<env>.settings.tfvars`.

**The first deployment must be `./deploy-all.sh <env>`** (or `./deploy-boxing.sh <env> --full`).
This engine needs two changes on matchmaker's side that the targeted per-engine plan doesn't touch:

- matchmaker has to hold the engine's API key
- `PUT /characters/{characterId}/state` has to be an engine route (`local.engine_routes` in
  `terraform/modules/api/main.tf`). Behind the JWT authorizer, no game could ever write a
  character's state.

After that, `./deploy-boxing.sh <env>` redeploys the engine alone.

Then register the game with the outputs: `boxing_create_game_url` as `url` and `boxing` as
`external_id`. Both deploy scripts print the exact `psql` command.

## Known limits

- **Callbacks are best-effort**, as in the other engines. A lost one is what matchmaker's
  `refresh` repairs. The fighter write is the exception: it happens before the build is accepted.
- **Matchmaker's UI still says "character".** The game calls them fighters, and so do this engine's
  pages, but matchmaker has no per-game name for its characters.
- **Two builds of one corner racing each other** from two tabs both reach matchmaker, and only the
  first reaches the bout. Matchmaker keeps whichever wrote last.
