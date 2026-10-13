-- Takes the local database back to V51, so that the next test run migrates V52 and V53 afresh.
--
-- V52 is being rewritten in place (design/new-tournament-plan.md): it has never been deployed, but the
-- local database has it, and Flyway will neither re-apply a changed migration nor accept its new
-- checksum. This undoes whatever any version of V52, and V53, left here, and forgets that they ran.
--
--   PGPASSWORD=matchmaker psql -h localhost -U matchmaker -d matchmaker -X -v ON_ERROR_STOP=1 \
--       -f design/reset-tournament-schema.sql
--
-- Local test data only: every tournament match, and everything hanging off it, is deleted.

BEGIN;

-- Tournament matches and what hangs off them, found by whichever column a version of V52 marks them with.
CREATE TEMP TABLE gone (game_id INT, match_id TEXT) ON COMMIT DROP;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'match' AND column_name = 'tournament_id') THEN
        EXECUTE 'INSERT INTO gone SELECT game_id, match_id FROM match WHERE tournament_id IS NOT NULL';
    END IF;
END $$;
DELETE FROM turn t USING gone g WHERE t.game_id = g.game_id AND t.match_id = g.match_id;
DELETE FROM result r USING participant p, gone g
 WHERE r.game_id = p.game_id AND r.participant_id = p.participant_id
   AND p.game_id = g.game_id AND p.match_id = g.match_id;
DELETE FROM character_participant c USING participant p, gone g
 WHERE c.game_id = p.game_id AND c.participant_id = p.participant_id
   AND p.game_id = g.game_id AND p.match_id = g.match_id;
DROP TABLE IF EXISTS tournament_seat, tournament_match CASCADE;
DELETE FROM participant p USING gone g WHERE p.game_id = g.game_id AND p.match_id = g.match_id;
DELETE FROM match m USING gone g WHERE m.game_id = g.game_id AND m.match_id = g.match_id;

-- Every table any version of V52 creates.
DROP TABLE IF EXISTS
    seed, pool_member, atomic_pool, pool_group, pool, round,
    character_entrant, entrant, tournament_character_invitation, tournament_invitation,
    nesting_stage, atomic_stage, stage, tournament,
    tournament_model_game, nesting_stage_model, atomic_stage_model, stage_model, tournament_model
    CASCADE;

-- match, as V51 left it.
-- CASCADE: participant's foreign keys to match lean on these, and go with them.
DROP INDEX IF EXISTS match_game_id_challenge_id_match_id_idx, match_match_id_idx CASCADE;
-- CASCADE: participant's foreign keys to match name these columns, and go with them.
ALTER TABLE match
    DROP COLUMN IF EXISTS tournament_id CASCADE,
    DROP COLUMN IF EXISTS pool_id CASCADE,
    DROP COLUMN IF EXISTS round CASCADE,
    DROP COLUMN IF EXISTS stage_id CASCADE,
    DROP COLUMN IF EXISTS ties_allowed,
    DROP COLUMN IF EXISTS rubber,
    DROP COLUMN IF EXISTS status;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'match' AND column_name = 'completed_at') THEN
        ALTER TABLE match RENAME COLUMN completed_at TO completed;
    END IF;
END $$;
ALTER TABLE match ALTER COLUMN start SET NOT NULL;
-- The one-match-per-challenge index, in case a version of V52 dropped it.
CREATE UNIQUE INDEX IF NOT EXISTS match_game_id_challenge_id_idx ON match (game_id, challenge_id);

-- participant and character_participant, as V51 left them.
ALTER TABLE participant
    DROP COLUMN IF EXISTS challenge_id,
    DROP COLUMN IF EXISTS tournament_id CASCADE,
    DROP COLUMN IF EXISTS entrant_id CASCADE,
    DROP COLUMN IF EXISTS choose_order,
    DROP COLUMN IF EXISTS choose_started_at,
    DROP COLUMN IF EXISTS chosen_at,
    DROP COLUMN IF EXISTS forfeit;
ALTER TABLE participant ALTER COLUMN game_role_id SET NOT NULL;
ALTER TABLE participant ALTER COLUMN elo_start SET NOT NULL;
ALTER TABLE character_participant
    DROP COLUMN IF EXISTS tournament_id CASCADE,
    DROP COLUMN IF EXISTS entrant_id CASCADE;

-- result: today's V52 adds `manual`.
ALTER TABLE result DROP COLUMN IF EXISTS manual;

-- V53.
ALTER TABLE player DROP COLUMN IF EXISTS notify_tournament_round_done;
ALTER TABLE player_game DROP COLUMN IF EXISTS notify_tournament_round_done;

DELETE FROM flyway_schema_history WHERE version IN ('52', '53');

COMMIT;
