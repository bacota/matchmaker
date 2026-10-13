-- Tournaments (tournament-design.md, new-design.md).
--
-- A tournament is its design, its settings, and its state. The design is the tree of stages its
-- creator chose, as JSON. The state is the whole of its progress -- every stage run, every pool,
-- every result it has ranked -- also as JSON: it is read and rewritten whole, by one call at a
-- time under the tournament row's lock, and nothing queries inside it. NULL until the tournament
-- starts.
--
-- What is relational is what other tables point at, or what is looked up by something other than
-- the tournament: who has entered (entrant), who is invited (tournament_invitation), and each
-- match of a round (tournament_match), which joins to the `match` it becomes and is claimed, row by
-- row, by whoever creates that match.
CREATE TABLE tournament (
    game_id            INT    NOT NULL REFERENCES game,
    tournament_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    name               TEXT   NOT NULL,
    description        TEXT   NOT NULL DEFAULT '',
    owner              BIGINT NOT NULL REFERENCES player,
    design             JSONB  NOT NULL,
    -- The standard design it was built from, by key, for saying so; NULL for a custom one.
    standard_design    TEXT,
    state              JSONB,
    status             TEXT   NOT NULL DEFAULT 'draft'
                       CHECK (status IN ('draft', 'open', 'running', 'finished', 'cancelled')),
    auto_rounds        BOOLEAN NOT NULL DEFAULT false,
    friendly           BOOLEAN NOT NULL DEFAULT false,
    invitational       BOOLEAN NOT NULL DEFAULT true,
    late_entries       BOOLEAN NOT NULL DEFAULT false,
    min_rating         INT,
    max_rating         INT,
    entries_per_player INT    NOT NULL DEFAULT 1 CHECK (entries_per_player >= 1),
    -- The default a stage's round duration inherits (Q10); a stage may set its own.
    round_duration     INTERVAL,
    -- For a game whose players move at once and that has no max_turns of its own.
    max_turns          INT    CHECK (max_turns > 0),
    -- The round being played: when it began, and when every match of it must be over.
    round_started_at   TIMESTAMPTZ,
    round_deadline     TIMESTAMPTZ,
    -- When the owner last asked for the round's overdue matches to be checked, so that a burst of
    -- presses is one check.
    checked_at         TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    ended_at           TIMESTAMPTZ,
    create_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id),
    CHECK (min_rating IS NULL OR max_rating IS NULL OR min_rating <= max_rating)
);

CREATE INDEX tournament_owner ON tournament (owner);

CREATE TRIGGER trg_tournament_update_date
    BEFORE UPDATE ON tournament
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- Who may enter an invitational tournament. A character game's invitation names a player too: a
-- player enters with one of their characters, so it is the player who is asked.
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

CREATE TRIGGER trg_tournament_invitation_update_date
    BEFORE UPDATE ON tournament_invitation
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- An entry: a participant, in the design's word, which `participant` already means a seat in a
-- match. `player_id` is who entered. In a character game the entry is the character
-- (character_entrant), and whoever owns it when each match is created plays it, as V25's
-- invitations follow a character to its new owner.
--
-- `rating_at_entry` is what the rating limits were checked against and, until the tournament
-- starts, what it is seeded by. Withdrawing sets `withdrawn_at`; coming back clears it.
CREATE TABLE entrant (
    game_id         INT     NOT NULL,
    entrant_id      BIGINT  GENERATED ALWAYS AS IDENTITY,
    tournament_id   BIGINT  NOT NULL,
    game_type       CHAR(1) NOT NULL CHECK (game_type IN ('C', 'P')),
    player_id       BIGINT  NOT NULL REFERENCES player,
    rating_at_entry INT     NOT NULL,
    entered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at    TIMESTAMPTZ,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, entrant_id),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id)
);

CREATE INDEX entrant_tournament ON entrant (game_id, tournament_id);
CREATE INDEX entrant_player ON entrant (player_id);

CREATE TRIGGER trg_entrant_update_date
    BEFORE UPDATE ON entrant
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

CREATE TABLE character_entrant (
    game_id       INT     NOT NULL,
    entrant_id    BIGINT  NOT NULL,
    tournament_id BIGINT  NOT NULL,
    game_type     CHAR(1) NOT NULL CHECK (game_type = 'C'),
    character_id  BIGINT  NOT NULL,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, entrant_id),
    FOREIGN KEY (game_id, entrant_id) REFERENCES entrant,
    FOREIGN KEY (game_id, character_id) REFERENCES character (game_id, character_id),
    FOREIGN KEY (game_type, game_id) REFERENCES game (game_type, game_id),
    -- A character enters a tournament once.
    UNIQUE (game_id, tournament_id, character_id)
);

CREATE INDEX character_entrant_character ON character_entrant (character_id);

CREATE TRIGGER trg_character_entrant_update_date
    BEFORE UPDATE ON character_entrant
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- A match of a round, from the moment the round starts: `choosing` while its seats choose roles,
-- `due` once they have and the match is to be created, `started` once it has been, and `done` once
-- its result is in the tournament's state. Keyed by the pool and number the state gives it, so
-- that creating it twice is a key violation rather than a second match.
CREATE TABLE tournament_match (
    game_id       INT    NOT NULL,
    tournament_id BIGINT NOT NULL,
    pool_id       INT    NOT NULL,
    match_no      INT    NOT NULL,
    round         INT    NOT NULL,
    status        TEXT   NOT NULL CHECK (status IN ('choosing', 'due', 'started', 'done')),
    ties_allowed  BOOLEAN NOT NULL,
    match_id      TEXT,
    create_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, match_no),
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament,
    FOREIGN KEY (game_id, match_id) REFERENCES match,
    UNIQUE (game_id, match_id)
);

