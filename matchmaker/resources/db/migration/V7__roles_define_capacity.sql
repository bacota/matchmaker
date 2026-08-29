-- A challenge's size is its game's roles.
--
-- number_of_players let a challenge ask for a number its game's roles could not seat, and said
-- nothing about which of them would be played. Every acceptance names a role and no two
-- acceptances of a challenge may name the same one (V5), so the roles already answer both
-- questions: a challenge is full when every role is taken, and startable when every required
-- one is.
ALTER TABLE open_challenge DROP COLUMN number_of_players;

-- min_players and max_players were the same idea one level up, and no more able to express it:
-- two acceptances of a two-player game are not a playable match if both asked for the same role.
-- What a game needs is now exactly its non-optional roles.
ALTER TABLE game DROP COLUMN min_players;
ALTER TABLE game DROP COLUMN max_players;
