-- The models every game's tournaments are made from: the standard formats defined in the code
-- (StandardModels), written here by a site admin's sync, and shown in a game only where
-- tournament_model_game says so. Each is named by a descriptive id, 'world-cup' say; an inner design
-- of a nesting stage is a model of its own, named after the stage, 'world-cup/knockout'.
create table tournament_model(
    tournament_model_id      TEXT NOT NULL,
    -- The version of its definition in the code that was written here last: a sync rewrites a model
    -- whose version in the code is greater.
    version            INT NOT NULL DEFAULT 1 CHECK (version > 0),
    name               TEXT   NOT NULL,
    description        TEXT   NOT NULL DEFAULT '',
    classification     TEXT,
    -- The default pool size the create form suggests, which the creator may change. NULL suggests the
    -- game's R.
    pool_size          int CHECK (pool_size > 0),
    first_stage_model_id TEXT not null,
    late_entries       BOOLEAN NOT NULL DEFAULT false,
    -- False once the code no longer defines it: offered no longer, and kept because a tournament made
    -- from it has entrants. Its stages and inner models are deactivated with it.
    active             BOOLEAN NOT NULL DEFAULT true,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_model_id)
);


create table stage_model(
    tournament_model_id      TEXT not null,
    -- Descriptive within its model: 'groups', 'knockout', or 'stage-2' for a stage with no name.
    stage_model_id TEXT not null,
    -- Which subtype it is: 'A' an atomic_stage_model, 'N' a nesting_stage_model.
    kind               CHAR(1) NOT NULL CHECK (kind IN ('A', 'N')),
    -- How many entrants a pool of this stage holds, its groups' size for a nesting stage. NULL takes the
    -- tournament's default_pool_size.
    pool_size          int CHECK (pool_size > 0),
    name               TEXT   NOT NULL,
    description        TEXT   NOT NULL DEFAULT '',
    -- Copied to stage.concurrent_rounds when a tournament is made from the model.
    concurrent_rounds  boolean not null default false,
    repetitions       int,
    remaining_participants int,
    bracketed boolean not null default false,
    demote             int default 2 check (demote is null or demote > 1),
    max_stratum    int default 0,
    min_stratum     int default 0,
    points_for_win  int not null default  2,
    points_for_draw int not null default 1,
    points_bonus boolean not null default true,
    promote      int default 1 check (promote is null or demote is null or promote < demote),
    rubber_match boolean not null default false, -- use a rubber match to break ties in pools, otherwise use total score differential
    strict_strata boolean not null default true,
    tie_breaker_stage boolean not null default false,
    seed_policy text not null check (seed_policy in ('CONST', 'ELO', 'PREV', 'WINNER')),
    current_stage_model_id TEXT,
    -- False with its model's active.
    active             BOOLEAN NOT NULL DEFAULT true,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_model_id, stage_model_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (tournament_model_id, stage_model_id, kind),
    FOREIGN KEY (tournament_model_id) references tournament_model,
    FOREIGN KEY (tournament_model_id, current_stage_model_id) references stage_model,
    CHECK (repetitions is null or remaining_participants is null) -- if both are null then stage is repeated until manually stopped by owner of tournament
);

-- Deferred: a model and its stages reference each other, so they are inserted in one transaction and
-- checked at its commit.
alter table tournament_model add foreign key (tournament_model_id, first_stage_model_id) references stage_model
    DEFERRABLE INITIALLY DEFERRED;
create index on tournament_model (tournament_model_id, first_stage_model_id);

create index on stage_model(tournament_model_id, current_stage_model_id) where current_stage_model_id is not null;

create table atomic_stage_model(
    tournament_model_id      TEXT not null,
    stage_model_id TEXT not null,
    kind               CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    rotate_roles boolean not null default false,
    PRIMARY KEY (tournament_model_id, stage_model_id),
    FOREIGN KEY (tournament_model_id, stage_model_id, kind)
        references stage_model (tournament_model_id, stage_model_id, kind)
);

