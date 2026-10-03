# Plan: Archiving Completed Matches

A finished match is copied to S3 so it can still be viewed after the engine's live copy is gone.
**Matchmaker owns the archive; the engine moves the bytes.** Matchmaker decides where each archive
goes and issues short-lived presigned S3 urls. The engine uploads and downloads with them directly,
so the payload never passes through matchmaker and no engine holds AWS credentials.

Written against the code as of `967a164`.


## Why this split

- **IAM.** Only matchmaker's role can touch the bucket. Engines, third-party ones included, get no
  AWS credentials: they hold a url good for one object for a few minutes.
- **Placement.** Matchmaker picks the key, so archives can be laid out by things the engine does
  not know: whether the match was friendly, its date, its players, a retention tier. Storage class
  and tags can be signed into the upload too, and matchmaker can move or re-tier objects later by
  server-side copy without involving the engine.
- **No payload through matchmaker.** Presigning is a local computation, with no call to AWS, so
  issuing a url costs nothing. A state of any size goes straight between the engine and S3.
- **The engine keeps the format.** The archive is the engine's own stored JSON, and only the
  engine renders it, applying its own masking. Matchmaker never parses it.

Payload size is not really the deciding reason: game states are kilobytes. What decides it is
keeping S3 out of every engine's hands and keeping the archive's layout in matchmaker's control.
Third-party games will need an engine wrapper to talk to matchmaker anyway, and these four routes
become part of that contract.


## Rules fixed from the start

- **No archive url for a browser.** An archive holds hidden information (a Stratego setup, ranks
  never revealed), so it is read only through the engine, which serves the usual per-viewer masked
  view. A presigned GET is handed to the engine and never passed on.
- **Write-once.** Once an archive is confirmed, matchmaker issues no further upload url for that
  match. Bucket versioning on; Object Lock if that needs enforcing rather than relying on matchmaker.
- **The live copy is dropped as soon as the archive is confirmed, and not before.** An engine that
  crashes between finishing a match and uploading it leaves the match pending, and it is retried.
  Nothing is lost. There is no grace period: once matchmaker confirms, the engine removes its item,
  and from then on the match is read from the archive.
