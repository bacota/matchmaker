-- Whether the match a challenge becomes is friendly (V36), decided when the challenge is offered
-- and copied to the match when it starts, like the challenge's other terms. Only a game's admin
-- may offer one that is not; see ChallengeService.create.
ALTER TABLE challenge ADD COLUMN friendly BOOLEAN NOT NULL DEFAULT true;
