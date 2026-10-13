-- Matchmaker's schema (Postgres / Aurora Serverless), whole.
--
-- The 53 migrations it was built up from were folded into this one file when every database was rebuilt
-- from scratch; git history has them, and the reasons recorded there that still hold are kept here, beside
-- what they explain.
--
-- Conventions throughout:
--
--   * Rows that belong to a game are keyed by (game_id, ...), and a reference to one carries game_id as
--     part of a composite foreign key: an object can only reference a character, a role, a challenge or a
--     match of its own game. A row's own id is generated globally unique all the same, so it identifies the
--     row on its own everywhere but in a foreign key.
--   * game_type ('C' a game of characters, 'P' a game of players) is carried by the rows whose shape depends
--     on it, with a foreign key to (game_type, game_id), so that a character row can only belong to a
--     character game.
--   * A small, open-ended set of values is text under a check constraint rather than a Postgres enum:
--     adding a value is one ALTER, and the value reads as itself in a query.
--   * Every table with create_date and update_date has them maintained by a trigger (set_update_date, at
--     the end) rather than by the application: update_date is stamped on every update, and create_date is
--     kept as it was.

CREATE FUNCTION set_update_date()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_date := now();
    NEW.create_date := OLD.create_date;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- =====================================================================
-- Players
-- =====================================================================

-- Notification preferences: twelve kinds (`NotificationType`), each a nullable boolean on `player` (for
-- every game) and on `player_game` (for one game), where NULL is not "no" but "I have not said". A seat
-- of a match is stamped with the resolved answer when it is created -- player_game, then player, then
-- "send it" (`NotificationLevels.unsaid`) -- and keeps it: see participant. The Scala side binds the
-- columns positionally (`SkunkCodecs.notificationPreferences`) in `NotificationType` order, so the order
-- they are named in a statement matters, though their order in the table does not.
--
--   challenge_accepted        somebody accepted, or backed out of, a challenge you offered
--   challenge_ready           every role in a challenge you offered is filled: you can start it
--   acceptance_changed        somebody else accepted, or backed out of, a challenge you accepted
--   accepted_challenge_ready  every role in a challenge you accepted is filled
--   invitation_received       somebody has invited you to a challenge of theirs
--   invitation_accepted       somebody you invited has joined your challenge
--   invitation_rejected       somebody you invited has turned it down
--   match_started             a match you are in has begun
--   turn_taken                somebody moved in a match you are in
--   your_turn                 it is your turn
--   match_ended               a match you are in is over, or has been called off
--   tournament_round_done     the last match of a round of your tournament is recorded
CREATE TABLE player (
    player_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Unique as stored, so case sensitive: the search below folds case, and offers both.
    nickname     TEXT NOT NULL UNIQUE,
    -- An overall admin: may do anywhere what a game's admin (game_admin) may do in their game.
    is_admin     BOOLEAN NOT NULL,
    -- The Cognito subject the caller is identified by.
    external_id  TEXT NOT NULL UNIQUE,
    -- A copy of the Cognito address, so that a notification's recipients are a query rather than a call
    -- to Cognito per recipient. Kept in step through `PUT /me/email`, which the account form calls on a
    -- change and every load calls when the token's claim differs. NULL where there is none to copy
    -- (local development). Not unique: two players sharing a mailbox each keep their own identity, and
    -- Cognito already enforces what has to hold.
    email        TEXT,
    notify_challenge_accepted       BOOLEAN,
    notify_challenge_ready          BOOLEAN,
    notify_acceptance_changed       BOOLEAN,
    notify_accepted_challenge_ready BOOLEAN,
    notify_invitation_received      BOOLEAN,
    notify_invitation_accepted      BOOLEAN,
    notify_invitation_rejected      BOOLEAN,
    notify_match_started            BOOLEAN,
    notify_turn_taken               BOOLEAN,
    notify_your_turn                BOOLEAN,
    notify_match_ended              BOOLEAN,
    notify_tournament_round_done    BOOLEAN,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The player search matches a prefix of a normalized nickname: case folded, each run of whitespace one
-- space, and trimmed. The expression is the whole contract with `PlayerRepo.searchByNicknamePrefix`,
-- which has to spell it identically for the planner to use this. The whitespace class is written out
-- rather than `\s`, whose meaning in Postgres depends on the locale, so that it is the same function as
-- the Scala side's. `text_pattern_ops` compares byte by byte, which makes the names with a prefix one
-- range -- a btree in a non-C collation cannot serve `LIKE 'abc%'`. The prefix bounds come from the
-- pattern's value, so a forced generic plan falls back to a filtered scan: correct, only slower.
CREATE INDEX player_nickname_search ON player (
    btrim(regexp_replace(lower(nickname), '[ \t\n\r\f\v]+', ' ', 'g')) text_pattern_ops
);

-- Addresses we have stopped writing to, and why: SES's own suppression list stops delivery, but not our
-- queueing, our spending reputation on calls it discards, or our telling a player why they hear nothing.
--
-- Keyed by the address, not the player: SES reports an address, the address may belong to no player row,
-- and a player who changes their address must not inherit the old one's suppression. Lowercased by the
-- only writer, so that a lookup is an equality on the key.
CREATE TABLE email_suppression (
    email         TEXT PRIMARY KEY,
    -- `SuppressionReason`. The most serious event wins rather than the latest: a complaint followed by a
    -- delay is still a complaint.
    reason        TEXT NOT NULL CHECK (reason IN ('bounce', 'complaint', 'delay')),
    -- Whether one event was enough: a complaint or a permanent bounce. Transient failures accumulate in
    -- `occurrences` and suppress only at a threshold.
    permanent     BOOLEAN NOT NULL,
    -- SES's own words, for the log and support; never shown to the player.
    diagnostic    TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    -- Events inside the current window: reset to 1, not incremented, when the previous one is older than
    -- the window or the row was released.
    occurrences   INTEGER NOT NULL CHECK (occurrences > 0),
    -- When the player said to try again. NULL is suppressed. Released rather than deleted, so that a
    -- second failure shows as a second one.
    released_at   TIMESTAMPTZ
);

-- "Which of these recipients are suppressed": partial, since released rows are no answer to it and are
-- the ones that accumulate.
CREATE INDEX email_suppression_active ON email_suppression (email) WHERE released_at IS NULL;

-- One row per SES event already counted, so that a redelivery -- the ordinary behaviour of an
-- at-least-once queue -- cannot count it again: the insert and the count are one transaction, and a
-- conflict on this key makes the count a no-op. Not pruned: it is the only per-event record of what SES
-- said. If it ever matters, prune by age alone.
CREATE TABLE email_suppression_event (
    -- Derived only from fields SES generates (`SesEvent.identity`), never from the queue's message id,
    -- which a redelivery changes.
    event_id    TEXT PRIMARY KEY,
    -- Lowercased. No foreign key: that an event was seen stays true after its suppression is released.
    email       TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX email_suppression_event_email ON email_suppression_event (email, recorded_at DESC);


-- =====================================================================
-- Games
-- =====================================================================

CREATE TABLE game (
    game_id           INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_type         CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    -- The stable handle: what the engine is told, and what registration and deploy scripts find a game
    -- by. `display_name` is what players see, and what an admin may change.
    name              TEXT NOT NULL,
    display_name      TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    description       TEXT NOT NULL,
    url               TEXT NOT NULL,
    -- Where a player makes a character, in a character game's engine: characters are made there, not in
    -- matchmaker. NULL for a plain game, or an engine with no such page.
    character_url     TEXT,
    active            BOOLEAN NOT NULL,
    external_id       TEXT NOT NULL,
    -- What happens when a turn runs out: a rule of the game, set by its admin, where the time limit is
    -- the challenger's.
    timeout_action    TEXT NOT NULL DEFAULT 'FORFEIT' CHECK (timeout_action IN ('FORFEIT')),
    -- The admin saying no role has an advantage in winning. Otherwise a rated match moves each player's
    -- rating in their role as well as overall (elo_role_rating).
    unimportant_roles BOOLEAN NOT NULL DEFAULT false,
    -- Whether players move in turn ('seq') or all at once ('sim'), which decides how a tournament divides
    -- a round's time; and how many turns a simultaneous game lasts, where it can say.
    move_type         TEXT NOT NULL DEFAULT 'seq' CONSTRAINT game_move_type_check CHECK (move_type IN ('seq', 'sim')),
    max_turns         INT CONSTRAINT game_max_turns_check CHECK (max_turns > 0),
    create_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON game (external_id);
-- For the (game_type, game_id) foreign keys.
CREATE UNIQUE INDEX ON game (game_type, game_id);

-- A seat a match can be played with. Its key is (game_id, game_role_id), so that every reference to a
-- role names its game and cannot point at another game's.
CREATE TABLE game_role (
    game_role_id     INT GENERATED ALWAYS AS IDENTITY,
    game_id          INT NOT NULL REFERENCES game,
    -- What the engine is told the seat is; `display_name` is what players are shown.
    name             TEXT NOT NULL,
    display_name     TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    -- A required role must be filled for a match to start; an optional one need not be. A recommended
    -- one need not be either, but counts toward a tournament pool's size: a game's R is its required and
    -- recommended roles together.
    type             TEXT NOT NULL DEFAULT 'required'
                     CONSTRAINT game_role_type_check CHECK (type IN ('required', 'optional', 'recommended')),
    -- How a tournament hands roles out by seed: the highest level to the highest seed. NULL is none.
    preference_level INT,
    create_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_role_id)
);

CREATE TABLE game_parameter (
    game_id           INT NOT NULL REFERENCES game,
    game_parameter_id INT GENERATED ALWAYS AS IDENTITY,
    -- The key of a challenge's settings and of what the engine is handed; `display_name` only captions it.
    name              TEXT NOT NULL,
    display_name      TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    default_value     TEXT,
    create_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_parameter_id)
);

CREATE TABLE game_parameter_value (
    game_id           INT NOT NULL,
    game_parameter_id INT NOT NULL,
    value             TEXT NOT NULL,
    create_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_parameter_id, value),
    FOREIGN KEY (game_id, game_parameter_id) REFERENCES game_parameter
);

