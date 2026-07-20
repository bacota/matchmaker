-- Matchmaker data model DDL (Postgres / Aurora Serverless)

-- ---------------------------------------------------------------------
-- Common trigger machinery: every table gets create_date and update_date
-- columns that are maintained by triggers rather than application code.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION set_update_date()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_date := now();
    NEW.create_date := OLD.create_date;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------

CREATE TABLE player (
    player_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nickname     TEXT NOT NULL UNIQUE,
    cognito_id   TEXT NOT NULL UNIQUE,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
create index on player(nickname);
create index on player(cognito_id);

CREATE TABLE game (
    game_id      INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT NOT NULL,
    description  TEXT NOT NULL,
    url          TEXT NOT NULL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE role (
    game_id      INT NOT NULL REFERENCES game,
    role_id          INT GENERATED ALWAYS AS IDENTITY,
    name         TEXT NOT NULL,
    optional     BOOLEAN NOT NULL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, role_id)
);


CREATE TABLE game_parameter (
    game_id      INT NOT NULL REFERENCES game,
    game_parameter_id          INT GENERATED ALWAYS AS IDENTITY,
    name         TEXT NOT NULL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_parameter_id)
);

CREATE TABLE game_parameter_value (
    game_id              INT NOT NULL,
    game_parameter_id  INT NOT NULL,
    value                TEXT NOT NULL,
    create_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, game_parameter_id, value),
    FOREIGN KEY (game_id, game_parameter_id) REFERENCES game_parameter
);

CREATE TABLE match (
    game_id      INT NOT NULL REFERENCES game,
    match_id     TEXT NOT NULL,
    completed    BOOLEAN NOT NULL DEFAULT FALSE,
    start        TIMESTAMPTZ NOT NULL,
    time_limit   INTERVAL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, match_id)
);

CREATE TABLE match_setting (
    game_id               INT NOT NULL,
    match_id              TEXT NOT NULL,
    game_parameter_id   INT NOT NULL,
    value  TEXT NOT NULL,
    create_date           TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, game_parameter_id),
    FOREIGN KEY (game_id, match_id) REFERENCES match,
    FOREIGN KEY (game_id, game_parameter_id, value)  REFERENCES game_parameter_value
);


CREATE TABLE participant (
    game_id         INT NOT NULL,
    match_id        TEXT NOT NULL,
    role_id    INT NOT NULL,
    player_id         BIGINT NOT NULL REFERENCES player,
    pending         BOOLEAN NOT NULL DEFAULT FALSE,
    due             TIMESTAMPTZ,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (game_id, match_id) REFERENCES match,
    FOREIGN KEY (game_id, role_id) REFERENCES role,
    PRIMARY KEY (game_id, match_id, role_id)
);
create index on participant(player_id);
create index participant_pending on participant(game_id, match_id, player_id) where pending=true;

CREATE TABLE result (
    game_id         INT NOT NULL,
    match_id        TEXT NOT NULL,
    role_id    INT NOT NULL,
    player_id         BIGINT NOT NULL REFERENCES player,
    rank            INTEGER NOT NULL,
    score           NUMERIC NOT NULL,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, match_id, role_id),
    FOREIGN KEY (game_id, match_id, role_id) references participant
);


CREATE TABLE open_challenge (
    challenge_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id      INT NOT NULL REFERENCES game,
    challenger   BIGINT NOT NULL REFERENCES player,
    message TEXT,
    number_of_players SMALLINT NOT NULL,
    start        TIMESTAMPTZ,
    time_limit   INTERVAL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
create index on open_challenge(challenger);

CREATE TABLE challenge_setting (
    game_id               INT NOT NULL REFERENCES GAME,
    challenge_id              BIGINT NOT NULL REFERENCES OPEN_CHALLENGE,
    game_parameter_id   INT NOT NULL,
    value  TEXT NOT NULL,
    create_date           TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (challenge_id, game_parameter_id),
    FOREIGN KEY (game_id, game_parameter_id, value)  REFERENCES game_parameter_value
);


CREATE TABLE acceptance (
    challenge_id      BIGINT NOT NULL REFERENCES OPEN_challenge,
    player_id      BIGINT NOT NULL REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (challenge_id, player_id)
);

create index on acceptance(player_id);

-- ---------------------------------------------------------------------
-- Triggers: attach update_date maintenance to every table.
-- create_date is set once via column default and never modified.
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'player', 'game', 'role', 'game_parameter', 'game_parameter_value',
        'match', 'match_setting', 'participant', 'result',
        'open_challenge', 'challenge_setting', 'acceptance'
    ]
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_update_date
                BEFORE UPDATE ON %1$s
                FOR EACH ROW EXECUTE FUNCTION set_update_date();', t);
    END LOOP;
END $$;
