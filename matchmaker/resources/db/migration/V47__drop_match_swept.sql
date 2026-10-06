-- The daily sweep that kept `swept_at` (V39) is gone: a match's ending is now a message on an SQS
-- queue, whose redelivery retries it and whose dead-letter queue holds one that can never be
-- settled, so nothing needs to remember when a match was last asked about.
ALTER TABLE match DROP COLUMN swept_at;