create table nesting_stage_model(
    tournament_model_id      TEXT not null,
    stage_model_id TEXT not null,
    kind               CHAR(1) NOT NULL DEFAULT 'N' CHECK (kind = 'N'),
    inner_tournament_model_id TEXT NOT NULL,
    PRIMARY KEY (tournament_model_id, stage_model_id),
    FOREIGN KEY (inner_tournament_model_id) references tournament_model,
    FOREIGN KEY (tournament_model_id, stage_model_id, kind)
        references stage_model (tournament_model_id, stage_model_id, kind)
);

create index on nesting_stage_model(inner_tournament_model_id);

-- Which models a game offers where a tournament is created, as its admins choose. A tournament made
-- from a model the game stops offering keeps it.
create table tournament_model_game(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_model_id TEXT  NOT NULL REFERENCES tournament_model,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_model_id)
);

-- For the foreign key: without it, deleting a model would scan every game's choices.
create index on tournament_model_game (tournament_model_id);


create table tournament(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    name               TEXT   NOT NULL,
    description        TEXT   NOT NULL DEFAULT '',
    -- Who runs it: starts and checks its rounds, stops it, decides a cancelled match.
    owner              BIGINT NOT NULL REFERENCES player,
    status             TEXT   NOT NULL DEFAULT 'draft'
                       CHECK (status IN ('draft', 'open', 'running', 'finished', 'cancelled')),
    -- The model it was made from: one its game offered then. Its stages are copied, so a sync that
    -- rewrites the model later does not change it.
    tournament_model_id TEXT NOT NULL,
    concurrent_rounds  boolean not null default false, 
    first_stage_id BIGINT not null,
    late_entries       BOOLEAN NOT NULL DEFAULT false,
    automatic_rounds boolean not null default false,
    friendly  boolean not null default true,
    invitational boolean not null default false,
    min_rating       int default null,
    max_rating        int default null,
    public        boolean not null default true,
    entries_per_player int not null default 1,
    max_turns int,
    -- The size of a pool of any stage that does not set its own.
    default_pool_size  int NOT NULL CHECK (default_pool_size > 0),
    round_duration interval,
    current_round int,
    stop_requested boolean not null default false,
    -- When the owner last asked for the round's overdue matches to be checked, so that a burst of
    -- presses is one check.
    checked_at         TIMESTAMPTZ,    
    started_at  TIMESTAMPTZ,
    ended_at  TIMESTAMPTZ,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id),
    FOREIGN KEY (tournament_model_id) REFERENCES tournament_model,
    CHECK (min_rating IS NULL OR max_rating IS NULL OR min_rating <= max_rating),
    CHECK (entries_per_player >= 1),
    CHECK (max_turns > 0)
);

CREATE INDEX tournament_owner ON tournament (owner);
-- For the foreign key: without it, deleting a model would scan every tournament.
CREATE INDEX tournament_model_used ON tournament (tournament_model_id);


create table stage(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT NOT NULL,
    stage_id BIGINT  NOT NULL GENERATED ALWAYS AS IDENTITY,
    -- Which subtype it is: 'A' an atomic_stage, 'N' a nesting_stage.
    kind               CHAR(1) NOT NULL CHECK (kind IN ('A', 'N')),
    -- How many entrants a pool of this stage holds, its groups' size for a nesting stage. NULL takes the
    -- tournament's default_pool_size.
    pool_size          int CHECK (pool_size > 0),
    name               TEXT   NOT NULL,
    description        TEXT   NOT NULL DEFAULT '',
    concurrent_rounds  boolean not null default false,     
    bracketed boolean not null default false,
    repetitions       int,
    remaining_participants int,
    demote             int default 2 check (demote is null or demote > 1),
    max_stratum    int default 0,
    min_stratum     int default 0,
    points_for_win  int not null default  2,
    points_for_draw int not null default 1,
    points_bonus boolean not null default true,
    promote      int default 1 check (promote is null or demote is null or promote < demote),
    rubber_match boolean not null default false, -- use a rubber match to break ties in pools, otherwise use total score differential
    strict_strata boolean not null default true,
    tie_breaker_stage boolean not null default false,
    seed_policy text not null check (seed_policy in ('CONST', 'ELO', 'PREV', 'WINNER')),
    current_stage_id BIGINT,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, stage_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id) references tournament ,
    FOREIGN KEY (game_id, tournament_id, current_stage_id) references stage,
    CHECK (repetitions is null or remaining_participants is null) -- if both are null then stage is repeated until manually stopped by owner of tournament
);

