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
Third-party games will need an engine wrapper to talk to matchmaker anyway, and these three routes
become part of that contract.


## Rules fixed from the start

- **No archive url for a browser.** An archive holds hidden information (a Stratego setup, ranks
  never revealed), so it is read only through the engine, which serves the usual per-viewer masked
  view. A presigned GET is handed to the engine and never passed on.
- **Write-once.** Once an archive is confirmed, matchmaker issues no further upload url for that
  match. Bucket versioning on; Object Lock if that needs enforcing rather than relying on matchmaker.
- **The live copy is dropped only after the archive is confirmed.** An engine that crashes between
  finishing a match and uploading it leaves the match pending, and it is retried. Nothing is lost.
- **Private bucket**, encrypted, all public access blocked.


## Flow

All three are engine routes, authenticated with the game's API key, so they go in
`local.engine_routes`, not `local.routes`. Matchmaker checks that the match belongs to the calling
engine's game, as the results callback already does.

### 1. Ask for an upload

`POST /games/{gameId}/matches/{matchId}/archive`, sent by the engine when the match finishes.

Matchmaker:
- refuses a match that is not over, and one already archived;
- decides the key, e.g. `{gameExternalId}/{yyyy}/{mm}/{matchId}.json`, prefixed or tagged by
  whatever the archive should be organized by (friendly, retention tier);
- returns a **presigned POST** rather than a PUT. Its policy can cap the size
  (`content-length-range`) and pin the content type and checksum, which a presigned PUT cannot;
- records the key and an `archive_requested` time on the match.

The request body says which version of its stored format the engine wrote. It is kept as object
metadata, so a later engine version can still read an old archive.

### 2. Upload, then confirm

The engine uploads to S3 itself, then calls
`POST /games/{gameId}/matches/{matchId}/archive/confirm`.

Matchmaker checks the object exists (`HeadObject`: size and checksum match what was asked for) and
sets `archived_at`. Only then may the engine drop its live copy, by setting `expiresAt` on the
DynamoDB item.

An S3 event notification could replace the confirm call. The explicit call is simpler to test and
to run locally, and it tells the engine in the same exchange that it may let go.

### 3. Read back

When a finished match is viewed and the engine no longer has it,
`GET /games/{gameId}/matches/{matchId}/archive` returns a short-lived presigned GET. The engine
fetches the archive, decodes it with its normal stored-match codec, and serves the usual masked
view and replay. It may cache the decoded match for the length of the request; it does not write
it back to DynamoDB.


## Matchmaker changes

### Migration

`V38__match_archive.sql`: on `match`, `archive_key TEXT`, `archive_requested TIMESTAMPTZ`,
`archived_at TIMESTAMPTZ`, plus an index on unarchived completed matches for the sweep below.

### Code

- An `ArchiveStore` trait with three operations: presign an upload, presign a download, and check
  an object. An S3 implementation using the AWS SDK's presigner (no network call to sign), and a
  local one.
- `ArchiveService`: the three operations above. One transaction per call, with `FOR UPDATE` on the
  match row, per CLAUDE.md. Issuing a url and checking an object are calls outside the database:
  re-read under lock after them.
- `Router` cases, terraform `local.engine_routes` entries, and `RouterSpec` `routed` entries — the
  three changes for each new route.
- Optionally, a `Match` field or `MatchSummary` flag saying a match is archived, if the UI should
  say so.

### Terraform

- The bucket: private, versioned, encrypted, public access blocked, with a lifecycle rule for
  retention and transitions to colder storage.
- The matchmaker Lambda role gets `s3:PutObject`, `s3:GetObject` and `s3:GetObjectAttributes` on
  the bucket. Presigned urls act with the signer's permissions, so this role is the only one that
  needs them. Engines get nothing.
- `ARCHIVE_BUCKET` in matchmaker's environment.

### Sweep

A scheduled check for completed matches with no `archived_at` after some grace period, to report
them, or to prompt the engine through a status call. This is what catches an engine that finished a
match and never asked to archive it.


## Engine changes (`engines/common`)

- `GameEngine.reportResults` is already the single point where a match finishes, for every engine.
  Archiving goes beside it, best effort like the results report: ask for an upload, upload the
  stored JSON, confirm, then set `expiresAt`.
- The stored JSON is a pinned contract (`StoredMatchSpec`), so writing exactly that to S3 means the
  existing codec reads an archived match back unchanged.
- `MatchStore.get` falling back to the archive when DynamoDB has no item, for the read paths
  only: the board, the state routes, and replay. Moves on a finished match are refused anyway.
- A `Matchmaker` client method for each of the three routes, and its local test double.
- The match table's TTL is already configured on `expiresAt` (`terraform/modules/engine/main.tf`);
  nothing writes it yet. This is what starts writing it.


## Local development

The local server needs an S3 stand-in so engines and tests run offline, as they do now. Either:

- MinIO in a container, using the real presigner pointed at it; or
- a small fake `ArchiveStore` that keeps objects in a directory and returns `http://localhost`
  urls the local server handles itself.

The fake keeps `local-test.sh` free of new dependencies; MinIO exercises the real signing. The fake
is enough for the test suites, and MinIO is worth it once, for the end-to-end check.


## Open questions

- **Unfinished matches.** A match nobody finishes never completes, so archiving on completion never
  reaches it. Either archive abandoned matches on a timeout, or accept that only completed matches
  are kept.
- **Messages.** On the `message-boards` branch, messages are in their own table with a one-year
  expiry. If they belong with the match's archive, that work must copy them too, either into the
  same object or as a sibling object under the same key prefix.
- **Cancelled matches.** They have no result. Archive them or not?
- **Retention.** How long archives are kept, and whether friendly and non-friendly matches are kept
  differently. This decides the key layout and the lifecycle rules.
- **Grace before eviction.** How long after confirmation the live copy lingers (the `expiresAt`
  value). A short delay keeps a page that is still open working without a fetch from S3.


## Order of work

1. Matchmaker: migration, `ArchiveStore` with the local fake, `ArchiveService`, the three routes
   (router, terraform, `RouterSpec`), and tests.
2. Terraform: the bucket and the role's permissions.
3. `engines/common`: the client calls, archiving beside `reportResults`, the `MatchStore` fallback,
   and writing `expiresAt`. All four engines pick it up together.
4. An end-to-end check against MinIO: finish a Stratego match, let its live copy expire, and replay
   it from the archive.
5. The sweep.
