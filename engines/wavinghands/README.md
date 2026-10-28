# Waving Hands

A game engine implementing the game API of `interaction-design.txt`: Richard Bartle's *Waving
Hands*, a duel of wizards. Each turn every wizard gestures with both hands, in secret, and the
gestures are revealed and carried out at once. A series of gestures with one hand makes a spell,
and series overlap, so one gesture can be part of several spells in progress. A wizard who takes
fifteen points of damage dies; the last wizard standing wins.

The rules are the ones published as *Waving Hands* in *Duel Purpose*
(<http://www.gamecabinet.com/rules/WavingHands.html>). This engine plays them as a **game without
roles** for any number of wizards: matchmaker seats two or more players, in no particular order,
and every wizard still fighting gives orders each turn.

## Phases

The rules are being built in three phases. Gestures that would complete a spell not yet built
complete nothing.

1. **The core duel — this phase.** Shield, counter-spell, magic mirror, remove enchantment,
   dispel magic, cure light and heavy wounds, summon goblin, ogre, troll and giant, missile, finger
   of death, lightning bolt (both series; the short one once per battle), cause light and heavy
   wounds, fireball, amnesia, fear, paralysis, anti-spell, protection from evil and resist heat;
   stabs, monsters and surrender.
2. **Everything but four spells.** Summon elemental, fire and ice storms, resist cold, confusion,
   charm person and charm monster, disease, poison, blindness, invisibility and raise dead.
3. **The whole rulebook.** Haste, time stop, delayed effect and permanency, which give one wizard
   extra turns or bank spells.

## How the rules are played here

Where the rules leave a choice to a referee, or were written for two players, this is what the
engine does:

- **Orders.** A wizard gives both hands' gestures, the spells to cast where the gestures complete
  more than one, a target for each spell, whom a stab is aimed at, and what each of their monsters
  attacks. Orders are given once a turn and cannot be changed.
- **Choices made in advance.** Choices the rules make after the gestures are revealed are made
  with the orders instead: which hand a paralysis freezes, and what a newly summoned monster
  attacks on its first turn.
- **Default targets.** A protection, cure or summons goes to its caster; anything else goes to the
  opponent when exactly one is left, and otherwise to nobody unless a target is named. A monster
  with no orders attacks the sole opponent, if there is one.
- **One spell per gesture.** Each hand's last gesture completes one spell at most. A spell whose
  last gesture needs both hands, such as magic mirror or the short lightning bolt, takes both.
  When the gestures complete more than one spell, the wizard chooses; by default the longest is
  cast.
- **Reflection.** A spell turned back by a magic mirror is not reflected again.
- **Dispel magic is universal.** It stops every other spell cast that turn whatever its own
  subject: cast at nobody, or at a wizard who is out, it still works, and only its shield needs a
  subject. Nothing stops it — the rules exempt it from counter-spells and mirrors.
- **Several wizards.** A wizard who surrenders is out, and the duel goes on among the others. The
  last wizard standing wins. The others rank by how long they lasted, and on the same turn a wizard
  who surrendered and lived ranks above one who died.
- **No draw allowed.** If a match may not end in a draw and the last wizards fall together, the
  least wounded wins, and failing that the first seated.
- **A turn limit.** A duel still going after 100 turns ends, and the wizards still standing rank
  by their wounds.

## The play page

`/matches/<id>/play` signs the player in and shows:

- every wizard, with their damage and anything affecting them;
- the monsters;
- the orders form;
- every turn's gestures, and what each turn did;
- a reference of the spells.

The form offers only the gestures a wizard may make; a hand held by amnesia or paralysis offers one.
For each pair of gestures the server sends the spells they would complete, so the page never works
out the rules for itself.

The form is built once per turn, so the page keeping itself current does not throw away a
half-made choice. Controls are labelled 16px fields with 44px touch targets, and changes are
announced.

## Playing locally

On port 8094:

```bash
GAME_EXTERNAL_ID=wavinghands-dev mill -j 4 --ticker false engines.wavinghands.runMain com.vivi.wavinghands.LocalServer
psql -h localhost -U matchmaker matchmaker -v url="http://localhost:8094/games" -v external_id="wavinghands-dev" \
     -f engines/wavinghands/register-game.sql
```

`MATCHMAKER_OFFLINE=true` runs it with nothing to call back to. In the trusted local mode, open
`/matches/<id>/play?as=<cognito sub>` as each player.

## Deployed

```hcl
# environments/dev.settings.tfvars
deploy_wavinghands = true
```

`./deploy-wavinghands.sh dev` deploys the engine by itself; `./deploy-all.sh dev` deploys it with
matchmaker. Then add the game on matchmaker's admin page ("Add a Game"). The deploy scripts print
the urls; the form wants:

- **Name**: Waving Hands
- **Game engine url**: the `wavinghands_create_game_url` output
- **Engine identity**: `wavinghands`
- **API key**: `./terraform/tf.sh <env> output -raw wavinghands_api_key`
- **Type**: plain (leave "Requires characters" unticked)
- **Seats**: Any number of players, **Fewest players** 2, **Most players** 8 (or blank for no
  limit)
- **Moves**: Simultaneous, which a game without roles always is
- **Maximum turns**: 20. A duel has no fixed length, but matchmaker divides a round's time among a
  simultaneous game's turns, and refuses a challenge for one that gives no number. Twenty is a
  typical duel; the engine itself plays on until the duel is decided, or to 100 turns.
- **Parameters**: none

## Security

What the security reviewer checks this engine against, beside what every engine shares.

- **Hidden:** the orders given for the turn being played. Every viewer learns only who has given orders
  (`hasOrdered`). A turn's gestures are shown once every wizard has given orders. Blindness and invisibility, which
  hide gestures in the full rules, are not played. Adding either one adds hidden information, and this section
  changes with it.
- **Text players write:** the match's title and nicknames, which are the wizards' names and appear in the battle
  reports. The page escapes them with the shared `escapeHtml`.
- **Routes of its own:** none.
