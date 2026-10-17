-- Whether a model is one a tournament can be made from, as the code defines it: what is listed, offered and synced.
-- An inner model -- the design a nesting stage's pools play -- is not. Until now a model's classification said so, which
-- left a root model unable to go without one; every stored model with a classification is a root.
ALTER TABLE tournament_model ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT false;
UPDATE tournament_model SET is_root = classification IS NOT NULL;