-- A parameter's default is one of its values. Deferrable: the two reference each other.
ALTER TABLE game_parameter ADD FOREIGN KEY (game_id, game_parameter_id, default_value)
    REFERENCES game_parameter_value DEFERRABLE;

-- The key matchmaker and a game's engine authenticate each other with, set on the game's form. Apart
-- from `game` so that nothing reading a game carries its key along by accident; no API answer includes
-- it. Stored as it is, because matchmaker presents it on every call to the engine. A game with none can
-- be played against an engine that asks for none (a local one). Unique: a key says which game's engine
-- is calling, so a shared one would vouch for either.
CREATE TABLE game_api_key (
    game_id     INT PRIMARY KEY REFERENCES game,
    api_key     TEXT NOT NULL UNIQUE CHECK (btrim(api_key) <> ''),
    create_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The players who administer a game. Made by an overall admin or another admin of the game, and
-- `granted_by` is who: an overall admin may remove any, a game's admin only those they made
-- (GameAdminService). An overall admin has no rows here: what they may do they may do anywhere.
CREATE TABLE game_admin (
    player_id   BIGINT NOT NULL REFERENCES player,
    game_id     INT NOT NULL REFERENCES game,
    granted_by  BIGINT NOT NULL REFERENCES player,
    create_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, game_id)
);

CREATE INDEX ON game_admin (game_id);

-- A player's notification preferences for one game (see player). A row exists only for a game a player
-- has said something about; none reads the same as a row of NULLs.
CREATE TABLE player_game (
    player_id BIGINT NOT NULL REFERENCES player,
    game_id   INT NOT NULL REFERENCES game,
    notify_challenge_accepted       BOOLEAN,
    notify_challenge_ready          BOOLEAN,
    notify_acceptance_changed       BOOLEAN,
    notify_accepted_challenge_ready BOOLEAN,
    notify_invitation_received      BOOLEAN,
    notify_invitation_accepted      BOOLEAN,
    notify_invitation_rejected      BOOLEAN,
    notify_match_started            BOOLEAN,
    notify_turn_taken               BOOLEAN,
    notify_your_turn                BOOLEAN,
    notify_match_ended              BOOLEAN,
    notify_tournament_round_done    BOOLEAN,
    create_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, game_id)
);

CREATE INDEX ON player_game (game_id);

-- A character of a character game, made in the game's engine and reported to matchmaker. Its owner may
-- change (CharacterService.update); everything addressed to a character reads the owner through
-- `player_id` at each use rather than copying it.
CREATE TABLE "character" (
    character_id BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    game_id      INT NOT NULL REFERENCES game,
    name         TEXT NOT NULL,
    description  TEXT NOT NULL,
    state        JSONB NOT NULL,
    player_id    BIGINT REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, character_id)
);

CREATE INDEX ON "character" (player_id);


-- =====================================================================
-- Challenges
-- =====================================================================

-- An offer of a match. Its terms -- time limit, settings, publicity, liveness, friendliness -- are the
-- challenger's, and are copied to the match when it starts, so that editing a challenge cannot change a
-- match already played under it. Starting a challenge does not delete it: a match keeps the challenge it
-- came from.
CREATE TABLE challenge (
    game_id          INT NOT NULL REFERENCES game,
    challenge_id     BIGINT GENERATED ALWAYS AS IDENTITY,
    game_type        CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    challenger       BIGINT NOT NULL REFERENCES player,
    message          TEXT DEFAULT '',
    start            TIMESTAMPTZ,
    -- How long a player has: per turn, or across the whole match (TOTAL, a chess clock). `time_limit_unit`
    -- is the unit it was offered in, kept so that "48 hours" is not read back as "2 days".
    time_limit       INTERVAL,
    time_limit_kind  TEXT NOT NULL DEFAULT 'PER_TURN' CHECK (time_limit_kind IN ('PER_TURN', 'TOTAL')),
    time_limit_unit  TEXT NOT NULL DEFAULT 'MINUTES'
                     CONSTRAINT challenge_time_limit_unit_check CHECK (time_limit_unit IN ('SECONDS', 'MINUTES', 'HOURS', 'DAYS')),
    settings         JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Whether the match it becomes is public. There is no role here: the challenger's role is on their own
    -- acceptance, which creating a challenge makes, like everybody else's.
    public           BOOLEAN NOT NULL DEFAULT false,
    -- The match a start is making: written under the challenge's lock by the first start, so that a second
    -- one, arriving while the first waits on the engine, is refused; cleared if the engine call fails. Not
    -- a foreign key, since that undo deletes the match.
    started_match_id BIGINT,
    -- Whether it starts itself as soon as every required role is taken.
    auto_start       BOOLEAN NOT NULL DEFAULT false,
    -- Whether a stranger may accept it. Who has been asked is `invitation`, a separate fact: an open
    -- challenge may hold a seat for an invitee, and a closed one may be reopened without touching who
    -- was asked.
    is_open          BOOLEAN NOT NULL DEFAULT true,
    -- A live match's turns and clock are run by the engine itself (see match).
    live             BOOLEAN NOT NULL DEFAULT false,
    -- Only a game's admin may offer one that is not (ChallengeService.create).
    friendly         BOOLEAN NOT NULL DEFAULT true,
    create_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id),
    FOREIGN KEY (game_id, game_type) REFERENCES game (game_id, game_type),
    -- A live match is played against a clock of at least a second.
    CONSTRAINT challenge_live_time_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit >= INTERVAL '1 second'))
);