create index on stage(game_id, tournament_id, current_stage_id) where current_stage_id is not null;

-- Deferred: a tournament and its stages reference each other, so they are inserted in one transaction and
-- checked at its commit.
alter table tournament add foreign key (game_id, tournament_id, first_stage_id) references stage
    DEFERRABLE INITIALLY DEFERRED;
create index on tournament (game_id, tournament_id, first_stage_id);


create table atomic_stage(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT not null,
    stage_id BIGINT not null,
    kind               CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    rotate_roles boolean not null default false,
    PRIMARY KEY (game_id, tournament_id, stage_id),
    FOREIGN KEY (game_id, tournament_id, stage_id, kind) references stage (game_id, tournament_id, stage_id, kind)
);

-- Each pool of a nesting stage plays an inner design: a chain of stages of the same tournament, from
-- inner_first_stage_id along each stage's current_stage_id. Every stage, pool and seed of an inner
-- design belongs to the root tournament, so tournament_id is the root's everywhere.
create table nesting_stage(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT not null,
    stage_id BIGINT not null,
    kind               CHAR(1) NOT NULL DEFAULT 'N' CHECK (kind = 'N'),
    inner_first_stage_id BIGINT not null,
    PRIMARY KEY (game_id, tournament_id, stage_id),
    FOREIGN KEY (game_id, tournament_id, stage_id, kind) references stage (game_id, tournament_id, stage_id, kind),
    FOREIGN KEY (game_id, tournament_id, inner_first_stage_id) references stage
);

create index on nesting_stage(game_id, tournament_id, inner_first_stage_id);

-- Before SEED, which references it. A round is played within one atomic stage.
CREATE TABLE ROUND (
    game_id       INT     NOT NULL,
    tournament_id BIGINT  NOT NULL,
    round int NOT NULL,
    stage_id bigint NOT NULL,
    start_date  TIMESTAMPTZ,   -- null until started
    deadline  TIMESTAMPTZ ,
    complete boolean not null default false,
    PRIMARY KEY (game_id, tournament_id, round),
    -- For match's foreign key, which names the stage as well, so that a match's round and pool agree on it.
    UNIQUE (game_id, tournament_id, round, stage_id),
    FOREIGN KEY  (game_id, tournament_id, stage_id) references atomic_stage
);

create index on ROUND(game_id, tournament_id, stage_id);

alter table tournament add FOREIGN KEY (game_id, tournament_id, current_round) references round;
create index on tournament(game_id, tournament_id, current_round) where current_round is not null;


-- Who may enter an invitational tournament. 

CREATE TABLE tournament_invitation (
    game_id       INT    NOT NULL,
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
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    player_id     BIGINT NOT NULL, 
    character_id     BIGINT NOT NULL,
    game_type Char(1) not null default 'C'  CHECK (game_type = 'C'),
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, player_id, character_id),
    FOREIGN KEY (game_id, game_type) REFERENCES game(game_id, game_type),
    FOREIGN KEY (game_id, tournament_id, player_id) REFERENCES tournament_invitation,
    FOREIGN KEY (game_id, character_id) references character
);

create index on tournament_character_invitation (game_id, character_id);

-- `rating_at_entry` is what the rating limits were checked against and, until the tournament
-- starts, what it is seeded by. Withdrawing sets `withdrawn_at`; coming back clears it.
CREATE TABLE entrant (
    game_id         INT     NOT NULL,
    entrant_id      BIGINT  GENERATED ALWAYS AS IDENTITY,
    tournament_id   BIGINT  NOT NULL,
    game_type       CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    player_id       BIGINT  NOT NULL REFERENCES player,
    rating_at_entry INT     NOT NULL,
    -- Where they finished in the tournament, once it is over.
    final_rank INT,
    entered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at    TIMESTAMPTZ,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id)
);

