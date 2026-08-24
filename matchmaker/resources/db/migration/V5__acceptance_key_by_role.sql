-- Keys an acceptance by the role it takes rather than by the player who takes it.
--
-- The old primary key, (game_id, challenge_id, player_id), said "one acceptance per player per
-- challenge" -- a rule about people. The new one, (game_id, challenge_id, game_role_id), says
-- "one acceptance per role per challenge" -- a rule about seats, which is the thing a challenge
-- is actually made of and the thing a start waits to see filled. V4 had to add a separate unique
-- index to state it; as the primary key it needs no index of its own.
--
-- The rule the old key enforced is not gone, it has moved: a player still may not accept the same
-- challenge twice, but that is now checked in OpenChallengeService.accept (under the challenge's
-- FOR UPDATE lock, so two simultaneous attempts cannot both pass) rather than by the database.
-- Deliberately so -- it is a policy that may be relaxed, and a game where one player holds two
-- seats is a coherent thing to want. Relaxing it will then be a change to that check and nothing
-- else, rather than a second migration of this table's key.

-- ---------------------------------------------------------------------
-- character_acceptance follows the same key
-- ---------------------------------------------------------------------
--
-- It is a 1:1 extension of an acceptance row, so it is keyed the way the row it extends is. Its
-- player_id was only ever there to point back at that row, and dropping the column takes the old
-- foreign key with it -- which is also what frees the old primary key to be replaced.

ALTER TABLE character_acceptance ADD COLUMN game_role_id INT;

UPDATE character_acceptance ca
   SET game_role_id = a.game_role_id
  FROM acceptance a
 WHERE a.game_id = ca.game_id AND a.challenge_id = ca.challenge_id AND a.player_id = ca.player_id;

ALTER TABLE character_acceptance ALTER COLUMN game_role_id SET NOT NULL;
ALTER TABLE character_acceptance DROP COLUMN player_id;

-- ---------------------------------------------------------------------
-- The key itself
-- ---------------------------------------------------------------------

ALTER TABLE acceptance DROP CONSTRAINT acceptance_pkey;
-- Redundant now: V4 added it to say exactly what the new primary key says.
DROP INDEX acceptance_role_unique;
ALTER TABLE acceptance ADD PRIMARY KEY (game_id, challenge_id, game_role_id);

ALTER TABLE character_acceptance ADD PRIMARY KEY (game_id, challenge_id, game_role_id);
ALTER TABLE character_acceptance
    ADD FOREIGN KEY (game_id, challenge_id, game_role_id) REFERENCES acceptance;

-- ---------------------------------------------------------------------
-- Reading an acceptance by player
-- ---------------------------------------------------------------------
--
-- "What has this player accepted?" and "has this player already accepted this challenge?" are no
-- longer prefixes of the primary key, and the second of the two is now the only thing standing
-- between a player and two seats in one challenge -- so it runs on every accept and wants an
-- index of its own. Leading with player_id serves both: the whole key for the check, the first
-- column alone for the listing.
CREATE INDEX ON acceptance (player_id, game_id, challenge_id);

-- Superseded by the index above, whose first column it is.
DROP INDEX acceptance_player_id_idx;
