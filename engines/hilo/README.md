# Hilo

A number game for any number of players, with no roles, and the simplest multiplayer game there
is to test with.

## The rules

With N players, every player picks a number from 1 to N, all at once and in secret. The highest
number that nobody else picked wins. If every number picked was picked by two players or more,
nobody wins the round and everyone picks again, until somebody does.

So with three players picking 3, 3 and 2, the player who picked 2 wins: 3 was the highest number,
but it was picked twice.

- **Never a draw.** A round nobody wins is played again, so every match ends with one winner, ranked
  1, and everyone else ranked 2.
- **Picks are hidden** until the last player of the round has picked; then the whole round is
  shown. A pick cannot be changed.
- **A live match** whose clock runs out is decided by the clock, as in every engine: whoever ran out
  loses, and everyone else wins.

## The play page

`/matches/<id>/play` signs the player in and shows a button for each number, who has picked, and
every round played. The buttons are 44px touch targets, the status line is announced as it
changes, and the page keeps itself current, polled or with Play Live.

## Playing locally

On port 8095:

```bash
GAME_EXTERNAL_ID=hilo-dev mill -j 4 --ticker false engines.hilo.runMain com.vivi.hilo.LocalServer
psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8095/games" -v external_id="hilo-dev" \
     -f engines/hilo/register-game.sql
```

`MATCHMAKER_OFFLINE=true` runs it with nothing to call back to. In the trusted local mode, open
`/matches/<id>/play?as=<cognito sub>` as each player.

## Deployed

```hcl
# environments/dev.settings.tfvars
deploy_hilo = true
```

`./deploy-hilo.sh dev` deploys the engine by itself; `./deploy-all.sh dev` deploys it with
matchmaker. Then add the game on matchmaker's admin page ("Add a Game"). The deploy scripts print
the urls; the form wants:

- **Name**: Hilo
- **Game engine url**: the `hilo_create_game_url` output
- **Engine identity**: `hilo`
- **API key**: `./terraform/tf.sh <env> output -raw hilo_api_key`
- **Type**: plain (leave "Requires characters" unticked)
- **Seats**: Any number of players, **Fewest players** 2, **Most players** blank for no limit
- **Moves**: Simultaneous, which a game without roles always is
- **Maximum turns**: 3. A match has no fixed length, but matchmaker divides a round's time among a
  simultaneous game's turns, and refuses a challenge for one that gives no number. Most matches
  are decided in a round or two; the engine plays on until one is.
- **Parameters**: none
