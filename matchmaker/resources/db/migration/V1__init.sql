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
    is_admin      BOOLEAN NOT NULL,
    external_id    TEXT NOT NULL UNIQUE,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
create index on player(nickname);
create index on player(external_id);

CREATE TABLE game (
    game_id      INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT NOT NULL,
    description  TEXT NOT NULL,
    url          TEXT NOT NULL,
    active Boolean NOT NULL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE player_game (
    game_id INT not null primary key references game
);

CREATE TABLE character_game (
    game_id INT not null primary key references game
);

CREATE TABLE character (
character_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
game_id BIGINT NOT NULL REFERENCES character_game,
name TEXT NOT NULL,
description TEXT NOT NULL,
state JSONB NOT NULL,
player_id BIGINT REFERENCES player,
create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);

create index on character(player_id);

CREATE TABLE game_role (
    game_role_id          INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id      INT NOT NULL REFERENCES game ,
    name         TEXT NOT NULL,
    optional     BOOLEAN NOT NULL,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
create index on game_role(game_role_id);

CREATE TABLE game_parameter (
    game_id      INT NOT NULL REFERENCES game,
    game_parameter_id          INT GENERATED ALWAYS AS IDENTITY,
    name         TEXT NOT NULL,
    default_value TEXT,
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

alter table game_parameter add foreign key (game_id, game_parameter_id, default_value)
references game_parameter_value;

CREATE TABLE match (
    game_id      INT NOT NULL REFERENCES game,
    match_id     TEXT NOT NULL,
    description TEXT NOT NULL default '',
    completed    BOOLEAN NOT NULL DEFAULT FALSE,
    start        TIMESTAMPTZ NOT NULL,
    time_limit   INTERVAL,
    settings     JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, match_id)
);

CREATE TABLE player_match(
    game_id      INT NOT NULL REFERENCES player_game,
    match_id     TEXT NOT NULL,
    PRIMARY KEY (game_id, match_id),
    FOREIGN KEY  (game_id, match_id) references match

);

CREATE TABLE character_match(
    game_id      INT NOT NULL REFERENCES character_game,
    match_id     TEXT NOT NULL,
    PRIMARY KEY (game_id, match_id),
    FOREIGN KEY  (game_id, match_id) references match
);

CREATE TABLE participant (
    participant_id BIGINT NOT NULL PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    game_id         INT NOT NULL,
    match_id        TEXT NOT NULL,
    player_id         BIGINT NOT NULL REFERENCES player,
    pending         BOOLEAN NOT NULL DEFAULT FALSE,
    completed   BOOLEAN NOT NULL,
    due             TIMESTAMPTZ,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (game_id, match_id) REFERENCES match
);
create index on participant(player_id);
create index on participant(game_id, match_id);
create index participant_pending on participant(game_id, match_id, player_id) where pending=true;



CREATE TABLE player_participant(
    participant_id BIGINT NOT NULL PRIMARY KEY REFERENCES participant,
    game_id         INT NOT NULL,
    match_id        TEXT NOT NULL,
    game_role_id      INT NOT NULL REFERENCES game_role,
    FOREIGN KEY (game_id, match_id) REFERENCES player_match
);
create index on player_participant(game_id, match_id);


CREATE TABLE character_participant(
    participant_id BIGINT NOT NULL PRIMARY KEY REFERENCES participant,
    game_id         INT NOT NULL,
    match_id        TEXT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES character ,
    FOREIGN KEY (game_id, match_id) REFERENCES character_match
);
create index on character_participant(game_id, match_id);
create index on character_participant(character_id);

CREATE TABLE result (
    participant_id   BIGINT NOT NULL PRIMARY KEY  REFERENCES participant,
    rank            INTEGER NOT NULL,
    score           NUMERIC NOT NULL,
    create_date     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date     TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE open_challenge (
    challenge_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenger   BIGINT NOT NULL REFERENCES player,
    message TEXT default '',
    number_of_players SMALLINT NOT NULL,
    start        TIMESTAMPTZ,
    time_limit   INTERVAL,
    settings     JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);
create index on open_challenge(challenger);

CREATE TABLE player_open_challenge(
     challenge_id  BIGINT PRIMARY KEY REFERENCES open_challenge,
     game_id INT NOT NULL REFERENCES player_game
);

CREATE TABLE character_open_challenge(
    challenge_id  BIGINT PRIMARY KEY REFERENCES open_challenge,
    character_id  BIGINT NOT NULL REFERENCES character,
     game_id INT NOT NULL REFERENCES character_game
);
create index on character_open_challenge(character_id);

CREATE TABLE acceptance (
    challenge_id      BIGINT NOT NULL REFERENCES open_challenge,
    player_id         BIGINT NOT NULL REFERENCES player,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (challenge_id, player_id)
);
create index on acceptance(player_id);


CREATE TABLE player_acceptance (
    challenge_id      BIGINT NOT NULL REFERENCES player_open_challenge,
    player_id BIGINT NOT NULL,
    PRIMARY KEY (challenge_id, player_id),
    FOREIGN KEY (challenge_id, player_id) references acceptance
);

CREATE TABLE character_acceptance (
    challenge_id      BIGINT NOT NULL  REFERENCES character_open_challenge,
    player_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES character,
    PRIMARY KEY (challenge_id, player_id, character_id),
    FOREIGN KEY (challenge_id, player_id) references acceptance
);
create index on character_acceptance(character_id);





-- ---------------------------------------------------------------------
-- Triggers: attach update_date maintenance to every table.
-- create_date is set once via column default and never modified.
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'player', 'game', 'game_role', 'game_parameter', 'game_parameter_value', 'character',
        'match', 'participant', 'result',
        'open_challenge', 'acceptance'
    ]
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_update_date
                BEFORE UPDATE ON %1$s
                FOR EACH ROW EXECUTE FUNCTION set_update_date();', t);
    END LOOP;
END $$;