CREATE INDEX ON challenge (challenger);
-- Every listing of challenges starts from a game and whether each is open. Not partial, so that it serves
-- a lookup by game alone too.
CREATE INDEX challenge_game_open ON challenge (game_id, is_open);

-- The character a character game's challenge is offered on behalf of.
CREATE TABLE character_challenge (
    game_id      INT NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    game_type    CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id BIGINT NOT NULL,
    PRIMARY KEY (game_id, challenge_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, game_type) REFERENCES game (game_id, game_type),
    FOREIGN KEY (game_id, character_id) REFERENCES "character" (game_id, character_id)
);

CREATE INDEX ON character_challenge (game_id, character_id);

-- A seat taken in a challenge. Keyed by the role, since a challenge is made of seats: one acceptance per
-- role, which is what lets a start wait for every required role. One seat per player is a policy, checked
-- in ChallengeService.accept under the challenge's lock, rather than a key, so that it can be relaxed.
CREATE TABLE acceptance (
    game_id      INT NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    game_role_id INT NOT NULL,
    game_type    CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    player_id    BIGINT NOT NULL REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, game_role_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, game_type) REFERENCES game (game_id, game_type),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

-- "What has this player accepted?", and "has this player already accepted this challenge?" on every accept.
CREATE INDEX ON acceptance (player_id, game_id, challenge_id);

-- The character an acceptance of a character game seats.
CREATE TABLE character_acceptance (
    game_id      INT NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    game_role_id INT NOT NULL,
    game_type    CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id BIGINT NOT NULL,
    PRIMARY KEY (game_id, challenge_id, game_role_id),
    FOREIGN KEY (game_id, challenge_id, game_role_id) REFERENCES acceptance,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    FOREIGN KEY (game_id, character_id) REFERENCES "character"
);

CREATE INDEX ON character_acceptance (game_id, character_id);

-- Who has been asked to accept a challenge of a plain game. Permission and nothing more: it outlives the
-- acceptance it leads to, and goes only when rejected, revoked, or with its challenge. `game_role_id`
-- names a seat held for the invitee -- nobody else may take it (ChallengeService.accept) -- or is NULL for
-- any free seat.
CREATE TABLE invitation (
    game_id      INT NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    player_id    BIGINT NOT NULL REFERENCES player,
    game_role_id INT,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, player_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

-- "Every invitation addressed to me", on every sign-in.
CREATE INDEX invitation_player ON invitation (player_id);

-- An invitation in a character game, which names the character: whoever owns it when they answer may
-- answer, so it follows a transfer. Otherwise as `invitation`; the two never hold rows for the same game
-- type, which ChallengeService enforces.
CREATE TABLE character_invitation (
    game_id      INT NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    game_role_id INT,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, character_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, character_id) REFERENCES "character" (game_id, character_id),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

CREATE INDEX character_invitation_character ON character_invitation (character_id);


-- =====================================================================
-- Tournament models
-- =====================================================================
--
-- (tournament-design.md, design/new-tournament-plan.md.) The models are every game's: the standard formats
-- defined in the code (StandardModels), written here by a site admin's sync, and shown in a game where
-- tournament_model_game says so. A model's stages are chained from first_stage_model_id along each
-- stage's current_stage_model_id, each atomic (its pools play matches) or nesting (its pools each play an
-- inner model). Creating a tournament copies the model's stages, and those of every inner model, into
-- stage rows of the one tournament.
--
-- Supertype and subtype: every stage model, stage and pool of a kind has its subtype row -- an 'A' stage an
-- atomic one, an 'N' stage a nesting one, a 'G' pool a pool_group, an 'A' pool an atomic_pool. The kind
-- columns stop a row having the wrong subtype; a foreign key from the supertype to the subtype, through a
-- generated column holding the kind only where that subtype applies (NULL, and so unchecked, otherwise),
-- makes it have the right one. Those keys are deferred to commit, as are the ones between a model or
-- tournament and its first stage, since each pair references the other and one has to be inserted first.

-- Named by a descriptive id, 'world-cup' say; an inner design of a nesting stage is a model of its own,
-- named after the stage, 'world-cup/knockout'.
CREATE TABLE tournament_model (
    tournament_model_id  TEXT PRIMARY KEY,
    -- The version of its definition in the code last written here: a sync rewrites a model whose code
    -- version is greater.
    version              INT NOT NULL DEFAULT 1 CHECK (version > 0),
    name                 TEXT NOT NULL,
    description          TEXT NOT NULL DEFAULT '',
    classification       TEXT,
    -- The default pool size the create form suggests. NULL suggests the game's R.
    pool_size            INT CHECK (pool_size > 0),
    first_stage_model_id TEXT NOT NULL,
    late_entries         BOOLEAN NOT NULL DEFAULT false,
    -- False once the code no longer defines it and a tournament made from it has entrants: offered no
    -- longer, and kept for them. Its stages and inner models are deactivated with it.
    active               BOOLEAN NOT NULL DEFAULT true,
    create_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stage_model (
    tournament_model_id    TEXT NOT NULL REFERENCES tournament_model,
    -- Descriptive within its model: 'groups', 'knockout', or 'stage-2' for a stage with no name.
    stage_model_id         TEXT NOT NULL,
    -- 'A' an atomic_stage_model, 'N' a nesting_stage_model.
    kind                   CHAR(1) NOT NULL CHECK (kind IN ('A', 'N')),
    -- How many entrants a pool of this stage holds, its groups' size for a nesting stage. NULL takes the
    -- tournament's default_pool_size.
    pool_size              INT CHECK (pool_size > 0),
    name                   TEXT NOT NULL,
    description            TEXT NOT NULL DEFAULT '',
    concurrent_rounds      BOOLEAN NOT NULL DEFAULT false,
    -- If both are NULL the stage repeats until the tournament's owner stops it.
    repetitions            INT,
    remaining_participants INT,
    bracketed              BOOLEAN NOT NULL DEFAULT false,
    demote                 INT DEFAULT 2 CHECK (demote IS NULL OR demote > 1),
    max_stratum            INT DEFAULT 0,
    min_stratum            INT DEFAULT 0,
    points_for_win         INT NOT NULL DEFAULT 2,
    points_for_draw        INT NOT NULL DEFAULT 1,
    points_bonus           BOOLEAN NOT NULL DEFAULT true,
    promote                INT DEFAULT 1 CHECK (promote IS NULL OR demote IS NULL OR promote < demote),
    -- A rubber match breaks ties in a pool; otherwise the total score differential does.
    rubber_match           BOOLEAN NOT NULL DEFAULT false,
    strict_strata          BOOLEAN NOT NULL DEFAULT true,
    tie_breaker_stage      BOOLEAN NOT NULL DEFAULT false,
    seed_policy            TEXT NOT NULL CHECK (seed_policy IN ('CONST', 'ELO', 'PREV', 'WINNER')),
    current_stage_model_id TEXT,
    -- False with its model's active.
    active                 BOOLEAN NOT NULL DEFAULT true,
    atomic_kind            CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    nesting_kind           CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'N' THEN kind END) STORED,
    create_date            TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_model_id, stage_model_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (tournament_model_id, stage_model_id, kind),
    FOREIGN KEY (tournament_model_id, current_stage_model_id) REFERENCES stage_model,
    CHECK (repetitions IS NULL OR remaining_participants IS NULL)
);

