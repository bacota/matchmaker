# Plan: a challenge is a tournament

A challenge becomes the simplest kind of tournament: one atomic stage, played once, with one pool, whose pool
size is between the game's required roles and its total roles. The `challenge`, `acceptance` and `invitation`
tables go, along with their character counterparts. Every match is a tournament's.

The one thing a challenge loses is choosing a role on accepting. A challenge always has its players choose roles
after it starts, in seed order, strongest first: the tournament option "always choose roles" is set on the model.

Every database is rebuilt from `V1__init.sql`, so the schema changes are made in place.

## Decisions

| Question | Decision |
|---|---|
| Time limit | The tournament's `round_duration`, spent through `Clock.forMatch`. A challenge can no longer choose between a chess clock and a per-turn limit; that follows the game's move type. |
| Game parameters | `tournament.settings`, checked against the game's parameter values as a challenge's were, and copied to every match. Leaving them off tournaments was an omission. |
| Live | `tournament.live`, copied to every match. A live tournament needs a round duration of at least a second. |
| Message | Dropped. |
| Start | `tournament.start`, optional: when it is meant to be played, shown and nothing more, as a challenge's was. |
| Starting | `tournament.auto_start`: started by itself as soon as entries reach `max_entrants`. Otherwise the owner starts it once there are enough entries to fill the required roles. |
| Capacity | `tournament.max_entrants`, NULL for no limit. A challenge's is its pool size, so entry closes when the pool is full. |
| Pool size | The challenge's `default_pool_size`, which must be between the game's required roles and its total roles. |
| Choosing roles | `tournament_model.choose_roles` and `tournament.choose_roles`: every match's seats choose, in seed order, whatever the game's preference levels and whether its roles matter. |
| Draws | `stage_model.ties_allowed` / `stage.ties_allowed`, NULL for today's rule (no draw in a pool of two). The challenge model's stage allows them. |
| Mail | The challenge kinds are sent for every tournament: entering, withdrawing, filling up, and invitations sent, taken up and turned down. The columns keep their names. |
| UI | The challenge screens stay, as a short form and lists backed by challenge-model tournaments. Everything after creating one uses the tournament routes. |

## A pool no larger than the game's roles plays one match

Today a round-robin pool plays every R-subset of its members, where R is the game's required and recommended
roles. A challenge's pool can be larger than R and still be one match, when the game has optional roles. So a
pool whose size is between the required roles and the total roles plays one match of everyone, seated in the
required roles, then the recommended, then the optional. The only other formats this changes are those played
in a game with optional roles at a pool size above R.

## Schema

- Dropped: `challenge`, `character_challenge`, `acceptance`, `character_acceptance`, `invitation`,
  `character_invitation`.
- Renamed: `tournament_invitation` to `invitation`, `tournament_character_invitation` to `character_invitation`.
- `tournament`: `settings`, `live`, `start`, `choose_roles`, `auto_start`, `max_entrants`.
- `tournament_model`: `choose_roles`.
- `stage_model`, `stage`: `ties_allowed`.
- `match`: no `challenge_id`. `tournament_id`, `pool_id`, `round` and `stage_id` are NOT NULL.
- `participant`: no `challenge_id`. `tournament_id` and `entrant_id` are NOT NULL.

## The challenge flow

There is no `ChallengeService`. Its three calls are `TournamentService`'s, since they are its create and enter with
a few rules on top:

- **`createChallenge`** (`POST /games/{gameId}/challenges`) does all of this in one transaction:
  - makes a tournament of the challenge model and opens it at once;
  - sets `max_entrants` to the pool size, and turns on automatic rounds;
  - enters the challenger, unless a game's admin is hosting a match they won't play in;
  - sends the invitations.

  A hosted challenge must start by itself, and only a game's admin may host one or offer a rated one, as before.
- **`challengesForGame`** (`GET /games/{gameId}/challenges`) lists a game's open challenges.
- **`challengesFor`** (`GET /me/challenges`) lists the caller's open challenges. The home screen splits them into
  invitations, ready to start, and waiting to start.

Entering, withdrawing, starting, cancelling, inviting and choosing roles use the tournament routes.

### What was dropped

- **The match description.** A challenge's match has no description.
- **Character invitations.** Inviting a particular character went with the old tables. An invitation is now to a
  player, who enters with any character of theirs. `character_invitation` is kept (renamed) but unused.
- **A start from a single entry.** Starting needs at least two entries, and enough to fill the required roles,
  as any tournament does.

## Order of work

All six steps are done:

1. Schema, model and repositories for the new tournament columns, then the challenge model, scheduling and
   `ties_allowed`.
2. Tournament services: capacity, automatic start, settings and live on launched matches, notifications.
3. The challenge calls, and removal of the old challenge, acceptance and invitation code, including
   `GameEngineService.start`.
4. Routes, JSON, terraform and `RouterSpec`.
5. UI.
6. Tests.

In the UI, the tournament form also offers the new options: choosing roles, live, the most entries, starting when
full, a planned start, and the game's parameters.
