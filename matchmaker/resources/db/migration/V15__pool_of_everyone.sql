-- A format whose one pool is everyone entered (StandardModel.hasPoolOfEveryone), written by a sync from the code. A
-- tournament made from one with no pool size asked for has none until it starts, when it is set to how many have
-- entered; any other keeps the size it was made with.
ALTER TABLE tournament_model ADD COLUMN has_pool_of_everyone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE tournament ALTER COLUMN default_pool_size DROP NOT NULL;
