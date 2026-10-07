-- Tournaments: the schema of tournament-design.md, nothing yet reading it.
--
-- One migration for all of it, since no code reads any of it until the tournament service does. It
-- builds on the three before it: V50's nullable match.challenge_id, which the origin CHECK on match
-- depends on; V51's no_tie, which tie-break matches set; and V52's nullable participant.game_role_id,
-- for seats whose roles are chosen in the engine.
--
-- Conventions, as everywhere since V1: game_id is INT, ids are identities, every table has
-- create_date and update_date with a trigger keeping update_date, and a character table carries
-- game_type = 'C' with a foreign key to game on (game_type, game_id), so a character row can exist
-- only in a character game.
--
-- "plan Dn" below refers to the decisions in the tournament implementation plan.

-- ---------------------------------------------------------------------
-- The tournament
-- ---------------------------------------------------------------------

CREATE TABLE tournament (
    game_id                 INT    NOT NULL REFERENCES game,
    tournament_id           BIGINT GENERATED ALWAYS AS IDENTITY,
    -- ELIM and CYCLIC have an elimination_tournament row and LADDER has none. A CHECK cannot span two tables,
    -- so TournamentService keeps that true.
    tournament_class        TEXT   NOT NULL CHECK (tournament_class IN ('LADDER', 'ELIM', 'CYCLIC')),
    name                    TEXT   NOT NULL CHECK (btrim(name) <> ''),
    -- The player managing it: its creator at first, and whoever a game admin hands it to after.
    owner                   BIGINT NOT NULL REFERENCES player,
    invitational            BOOLEAN NOT NULL,
    -- Whether its matches can be watched by anybody, as match.public.
    public                  BOOLEAN NOT NULL DEFAULT false,
    -- Every match is friendly, or none is. Only a game admin may create one that is not, and it cannot change
    -- once the tournament has started; both are TournamentService's to enforce.
    friendly                BOOLEAN NOT NULL DEFAULT true,
    -- Whether its matches are live. Read once per round, when the round starts, onto tournament_round.live:
    -- an edit takes effect from the next round, never part-way through one.
    live                    BOOLEAN NOT NULL DEFAULT false,
    -- Must be 1 for now: see "For later" in the design. Lifting it means dropping this CHECK and
    -- tournament_entry_one_per_player.
    max_entries_per_player  SMALLINT NOT NULL DEFAULT 1 CHECK (max_entries_per_player = 1),
    -- How long a round lasts unless the round says otherwise. Each player's chess clock in a match is half of
    -- it, and a live match needs at least a second on the clock (match_live_time_limit).
    round_duration          INTERVAL NOT NULL CHECK (round_duration >= INTERVAL '2 seconds'),
    -- Who may enter an open tournament, by overall Elo rating; either bound may be left off. Ignored for an
    -- invitational one.
    min_rating              INT,
    max_rating              INT,
    -- Times each player goes through every role in a round. 0 is the usual: everybody plays everybody, and
    -- roles are chosen by seed.
    rotations               INT    NOT NULL DEFAULT 0 CHECK (rotations >= 0),
    started_at              TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    create_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id),
    CHECK (min_rating IS NULL OR max_rating IS NULL OR min_rating <= max_rating),
    CHECK (ended_at IS NULL OR started_at IS NOT NULL)
);

-- "Tournaments I run", on the home page.
CREATE INDEX tournament_owner ON tournament (owner);

-- The settings an elimination or cyclic tournament has and a ladder does not.
CREATE TABLE elimination_tournament (
    game_id                 INT    NOT NULL,
    tournament_id           BIGINT NOT NULL,
    tournament_type         TEXT   NOT NULL
        CHECK (tournament_type IN ('SingleElim', 'DoubleElim', 'ZeroElim', 'Repechage', 'RoundRobin', 'Playoff')),
    -- At least the game's mandatory roles: that needs game_role, so it is TournamentService's.
    pool_size               INT    NOT NULL CHECK (pool_size >= 1),
    min_pool_advance        INT    NOT NULL DEFAULT 1,
    -- Playoff only, and only in its elimination rounds.
    elimination_rotations   INT    NOT NULL DEFAULT 0 CHECK (elimination_rotations >= 0),
    tiebreaker              TEXT   NOT NULL DEFAULT 'SCORE' CHECK (tiebreaker IN ('SCORE', 'REMATCH')),
    create_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    CHECK (min_pool_advance BETWEEN 1 AND pool_size)
);