CREATE INDEX tournament_match_round ON tournament_match (game_id, tournament_id, round);

CREATE TRIGGER trg_tournament_match_update_date
    BEFORE UPDATE ON tournament_match
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- A seat of a tournament match: who sits in it, and the role they play -- NULL while it is still to
-- be chosen, `choose_order` saying when this seat's turn to choose comes. `choose_started_at` and
-- `chosen_at` are what the choice cost, charged to the seat's clock. `forfeit` is a seat that loses
-- before the match begins: a withdrawn entrant, or a second entry of a player already seated.
-- `participant_id` is the seat it became.
CREATE TABLE tournament_seat (
    game_id           INT    NOT NULL,
    tournament_id     BIGINT NOT NULL,
    pool_id           INT    NOT NULL,
    match_no          INT    NOT NULL,
    entrant_id        BIGINT NOT NULL,
    game_role_id      INT,
    choose_order      INT,
    choose_started_at TIMESTAMPTZ,
    chosen_at         TIMESTAMPTZ,
    forfeit           BOOLEAN NOT NULL DEFAULT false,
    participant_id    BIGINT,
    create_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, tournament_id, pool_id, match_no, entrant_id),
    FOREIGN KEY (game_id, tournament_id, pool_id, match_no) REFERENCES tournament_match,
    FOREIGN KEY (game_id, entrant_id) REFERENCES entrant,
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id),
    FOREIGN KEY (game_id, participant_id) REFERENCES participant
);

CREATE INDEX tournament_seat_entrant ON tournament_seat (game_id, entrant_id);

CREATE TRIGGER trg_tournament_seat_update_date
    BEFORE UPDATE ON tournament_seat
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- A match comes from a challenge or from a tournament, never both and never neither.
--
-- Checked against every existing row. Nothing deployed creates a match without a challenge; a
-- database where tests wrote such matches between V50 and this has to be rid of them first.
ALTER TABLE match ADD COLUMN tournament_id BIGINT;
ALTER TABLE match ADD CONSTRAINT match_tournament_fk
    FOREIGN KEY (game_id, tournament_id) REFERENCES tournament (game_id, tournament_id);
ALTER TABLE match ADD CONSTRAINT match_one_origin
    CHECK ((challenge_id IS NULL) <> (tournament_id IS NULL));
CREATE INDEX match_tournament ON match (game_id, tournament_id) WHERE tournament_id IS NOT NULL;

-- Ranks the tournament's owner gave a cancelled tournament match by hand (new-design.md, D12).
-- Only ever true on a cancelled tournament match; nothing that moves a rating reads a cancelled
-- match, so these rows decide the tournament and nobody's rating.
ALTER TABLE result ADD COLUMN manual BOOLEAN NOT NULL DEFAULT false;

-- A match that exists before its engine game (design/new-tournament-plan.md, Phase 2).
--
-- A tournament match is written when its pool is formed, long before the engine has a game for it:
-- `status` says where it is. 'S' scheduled, 'C' its seats choosing roles, 'D' due to be created in
-- the engine, 'P' in progress, 'F' finished -- completed or cancelled. It is the source of truth, and
-- the checks below keep the other columns from disagreeing with it. A challenge's match is created in
-- the engine as it is written, so it starts at 'P'.
ALTER TABLE match ADD COLUMN status TEXT CHECK (status IN ('S', 'C', 'D', 'P', 'F'));
UPDATE match SET status = 'F' WHERE completed IS NOT NULL OR cancelled;
UPDATE match SET status = 'P' WHERE status IS NULL;
ALTER TABLE match ALTER COLUMN status SET NOT NULL;

-- Status says that a match is over; completed_at says when.
ALTER TABLE match RENAME COLUMN completed TO completed_at;
ALTER TABLE match ADD CONSTRAINT match_finished
    CHECK ((status = 'F') = (completed_at IS NOT NULL OR cancelled));

-- Only a tournament match waits before it is played, and one not yet created in the engine has no
-- urls and no start.
ALTER TABLE match ALTER COLUMN start DROP NOT NULL;
ALTER TABLE match ADD CONSTRAINT match_waits_in_tournament
    CHECK (status NOT IN ('S', 'C', 'D') OR tournament_id IS NOT NULL);
ALTER TABLE match ADD CONSTRAINT match_not_yet_created
    CHECK (status NOT IN ('S', 'C', 'D') OR (status_url IS NULL AND play_url IS NULL AND start IS NULL));

-- A seat names its challenge, as a tournament seat will name its tournament, so that each can reach
-- its match through a key of its own origin.
CREATE UNIQUE INDEX match_game_id_challenge_id_match_id_idx ON match (game_id, challenge_id, match_id);
ALTER TABLE participant ADD COLUMN challenge_id BIGINT;
UPDATE participant p SET challenge_id = m.challenge_id
  FROM match m WHERE m.game_id = p.game_id AND m.match_id = p.match_id;
ALTER TABLE participant ADD FOREIGN KEY (game_id, challenge_id, match_id)
    REFERENCES match (game_id, challenge_id, match_id);
CREATE INDEX participant_challenge ON participant (game_id, challenge_id) WHERE challenge_id IS NOT NULL;

-- A tournament seat is written before its role is chosen and before its ratings are taken at launch.
-- A challenge seat is accepted into a role and rated as it sits down, so it always has both.
ALTER TABLE participant ALTER COLUMN game_role_id DROP NOT NULL;
ALTER TABLE participant ALTER COLUMN elo_start DROP NOT NULL;
ALTER TABLE participant ADD CONSTRAINT participant_challenge_seated
    CHECK (challenge_id IS NULL OR (game_role_id IS NOT NULL AND elo_start IS NOT NULL));