CREATE INDEX entrant_tournament ON entrant (game_id, tournament_id);
CREATE INDEX entrant_player ON entrant (player_id);


-- A character may have only one entry to a tournament
CREATE TABLE character_entrant (
    game_id       INT     NOT NULL,
    tournament_id BIGINT  NOT NULL,
    entrant_id    BIGINT  NOT NULL,
    game_type     CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id  BIGINT  NOT NULL,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, entrant_id) REFERENCES entrant,
    FOREIGN KEY (game_id, character_id) REFERENCES character (game_id, character_id),
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    -- A character enters a tournament once.
    UNIQUE (game_id, tournament_id, character_id),
    -- For character_participant's foreign key, which names the character as well as the entrant.
    UNIQUE (game_id, tournament_id, entrant_id, character_id)
);

CREATE INDEX character_entrant_character ON character_entrant (game_id,character_id);


-- A set of entrants playing together. A pool is one of:
--   - a POOL_GROUP, a pool of a nesting stage, which plays that stage's inner design: the pools of the
--     inner design have it as their parent;
--   - an ATOMIC_POOL, a pool of an atomic stage, which plays matches;
--   - the tournament's one root pool, which is neither: no parent, no stage and no round. It is the
--     parent of the top-level pools, and the scope of the top-level seeds. Its members are the
--     tournament's entrants, so it has no pool_member rows, and their final ranks are entrant.final_rank.
CREATE TABLE POOL (
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT not null,
    pool_id BIGINT not null GENERATED ALWAYS AS IDENTITY,
    -- Which it is: 'R' the root pool, 'G' a pool_group, 'A' an atomic_pool.
    kind          CHAR(1) NOT NULL CHECK (kind IN ('R', 'G', 'A')),
    parent_pool_id BIGINT,
    -- The round it began in: its members entered it with their SEED rows as of this round, and the
    -- pools of one playing of a stage are its pools begun in the same round. NULL for the root pool,
    -- which exists before any round.
    round INT,
    stratum INT NOT NULL default 0,
    completed_at TIMESTAMPTZ,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id),
    -- For the subtypes' foreign keys, which name the kind as well.
    UNIQUE (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, parent_pool_id) references pool,
    FOREIGN KEY (game_id, tournament_id, round) references round,
    -- Only the root pool has no parent, and it alone has no round.
    CHECK ((kind = 'R') = (parent_pool_id IS NULL)),
    CHECK ((kind = 'R') = (round IS NULL))
);

-- One root pool per tournament.
create unique index pool_root on pool (game_id, tournament_id) where kind = 'R';
Create index on pool(game_id, tournament_id, parent_pool_id) where parent_pool_id is not null;
Create index on pool(game_id, tournament_id, round) where round is not null;

-- A pool of a nesting stage: a group playing the stage's inner design.
CREATE TABLE POOL_GROUP (
    game_id            INT    NOT NULL,
    tournament_id      BIGINT not null,
    pool_id BIGINT not null,
    kind          CHAR(1) NOT NULL DEFAULT 'G' CHECK (kind = 'G'),
    stage_id BIGINT not null,
    PRIMARY KEY (game_id, tournament_id, pool_id),
    FOREIGN KEY (game_id, tournament_id, pool_id, kind) references pool (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id) references nesting_stage
);

Create index on pool_group(game_id, tournament_id, stage_id);