-- ---------------------------------------------------------------------
-- Invitations and entries
-- ---------------------------------------------------------------------

-- Who has been asked into an invitational tournament, in a game played by players.
CREATE TABLE tournament_invitation (
    game_id        INT    NOT NULL,
    tournament_id  BIGINT NOT NULL,
    player_id      BIGINT NOT NULL REFERENCES player,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, player_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament
);

-- "Invitations to me", on the home page. The key leads with game_id, so it cannot answer that.
CREATE INDEX tournament_invitation_player ON tournament_invitation (player_id);

-- And in a character game, which character has been asked, as character_invitation (V25): its owner is
-- whoever owns it when the invitation is read, so an invitation follows a character that changes hands.
CREATE TABLE character_tournament_invitation (
    game_id        INT     NOT NULL,
    tournament_id  BIGINT  NOT NULL,
    game_type      CHAR(1) NOT NULL DEFAULT 'C' CHECK (game_type = 'C'),
    character_id   BIGINT  NOT NULL,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, character_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    FOREIGN KEY (game_id, character_id) REFERENCES character (game_id, character_id)
);

-- "Invitations to my characters", by way of the characters the caller owns.
CREATE INDEX character_tournament_invitation_character ON character_tournament_invitation (character_id);

-- One player or character signed up for a tournament.
CREATE TABLE tournament_entry (
    game_id        INT    NOT NULL,
    tournament_id  BIGINT NOT NULL,
    entry_id       BIGINT GENERATED ALWAYS AS IDENTITY,
    -- Who entered. For a character entry this is who entered it, not who plays it: the character's owner when
    -- each match is created plays it (plan D10).
    player_id      BIGINT NOT NULL REFERENCES player,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entry_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament
);

-- max_entries_per_player = 1, said by the database. A named index rather than a constraint, so that lifting
-- the limit is one DROP INDEX.
CREATE UNIQUE INDEX tournament_entry_one_per_player ON tournament_entry (game_id, tournament_id, player_id);

-- "Tournaments I have entered", and a player's deletion.
CREATE INDEX tournament_entry_player ON tournament_entry (player_id);

-- The character an entry is, in a character game.
CREATE TABLE character_tournament_entry (
    game_id        INT     NOT NULL,
    tournament_id  BIGINT  NOT NULL,
    entry_id       BIGINT  NOT NULL,
    game_type      CHAR(1) NOT NULL DEFAULT 'C' CHECK (game_type = 'C'),
    character_id   BIGINT  NOT NULL,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, entry_id),
    FOREIGN KEY (game_id, tournament_id, entry_id) REFERENCES tournament_entry,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    FOREIGN KEY (game_id, character_id) REFERENCES character (game_id, character_id),
    -- A character enters a tournament once. Leading with the character, so it also answers "the tournaments
    -- this character is in".
    UNIQUE (game_id, character_id, tournament_id)
);

-- ---------------------------------------------------------------------
-- The seeded field
-- ---------------------------------------------------------------------

