-- Every seat now names the role it plays.
--
-- game_role_id was nullable on both acceptance and participant, which made "which role is this
-- player taking?" a question with three answers: a real role, no role, or a role the game does
-- not define. A challenge could then be started with a seat that named no role at all, and the
-- engine was told nothing rather than something -- fine for a game whose seats are
-- interchangeable, useless for one whose seats are not (tic-tac-toe's X and O). Making the
-- column mandatory collapses that to one answer, and is what lets a start be refused until every
-- required role of the game is actually taken.
--
-- Consequence worth stating plainly: a game with no roles at all can no longer have challenges,
-- because there is no role for its acceptances to name. Every game must now define at least one.

-- ---------------------------------------------------------------------
-- Existing rows
-- ---------------------------------------------------------------------

-- An acceptance with no role cannot be given one after the fact -- nobody chose it -- and an
-- acceptance is a transient offer, not history, so the roleless ones go. Their challenges go with
-- them: a challenge whose challenger's acceptance has been deleted is not a challenge anyone can
-- accept or start.
DELETE FROM character_acceptance ca
 USING acceptance a
 WHERE ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
   AND a.game_role_id IS NULL;

DELETE FROM acceptance WHERE game_role_id IS NULL;

DELETE FROM character_open_challenge cc
 WHERE NOT EXISTS (
   SELECT 1 FROM acceptance a WHERE a.game_id = cc.game_id AND a.challenge_id = cc.challenge_id
 );

DELETE FROM open_challenge oc
 WHERE NOT EXISTS (
   SELECT 1 FROM acceptance a WHERE a.game_id = oc.game_id AND a.challenge_id = oc.challenge_id
 );

-- Participants are history: a match that was played was played, and deleting its seats would
-- throw away results that reference them. So instead each affected game gets one optional
-- 'unassigned' role, and the roleless seats are pointed at it. It records what is actually known
-- -- that nobody chose a role -- rather than inventing a side for someone who never picked one.
-- Optional, so it is never a role a start waits to see filled.
INSERT INTO game_role (game_id, name, optional)
SELECT DISTINCT p.game_id, 'unassigned', true
  FROM participant p
 WHERE p.game_role_id IS NULL;

UPDATE participant p
   SET game_role_id = (
     SELECT r.game_role_id FROM game_role r
      WHERE r.game_id = p.game_id AND r.name = 'unassigned'
      ORDER BY r.game_role_id LIMIT 1
   )
 WHERE p.game_role_id IS NULL;

-- ---------------------------------------------------------------------
-- Constraints
-- ---------------------------------------------------------------------

ALTER TABLE acceptance ALTER COLUMN game_role_id SET NOT NULL;
ALTER TABLE participant ALTER COLUMN game_role_id SET NOT NULL;

-- Two players in one challenge cannot take the same role: if they could, "every role is filled"
-- would be satisfiable while a role sat empty. The service checks this too, so that a second
-- taker of a role gets a 409 explaining itself rather than a constraint violation.
--
-- Deliberately not mirrored on participant: participants are made from acceptances, which this
-- already constrains, and the legacy 'unassigned' seats above would violate it.
CREATE UNIQUE INDEX acceptance_role_unique ON acceptance (game_id, challenge_id, game_role_id);