- **A cancelled match's live copy is removed too**, with no archive. Matchmaker tells the engine
  when a match is cancelled (see [Cancelled matches](#cancelled-matches)).
- **Private buckets**, encrypted, all public access blocked.
- **Two buckets, by retention.** A friendly match's archive is kept for 30 days; every other
  match's is kept permanently. They go to separate buckets, and only the friendly bucket has a
  lifecycle policy, so nothing in the permanent bucket can be expired by a misconfigured rule.
  Matchmaker chooses the bucket from the match's `friendly` flag.
- **A completed match's classification is fixed.** `MatchService.setFriendly` refuses to change
  whether a completed match is friendly (409), so the bucket its archive went to is always the one
  its flag names, and an archive never has to move. A cancelled match can still be changed; it is
  not archived.
- **Only completed matches are archived.** Cancelled and abandoned matches are not.


## Flow

All four are engine routes, authenticated with the game's API key, so they go in
`local.engine_routes`, not `local.routes`. Matchmaker checks that the match belongs to the calling
engine's game, as the results callback already does.

### 1. Ask for an upload

`POST /games/{gameId}/matches/{matchId}/archive`, sent by the engine when the match finishes.

Matchmaker:
- refuses a match that is not over, and one already archived;
- picks the bucket from the match's `friendly` flag: the friendly bucket if it is friendly, the
  permanent bucket otherwise. The flag cannot change once the match is completed, so this choice
  holds;
- decides the key: `{game name}/{yyyy-mm-dd}/{matchId}.json`, for example
  `stratego/2026-10-03/3b0f6a52-8c1e-4d7a-9e21-5f4c0d8a7b19.json`. The key is laid out for a
  person browsing the bucket:
  - **A folder per game**, named by the game's `name`, its stable handle, not by `displayName`,
    which an admin may change, or by the numeric `gameId`, which means nothing to a reader.
  - **A subfolder per day** the match completed, from `completed` in UTC, so a day's matches are
    together and a folder never holds more than a day's worth.
  - **The file named by the match id**, so a match is found from the id matchmaker shows. Its
    completion date, which gives the folder, is on the same row, and `archive_key` holds the whole
    key for anyone looking it up from the database.

  Match ids are random UUIDs (`GameEngineService.start`), so two keys never collide even though
  the schema does not make a game's `name` unique.

  The key is fixed once recorded: a later rename of the game does not move its archives. The name
  goes into the key as it is if it is already safe for a key (letters, digits, `-`, `_`, `.`),
  and is percent-encoded otherwise;
- returns a **presigned POST** rather than a PUT. Its policy can cap the size
  (`content-length-range`) and pin the content type and checksum, which a presigned PUT cannot;
- records the key and an `archive_requested` time on the match.

The request body says which version of its stored format the engine wrote. It is kept as object
metadata, so a later engine version can still read an old archive.

### 2. Upload, then confirm

The engine uploads to S3 itself, then calls
`POST /games/{gameId}/matches/{matchId}/archive/confirm`.

Matchmaker checks the object exists (`HeadObject`: size and checksum match what was asked for) and
sets `archived_at`. The engine removes its live copy as soon as the confirm returns success
(see [Removing the live copy](#removing-the-live-copy)).

An S3 event notification could replace the confirm call. The explicit call is simpler to test and
to run locally, and it tells the engine in the same exchange that it may let go.

### 3. Read back

When a finished match is viewed and the engine no longer has it, the engine calls
`POST /games/{gameId}/matches/{matchId}/archive/read`, which returns a short-lived presigned GET.
The engine fetches the archive, decodes it with its normal stored-match codec, and serves the usual
masked view and replay. It may cache the decoded match for the length of the request; it does not
write it back to DynamoDB.

A POST rather than a GET because it can write: see expiry below.

### 4. Review and Watch links

The "Review game" and "Watch" buttons open the engine's own play and public urls. Once a match is
archived (`archived_at` set), matchmaker adds a query parameter to those urls, `archived=1`, so the
engine knows at once that it has no live copy and goes straight to the archive instead of missing
in DynamoDB first.

- **Added by matchmaker where it hands the urls out**, not by the UI: the match detail behind
  "Review game" and the summaries behind "Watch". The stored urls stay as the engine gave them, and
  the parameter is appended correctly whether or not the url already has a query string or a
  fragment.
- **A hint, not an instruction.** The engine trusts it only to choose where to look. A url without
  it (an old bookmark, a link opened before the archive was confirmed) still works: DynamoDB first,
  then the archive.

### 5. An expired friendly archive

A friendly archive is deleted by the bucket's lifecycle rule 30 days after it was written. S3 runs
the rule no sooner than that, but not at an exact moment: an object can outlive its 30 days by a
day or so. So past 30 days the object's existence decides, not the clock, and matchmaker checks it
before offering a link.

- **Before showing "Review game" or "Watch".** When matchmaker builds a match or a summary for a
  friendly match that was archived more than 30 days ago and has no `archive_expired_at` yet, it
  checks the object with `HeadObject`. If S3 says it is gone (404), it sets `archive_expired_at`
  and the summary carries `archiveExpired = true`; otherwise the link is shown as usual.
- **Each match is checked only until it is found gone.** Once `archive_expired_at` is set, the
  database answers and S3 is not asked again. Matches younger than 30 days and non-friendly matches
  are never checked. So the checks are few: only friendly matches in the day or so between their
  30 days ending and S3 deleting them, each time they are listed in that window.
- **A failed check shows the link.** A timeout or any answer other than 404 counts as present: a
  link that leads to the engine's "expired" page is better than hiding a match that is still there.
  Checks for one list run in parallel, with a short timeout.
- **Outside the read's transaction, then recorded on its own.** `HeadObject` is an external call, so
  it is made outside any transaction. Recording what it found is a single
  `UPDATE match SET archive_expired_at = now() WHERE ... AND archive_expired_at IS NULL`: the
  condition is in the update itself, so it needs no lock and two lists recording it at once do no
  harm.
- **The engine's read uses the same rule.** When the engine asks to read a friendly archive more
  than 30 days old, matchmaker checks the object the same way. If it is gone, matchmaker records
  that and answers 410 Gone instead of a url; if it is still there, it issues the url.
- **The engine reports a missing object too.** If a url matchmaker issued finds nothing (404), the
  engine calls `POST /games/{gameId}/matches/{matchId}/archive/expired`. Matchmaker confirms with
  `HeadObject` that the object really is gone before recording it, so a transient failure cannot
  hide a match. This covers an object deleted between matchmaker's check and the engine's download.
- **The engine tells the player.** On a 410 or a 404 it serves a page saying the match was friendly
  and its archive has expired, rather than an error.
- **Matchmaker stops offering the links.** For a match with `archiveExpired` the UI shows neither
  "Review game" nor "Watch". It says "archive expired" in their place, so the player knows why the
  button is gone.

`archiveExpired` is a new field on `Match` and `MatchSummary` with a default of `false`, so that a
body without it still decodes (a field without a default must be present on the wire).

A permanent archive never expires and is never checked this way. A 404 for one is an error to
report, not something to record.

### Cancelled matches

A cancelled match is not archived, but its live copy should not linger either. Today nothing tells
the engine a match was cancelled: `MatchService.cancel` updates matchmaker's rows and notifies the
players, and the engine's item stays until somebody deletes it.

So the engine gets a cancel call. Like the status, play and public urls, its url comes from the
engine's answer to `createGame`, as an optional `cancelUrl`; an engine that gives none is simply not
told. Matchmaker calls it after the cancel commits, outside the transaction and unable to fail the
cancel — the same terms as the notification beside it. The engine deletes its item and refuses any
further move.

An engine that is down when the match is cancelled keeps its item. Matchmaker records
`engine_released` on the match when the call succeeds, and the sweep retries cancelled matches
without it.


### Removing the live copy

Two ways: set `expiresAt` to now and let DynamoDB's TTL delete the item, or call `DeleteItem`.

TTL is not cheaper here. The deletion TTL performs is free, but setting `expiresAt` is itself a
write: an `UpdateItem` on the match's single item, charged by the item's full size, just as a
`DeleteItem` is. The table is on-demand billing and holds one item per match (`hash_key =
matchId`), so either way it is one write of the same size. TTL saves money only when the attribute
can ride on a write that is happening anyway, and the confirm is not followed by one.

TTL is also slow: DynamoDB deletes expired items within a day or two, not at once, and until then
the item is still read back. That is harmless, since it is a correct copy, but it means "removed as
soon as it is confirmed" would not be true, and the end-to-end check could not see the fallback to
the archive without waiting.

So the engine calls `DeleteItem` directly, both after a confirm and on a cancel. The TTL on
`expiresAt` stays configured but unused by this work. If a later change makes the engine write the
item once more after confirming anyway, folding `expiresAt` into that write would make TTL the
cheaper choice.


## Matchmaker changes

### Migration

`V38__match_archive.sql`, on `match`:

- `archive_key TEXT`, `archive_requested TIMESTAMPTZ`, `archived_at TIMESTAMPTZ`, and an index on
  unarchived completed matches for the sweep below;
- `archive_expired_at TIMESTAMPTZ`, set when a friendly archive is found to have expired;
- `cancel_url TEXT` and `engine_released TIMESTAMPTZ`, for cancelled matches.

No bucket column: the bucket follows from `friendly`, which is fixed by then, and bucket names stay
configuration.

### Code

- An `ArchiveStore` trait with three operations: presign an upload, presign a download, and check
  an object. Each takes which bucket, friendly or permanent. An S3 implementation using the AWS
  SDK's presigner (no network call to sign), and a local one.
- `ArchiveService`: the four engine routes — upload, confirm, read, expired. One transaction per
  call, with `FOR UPDATE` on the match row, per CLAUDE.md. Checking an object is a call outside the
  database: re-read under lock after it.
- `GameEngineClient.cancel`, and `cancelUrl` on the create response and the `match` row. Called
  from `MatchService.cancel` after its commit.
- `archived=1` appended to the play and public urls of an archived match wherever they are handed
  out, and `archiveExpired` on `Match` and `MatchSummary`.
- The existence check where matches and summaries are built (`MatchService`'s detail and list
  calls, and the public list behind "Watch"): friendly matches archived more than 30 days ago with
  no `archive_expired_at`, checked in parallel after the read, then recorded.
- UI: no "Review game" or "Watch" for a match whose archive has expired, and a line saying so.
- `Router` cases, terraform `local.engine_routes` entries, and `RouterSpec` `routed` entries — the
  three changes for each new route.

### Terraform

- Two buckets, both private, versioned, encrypted, with public access blocked.
  - **Permanent**: no lifecycle configuration at all. Object Lock, if write-once is to be enforced,
    goes here.
  - **Friendly**: one lifecycle rule. It expires current objects 30 days after creation. Because the
    bucket is versioned, expiry only adds a delete marker, so the rule also expires noncurrent
    versions (after a day) and removes expired delete markers; otherwise nothing is ever freed. No
    Object Lock: a retention period would fight the expiry.
- The matchmaker Lambda role gets `s3:PutObject`, `s3:GetObject` and `s3:GetObjectAttributes` on
  both buckets, and `s3:ListBucket` on the friendly one. Without `ListBucket`, `HeadObject` on a
  missing object answers 403 rather than 404, and an expired archive would be indistinguishable from
  a failed check — the links would never be hidden. Presigned urls act with the signer's
  permissions, so this role is the only one that needs them. Engines get nothing.
- `ARCHIVE_BUCKET` and `FRIENDLY_ARCHIVE_BUCKET` in matchmaker's environment.

### Sweep

A scheduled check for completed matches with no `archived_at` after some grace period, to report
them, or to prompt the engine through a status call. This is what catches an engine that finished a
match and never asked to archive it. It also retries the cancel call for cancelled matches with no
`engine_released`.


## Engine changes (`engines/common`)

- `GameEngine.reportResults` is already the single point where a match finishes, for every engine.
  Archiving goes beside it, best effort like the results report: ask for an upload, upload the
  stored JSON, confirm, then `DeleteItem` the match.
- The stored JSON is a pinned contract (`StoredMatchSpec`), so writing exactly that to S3 means the
  existing codec reads an archived match back unchanged.
- `MatchStore.get` falling back to the archive when DynamoDB has no item, for the read paths
  only: the board, the state routes, and replay. Moves on a finished match are refused anyway.
  With `archived=1` on the request, it skips DynamoDB and reads the archive directly.
- On 410 from matchmaker, or a 404 from S3 (reported through the expired route), a page saying the
  friendly match's archive has expired.
- A `Matchmaker` client method for each of the four routes, and its local test double.
- A cancel route, whose url goes back to matchmaker as `cancelUrl` in the create response. It
  deletes the match's item. Authenticated with the game's API key, like the other calls matchmaker
  makes to the engine.


## Local development

The local server needs an S3 stand-in so engines and tests run offline, as they do now. Either:

- MinIO in a container, using the real presigner pointed at it; or
- a small fake `ArchiveStore` that keeps objects in a directory and returns `http://localhost`
  urls the local server handles itself.

The fake keeps `local-test.sh` free of new dependencies; MinIO exercises the real signing. The fake
is enough for the test suites, and MinIO is worth it once, for the end-to-end check.


## Order of work

1. Matchmaker: migration, `ArchiveStore` with the local fake, `ArchiveService`, the four routes
   (router, terraform, `RouterSpec`), `archived=1` on the urls, `archiveExpired` and the UI, and
   tests.
2. Terraform: the two buckets, the friendly bucket's lifecycle rule, and the role's permissions.
3. `engines/common`: the client calls, archiving beside `reportResults`, the `MatchStore` fallback,
   deleting the item after a confirm, and the cancel route. All four engines pick it up together.
4. Matchmaker: `cancelUrl`, `GameEngineClient.cancel`, and the call from `MatchService.cancel`.
5. An end-to-end check against MinIO: finish a Stratego match, see its live copy removed, and replay
   it from the archive; cancel another and see its live copy removed.
6. The sweep.


## Out of scope for now

- **Messages.** Match messages (the `message-boards` branch) are not archived in this work.
