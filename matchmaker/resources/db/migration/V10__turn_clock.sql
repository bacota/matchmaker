-- Turns, one row per move, and the chess-clock time limit they make possible.
--
-- Until now the only thing recorded about a turn was the deadline of the one currently being
-- taken: `participant.due`, overwritten by every move. That is enough for a per-turn limit,
-- where each turn is judged on its own, and enough for nothing else — how long a player has
-- spent across the match is a sum over turns, and there were no turns to sum.
--
-- So each turn is kept. `taken_at` is when the move was made and `started_at` is when that
-- player's clock started for it (the move before, or the match's start for the first), which
-- makes a turn's cost `taken_at - started_at` and a player's total a SUM. Both are stored rather
-- than one being derived on read: the order a turn arrives in is not always the order it was
-- made in -- the engine may report several at once after a lost callback -- and a sum that
-- depends on the row before it in the table would be wrong the moment one arrives late.
CREATE TABLE turn (
    game_id        INT NOT NULL,
    match_id       TEXT NOT NULL,
    participant_id BIGINT NOT NULL,
    taken_at       TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A seat cannot make two moves at the same instant, so this is the turn's natural identity
    -- and it is what makes recording one idempotent: the move callback and a later status call
    -- both report the same turn, and the second insert is simply dropped.
    PRIMARY KEY (game_id, participant_id, taken_at),
    FOREIGN KEY (game_id, participant_id) REFERENCES participant,
    FOREIGN KEY (game_id, match_id) REFERENCES match
);

-- The two questions asked of this table are both per match: what is the latest turn we know of
-- (which is what the engine is then asked to report past), and what has each seat spent.
CREATE INDEX ON turn (game_id, match_id, taken_at);

CREATE TRIGGER trg_turn_update_date
    BEFORE UPDATE ON turn
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- What the existing time_limit column means. PER_TURN is what it has always meant -- every turn
-- gets the whole limit, and the clock resets on each move -- so that is the default and no
-- existing row changes behaviour. TOTAL is a chess clock: the limit is the player's budget for
-- the entire match, and every turn they take spends part of it.
--
-- On both tables for the same reason time_limit itself is on both: the challenger decides it
-- when they offer the challenge, and the match carries it forward so that changing a challenge
-- afterwards cannot change a match already being played under it.
--
-- Text under a check constraint rather than a PG enum, as with game.timeout_action, because the
-- set is expected to grow (a Fischer increment is the obvious next one) and 'TOTAL' reads as
-- itself in a query.
ALTER TABLE open_challenge
    ADD COLUMN time_limit_kind TEXT NOT NULL DEFAULT 'PER_TURN'
        CHECK (time_limit_kind IN ('PER_TURN', 'TOTAL'));

ALTER TABLE match
    ADD COLUMN time_limit_kind TEXT NOT NULL DEFAULT 'PER_TURN'
        CHECK (time_limit_kind IN ('PER_TURN', 'TOTAL'));