-- A pool of an atomic stage, which plays matches.
CREATE TABLE ATOMIC_POOL (
    game_id            INT    NOT NULL,
    tournament_id      BIGINT not null,
    pool_id BIGINT not null,
    kind          CHAR(1) NOT NULL DEFAULT 'A' CHECK (kind = 'A'),
    stage_id BIGINT not null,
    PRIMARY KEY (game_id, tournament_id, pool_id),
    -- For match's foreign key, which names the stage as well, so that a match's round and pool agree on it.
    UNIQUE (game_id, tournament_id, pool_id, stage_id),
    FOREIGN KEY (game_id, tournament_id, pool_id, kind) references pool (game_id, tournament_id, pool_id, kind),
    FOREIGN KEY (game_id, tournament_id, stage_id) references atomic_stage
);

Create index on atomic_pool(game_id, tournament_id, stage_id);

CREATE TABLE POOL_MEMBER(
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT not null,
    pool_id BIGINT not null,
    entrant_id BIGINT not null,
    final_rank int,
    -- Fractional: a points bonus is half as much again, and engines report scores that need not be whole.
    total_score double precision,
    score_against double precision,
    pool_points double precision,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id, pool_id) references pool,
    FOREIGN KEY (game_id, tournament_id, entrant_id) references entrant
);

Create index on pool_member (game_id, tournament_id, entrant_id);

-- Seeds and strata, and only here (pool_member records results, not strata), scoped by the pool they apply within: the root pool for the tournament's own
-- seeds, a nesting stage's pool for the seeds of its inner design. A row is added only when a seed
-- or stratum is recomputed or changed: an entrant's seed in a pool is their row with the latest round
-- at or before the one asked about.
CREATE TABLE SEED (
    game_id       INT     NOT NULL,
    tournament_id BIGINT  NOT NULL,
    pool_id       BIGINT  NOT NULL,
    entrant_id    BIGINT  NOT NULL,
    round INT NOT NULL,
    seed INT NOT NULL,
    stratum INT NOT NULL,
    eliminated INT,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, round, seed),
    FOREIGN KEY (game_id, tournament_id, pool_id) references pool,
    FOREIGN KEY (game_id, tournament_id, round) references round,
    FOREIGN KEY (game_id, tournament_id, eliminated) references round,
    FOREIGN KEY (game_id, tournament_id, entrant_id) references entrant
);

create unique index on SEED  (game_id, tournament_id, pool_id, entrant_id, round);
create  index on SEED  (game_id, tournament_id, round);
create  index on SEED  (game_id, tournament_id, entrant_id);
create  index on SEED  (game_id, tournament_id, eliminated) where eliminated is not null;

ALTER TABLE match ADD COLUMN tournament_id BIGINT;
ALTER TABLE match ADD COLUMN pool_id BIGINT;
ALTER TABLE match ADD COLUMN round INT;
-- The atomic stage it is played in: the stage of its round and of its pool, which the two foreign keys
-- below hold to be the same one.
ALTER TABLE match ADD COLUMN stage_id BIGINT;
ALTER TABLE match ALTER COLUMN start drop not null;
ALTER TABLE match ADD COLUMN ties_allowed boolean not null default true;
ALTER TABLE match ADD COLUMN rubber boolean not null default false;
-- A rubber match exists to break a tie, so it can never end in one.
ALTER TABLE match ADD CONSTRAINT match_rubber_decided CHECK (NOT rubber OR NOT ties_allowed);
alter table match add column    status        TEXT   CHECK (status IN ('S', 'C', 'D', 'P', 'F'));   -- for "Scheduled", "Choosing", "Due", "in Progress" or "Complete"
update match set status = 'F' where completed is not null or cancelled;
update match set status='P' where status is null;
alter table match alter column status set not null;

-- Status says that a match is over; completed_at says when.
alter table match rename column completed to completed_at;

-- Status is the source of truth, and over means completed or cancelled: this keeps the two in step.
alter table match add constraint match_finished
    CHECK ((status = 'F') = (completed_at IS NOT NULL OR cancelled));



-- A match is played in a pool of its atomic stage.
ALTER TABLE match ADD CONSTRAINT match_tournament_fk
    FOREIGN KEY (game_id, tournament_id, pool_id, stage_id)
    REFERENCES atomic_pool (game_id, tournament_id, pool_id, stage_id);

