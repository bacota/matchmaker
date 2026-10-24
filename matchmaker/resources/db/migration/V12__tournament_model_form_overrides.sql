-- A model's own wording of the competition form while it is the format chosen: a JSON object from a caption's or a
-- tip's key, 'labels.competition.poolSize' or 'tips.competition.poolSize', to the words used instead. Written by a
-- sync from the code's StandardModel.formOverrides; '{}' rewords nothing.
ALTER TABLE tournament_model ADD COLUMN form_overrides JSONB NOT NULL DEFAULT '{}';