-- An entry as the tournament plays it: seeded when the tournament starts -- or, in a ladder, when it enters
-- one already running -- and kept for the life of the tournament. Who it is comes from its entry (plan D11).
CREATE TABLE tournament_participant (
    game_id                    INT    NOT NULL,
    tournament_id              BIGINT NOT NULL,
    tournament_participant_id  BIGINT GENERATED ALWAYS AS IDENTITY,
    entry_id                   BIGINT NOT NULL,
    -- The current seed, overwritten at the end of every round (plan D2). participant.seed keeps the seed each
    -- player held as they sat down in each match, which is what a past round is drawn from.
    seed                       INT    NOT NULL CHECK (seed >= 1),
    -- The seed it started the tournament with, never updated: what a cyclic tournament's next cycle is seeded
    -- from (plan Phase 9).
    initial_seed               INT    NOT NULL CHECK (initial_seed >= 1),
    withdrawn                  BOOLEAN NOT NULL DEFAULT false,
    final_rank                 INT    CHECK (final_rank >= 1),
    -- A ladder's rank: 0 on entering, up one for a win and down one for a loss. Null in any other class.
    ladder_rank                INT,
    create_date                TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, tournament_participant_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_id, tournament_id, entry_id) REFERENCES tournament_entry,
    -- An entry is seeded once; re-entering a ladder after withdrawing clears `withdrawn` on the same row, which
    -- is how the old rank carries over.
    UNIQUE (game_id, tournament_id, entry_id),
    -- Deferred to the commit: reseeding swaps seeds in place, and the first half of a swap would otherwise
    -- collide with the second.
    UNIQUE (game_id, tournament_id, seed) DEFERRABLE INITIALLY DEFERRED
);

-- ---------------------------------------------------------------------
-- Rounds, pools and slots
-- ---------------------------------------------------------------------

-- A round: written for every round of an elimination tournament when it starts, and one at a time for a
-- ladder. The nullable settings override the tournament's for this round only; null is "as the tournament".
CREATE TABLE tournament_round (
    game_id           INT    NOT NULL,
    tournament_id     BIGINT NOT NULL,
    -- Numbered on through every cycle of a cyclic tournament; `cycle` says which cycle a round is in.
    round             INT    NOT NULL CHECK (round >= 1),
    cycle             INT    NOT NULL DEFAULT 1 CHECK (cycle >= 1),
    -- Seeds are recomputed from everybody's record so far as this round starts.
    reseed            BOOLEAN NOT NULL DEFAULT false,
    -- tournament.live as it stood when this round started, and what every match of the round is created with.
    -- Copied rather than read at creation, because the matches are created after the round starts, from the
    -- queue: an edit in between must not split a round.
    live              BOOLEAN NOT NULL DEFAULT false,
    duration          INTERVAL CHECK (duration >= INTERVAL '2 seconds'),
    rotations         INT    CHECK (rotations >= 0),
    pool_size         INT    CHECK (pool_size >= 1),
    min_pool_advance  INT    CHECK (min_pool_advance >= 1),
    tiebreaker        TEXT   CHECK (tiebreaker IN ('SCORE', 'REMATCH')),
    -- Its end is started_at + the duration in force, and is not stored: a round is over when its matches are,
    -- not when a date passes.
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    -- The owner's last Check round, which a second press within a minute is refused against.
    checked_at        TIMESTAMPTZ,
    create_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, round),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    CHECK (min_pool_advance IS NULL OR pool_size IS NULL OR min_pool_advance <= pool_size),
    CHECK (completed_at IS NULL OR started_at IS NOT NULL)
);

-- A pool: a group of slots whose occupants play each other in a round (plan D1). In the common case -- a pool
-- the size of one match, no rotations -- it is one match.
CREATE TABLE fixture (
    game_id        INT    NOT NULL,
    tournament_id  BIGINT NOT NULL,
    fixture_id     BIGINT GENERATED ALWAYS AS IDENTITY,
    round          INT    NOT NULL,
    -- Its place in the round, from 1: what the page calls it ("Pool A", "Match 3"), since a fixture id is
    -- never shown.
    position       INT    NOT NULL CHECK (position >= 1),
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, fixture_id),
    FOREIGN KEY (game_id, tournament_id, round) REFERENCES tournament_round,
    -- Also the index that lists a round's pools in order.
    UNIQUE (game_id, tournament_id, round, position)
);