create index on match(game_id, tournament_id, pool_id) where tournament_id is not null;
create index on match(game_id, tournament_id, round) where round is not null and tournament_id is not null;

ALTER TABLE match ADD 
    CHECK (
          ((challenge_id IS NULL) <> (tournament_id IS NULL))
          AND
         ( (tournament_id IS NULL) = (pool_id is null))
          AND
         ( (tournament_id IS NULL) = (round is null))
          AND
         ( (tournament_id IS NULL) = (stage_id is null))
     );

-- A tournament match is played in a round of its tournament, in that round's atomic stage: a
-- scheduled one too, so a planned round's row exists before it starts, with start_date NULL. Indexed by
-- the index on round above.
ALTER TABLE match ADD FOREIGN KEY (game_id, tournament_id, round, stage_id)
    REFERENCES round (game_id, tournament_id, round, stage_id);

-- Only a tournament match waits before it is played: a challenge's starts at once.
ALTER TABLE match ADD CONSTRAINT match_waits_in_tournament
    CHECK (status NOT IN ('S', 'C', 'D') OR tournament_id IS NOT NULL);

-- A match not yet created in the engine has no urls and no start.
ALTER TABLE match ADD CONSTRAINT match_not_yet_created
    CHECK (status NOT IN ('S', 'C', 'D') OR (status_url IS NULL AND play_url IS NULL AND start IS NULL));

create unique index on match (game_id, challenge_id, match_id);
-- For participant's foreign key to its tournament's match, as the one above is for its challenge's.
create unique index on match (game_id, tournament_id, match_id);
create unique index on match(match_id);

alter table participant add column challenge_id bigint;
update participant p set challenge_id = (select challenge_id from match m where m.game_id = p.game_id and m.match_id = p.match_id);
alter table participant add foreign key (game_id, challenge_id) references challenge;
alter table participant add foreign key (game_id, challenge_id, match_id) references match (game_id, challenge_id, match_id);
create index on participant (game_id, challenge_id) where challenge_id is not null;

alter table participant add column tournament_id bigint;
alter table participant add column  entrant_id bigint;
alter table participant add column  choose_order      INT;
alter table participant add column   choose_started_at TIMESTAMPTZ;
alter table participant add column   chosen_at         TIMESTAMPTZ;
alter table participant add column   forfeit           BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE participant ALTER COLUMN game_role_id drop not null;

alter table participant add  CHECK ( (tournament_id is NULL) = (entrant_id is NULL)  AND ( (tournament_id is NULL) <> (challenge_id is NULL) ) );
alter table participant add foreign key (game_id, tournament_id, entrant_id)  references entrant;

-- A tournament seat sits in a match of the same tournament, as a challenge seat does in its challenge's.
alter table participant add foreign key (game_id, tournament_id, match_id)
    references match (game_id, tournament_id, match_id);

-- Only a tournament seat can wait for its role. A challenge seat is accepted into one.
alter table participant add constraint participant_role_or_tournament
    CHECK (game_role_id IS NOT NULL OR tournament_id IS NOT NULL);

-- A tournament seat exists before its ratings are taken (elo_role_start was already nullable). A
-- challenge seat is rated as it sits down, so only a tournament seat may lack one.
ALTER TABLE participant ALTER COLUMN elo_start drop not null;
alter table participant add constraint participant_rated_or_tournament
    CHECK (elo_start IS NOT NULL OR tournament_id IS NOT NULL);