CREATE INDEX ON stage_model (tournament_model_id, current_stage_model_id) WHERE current_stage_model_id IS NOT NULL;

ALTER TABLE tournament_model ADD FOREIGN KEY (tournament_model_id, first_stage_model_id) REFERENCES stage_model
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ON tournament_model (tournament_model_id, first_stage_model_id);

CREATE TABLE atomic_stage_model (
    tournament_model_id TEXT NOT NULL,
    stage_model_id      TEXT NOT NULL,
    kind                CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    rotate_roles        BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (tournament_model_id, stage_model_id),
    UNIQUE (tournament_model_id, stage_model_id, kind),
    FOREIGN KEY (tournament_model_id, stage_model_id, kind)
        REFERENCES stage_model (tournament_model_id, stage_model_id, kind)
);

CREATE TABLE nesting_stage_model (
    tournament_model_id       TEXT NOT NULL,
    stage_model_id            TEXT NOT NULL,
    kind                      CHAR(1) NOT NULL DEFAULT 'N' CHECK (kind = 'N'),
    inner_tournament_model_id TEXT NOT NULL REFERENCES tournament_model,
    PRIMARY KEY (tournament_model_id, stage_model_id),
    UNIQUE (tournament_model_id, stage_model_id, kind),
    FOREIGN KEY (tournament_model_id, stage_model_id, kind)
        REFERENCES stage_model (tournament_model_id, stage_model_id, kind)
);

CREATE INDEX ON nesting_stage_model (inner_tournament_model_id);

ALTER TABLE stage_model
    ADD FOREIGN KEY (tournament_model_id, stage_model_id, atomic_kind)
        REFERENCES atomic_stage_model (tournament_model_id, stage_model_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (tournament_model_id, stage_model_id, nesting_kind)
        REFERENCES nesting_stage_model (tournament_model_id, stage_model_id, kind) DEFERRABLE INITIALLY DEFERRED;

-- Which models a game offers where a tournament is created, as its admins choose. A tournament made from a
-- model the game stops offering keeps it.
CREATE TABLE tournament_model_game (
    game_id             INT NOT NULL REFERENCES game,
    tournament_model_id TEXT NOT NULL REFERENCES tournament_model,
    create_date         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_model_id)
);

-- For the foreign key: without it, deleting a model would scan every game's choices.
CREATE INDEX ON tournament_model_game (tournament_model_id);


-- =====================================================================
-- Tournaments
-- =====================================================================
--
-- Every stage, pool, seed, match and seat of a tournament -- however deeply nested -- carries the root
-- tournament's id. Its running state is rows: rounds (each played in one atomic stage), pools, their
-- members' results, seeds and strata scoped by pool (a row only when one changes), and its matches and
-- seats, which are `match` and `participant` rows from the moment a pool is formed -- long before the
-- engine has a game.

CREATE TABLE tournament (
    game_id             INT NOT NULL REFERENCES game,
    tournament_id       BIGINT GENERATED ALWAYS AS IDENTITY,
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    -- Who runs it: starts and checks its rounds, stops it, decides a cancelled match.
    owner               BIGINT NOT NULL REFERENCES player,
    status              TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'open', 'running', 'finished', 'cancelled')),
    -- The model it was made from, one its game offered then. Its stages are copied, so a sync that
    -- rewrites the model later does not change it.
    tournament_model_id TEXT NOT NULL REFERENCES tournament_model,
    concurrent_rounds   BOOLEAN NOT NULL DEFAULT false,
    first_stage_id      BIGINT NOT NULL,
    late_entries        BOOLEAN NOT NULL DEFAULT false,
    automatic_rounds    BOOLEAN NOT NULL DEFAULT false,
    friendly            BOOLEAN NOT NULL DEFAULT true,
    invitational        BOOLEAN NOT NULL DEFAULT false,
    min_rating          INT,
    max_rating          INT,
    public              BOOLEAN NOT NULL DEFAULT true,
    entries_per_player  INT NOT NULL DEFAULT 1 CHECK (entries_per_player >= 1),
    max_turns           INT CHECK (max_turns > 0),
    -- The size of a pool of any stage that does not set its own.
    default_pool_size   INT NOT NULL CHECK (default_pool_size > 0),
    round_duration      INTERVAL,
    current_round       INT,
    stop_requested      BOOLEAN NOT NULL DEFAULT false,
    -- When the owner last asked for the round's overdue matches to be checked, so that a burst of presses
    -- is one check.
    checked_at          TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    create_date         TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id),
    CHECK (min_rating IS NULL OR max_rating IS NULL OR min_rating <= max_rating)
);

CREATE INDEX tournament_owner ON tournament (owner);
-- For the foreign key: without it, deleting a model would scan every tournament.
CREATE INDEX tournament_model_used ON tournament (tournament_model_id);

-- A stage model copied into a tournament.
CREATE TABLE stage (
    game_id                INT NOT NULL REFERENCES game,
    tournament_id          BIGINT NOT NULL,
    stage_id               BIGINT GENERATED ALWAYS AS IDENTITY,
    -- 'A' an atomic_stage, 'N' a nesting_stage.
    kind                   CHAR(1) NOT NULL CHECK (kind IN ('A', 'N')),
    -- NULL takes the tournament's default_pool_size.
    pool_size              INT CHECK (pool_size > 0),
    name                   TEXT NOT NULL,
    description            TEXT NOT NULL DEFAULT '',
    concurrent_rounds      BOOLEAN NOT NULL DEFAULT false,
    bracketed              BOOLEAN NOT NULL DEFAULT false,
    -- If both are NULL the stage repeats until the tournament's owner stops it.
    repetitions            INT,
    remaining_participants INT,
    demote                 INT DEFAULT 2 CHECK (demote IS NULL OR demote > 1),
    max_stratum            INT DEFAULT 0,
    min_stratum            INT DEFAULT 0,
    points_for_win         INT NOT NULL DEFAULT 2,
    points_for_draw        INT NOT NULL DEFAULT 1,
    points_bonus           BOOLEAN NOT NULL DEFAULT true,
    promote                INT DEFAULT 1 CHECK (promote IS NULL OR demote IS NULL OR promote < demote),
    -- A rubber match breaks ties in a pool; otherwise the total score differential does.
    rubber_match           BOOLEAN NOT NULL DEFAULT false,
    strict_strata          BOOLEAN NOT NULL DEFAULT true,
    tie_breaker_stage      BOOLEAN NOT NULL DEFAULT false,
    seed_policy            TEXT NOT NULL CHECK (seed_policy IN ('CONST', 'ELO', 'PREV', 'WINNER')),
    current_stage_id       BIGINT,
    atomic_kind            CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    nesting_kind           CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'N' THEN kind END) STORED,
    create_date            TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, stage_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_id, tournament_id, current_stage_id) REFERENCES stage,
    CHECK (repetitions IS NULL OR remaining_participants IS NULL)
);

