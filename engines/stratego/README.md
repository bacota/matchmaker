# Stratego

A two-player game engine implementing the game API of `interaction-design.txt`: classic Stratego on
a 10x10 board, 40 pieces a side.

Like every engine here it is not part of matchmaker, and it shares everything that is not the game
itself with the others through `engines/common`. That includes the four exchanges with matchmaker,
the sign-in, Play Live, live matches and the environment variables, all of which are described once
in `engines/tictactoe/README.md` and work the same here.

## What it exercises

- **Hidden information for a whole match.** Each player sees only their own ranks and the enemy
  pieces that have been revealed. The public board sees only revealed ranks. What a viewer may see
  is decided in `Engine.stateOf`, and the rest is left out of the response, not just left undrawn
  by the page.
- **Two phases in one match.** During setup both seats are pending at once, as in rps, and the
  first setup's callback names nobody. Once both armies are down, Red moves first and the sides
  alternate, as in tic-tac-toe. The second setup's callback names Red, and Red's clock starts from
  that moment.

## Rules

Classic (ISF) rules:

| rank | count | | rank | count |
|---|---|---|---|---|
| Marshal (10) | 1 | | Sergeant (4) | 4 |
| General (9) | 1 | | Miner (3) | 5 |
| Colonel (8) | 2 | | Scout (2) | 8 |
| Major (7) | 3 | | Spy (S) | 1 |
| Captain (6) | 4 | | Bomb (B) | 6 |
| Lieutenant (5) | 4 | | Flag (F) | 1 |

- **Deployment:** Red deploys on rows 1–4 and Blue on rows 7–10. The lakes are c5, d5, g5, h5, c6,
  d6, g6 and h6.
- **Movement:**
  - A piece moves one square orthogonally.
  - A scout moves any distance in a straight line and may attack the first enemy it meets, but
    never jumps.
  - Bombs and the flag never move, and nothing enters a lake.
- **Combat:**
  - The higher rank wins, and equal ranks both fall.
  - A spy that *attacks* the marshal takes it.
  - A bomb destroys any attacker except a miner.
  - Anything takes the flag.
- **What the opponent learns:** a battle reveals both pieces to everyone. A scout that moves more
  than one square reveals itself. A piece that has moved is marked as moved, which shows it is not a
  bomb or the flag.
- **Two-square rule:** a piece may not make a fourth consecutive move between the same two squares.
- **End of the match:**
  - Capturing the flag wins.
  - A player with nothing they may move, on their turn, loses. If neither side can move, it is a
    draw.
  - After `maxMoves` piece moves by both sides together, the match is a draw.

Not enforced: the **more-squares rule**, that a player may not chase one piece endlessly with
another. The ISF states it only by example. The move cap is what ends a match that would otherwise
never finish.

A setup that walls in every movable piece behind bombs is legal, and loses on Red's first turn or
Blue's. The engine does not warn about it.

### Parameter

| parameter | meaning |
|---|---|
| `maxMoves` | Piece moves, both sides together, before the match is drawn. Default 2000. A match created without it is stored without it, so changing the default changes the cap on matches already in progress. |

## The play API

| route | body | answer |
|---|---|---|
| `POST /matches/{id}/moves` | `{"setup":[40 ranks]}`, listed in the order of the side's home squares, ascending (a1…j4 for Red, a7…j10 for Blue) | the new state |
| `POST /matches/{id}/moves` | `{"from":30,"to":40}`, squares numbered 0 (a1) to 99 (j10) | the new state |
| `GET /matches/{id}/state` | | the caller's state |
| `GET /matches/{id}/board/state` | | the public state |

The state includes `legalMoves`, as `[from, to]` pairs, for the player whose turn it is. The page
highlights moves from that list and never works out what is legal for itself.

## Playing locally

The same as tic-tac-toe, on port 8093:

```bash
GAME_EXTERNAL_ID=stratego-dev mill -j 4 --ticker false engines.stratego.runMain com.vivi.stratego.LocalServer
psql "$DATABASE_URL" -v url="http://localhost:8093/games" -v external_id="stratego-dev" \
     -f engines/stratego/register-game.sql
```

`MATCHMAKER_OFFLINE=true` runs it with nothing to call back to. In the trusted local mode, open
`/matches/<id>/play?as=<cognito sub>` as each player.

## The page

- **Setup:** your army starts shuffled. Tap two of your squares to swap them, or use the two
  selects, then deploy. The arrangement is kept in the browser until you deploy.
- **Play:** tap a piece, then one of the highlighted squares. Each viewer sees their own side at
  the bottom.
- **On a phone:** ten squares across comes to about 32px a square, under the 44px a touch target
  should be. So the Piece and To selects below the board make every move at full size, and they
  also serve a keyboard or a screen reader.
- **Accessibility:** every square is a labelled button, and arrow keys move between them. Each move
  and battle is announced once.

## Deployed

```hcl
# environments/dev.settings.tfvars
deploy_stratego = true
```

The first deployment has to go through `./deploy-all.sh dev` (or `./deploy-stratego.sh dev --full`).
Matchmaker's own function has to be given this engine's API key, and the engine-only plan leaves
matchmaker alone. After that, `./deploy-stratego.sh dev` deploys the engine by itself. It prints the
command that registers the game: `create_game_url` as `url`, and `stratego` as `external_id`.