-- A place in a pool, and how it is filled: by nobody (a bye), by the holder of a seed when the round starts,
-- or by whoever finished at `rank` in a pool of an earlier round. Exactly one of the three (plan D3).
CREATE TABLE fixture_slot (
    game_id                    INT    NOT NULL,
    tournament_id              BIGINT NOT NULL,
    fixture_id                 BIGINT NOT NULL,
    slot_id                    BIGINT GENERATED ALWAYS AS IDENTITY,
    bye                        BOOLEAN NOT NULL DEFAULT false,
    seed                       INT    CHECK (seed >= 1),
    prev_fixture_id            BIGINT,
    rank                       INT    CHECK (rank >= 1),
    -- Who fills it, settled when its round starts: the seed's holder, or the earlier pool's finisher, or the
    -- player the fill rule brought up. Null before the round starts, and null after it for a bye -- a bye slot,
    -- or one whose player had already withdrawn when the round started. This is what a round's matches are
    -- created from, after the round has started, from the queue.
    --
    -- It is also the only record of whose a tournament seat is: participant names its slot, not its tournament
    -- participant. So once set it is never changed or cleared. A player who withdraws mid-round keeps their
    -- slots, and the seats of matches already played keep their entrant; withdrawal is
    -- tournament_participant.withdrawn, and only the slots of rounds not yet started are resolved as byes.
    tournament_participant_id  BIGINT,
    create_date                TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- slot_id is unique on its own; fixture_id is in the key for participant's foreign key to name.
    PRIMARY KEY (game_id, tournament_id, fixture_id, slot_id),
    FOREIGN KEY (game_id, tournament_id, fixture_id) REFERENCES fixture,
    FOREIGN KEY (game_id, tournament_id, prev_fixture_id) REFERENCES fixture (game_id, tournament_id, fixture_id),
    FOREIGN KEY (game_id, tournament_id, tournament_participant_id) REFERENCES tournament_participant,
    -- One slot per finishing place of an earlier pool. Slots filled some other way have a null prev_fixture_id,
    -- and nulls never collide here.
    UNIQUE (game_id, tournament_id, prev_fixture_id, rank),
    CONSTRAINT fixture_slot_one_source CHECK (num_nonnulls(NULLIF(bye, false), seed, prev_fixture_id) = 1),
    CONSTRAINT fixture_slot_rank_with_pool CHECK ((prev_fixture_id IS NULL) = (rank IS NULL)),
    CONSTRAINT fixture_slot_bye_unfilled CHECK (NOT bye OR tournament_participant_id IS NULL)
);

-- "Where is this player in the bracket", for the tournament page.
CREATE INDEX fixture_slot_participant ON fixture_slot (game_id, tournament_id, tournament_participant_id);

-- ---------------------------------------------------------------------
-- Matches and seats
-- ---------------------------------------------------------------------

-- A tournament match names its pool and its number within it. The number is what makes creating it
-- idempotent: a queued MatchDue delivered twice fails on the unique index below rather than making two
-- (plan D4, D5).
ALTER TABLE match ADD COLUMN tournament_id BIGINT;
ALTER TABLE match ADD COLUMN fixture_id    BIGINT;
ALTER TABLE match ADD COLUMN match_no      INT CHECK (match_no >= 1);

ALTER TABLE match ADD CONSTRAINT match_fixture
    FOREIGN KEY (game_id, tournament_id, fixture_id) REFERENCES fixture (game_id, tournament_id, fixture_id);

-- All three or none.
ALTER TABLE match ADD CONSTRAINT match_tournament_columns
    CHECK (num_nulls(tournament_id, fixture_id, match_no) IN (0, 3));

-- Started from a challenge or from a fixture, never both. Not "exactly one": V50 made a match with no
-- challenge a match its creator made, and one with neither may already exist. So a tournament match is one
-- with a fixture, which is what the code asks -- not one without a challenge.
ALTER TABLE match ADD CONSTRAINT match_origin
    CHECK (challenge_id IS NULL OR fixture_id IS NULL);

