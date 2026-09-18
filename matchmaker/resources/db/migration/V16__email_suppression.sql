-- Addresses we have stopped writing to, and why.
--
-- Until now sending was one-way. A mail went on the queue, the mailer called SES, and whatever SES
-- learned afterwards -- that the mailbox does not exist, that the person reported us as spam --
-- came back to nobody. A dead address cost three redeliveries and a message in the mail DLQ, and
-- then the next event mailed it again, forever.
--
-- SES's own account-level suppression list already stops *delivery* to an address that has bounced
-- or complained. What it cannot do is stop us queueing, stop us spending sending reputation on
-- calls that will be discarded, tell a player why they hear nothing, or treat a complaint as the
-- opt-out it plainly is. This table is the record matchmaker acts on.
--
-- Keyed by the address, not by player_id, for three reasons. SES reports an address. The address
-- that bounced may belong to no player row at all -- a Cognito account that never registered, or a
-- row whose email column has since changed. And a player who changes their address (which the
-- account screen lets them do, via Cognito) should not inherit the old one's suppression: with the
-- address as the key, the new one simply has no row, and the next mail goes out. That is the
-- primary fix for a bounce, and it works by itself.
--
-- Lowercased by the only writer. The local part of an address is technically case-sensitive and in
-- practice never is; SES reports whatever the sender wrote, and `player.email` holds whatever claim
-- Cognito issued, so the two can differ in case for the same mailbox. Folding once on the way in
-- means the lookup is an equality test on the primary key rather than a scan with lower().
CREATE TABLE email_suppression (
    email         TEXT PRIMARY KEY,

    -- 'bounce' | 'complaint' | 'delay', per `SuppressionReason`. Text under a check constraint
    -- rather than a discriminator character, as timeout_action is: the set may grow (SES has
    -- Reject, Rendering Failure and more), and the value reads as itself in a query.
    --
    -- The most serious event wins rather than the most recent: a complaint that is followed by a
    -- transient delay is still a complaint, because that is what decides both what we say to the
    -- player and whether there is a button for them to press.
    reason        TEXT        NOT NULL CHECK (reason IN ('bounce', 'complaint', 'delay')),

    -- Whether one event was enough. A complaint always is, and so is a permanent bounce: the
    -- mailbox does not exist and no amount of waiting will conjure it. A transient bounce or a
    -- delivery delay is not -- a full mailbox that empties itself should not cost a player their
    -- notifications -- so those accumulate in `occurrences` and suppress only at a threshold.
    permanent     BOOLEAN     NOT NULL,

    -- SES's own words for what went wrong, when it offered any. For the log and for support; never
    -- shown to the player, who is not the audience for "smtp; 550 5.1.1 user unknown".
    diagnostic    TEXT,

    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,

    -- How many events inside the current window. Reset to 1, not incremented, when the previous
    -- one is older than the window or when the row has been released -- which is what makes the
    -- threshold "three in seven days" rather than "three ever".
    occurrences   INTEGER     NOT NULL CHECK (occurrences > 0),

    -- When somebody said to try again: the player pressing the button on the notifications screen
    -- after fixing their mailbox. NULL means suppressed. A row is released rather than deleted so
    -- that a second failure is visible as a second failure, and so the history survives the fix.
    released_at   TIMESTAMPTZ
);

-- The one query that is not by primary key: "which of these addresses are suppressed", asked with
-- the recipients of one event. Partial, because a released row is not an answer to it and because
-- the released rows are the ones that accumulate.
CREATE INDEX email_suppression_active ON email_suppression (email) WHERE released_at IS NULL;