CREATE INDEX ON stage (game_id, tournament_id, current_stage_id) WHERE current_stage_id IS NOT NULL;

ALTER TABLE tournament ADD FOREIGN KEY (game_id, tournament_id, first_stage_id) REFERENCES stage
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ON tournament (game_id, tournament_id, first_stage_id);

CREATE TABLE atomic_stage (
    game_id       INT NOT NULL REFERENCES game,
    tournament_id BIGINT NOT NULL,
    stage_id      BIGINT NOT NULL,
    kind          CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    rotate_roles  BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, stage_id),
    UNIQUE (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id, kind) REFERENCES stage (game_id, tournament_id, stage_id, kind)
);

-- Each pool of a nesting stage plays an inner design: a chain of stages of the same tournament, from
-- inner_first_stage_id along each stage's current_stage_id.
CREATE TABLE nesting_stage (
    game_id              INT NOT NULL REFERENCES game,
    tournament_id        BIGINT NOT NULL,
    stage_id             BIGINT NOT NULL,
    kind                 CHAR(1) NOT NULL DEFAULT 'N' CHECK (kind = 'N'),
    inner_first_stage_id BIGINT NOT NULL,
    PRIMARY KEY (game_id, tournament_id, stage_id),
    UNIQUE (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id, kind) REFERENCES stage (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id, inner_first_stage_id) REFERENCES stage
);

CREATE INDEX ON nesting_stage (game_id, tournament_id, inner_first_stage_id);

ALTER TABLE stage
    ADD FOREIGN KEY (game_id, tournament_id, stage_id, atomic_kind)
        REFERENCES atomic_stage (game_id, tournament_id, stage_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (game_id, tournament_id, stage_id, nesting_kind)
        REFERENCES nesting_stage (game_id, tournament_id, stage_id, kind) DEFERRABLE INITIALLY DEFERRED;

-- A round is played within one atomic stage. A planned round's row exists before it starts, with
-- start_date NULL.
CREATE TABLE round (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    round         INT NOT NULL,
    stage_id      BIGINT NOT NULL,
    start_date    TIMESTAMPTZ,
    deadline      TIMESTAMPTZ,
    complete      BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (game_id, tournament_id, round),
    -- For match's foreign key, which names the stage as well, so that a match's round and pool agree on it.
    UNIQUE (game_id, tournament_id, round, stage_id),
    FOREIGN KEY (game_id, tournament_id, stage_id) REFERENCES atomic_stage
);

CREATE INDEX ON round (game_id, tournament_id, stage_id);

ALTER TABLE tournament ADD FOREIGN KEY (game_id, tournament_id, current_round) REFERENCES round;
CREATE INDEX ON tournament (game_id, tournament_id, current_round) WHERE current_round IS NOT NULL;

-- Who may enter an invitational tournament.
CREATE TABLE tournament_invitation (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    player_id     BIGINT NOT NULL REFERENCES player,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, player_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament
);

-- "Tournaments I am invited to", for the home page.
CREATE INDEX tournament_invitation_player ON tournament_invitation (player_id);

CREATE TABLE tournament_character_invitation (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    player_id     BIGINT NOT NULL,
    character_id  BIGINT NOT NULL,
    game_type     CHAR(1) NOT NULL DEFAULT 'C' CHECK (game_type = 'C'),
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, player_id, character_id),
    FOREIGN KEY (game_id, game_type) REFERENCES game (game_id, game_type),
    FOREIGN KEY (game_id, tournament_id, player_id) REFERENCES tournament_invitation,
    FOREIGN KEY (game_id, character_id) REFERENCES "character"
);

CREATE INDEX ON tournament_character_invitation (game_id, character_id);

CREATE TABLE entrant (
    game_id         INT NOT NULL,
    entrant_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    tournament_id   BIGINT NOT NULL,
    game_type       CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    player_id       BIGINT NOT NULL REFERENCES player,
    -- What the rating limits were checked against and, until the tournament starts, what it is seeded by.
    rating_at_entry INT NOT NULL,
    -- Where they finished, once it is over.
    final_rank      INT,
    entered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set by withdrawing, cleared by coming back.
    withdrawn_at    TIMESTAMPTZ,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id)
);

CREATE INDEX entrant_tournament ON entrant (game_id, tournament_id);
CREATE INDEX entrant_player ON entrant (player_id);

CREATE TABLE character_entrant (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    entrant_id    BIGINT NOT NULL,
    game_type     CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id  BIGINT NOT NULL,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, entrant_id) REFERENCES entrant,
    FOREIGN KEY (game_id, character_id) REFERENCES "character" (game_id, character_id),
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    -- A character enters a tournament once.
    UNIQUE (game_id, tournament_id, character_id),
    -- For character_participant's foreign key, which names the character as well as the entrant.
    UNIQUE (game_id, tournament_id, entrant_id, character_id)
);

CREATE INDEX character_entrant_character ON character_entrant (game_id, character_id);

-- A set of entrants playing together. A pool is one of:
--   - a pool_group, a pool of a nesting stage, which plays that stage's inner design: the pools of the
--     inner design have it as their parent;
--   - an atomic_pool, a pool of an atomic stage, which plays matches;
--   - the tournament's one root pool, which is neither: no parent, no stage and no round. It is the parent
--     of the top-level pools and the scope of the top-level seeds. Its members are the tournament's
--     entrants, so it has no pool_member rows, and their final ranks are entrant.final_rank.
CREATE TABLE pool (
    game_id        INT NOT NULL REFERENCES game,
    tournament_id  BIGINT NOT NULL,
    pool_id        BIGINT GENERATED ALWAYS AS IDENTITY,
    -- 'R' the root pool, 'G' a pool_group, 'A' an atomic_pool.
    kind           CHAR(1) NOT NULL CHECK (kind IN ('R', 'G', 'A')),
    parent_pool_id BIGINT,
    -- The round it began in: its members entered it with their seed rows as of this round, and the pools
    -- of one playing of a stage are its pools begun in the same round. NULL for the root pool.
    round          INT,
    stratum        INT NOT NULL DEFAULT 0,
    completed_at   TIMESTAMPTZ,
    -- The root pool has no subtype, so both are NULL for it.
    group_kind     CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'G' THEN kind END) STORED,
    atomic_kind    CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, parent_pool_id) REFERENCES pool,
    FOREIGN KEY (game_id, tournament_id, round) REFERENCES round,
    -- Only the root pool has no parent, and it alone has no round.
    CHECK ((kind = 'R') = (parent_pool_id IS NULL)),
    CHECK ((kind = 'R') = (round IS NULL))
);

