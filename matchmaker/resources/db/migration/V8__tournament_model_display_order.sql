-- Where a model is listed among the formats a game offers, lowest first: the create form's drop-down and the admins'
-- list of formats. Ties are broken by name. A sync writes it from the code; an inner model, never listed, keeps 0.
ALTER TABLE tournament_model ADD COLUMN display_order INT NOT NULL DEFAULT 0;
