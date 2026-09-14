-- Who wants to hear about what.
--
-- Every notification matchmaker sends is one of eight kinds (see `NotificationType`), and a player
-- may want some of them and not others -- and may want that answer to differ per game, and again
-- per match. The rule is a chain of defaults, asked in order of how specifically it was stated:
--
--   participant  -- this player, in this one match: the mute on a game that has got noisy
--   player_game  -- this player, in this game: "tell me about my chess, not my tic-tac-toe"
--   player       -- this player, everywhere
--   game         -- what the game's author decided its players should hear by default
--
-- Hence the column types. The three specific levels are nullable, where NULL is not "no" but "I
-- have not said" -- which is what makes the chain a chain, and what the forms' third option ("Use
-- Default") writes. The game's are NOT NULL: the chain has to end somewhere, and ending it at the
-- game rather than at a constant in the code is what lets a game that is played in bursts ship
-- quieter defaults than one played over weeks. The form that registers a game requires all eight
-- for that reason -- there is no value for the admin to leave unsaid.
--
-- Booleans per kind rather than a preferences table keyed by kind, because the set of kinds is
-- fixed by the code that sends them: a row naming a ninth kind would be a row nothing reads, and
-- the resolution above is one COALESCE per column rather than an outer join per level per kind.
-- The cost is a migration when a kind is added, which is the same migration that would be needed
-- to give the new kind a default anyway.

-- What each notification is, in `NotificationType` order. The Scala side binds these positionally
-- (`SkunkCodecs.notificationPreferences`), so the order of the columns in a statement matters,
-- though the order of the columns in the table does not.
--
--   challenge_accepted        -- somebody accepted, or backed out of, a challenge you offered
--   challenge_ready           -- every role in a challenge you offered is filled: you can start it
--   acceptance_changed        -- somebody else accepted, or backed out of, a challenge you accepted
--   accepted_challenge_ready  -- every role in a challenge you accepted is filled
--   match_started             -- a match you are in has begun
--   turn_taken                -- somebody moved in a match you are in
--   your_turn                 -- it is your turn
--   match_ended               -- a match you are in is over, or has been called off

ALTER TABLE player
    ADD COLUMN notify_challenge_accepted       BOOLEAN,
    ADD COLUMN notify_challenge_ready          BOOLEAN,
    ADD COLUMN notify_acceptance_changed       BOOLEAN,
    ADD COLUMN notify_accepted_challenge_ready BOOLEAN,
    ADD COLUMN notify_match_started            BOOLEAN,
    ADD COLUMN notify_turn_taken               BOOLEAN,
    ADD COLUMN notify_your_turn                BOOLEAN,
    ADD COLUMN notify_match_ended              BOOLEAN;

-- On `participant` rather than on (player, match): a participant *is* a player in a match, and the
-- player who holds two seats in one match wanted quiet in that match, not in one seat of it -- so
-- the writer updates every seat the player holds there. Existing rows get NULL, which is to say
-- every match already being played keeps answering from the levels below it.
ALTER TABLE participant
    ADD COLUMN notify_challenge_accepted       BOOLEAN,
    ADD COLUMN notify_challenge_ready          BOOLEAN,
    ADD COLUMN notify_acceptance_changed       BOOLEAN,
    ADD COLUMN notify_accepted_challenge_ready BOOLEAN,
    ADD COLUMN notify_match_started            BOOLEAN,
    ADD COLUMN notify_turn_taken               BOOLEAN,
    ADD COLUMN notify_your_turn                BOOLEAN,
    ADD COLUMN notify_match_ended              BOOLEAN;

-- DEFAULT TRUE for the same reason V9 defaulted `timeout_action` to 'FORFEIT': every game that
-- exists already had this decided for it by the behaviour it has been running with, and that
-- behaviour is "send it". The default stays on the column so that a game inserted by a migration
-- or a fixture is complete without naming all eight; `GameService` is what refuses an API caller
-- who leaves one unsaid.
ALTER TABLE game
    ADD COLUMN notify_challenge_accepted       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_challenge_ready          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_acceptance_changed       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_accepted_challenge_ready BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_match_started            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_turn_taken               BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_your_turn                BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_match_ended              BOOLEAN NOT NULL DEFAULT TRUE;

-- A player's preferences for one game. Its own table rather than more columns somewhere, because
-- this is the one level of the chain that is a pair: it exists only for the (player, game)
-- combinations a player has actually said something about, and a player who has said nothing about
-- a game has no row here at all -- which reads the same as a row of NULLs and costs nothing.
--
-- No surrogate key: the pair is the identity, and a second row for the same pair would be a second
-- answer to one question.
CREATE TABLE player_game (
    player_id BIGINT NOT NULL REFERENCES player,
    game_id   INT    NOT NULL REFERENCES game,
    notify_challenge_accepted       BOOLEAN,
    notify_challenge_ready          BOOLEAN,
    notify_acceptance_changed       BOOLEAN,
    notify_accepted_challenge_ready BOOLEAN,
    notify_match_started            BOOLEAN,
    notify_turn_taken               BOOLEAN,
    notify_your_turn                BOOLEAN,
    notify_match_ended              BOOLEAN,
    create_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_id, game_id)
);

-- The account form reads every row a player has, so the index that matters is the leading column
-- of the primary key, which already serves it. This one is for the other direction: a game being
-- deleted, or a per-game notification asking about its own players.
CREATE INDEX ON player_game(game_id);