-- One root pool per tournament.
CREATE UNIQUE INDEX pool_root ON pool (game_id, tournament_id) WHERE kind = 'R';
CREATE INDEX ON pool (game_id, tournament_id, parent_pool_id) WHERE parent_pool_id IS NOT NULL;
CREATE INDEX ON pool (game_id, tournament_id, round) WHERE round IS NOT NULL;

CREATE TABLE pool_group (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       BIGINT NOT NULL,
    kind          CHAR(1) NOT NULL DEFAULT 'G' CHECK (kind = 'G'),
    stage_id      BIGINT NOT NULL,
    PRIMARY KEY (game_id, tournament_id, pool_id),
    UNIQUE (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, pool_id, kind) REFERENCES pool (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id) REFERENCES nesting_stage
);

CREATE INDEX ON pool_group (game_id, tournament_id, stage_id);

CREATE TABLE atomic_pool (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       BIGINT NOT NULL,
    kind          CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    stage_id      BIGINT NOT NULL,
    PRIMARY KEY (game_id, tournament_id, pool_id),
    UNIQUE (game_id, tournament_id, pool_id, kind),
    -- For match's foreign key, which names the stage as well, so that a match's round and pool agree on it.
    UNIQUE (game_id, tournament_id, pool_id, stage_id),
    FOREIGN KEY (game_id, tournament_id, pool_id, kind) REFERENCES pool (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id) REFERENCES atomic_stage
);

CREATE INDEX ON atomic_pool (game_id, tournament_id, stage_id);

ALTER TABLE pool
    ADD FOREIGN KEY (game_id, tournament_id, pool_id, group_kind)
        REFERENCES pool_group (game_id, tournament_id, pool_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (game_id, tournament_id, pool_id, atomic_kind)
        REFERENCES atomic_pool (game_id, tournament_id, pool_id, kind) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE pool_member (
    game_id       INT NOT NULL REFERENCES game,
    tournament_id BIGINT NOT NULL,
    pool_id       BIGINT NOT NULL,
    entrant_id    BIGINT NOT NULL,
    final_rank    INT,
    -- Fractional: a points bonus is half as much again, and engines report scores that need not be whole.
    total_score   DOUBLE PRECISION,
    score_against DOUBLE PRECISION,
    pool_points   DOUBLE PRECISION,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, pool_id) REFERENCES pool,
    FOREIGN KEY (game_id, tournament_id, entrant_id) REFERENCES entrant
);

CREATE INDEX ON pool_member (game_id, tournament_id, entrant_id);

-- Seeds and strata, and only here (pool_member records results), scoped by the pool they apply within: the
-- root pool for the tournament's own seeds, a nesting stage's pool for its inner design's. A row is added
-- only when a seed or stratum is recomputed or changed: an entrant's seed in a pool is their row with the
-- latest round at or before the one asked about.
CREATE TABLE seed (
    game_id       INT NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       BIGINT NOT NULL,
    entrant_id    BIGINT NOT NULL,
    round         INT NOT NULL,
    seed          INT NOT NULL,
    stratum       INT NOT NULL,
    eliminated    INT,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, round, seed),
    FOREIGN KEY (game_id, tournament_id, pool_id) REFERENCES pool,
    FOREIGN KEY (game_id, tournament_id, round) REFERENCES round,
    FOREIGN KEY (game_id, tournament_id, eliminated) REFERENCES round,
    FOREIGN KEY (game_id, tournament_id, entrant_id) REFERENCES entrant
);

CREATE UNIQUE INDEX ON seed (game_id, tournament_id, pool_id, entrant_id, round);
CREATE INDEX ON seed (game_id, tournament_id, round);
CREATE INDEX ON seed (game_id, tournament_id, entrant_id);
CREATE INDEX ON seed (game_id, tournament_id, eliminated) WHERE eliminated IS NOT NULL;


-- =====================================================================
-- Matches
-- =====================================================================

-- Match ids are drawn from one sequence for every game, before the match row is written: the engine is
-- told the id when it makes its game, and the row follows its answer. So an id alone picks one match.
CREATE SEQUENCE match_id_seq;

-- A match, from a challenge or in a tournament.
CREATE TABLE match (
    game_id            INT NOT NULL REFERENCES game,
    match_id           BIGINT NOT NULL,
    -- 'S' scheduled, 'C' choosing roles, 'D' due, 'P' in progress, 'F' finished. The source of truth for
    -- whether it is over; `completed_at` says when.
    status             TEXT NOT NULL CHECK (status IN ('S', 'C', 'D', 'P', 'F')),
    -- Whose match it is: may cancel it, and the listings say it is theirs. A challenge's challenger, a
    -- tournament's owner.
    creator            BIGINT NOT NULL REFERENCES player,
    -- The challenge it came from, or the tournament, pool, round and atomic stage it is played in.
    challenge_id       BIGINT,
    tournament_id      BIGINT,
    pool_id            BIGINT,
    round              INT,
    stage_id           BIGINT,
    description        TEXT NOT NULL DEFAULT '',
    -- NULL until the engine has made its game.
    start              TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    -- Called off by its creator: over, but neither completed (nobody won) nor deleted (it is history).
    cancelled          BOOLEAN NOT NULL DEFAULT false,
    -- The terms it is played under, copied from its challenge (see challenge).
    time_limit         INTERVAL,
    time_limit_kind    TEXT NOT NULL DEFAULT 'PER_TURN' CHECK (time_limit_kind IN ('PER_TURN', 'TOTAL')),
    time_limit_unit    TEXT NOT NULL DEFAULT 'MINUTES' CHECK (time_limit_unit IN ('SECONDS', 'MINUTES', 'HOURS', 'DAYS')),
    settings           JSONB NOT NULL DEFAULT '{}'::jsonb,
    public             BOOLEAN NOT NULL DEFAULT false,
    -- The engine runs a live match's turns and clock itself: it is told the time limit, sends no move
    -- callbacks, and forfeits a seat whose clock runs out. Matchmaker neither tracks turns nor enforces
    -- the limit.
    live               BOOLEAN NOT NULL DEFAULT false,
    -- A match that is not friendly is rated. A game's admin may change it, even once finished
    -- (MatchService.setFriendly).
    friendly           BOOLEAN NOT NULL DEFAULT true,
    -- A tournament match may be drawn unless it is a rubber match, which exists to break a tie.
    ties_allowed       BOOLEAN NOT NULL DEFAULT true,
    rubber             BOOLEAN NOT NULL DEFAULT false,
    -- What the engine answered its create with. `public_url` only for a public match; `cancel_url` NULL
    -- from an engine with no such route, which is then not told of a cancel.
    status_url         TEXT,
    play_url           TEXT,
    public_url         TEXT,
    cancel_url         TEXT,
    -- When the engine acknowledged a cancel.
    engine_released    TIMESTAMPTZ,
    -- The engine's number for the latest move applied to the seats. Callbacks can arrive out of order, and
    -- one whose number is not above this is older than what the seats say: only its turn is recorded. NULL
    -- from an engine that does not number its moves.
    move_sequence      BIGINT,
    -- How it came out, as the engine said: a line of HTML as `SummaryHtml.accept` cleaned it, shown in
    -- place of the result table. NULL where the engine sent none.
    result_summary     TEXT,
    -- Its archive in S3 (archiving-matches-plan.md): the engine's stored JSON, uploaded to a url matchmaker
    -- signs. The key matchmaker chose; the checksum the upload was signed for, base64 as S3 reports it;
    -- when the engine last asked to upload; when the upload was confirmed, after which the archive is the
    -- only copy; and when a friendly archive was found gone, its bucket expiring objects after 30 days.
    archive_key        TEXT,
    archive_sha256     TEXT,
    archive_requested  TIMESTAMPTZ,
    archived_at        TIMESTAMPTZ,
    archive_expired_at TIMESTAMPTZ,
    -- Which bucket the archive is in: true the friendly one, false the permanent one. Written down rather
    -- than following `friendly`, since a match made friendly after archiving is left where it was safe.
    -- NULL until the engine asks to upload.
    archive_friendly   BOOLEAN,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, match_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    -- Played in a pool of its atomic stage, and in a round of the same stage.
    FOREIGN KEY (game_id, tournament_id, pool_id, stage_id)
        REFERENCES atomic_pool (game_id, tournament_id, pool_id, stage_id),
    FOREIGN KEY (game_id, tournament_id, round, stage_id)
        REFERENCES round (game_id, tournament_id, round, stage_id),
    -- From a challenge or a tournament, never both; a tournament match has its pool, round and stage.
    CHECK (
        ((challenge_id IS NULL) <> (tournament_id IS NULL))
        AND ((tournament_id IS NULL) = (pool_id IS NULL))
        AND ((tournament_id IS NULL) = (round IS NULL))
        AND ((tournament_id IS NULL) = (stage_id IS NULL))
    ),
    CONSTRAINT match_finished CHECK ((status = 'F') = (completed_at IS NOT NULL OR cancelled)),
    -- Only a tournament match waits before it is played: a challenge's starts at once.
    CONSTRAINT match_waits_in_tournament CHECK (status NOT IN ('S', 'C', 'D') OR tournament_id IS NOT NULL),
    -- A match not yet made in the engine has no urls and no start.
    CONSTRAINT match_not_yet_created
        CHECK (status NOT IN ('S', 'C', 'D') OR (status_url IS NULL AND play_url IS NULL AND start IS NULL)),
    CONSTRAINT match_rubber_decided CHECK (NOT rubber OR NOT ties_allowed),
    CONSTRAINT match_live_time_limit
        CHECK (NOT live OR (time_limit IS NOT NULL AND time_limit >= INTERVAL '1 second'))
);

