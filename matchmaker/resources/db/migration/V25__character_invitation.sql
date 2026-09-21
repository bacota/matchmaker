-- Invitations addressed to characters, for character ('C'-type) games.
--
-- V22's `invitation` names a player, which is right for a game of players and wrong for a game of
-- characters: there a challenge is offered on behalf of a character, an acceptance seats one, and a
-- role held by an invitation is a seat one will play. An invitation to the owner let them answer
-- with any character they owned, and went stale the moment `CharacterService.update` handed the
-- character to somebody else -- the new owner could not use it, and the old one held permission for
-- a character they no longer had.
--
-- So a character game's invitation names the character, and who may answer it is whoever owns that
-- character *when they answer*: the owner is read through `character.player_id` at every use, never
-- copied here, which is what makes the invitation follow a transfer without anything having to move
-- it. `invitation` keeps the plain games; the two never hold rows for the same game type, which
-- `ChallengeService` enforces (the schema cannot: neither table carries a game_type).
--
-- Otherwise the same shape and reasoning as V22's table: permission and nothing more, outliving the
-- acceptance it leads to, deleted on reject, revoke or the challenge's deletion; game_role_id NULL
-- for "any free seat" and set for a seat held for this character.
CREATE TABLE character_invitation (
    game_id      INT    NOT NULL REFERENCES game,
    challenge_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    game_role_id INT,
    create_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, challenge_id, character_id),
    FOREIGN KEY (game_id, challenge_id) REFERENCES challenge,
    -- Composite, like every other reference to a character: it is what keeps a character from
    -- another game out of this challenge.
    FOREIGN KEY (game_id, character_id) REFERENCES character (game_id, character_id),
    FOREIGN KEY (game_id, game_role_id) REFERENCES game_role (game_id, game_role_id)
);

-- "Everything addressed to my characters" joins from character.player_id to here, so the lookup
-- that has no challenge in hand is by character.
CREATE INDEX character_invitation_character ON character_invitation (character_id);

CREATE TRIGGER trg_character_invitation_update_date
    BEFORE UPDATE ON character_invitation
    FOR EACH ROW EXECUTE FUNCTION set_update_date();

-- Existing invitations in character games. One whose player owns exactly one character in that
-- game is unambiguous and moves across as an invitation to that character. Any other -- a player
-- with no character there, or several -- names no character this migration could honestly pick,
-- and is dropped: the challenger can invite the right character again.
INSERT INTO character_invitation (game_id, challenge_id, character_id, game_role_id, create_date)
SELECT i.game_id, i.challenge_id, c.character_id, i.game_role_id, i.create_date
  FROM invitation i
  JOIN game g ON g.game_id = i.game_id AND g.game_type = 'C'
  JOIN character c ON c.game_id = i.game_id AND c.player_id = i.player_id
 WHERE (SELECT count(*) FROM character c2
         WHERE c2.game_id = i.game_id AND c2.player_id = i.player_id) = 1;

DELETE FROM invitation i
 USING game g
 WHERE g.game_id = i.game_id AND g.game_type = 'C';
