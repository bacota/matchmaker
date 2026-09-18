-- One row per SES event we have already counted, so a redelivery cannot count it again.
--
-- V16 counted transient failures and suppressed an address at three inside a week, and the repo
-- said a duplicate was tolerable because it brought the threshold forward by one. That was wrong.
-- SQS is at-least-once by design: a visibility timeout that expires while the consumer is still
-- working, a function that times out, a batch that fails after some of its messages were written --
-- any of them redelivers a message whose effects are already in the table. With a threshold of
-- three, one duplicate turns two real delays into a suppression, and the player it silences is
-- reachable. That is not an edge case to accept; it is the ordinary behaviour of the queue.
--
-- So the count is advanced only by an event whose identity is new. The identity is computed from
-- what SES itself put in the document (see `SesEvent.identity`) rather than from anything the queue
-- adds, because a redelivered message is a new SQS message id carrying the same notification -- the
-- SQS id is exactly the thing that cannot be deduplicated on.
--
-- A table rather than a column on email_suppression, because the grain is different: one address
-- accumulates many events, and it is the event that must be unique. The uniqueness constraint is
-- the whole mechanism -- the insert and the count happen in one transaction, and a conflict on this
-- primary key is what makes the count a no-op.
CREATE TABLE email_suppression_event (
    -- Deterministic, and derived only from fields SES generates: the event type, the mail's own
    -- message id, the recipient, and whichever of feedbackId or the event timestamp the document
    -- carries. Two receives of one notification produce the same value; two genuinely different
    -- events about the same mail and the same recipient do not, because neither a feedback id nor
    -- an event timestamp repeats.
    event_id    TEXT PRIMARY KEY,

    -- Lowercased, as email_suppression.email is. No foreign key: the ordering inside the
    -- transaction happens to satisfy one, but the two tables answer different questions -- this one
    -- records that an event was seen, which stays true after a suppression row is released or, one
    -- day, deleted.
    email       TEXT        NOT NULL,

    recorded_at TIMESTAMPTZ NOT NULL
);

-- For finding what an address's history actually was, which is the question support asks, and for
-- pruning by age if this table ever grows enough to matter.
CREATE INDEX email_suppression_event_email ON email_suppression_event (email, recorded_at DESC);

-- Nothing prunes this, deliberately, and it is worth saying why rather than leaving it to be
-- discovered. A row is needed for as long as a redelivery is possible, which the bounce queue's
-- fourteen-day retention bounds -- so anything older than that is dead weight for deduplication.
-- It is kept anyway because it is the only per-event record that exists: the suppression row holds
-- a count and the latest diagnostic, and these rows are what can answer "what exactly did SES tell
-- us, and when". The volume that buys is small; bounces and complaints are rare by construction,
-- and an environment where they are not has a bigger problem than this table.
--
-- If it ever does matter, the safe prune is by age and nothing else:
--     DELETE FROM email_suppression_event WHERE recorded_at < now() - INTERVAL '30 days';