-- One match per challenge; NULLs are distinct, so any number of tournament matches.
CREATE UNIQUE INDEX ON match (game_id, challenge_id);
-- For participant's foreign keys to its challenge's or its tournament's match.
CREATE UNIQUE INDEX ON match (game_id, challenge_id, match_id);
CREATE UNIQUE INDEX ON match (game_id, tournament_id, match_id);
CREATE UNIQUE INDEX ON match (match_id);
-- For the foreign key: without it, deleting a player would scan every match.
CREATE INDEX match_creator ON match (creator);
CREATE INDEX ON match (game_id, tournament_id, pool_id) WHERE tournament_id IS NOT NULL;
CREATE INDEX ON match (game_id, tournament_id, round) WHERE round IS NOT NULL AND tournament_id IS NOT NULL;
-- What is still owed something: completed matches never archived, and cancelled matches whose engine has
-- not acknowledged the cancel. Partial, so they hold almost nothing in steady state.
CREATE INDEX match_unarchived ON match (completed_at) WHERE completed_at IS NOT NULL AND archived_at IS NULL;
CREATE INDEX match_unreleased ON match (game_id) WHERE cancelled AND cancel_url IS NOT NULL AND engine_released IS NULL;

-- A seat of a match: a player in it, as a role.
CREATE TABLE participant (
    game_id              INT NOT NULL,
    participant_id       BIGINT GENERATED ALWAYS AS IDENTITY,
    game_type            CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    match_id             BIGINT NOT NULL,
    player_id            BIGINT NOT NULL REFERENCES player,
    -- Every seat names its role, but for a tournament seat still waiting to choose one.
    game_role_id         INT,
    -- Its match's challenge, or its tournament and entrant.
    challenge_id         BIGINT,
    tournament_id        BIGINT,
    entrant_id           BIGINT,
    -- Whether it is this seat's turn, and when that turn runs out.
    pending              BOOLEAN NOT NULL DEFAULT false,
    due                  TIMESTAMPTZ,
    -- When the seat was finished with -- the match completed or was cancelled, or the player went out of
    -- it -- which is what the completed lists' windows are ranges of. NULL while it is still in play.
    completed_at         TIMESTAMPTZ,
    forfeit              BOOLEAN NOT NULL DEFAULT false,
    -- A tournament seat's turn to choose its role, and when that began and ended.
    choose_order         INT,
    choose_started_at    TIMESTAMPTZ,
    chosen_at            TIMESTAMPTZ,
    -- The player's ratings, overall and in the seat's role, as the match began (elo_rating,
    -- elo_role_rating). A challenge seat is rated as it sits down; a tournament seat may exist before.
    -- The role's is NULL in a game whose roles are unimportant, and such a match is rated overall only.
    elo_start            INT,
    elo_role_start       INT,
    -- The seat's notifications, resolved when it is created (see player) and changed only by the player
    -- saying so about this match, or carrying a change into the matches they are in. Only the kinds that
    -- can happen to a match under way.
    notify_match_started BOOLEAN NOT NULL,
    notify_turn_taken    BOOLEAN NOT NULL,
    notify_your_turn     BOOLEAN NOT NULL,
    notify_match_ended   BOOLEAN NOT NULL,
    create_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, participant_id),
    FOREIGN KEY (game_id, match_id) REFERENCES match,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    FOREIGN KEY (game_id, tournament_id, entrant_id) REFERENCES entrant,
    -- A seat sits in a match of its own challenge or tournament.
    FOREIGN KEY (game_id, challenge_id, match_id) REFERENCES match (game_id, challenge_id, match_id),
    FOREIGN KEY (game_id, tournament_id, match_id) REFERENCES match (game_id, tournament_id, match_id),
    CHECK (((tournament_id IS NULL) = (entrant_id IS NULL)) AND ((tournament_id IS NULL) <> (challenge_id IS NULL))),
    CONSTRAINT participant_role_or_tournament CHECK (game_role_id IS NOT NULL OR tournament_id IS NOT NULL),
    CONSTRAINT participant_rated_or_tournament CHECK (elo_start IS NOT NULL OR tournament_id IS NOT NULL)
);

CREATE INDEX ON participant (player_id);
CREATE INDEX ON participant (game_id, match_id);
CREATE INDEX participant_pending ON participant (game_id, match_id, player_id) WHERE pending = true;
-- A player's seats split on whether each is finished, and the completed lists' windows within them.
CREATE INDEX participant_player_completed_at ON participant (player_id, completed_at);
CREATE INDEX ON participant (game_id, challenge_id) WHERE challenge_id IS NOT NULL;
-- For character_participant's foreign key. Not partial, since a foreign key cannot reference a partial
-- index; challenge seats, whose tournament_id is NULL, never conflict in it.
CREATE UNIQUE INDEX ON participant (game_id, tournament_id, entrant_id, participant_id);