-- A pool's matches, and the claim: match k of a fixture is made once.
CREATE UNIQUE INDEX match_fixture_match_no ON match (game_id, tournament_id, fixture_id, match_no);

-- What participant's foreign key to its match's fixture names. match_id is unique already, so this cannot
-- refuse anything; it exists so a seat's fixture can be held to its match's.
ALTER TABLE match ADD CONSTRAINT match_fixture_identity UNIQUE (game_id, match_id, tournament_id, fixture_id);

-- A tournament seat names the slot it was filled from, and the seed its player held as they sat down: the
-- record a past round is drawn from once seeds have moved on (plan D2). Whose seat it is comes from the slot
-- (fixture_slot.tournament_participant_id), and is not repeated here, where the two could disagree.
ALTER TABLE participant ADD COLUMN tournament_id  BIGINT;
ALTER TABLE participant ADD COLUMN fixture_id     BIGINT;
ALTER TABLE participant ADD COLUMN slot_id        BIGINT;
ALTER TABLE participant ADD COLUMN seed           INT CHECK (seed >= 1);

ALTER TABLE participant ADD CONSTRAINT participant_tournament_columns
    CHECK (num_nulls(tournament_id, fixture_id, slot_id, seed) IN (0, 4));

ALTER TABLE participant ADD CONSTRAINT participant_fixture_slot
    FOREIGN KEY (game_id, tournament_id, fixture_id, slot_id) REFERENCES fixture_slot;

-- The seat's fixture is its match's.
ALTER TABLE participant ADD CONSTRAINT participant_match_fixture
    FOREIGN KEY (game_id, match_id, tournament_id, fixture_id)
    REFERENCES match (game_id, match_id, tournament_id, fixture_id);

-- One seat per slot per match. Not per fixture: a slot in a pool of four plays three matches.
CREATE UNIQUE INDEX participant_match_slot ON participant (game_id, match_id, slot_id);

-- A slot's seats, which is how a tournament participant's seats across the tournament are found -- their
-- slots by fixture_slot_participant, then the seats here -- for standings and reseeding. It is also what a
-- deletion of a slot checks the participant_fixture_slot foreign key against.
CREATE INDEX participant_by_slot ON participant (game_id, tournament_id, fixture_id, slot_id);

-- ---------------------------------------------------------------------
-- Results and scores
-- ---------------------------------------------------------------------

-- Ranks the owner set by hand on cancelling a tournament match (plan D12). Rows with this set exist only on
-- a cancelled tournament match -- TournamentService's rule, since a CHECK cannot read match. Nothing rates them:
-- ratings move only in recordResults, which refuses a cancelled match.
ALTER TABLE result ADD COLUMN manual BOOLEAN NOT NULL DEFAULT false;

-- The key in a result's scores holding the game's numeric score, which the SCORE tiebreaker differences (plan
-- D8). Set by the game's admin; null is "ranks stand in for scores". `score` for boxing.
ALTER TABLE game ADD COLUMN score_key TEXT CHECK (btrim(score_key) <> '');

-- ---------------------------------------------------------------------
-- update_date triggers
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_tournament_update_date
    BEFORE UPDATE ON tournament FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_elimination_tournament_update_date
    BEFORE UPDATE ON elimination_tournament FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_invitation_update_date
    BEFORE UPDATE ON tournament_invitation FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_character_tournament_invitation_update_date
    BEFORE UPDATE ON character_tournament_invitation FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_entry_update_date
    BEFORE UPDATE ON tournament_entry FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_character_tournament_entry_update_date
    BEFORE UPDATE ON character_tournament_entry FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_participant_update_date
    BEFORE UPDATE ON tournament_participant FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_tournament_round_update_date
    BEFORE UPDATE ON tournament_round FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_fixture_update_date
    BEFORE UPDATE ON fixture FOR EACH ROW EXECUTE FUNCTION set_update_date();
CREATE TRIGGER trg_fixture_slot_update_date
    BEFORE UPDATE ON fixture_slot FOR EACH ROW EXECUTE FUNCTION set_update_date();