-- Not partial: a foreign key (character_participant's) cannot reference a partial index. Challenge seats,
-- whose tournament_id is NULL, never conflict in it.
create unique index on participant(game_id, tournament_id, entrant_id, participant_id);
create index on participant(game_id, challenge_id) where challenge_id is not null;

alter table character_participant add column tournament_id bigint;
alter table character_participant add column  entrant_id bigint;

alter table character_participant add foreign key (game_id, tournament_id, entrant_id, participant_id)
    references participant (game_id, tournament_id, entrant_id, participant_id);
alter table character_participant add foreign key (game_id, tournament_id, entrant_id, character_id)
    references character_entrant (game_id, tournament_id, entrant_id, character_id);
create index on character_participant (game_id, tournament_id, entrant_id, character_id) where tournament_id is not null;




-- Every stage model, stage and pool of a kind has its subtype row: an 'A' stage an atomic_stage, an
-- 'N' stage a nesting_stage, a 'G' pool a pool_group, an 'A' pool an atomic_pool. The kind columns
-- already stop a row having the wrong subtype; these foreign keys, from the supertype to the subtype,
-- make it have the right one.
--
-- Each generated column holds the kind only when that subtype applies, and is NULL otherwise, and a
-- foreign key with a NULL column is not checked. Deferred to commit, since a supertype and its subtype
-- reference each other and one has to be inserted first. Deleting a subtype row while its supertype
-- remains then fails at commit too.

ALTER TABLE atomic_stage_model ADD UNIQUE (tournament_model_id, stage_model_id, kind);
ALTER TABLE nesting_stage_model ADD UNIQUE (tournament_model_id, stage_model_id, kind);
ALTER TABLE stage_model
    ADD COLUMN atomic_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    ADD COLUMN nesting_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'N' THEN kind END) STORED,
    ADD FOREIGN KEY (tournament_model_id, stage_model_id, atomic_kind)
        REFERENCES atomic_stage_model (tournament_model_id, stage_model_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (tournament_model_id, stage_model_id, nesting_kind)
        REFERENCES nesting_stage_model (tournament_model_id, stage_model_id, kind) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE atomic_stage ADD UNIQUE (game_id, tournament_id, stage_id, kind);
ALTER TABLE nesting_stage ADD UNIQUE (game_id, tournament_id, stage_id, kind);
ALTER TABLE stage
    ADD COLUMN atomic_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    ADD COLUMN nesting_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'N' THEN kind END) STORED,
    ADD FOREIGN KEY (game_id, tournament_id, stage_id, atomic_kind)
        REFERENCES atomic_stage (game_id, tournament_id, stage_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (game_id, tournament_id, stage_id, nesting_kind)
        REFERENCES nesting_stage (game_id, tournament_id, stage_id, kind) DEFERRABLE INITIALLY DEFERRED;

-- The root pool ('R') has no subtype, so both columns are NULL for it.
ALTER TABLE pool_group ADD UNIQUE (game_id, tournament_id, pool_id, kind);
ALTER TABLE atomic_pool ADD UNIQUE (game_id, tournament_id, pool_id, kind);
ALTER TABLE pool
    ADD COLUMN group_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'G' THEN kind END) STORED,
    ADD COLUMN atomic_kind CHAR(1) GENERATED ALWAYS AS (CASE WHEN kind = 'A' THEN kind END) STORED,
    ADD FOREIGN KEY (game_id, tournament_id, pool_id, group_kind)
        REFERENCES pool_group (game_id, tournament_id, pool_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD FOREIGN KEY (game_id, tournament_id, pool_id, atomic_kind)
        REFERENCES atomic_pool (game_id, tournament_id, pool_id, kind) DEFERRABLE INITIALLY DEFERRED;


-- create_date and update_date: set_update_date() stamps update_date and keeps create_date as it was.
CREATE TRIGGER trg_tournament_model_update_date BEFORE UPDATE ON tournament_model
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_stage_model_update_date BEFORE UPDATE ON stage_model
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_update_date BEFORE UPDATE ON tournament
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_stage_update_date BEFORE UPDATE ON stage
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_invitation_update_date BEFORE UPDATE ON tournament_invitation
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_character_invitation_update_date BEFORE UPDATE ON tournament_character_invitation
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_entrant_update_date BEFORE UPDATE ON entrant
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_character_entrant_update_date BEFORE UPDATE ON character_entrant
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_seed_update_date BEFORE UPDATE ON seed
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_pool_update_date BEFORE UPDATE ON pool
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_pool_member_update_date BEFORE UPDATE ON pool_member
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