-- The character a seat of a character game plays, and for a tournament seat the entrant it plays for.
CREATE TABLE character_participant (
    game_id        INT NOT NULL,
    participant_id BIGINT NOT NULL,
    game_type      CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id   BIGINT NOT NULL,
    tournament_id  BIGINT,
    entrant_id     BIGINT,
    PRIMARY KEY (game_id, participant_id),
    FOREIGN KEY (game_id, participant_id) REFERENCES participant,
    FOREIGN KEY (game_id, character_id) REFERENCES "character" (game_id, character_id),
    FOREIGN KEY (game_id, tournament_id, entrant_id, participant_id)
        REFERENCES participant (game_id, tournament_id, entrant_id, participant_id),
    FOREIGN KEY (game_id, tournament_id, entrant_id, character_id)
        REFERENCES character_entrant (game_id, tournament_id, entrant_id, character_id)
);

CREATE INDEX ON character_participant (game_id, character_id);
CREATE INDEX ON character_participant (game_id, tournament_id, entrant_id, character_id) WHERE tournament_id IS NOT NULL;

-- How a seat came out.
CREATE TABLE result (
    game_id        INT NOT NULL,
    participant_id BIGINT NOT NULL,
    rank           INTEGER NOT NULL,
    scores         JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_winner      BOOLEAN NOT NULL,
    -- Whether the match was ended by a clock rather than by play: on every seat's row, since "won by
    -- forfeit" is as much a fact about the winner as "forfeited" is about the loser.
    forfeit        BOOLEAN NOT NULL DEFAULT false,
    -- What the match did to the seat's ratings, overall and in its role, worked out from the seats' starting
    -- ratings. NULL for a friendly match, which moves nobody, and the role's for a match rated overall only.
    elo_delta      INT,
    elo_role_delta INT,
    -- Ranks a tournament's owner gave a cancelled tournament match by hand. Nothing that moves a rating
    -- reads a cancelled match, so these decide the tournament and nobody's rating.
    manual         BOOLEAN NOT NULL DEFAULT false,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, participant_id),
    FOREIGN KEY (game_id, participant_id) REFERENCES participant
);

-- One row per move, which is what a chess-clock limit sums. `taken_at` is when the move was made and
-- `started_at` when that seat's clock started for it, both stored: callbacks can arrive late or several at
-- once, so a turn's cost must not depend on the row before it.
CREATE TABLE turn (
    game_id        INT NOT NULL,
    match_id       BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    taken_at       TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A seat cannot move twice at one instant, so this is the turn's identity, and what makes recording it
    -- idempotent: a callback and a later status call reporting the same turn insert it once.
    PRIMARY KEY (game_id, participant_id, taken_at),
    FOREIGN KEY (game_id, participant_id) REFERENCES participant,
    FOREIGN KEY (game_id, match_id) REFERENCES match
);

-- The latest turn known of a match, and what each seat has spent.
CREATE INDEX ON turn (game_id, match_id, taken_at);


-- =====================================================================
-- Ratings
-- =====================================================================

-- Each player's Elo rating in each game, moved by every rated (not friendly) match as it completes, and
-- set outright by the game's admin. No row is no rating yet, which is not the starting one.
CREATE TABLE elo_rating (
    game_id        INT NOT NULL REFERENCES game,
    player_id      BIGINT NOT NULL REFERENCES player,
    rating         INT NOT NULL,
    -- How many rated matches moved it; an admin's setting leaves it alone.
    matches        INT NOT NULL DEFAULT 0,
    -- The admin who last set it outright, kept as game_admin keeps granted_by.
    set_by         BIGINT REFERENCES player,
    -- The player's place on the game's leaderboard, by rating and then by matches: one more than how many
    -- stand higher, so ties share a place and the next is skipped. Kept a moment behind the rating by
    -- RankingService, and `ranked_rating` and `ranked_matches` are what it was worked out from: places are
    -- compared by those, so the leaderboard is always in order. NULL until placed.
    rank           INT,
    ranked_rating  INT,
    ranked_matches INT,
    -- The record over the same matches (MatchRecord.of): first alone a win, sharing first a draw, below it
    -- a loss. The forfeit counts are included in wins and losses, read 12 (2) - 5 (1) - 3.
    wins           INT NOT NULL DEFAULT 0,
    losses         INT NOT NULL DEFAULT 0,
    draws          INT NOT NULL DEFAULT 0,
    forfeit_wins   INT NOT NULL DEFAULT 0,
    forfeit_losses INT NOT NULL DEFAULT 0,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, player_id)
);

-- A player's ratings across games, and the foreign keys to them.
CREATE INDEX elo_rating_player ON elo_rating (player_id);
CREATE INDEX elo_rating_set_by ON elo_rating (set_by);
-- A game's leaderboard in order.
CREATE INDEX elo_rating_rank ON elo_rating (game_id, rank);
-- The players waiting to be placed, or taken off. The predicate is EloRatingRepo's `selectUnplaced` word
-- for word, which is what lets the planner use it.
CREATE INDEX elo_rating_unplaced ON elo_rating (game_id)
    WHERE ((matches > 0 OR set_by IS NOT NULL)
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
       OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL);

-- Ratings by role, for a game whose roles are not unimportant: a pool of their own, played seat against
-- seat by the same arithmetic as the overall ones, so the gap between roles is what a role is worth. As
-- elo_rating, but only ever moved by play.
CREATE TABLE elo_role_rating (
    game_id        INT NOT NULL,
    game_role_id   INT NOT NULL,
    player_id      BIGINT NOT NULL REFERENCES player,
    rating         INT NOT NULL,
    matches        INT NOT NULL DEFAULT 0,
    rank           INT,
    ranked_rating  INT,
    ranked_matches INT,
    wins           INT NOT NULL DEFAULT 0,
    losses         INT NOT NULL DEFAULT 0,
    draws          INT NOT NULL DEFAULT 0,
    forfeit_wins   INT NOT NULL DEFAULT 0,
    forfeit_losses INT NOT NULL DEFAULT 0,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_role_id, player_id),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

CREATE INDEX elo_role_rating_player ON elo_role_rating (player_id);
CREATE INDEX elo_role_rating_rank ON elo_role_rating (game_id, game_role_id, rank);
CREATE INDEX elo_role_rating_unplaced ON elo_role_rating (game_id)
    WHERE (matches > 0
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
       OR (NOT matches > 0 AND rank IS NOT NULL);


-- =====================================================================
-- update_date
-- =====================================================================

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'player', 'game', 'game_role', 'game_parameter', 'game_parameter_value', 'game_api_key', 'player_game',
        'character', 'challenge', 'acceptance', 'invitation', 'character_invitation',
        'tournament_model', 'stage_model', 'tournament', 'stage', 'tournament_invitation',
        'tournament_character_invitation', 'entrant', 'character_entrant', 'pool', 'pool_member', 'seed',
        'match', 'participant', 'result', 'turn', 'elo_rating', 'elo_role_rating'
    ]
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_update_date BEFORE UPDATE ON %1$I FOR EACH ROW EXECUTE FUNCTION set_update_date()',
            t);
    END LOOP;
END $$;
