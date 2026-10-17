-- Whether a tournament can be made from the model: one of the formats the code defines, which a game may offer and a
-- sync compares with the code. An inner model -- the design a nesting stage's pools play -- is not. Until now having a
-- classification said so, which left a root unable to go without one; every stored model with a classification is a
-- root.
ALTER TABLE tournament_model ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT false;
UPDATE tournament_model SET is_root = classification IS NOT NULL;
