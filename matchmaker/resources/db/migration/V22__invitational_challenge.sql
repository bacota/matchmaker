-- Challenges addressed to particular players.
--
-- Two facts, deliberately kept apart. `challenge.is_open` is the challenger's policy: may a
-- stranger accept this at all. `invitation` is the list of people who have been asked, which grows
-- a row at a time and is what a closed challenge is accepted from. Keeping them separate is what
-- lets an open challenge carry an invitation -- a nudge to a friend, with the seat reserved for
-- them -- and lets a closed one be re-opened without touching who has been asked.
--
-- The alternative, one nullable `opponent` column on `challenge`, was tried on paper first and
-- only works for two-player games: challenger plus opponent can never fill a roster of three.
ALTER TABLE challenge
    ADD COLUMN is_open BOOLEAN NOT NULL DEFAULT TRUE;

-- DEFAULT TRUE because that is what every challenge that already exists has been doing: anyone may
-- accept it. Same reasoning as V13's notification columns -- an existing behaviour becoming
-- explicit takes the default that names it, and only a new behaviour has to be asked for.

-- One game's challenges, and whether each is open: the two columns every listing of challenges
-- starts from.
--
-- Not partial (`WHERE is_open`), though that is the narrower index and the obvious one to reach for.
-- Measured against 22k challenges in one game, 2k of them closed: with `is_open` as a real column
-- the index answers `game_id = ? AND is_open` from the index alone, and it still serves a lookup by
-- `game_id` on its own -- which a partial index cannot, since it does not hold the closed rows at
-- all. The extra column is one byte a row for a table read far more often than written.
--
-- What neither version does is help `ChallengeRepo.listByGame` as it stands. That query has to show
-- a closed challenge to its challenger and its invitees, so its predicate is `is_open OR challenger
-- = $me OR EXISTS (invitation ...)`, and an OR cannot become an index condition: the planner takes
-- an index for `game_id` and applies the rest as a filter, exactly as it already did for
-- `started_match_id IS NULL`.
--
-- Recovering it means splitting that listing into a union of three branches -- open, mine, invited
-- -- each with an index-friendly predicate, which measures 15.6ms against the present 19.4ms on
-- that same 22k-challenge game. Two branches is not enough: leaving `is_open OR challenger = $me`
-- together in one of them blocks the index just as the single query does. It is not taken yet
-- because the select list is fifteen columns and two scalar subqueries, a union needs it written
-- three times, and drift between those copies would not be a wrong number -- it would be a
-- challenge shown to the wrong player. A real game has about three challenges. Revisit when one has
-- thousands; this index is what that change will be built on.
CREATE INDEX challenge_game_open ON challenge (game_id, is_open);

-- Who has been asked to accept a challenge, and as what.
--
-- The key is (game_id, challenge_id, player_id) because that triple *is* the fact: a second row for
-- one player in one challenge would be a second answer to one question. One player may of course
-- be invited to any number of different challenges, and a challenge may invite any number of
-- players -- which is what makes a four-player game's private match expressible, where a single
-- `opponent` column could not.
--
-- game_role_id is nullable, and the two cases are different invitations. Named, it is a seat held
-- for this player: they must accept as that role, and nobody else may take it (see
-- ChallengeService.accept, which is where that second half is enforced -- the schema cannot say
-- it, since the reservation and the acceptance are rows in different tables). NULL is "any seat
-- that is still free", which is what an invitation to a game of equals means.
--
-- No FK to acceptance and no flag saying whether the invitation has been taken up: an invitation
-- is permission, and it outlives the acceptance it leads to. A player who accepts, backs out, and
-- changes their mind again was invited throughout -- deleting the row on acceptance would lock
-- them out of a challenge they are still welcome in. The row goes when the invitation is rejected,
-- revoked, or the challenge it belongs to is deleted, and at no other time.
CREATE TABLE invitation (
    game_id      INT    NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    player_id    BIGINT NOT NULL REFERENCES player,
    game_role_id INT,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, player_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    -- The composite FK, rather than one to game_role_id alone, is what keeps a role from another
    -- game out of this table -- the same shape as acceptance's and participant's. Its columns have
    -- to be named: game_role's primary key is game_role_id by itself, and (game_id, game_role_id)
    -- is a separate unique index (V1), so an unqualified REFERENCES would aim at the wrong one.
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

-- The primary key leads with game_id, so it cannot serve "every invitation addressed to me", which
-- is what a player's home page asks on every sign-in and the only query here that is not already
-- holding a challenge in its hand.
CREATE INDEX invitation_player ON invitation (player_id);

-- V1 stamps update_date through a trigger per table, built by formatting the table name into
-- trg_%1$s_update_date. That loop has already run, so a table added afterwards gets its trigger
-- here, named the way that loop would have named it.
CREATE TRIGGER trg_invitation_update_date
    BEFORE UPDATE ON invitation
    FOR EACH ROW EXECUTE FUNCTION set_update_date();
