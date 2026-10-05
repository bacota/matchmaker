package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, ZoneOffset}
import scala.concurrent.duration._
import com.vivi.matchmaker.archive.{ArchiveBucket, ArchiveStore, SignedDownload, SignedUpload}
import com.vivi.matchmaker.archive.ArchiveBucket.isFriendly
import com.vivi.matchmaker.model.{GameId, Match, MatchId, MatchSummary}
import com.vivi.matchmaker.persistence.ArchiveRepo
import com.vivi.matchmaker.persistence.ArchiveRepo.ArchiveRow

/** A match's archive copied to the permanent bucket ahead of saying the match is not friendly: see
  * [[ArchiveService.prepareMove]].
  */
case class PreparedMove(gameId: GameId, matchId: MatchId, key: String)

/** What an engine is told when it asks to upload: a url to upload to, or that the match is already archived — in which
  * case its live copy is redundant and it may drop it.
  */
enum UploadAnswer {
    case Upload(signed: SignedUpload)
    case AlreadyArchived(archivedAt: Instant)
}

/** Completed matches' archives in S3 (archiving-matches-plan.md).
  *
  * Matchmaker owns the archive and the engine moves the bytes. The engine asks for a signed url, uploads its stored
  * match to it, and confirms; matchmaker checks what arrived and records it, and from then on the engine may drop its
  * live copy. When a finished match is viewed, the engine asks for a signed url to read the archive back. Matchmaker
  * never reads an archive: it holds what the engine hides from players, and only the engine knows how to mask it.
  *
  * The four engine calls are authorized as the game, like the engine's other callbacks — but found by match id and the
  * caller's external id rather than by game id, because an engine whose live copy is gone no longer knows which of
  * matchmaker's games the match belongs to. A match that is not the caller's game's is not found, as one that does not
  * exist.
  *
  * A friendly match's archive is in a bucket that expires it after [[FriendlyRetention]]. S3 runs its lifecycle rules
  * no sooner than that but not at an exact moment, so past that age the object's existence decides, not the clock:
  * [[settle]] checks it before a list offers a Review or Watch link, and [[download]] before it signs a url.
  */
class ArchiveService(
    sessionPool: SessionPool,
    store: ArchiveStore,
    now: () => Instant = () => Instant.now(),
    /** How long [[settle]] waits on any one check before showing the link anyway. */
    checkTimeout: FiniteDuration = 2.seconds
) {
    import ArchiveService._

    /** Asks for a url to upload match `matchId`'s archive to: `size` bytes whose SHA-256 is `sha256`, base64.
      *
      * Refused for a match that is not over, and for a cancelled one, which is not archived. A match already archived
      * is answered as such rather than refused, so that an engine retrying after a lost confirm learns it may drop its
      * copy. A second request before the confirm signs a fresh url for the same key: the first may simply have run out.
      */
    def requestUpload(
        matchId: MatchId,
        size: Long,
        sha256: String,
        formatVersion: Option[String],
        callerExternalId: String
    ): IO[UploadAnswer] =
        for {
            _ <- IO.raiseUnless(size > 0 && size <= MaxSize)(
              ValidationError(s"an archive must be between 1 and $MaxSize bytes, not $size")
            )
            _ <- IO.raiseUnless(isSha256(sha256))(
              ValidationError("sha256 must be the base64 of a 32-byte SHA-256 digest")
            )
            _ <- IO.raiseWhen(formatVersion.exists(v => v.length > 64 || !v.forall(c => c >= ' ' && c <= '~')))(
              ValidationError("formatVersion must be at most 64 printable ASCII characters")
            )
            decided <- sessionPool.use { session =>
                val repo = new ArchiveRepo(session)
                session.transaction.use { _ =>
                    for {
                        row <- requireForUpdate(repo, matchId, callerExternalId)
                        _ <- IO.raiseWhen(row.cancelled)(
                          ConflictError(s"match ${matchId.value} was cancelled, and a cancelled match is not archived")
                        )
                        completedAt <- IO.fromOption(row.completedAt)(
                          ConflictError(s"match ${matchId.value} is not over, and only a completed match is archived")
                        )
                        answer <- row.archivedAt match {
                            case Some(at) => IO.pure(Left(at))
                            case None     =>
                                // The key is fixed the first time it is chosen: a game renamed meanwhile does
                                // not move an archive half-way through being made.
                                val key = row.key.getOrElse(keyFor(row.gameName, completedAt, matchId))
                                // The bucket the url is signed for, recorded with it (V44): the match's as
                                // it stands now, and where `confirm` will look.
                                repo
                                    .recordRequest(row.gameId, matchId, key, sha256, row.friendly)
                                    .as(Right((row, key)))
                        }
                    } yield answer
                }
            }
            answer <- decided match {
                case Left(at) => IO.pure(UploadAnswer.AlreadyArchived(at))
                // Signed after the commit: signing is not a network call, but nothing outside the
                // database belongs inside one of its transactions.
                case Right((row, key)) =>
                    store
                        .signUpload(ArchiveBucket.of(row.friendly), key, size, sha256, formatVersion)
                        .map(UploadAnswer.Upload(_))
            }
        } yield answer

    /** The engine says it has uploaded match `matchId`'s archive. Matchmaker checks that the object is there and is the
      * one asked for, records it, and answers when it was archived — the engine's permission to drop its live copy.
      *
      * The check is a call to S3, so it is made between two reads rather than inside a transaction, and what it found
      * is held against the match as it stands under the lock afterwards: a second request may have asked for a
      * different upload meanwhile, and only the one most recently asked for is accepted.
      *
      * Three borrowings of the pool rather than one: no connection is held while S3 answers, so a slow check cannot tie
      * up the connections the API's other requests need.
      */
    def confirm(matchId: MatchId, callerExternalId: String): IO[Instant] =
        sessionPool
            .use(session => require(new ArchiveRepo(session), matchId, callerExternalId))
            .flatMap { before =>
                before.archivedAt match {
                    case Some(at) => IO.pure(at)
                    case None =>
                        for {
                            key <- IO.fromOption(before.key)(
                              ConflictError(s"no upload was asked for for match ${matchId.value}")
                            )
                            found <- store.head(bucketOf(before), key)
                            at <- sessionPool.use { session =>
                                val repo = new ArchiveRepo(session)
                                session.transaction.use { _ =>
                                    for {
                                        row <- requireForUpdate(repo, matchId, callerExternalId)
                                        at <- row.archivedAt match {
                                            case Some(at) => IO.pure(at)
                                            case None =>
                                                for {
                                                    _ <- IO.raiseUnless(row.key.contains(key))(
                                                      ConflictError(
                                                        s"the upload asked for for match ${matchId.value} changed while it was being checked"
                                                      )
                                                    )
                                                    arrived <- IO.fromOption(found)(
                                                      ConflictError(
                                                        s"no archive has arrived for match ${matchId.value}"
                                                      )
                                                    )
                                                    // A store that reports no checksum is trusted on the signature
                                                    // alone: the upload url was signed for this checksum, so nothing
                                                    // else could have been accepted under it.
                                                    _ <- IO.raiseWhen(
                                                      arrived.sha256.exists(s => !row.sha256.contains(s))
                                                    )(
                                                      ConflictError(
                                                        s"the archive that arrived for match ${matchId.value} is not the one asked for"
                                                      )
                                                    )
                                                    at <- repo.recordArchived(row.gameId, matchId)
                                                } yield at
                                        }
                                    } yield at
                                }
                            }
                        } yield at
                }
            }

    /** A url the engine may read match `matchId`'s archive from, to show a finished match whose live copy is gone.
      *
      * A friendly archive past its retention is checked first, and if it has gone, that is recorded and the engine is
      * told 410 so that it can say so; matchmaker stops offering links to it.
      */
    def download(matchId: MatchId, callerExternalId: String): IO[SignedDownload] =
        sessionPool
            .use(session => require(new ArchiveRepo(session), matchId, callerExternalId))
            .flatMap { row =>
                for {
                    archivedAt <- IO.fromOption(row.archivedAt)(
                      NotFoundError(s"match ${matchId.value} has no archive")
                    )
                    key <- IO.fromOption(row.key)(NotFoundError(s"match ${matchId.value} has no archive"))
                    _ <- IO.raiseWhen(row.expiredAt.isDefined)(expired(matchId))
                    bucket = bucketOf(row)
                    _ <-
                        if (mayHaveExpired(bucket.isFriendly, archivedAt, now()))
                            store.head(bucket, key).flatMap {
                                case Some(_) => IO.unit
                                case None    => recordExpired(row) *> IO.raiseError(expired(matchId))
                            }
                        else IO.unit
                    signed <- store.signDownload(bucket, key)
                } yield signed
            }

    /** The engine found nothing at a url [[download]] signed. For a friendly archive, matchmaker checks that it really
      * is gone — a failed download is not proof — and records it. A permanent archive never expires, so one that is
      * missing is a fault, refused here so the engine reports it rather than hiding the match.
      */
    def reportExpired(matchId: MatchId, callerExternalId: String): IO[Unit] =
        sessionPool
            .use(session => require(new ArchiveRepo(session), matchId, callerExternalId))
            .flatMap { row =>
                for {
                    key <- IO.fromOption(row.key.filter(_ => row.archivedAt.isDefined))(
                      NotFoundError(s"match ${matchId.value} has no archive")
                    )
                    _ <- IO.raiseUnless(bucketOf(row).isFriendly)(
                      ConflictError(
                        s"match ${matchId.value}'s archive is permanent and does not expire; a missing one is a fault"
                      )
                    )
                    _ <-
                        if (row.expiredAt.isDefined) IO.unit
                        else
                            store.head(ArchiveBucket.Friendly, key).flatMap {
                                case Some(_) =>
                                    IO.raiseError(ConflictError(s"match ${matchId.value}'s archive is still there"))
                                case None => recordExpired(row)
                            }
                } yield ()
            }

    /** Lists' half of the expiry: for each friendly summary archived longer ago than its retention and not yet known to
      * have expired, checks whether the archive is still there, and marks the ones that are not. Every other summary
      * passes through untouched.
      *
      * Bounded, because a list is unbounded and this is on a player's request: at most [[ChecksPerRequest]] matches are
      * checked, [[CheckParallelism]] at a time. A match left unchecked keeps its link for now, and the next list it is
      * in checks it — once one is found gone it is recorded, so a long history is worked through over a few loads and
      * then costs nothing.
      *
      * A check that fails or takes too long counts as present. A link to the engine's "expired" page is a small cost;
      * hiding a match whose archive is still there is not.
      */
    def settle(summaries: List[MatchSummary]): IO[List[MatchSummary]] = {
        val at = now()
        val due = summaries.filter(s => !s.archiveExpired && s.archivedAt.exists(mayHaveExpired(s.friendly, _, at)))
        if (due.isEmpty) IO.pure(summaries)
        else
            IO.parTraverseN(CheckParallelism)(due.take(ChecksPerRequest))(s =>
                expiredNow(s.gameId, s.matchId).map(gone => (s.gameId, s.matchId) -> gone)
            ).map { found =>
                val gone = found.collect { case (id, true) => id }.toSet
                summaries.map(s => if (gone((s.gameId, s.matchId))) markExpired(s) else s)
            }
    }

    /** [[settle]], for the one match a player is about to open. */
    def settle(m: Match): IO[Match] =
        if (m.archiveExpired || !m.archivedAt.exists(mayHaveExpired(m.friendly, _, now()))) IO.pure(m)
        else expiredNow(m.gameId, m.matchId).map(gone => if (gone) markExpired(m) else m)

    /* Whether the archive is gone, recording it if so. False for anything that cannot be decided --
     * no archive, a failed or slow check -- so that only a definite answer hides a link. */
    private def expiredNow(gameId: GameId, matchId: MatchId): IO[Boolean] =
        sessionPool
            .use(session => new ArchiveRepo(session).read(gameId, matchId))
            .flatMap {
                case Some(row) if row.expiredAt.isDefined => IO.pure(true)
                case Some(row @ ArchiveRow(_, _, _, _, _, _, Some(key), _, _, Some(_), _, _)) =>
                    store
                        .head(bucketOf(row), key)
                        .timeout(checkTimeout)
                        .flatMap {
                            case Some(_) => IO.pure(false)
                            case None    => recordExpired(row).as(true)
                        }
                case _ => IO.pure(false)
            }
            .handleErrorWith(e =>
                IO.blocking(System.err.println(s"archive check for match ${matchId.value} failed: $e")).as(false)
            )

    /** The first step of moving a match's archive from the friendly bucket to the permanent one (V44), when a game's
      * admin says a match archived as friendly was not: the copy, made before anything about the match changes, and
      * answered with what [[recordMove]] and [[finishMove]] then need. Nothing to copy — no archive yet, an archive
      * already permanent, or one already known to have expired — is `None`.
      *
      * Before the change rather than after it, and in the same request: if the copy fails, so does the request, and
      * nothing has changed. Until the change commits, readers are sent to the original, which is untouched.
      *
      * An archive the friendly bucket has already expired cannot be copied; that is recorded, and there is nothing to
      * move.
      */
    def prepareMove(gameId: GameId, matchId: MatchId): IO[Option[PreparedMove]] =
        sessionPool.use(session => new ArchiveRepo(session).read(gameId, matchId)).flatMap {
            case Some(row @ ArchiveRow(_, _, _, _, _, _, Some(key), _, _, Some(_), None, _))
                if bucketOf(row).isFriendly =>
                store.copy(ArchiveBucket.Friendly, ArchiveBucket.Permanent, key).flatMap {
                    case true  => IO.pure(Some(PreparedMove(gameId, matchId, key)))
                    case false => recordExpired(row).as(None)
                }
            case _ => IO.pure(None)
        }

    /** The second step: records, in the caller's transaction — the one that says the match is not friendly, holding its
      * lock — that its archive is now the permanent copy, and answers whether it did.
      *
      * Re-read under that lock, since time has passed since the copy. An archive confirmed in the friendly bucket in
      * between was never copied, and the request is refused to be tried again rather than leave it there to expire. An
      * upload asked for and not yet made is pointed at the permanent bucket instead: the url the engine holds is for
      * the friendly one, so its confirm finds nothing and it uploads again — prompted by the listener settling the
      * match's ending, which goes on asking until the match is archived (`EndingService`), if not before.
      */
    def recordMove(
        session: skunk.Session[IO],
        prepared: Option[PreparedMove],
        gameId: GameId,
        matchId: MatchId
    ): IO[Boolean] = {
        val repo = new ArchiveRepo(session)
        repo.read(gameId, matchId).flatMap {
            case Some(row) if row.key.isDefined && row.expiredAt.isEmpty && bucketOf(row).isFriendly =>
                (row.archivedAt, prepared) match {
                    case (Some(_), Some(_)) => repo.recordMoved(gameId, matchId, friendly = false).as(true)
                    case (Some(_), None) =>
                        IO.raiseError(
                          ConflictError(s"match ${matchId.value}'s archive was being written just now; try again")
                        )
                    case (None, _) => repo.recordMoved(gameId, matchId, friendly = false).as(false)
                }
            case _ => IO.pure(false)
        }
    }

    /** The last step, once the change has committed: the friendly original is deleted. Safe because moves only ever go
      * the other way — nothing can make the friendly copy the recorded one again. A delete that fails is logged and not
      * raised: the change has been made, and the friendly bucket's own expiry will remove the original within 30 days.
      *
      * There is no step for a change that failed after the copy. Its permanent copy is left where it is, unrecorded: a
      * concurrent change of the same match may have recorded that very object, under the same key, and deleting it
      * would delete the archive. Left, it costs a few kilobytes, and the next move overwrites it.
      */
    def finishMove(prepared: PreparedMove): IO[Unit] =
        store
            .remove(ArchiveBucket.Friendly, prepared.key)
            .handleErrorWith(e =>
                IO.blocking(
                  System.err.println(
                    s"could not delete the friendly original of match ${prepared.matchId.value}'s archive, " +
                        s"which its bucket will expire: $e"
                  )
                )
            )

    private def recordExpired(row: ArchiveRow): IO[Unit] =
        sessionPool.use(session => new ArchiveRepo(session).recordExpired(row.gameId, row.matchId))

    private def expired(matchId: MatchId): GoneError =
        GoneError(s"match ${matchId.value} was friendly, and its archive has expired")

    private def require(repo: ArchiveRepo, matchId: MatchId, callerExternalId: String): IO[ArchiveRow] =
        repo.readForEngine(matchId, callerExternalId).flatMap(found(matchId))

    private def requireForUpdate(repo: ArchiveRepo, matchId: MatchId, callerExternalId: String): IO[ArchiveRow] =
        repo.readForEngineForUpdate(matchId, callerExternalId).flatMap(found(matchId))

    // Not found rather than unauthorized for a match that is another game's: an engine is told
    // nothing about matches that are not its own, including that they exist.
    private def found(matchId: MatchId)(row: Option[ArchiveRow]): IO[ArchiveRow] =
        IO.fromOption(row)(NotFoundError(s"no match '${matchId.value}' in this engine's game"))
}

object ArchiveService {

    /** The bucket the archive is in (V44), or for a row from before that was recorded, the one its friendliness says —
      * which is where every such archive went, since a match could not change it then.
      */
    def bucketOf(row: ArchiveRow): ArchiveBucket = ArchiveBucket.of(row.archiveFriendly.getOrElse(row.friendly))

    /** How long a friendly match's archive is kept: the friendly bucket's lifecycle rule. */
    val FriendlyRetention: Duration = Duration.ofDays(30)

    /** How many possibly expired archives one list checks in S3, and how many of those checks run at once. */
    val ChecksPerRequest: Int = 25
    val CheckParallelism: Int = 8

    /** The longest a key segment may be. S3 allows 1,024 bytes for a whole key; this keeps a key a person can read, and
      * far inside that whatever a game is called.
      */
    val MaxSegment: Int = 100

    /** The largest archive accepted. Game states are kilobytes; this is a ceiling against a mistake, not a budget. */
    val MaxSize: Long = 16L * 1024 * 1024

    /** Whether an archive this old may have been expired by its bucket — which only a friendly one ever is. */
    def mayHaveExpired(friendly: Boolean, archivedAt: Instant, at: Instant): Boolean =
        friendly && archivedAt.plus(FriendlyRetention).isBefore(at)

    /** Where a match's archive goes: a folder per game, a subfolder per day it completed (UTC), and a file named by the
      * match id — laid out for a person browsing the bucket, who has a match id and a game in hand.
      *
      * The game's `name` is its stable handle: not `displayName`, which an admin may change, and not the numeric id,
      * which means nothing to a reader. Characters that are not safe in a key are percent-encoded, and a segment longer
      * than [[MaxSegment]] once encoded is cut short and given a hash of the whole, so that two long names that begin
      * alike still get folders of their own.
      */
    def keyFor(gameName: String, completedAt: Instant, matchId: MatchId): String = {
        val day = completedAt.atZone(ZoneOffset.UTC).toLocalDate
        s"${bounded(gameName)}/$day/${bounded(matchId.value)}.json"
    }

    private def bounded(raw: String): String = {
        val encoded = segment(raw)
        if (encoded.length <= MaxSegment) encoded
        else {
            val hash = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8))
                .take(8)
                .map(b => f"${b & 0xff}%02x")
                .mkString
            // Cut where it will not split a %XX escape in two.
            val cut = encoded.take(MaxSegment - hash.length - 1)
            val whole = cut.lastIndexOf('%') match {
                case i if i >= 0 && i > cut.length - 3 => cut.take(i)
                case _                                 => cut
            }
            s"$whole-$hash"
        }
    }

    private def segment(raw: String): String =
        if (
          raw.nonEmpty && raw.forall(c => c.isLetterOrDigit && c < 128 || c == '-' || c == '_' || c == '.') &&
          raw != "." && raw != ".."
        )
            raw
        else URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20").replace(".", "%2E")

    private def isSha256(value: String): Boolean =
        try java.util.Base64.getDecoder.decode(value).length == 32
        catch { case _: IllegalArgumentException => false }

    /** A summary whose archive has expired: no link to it any more. */
    def markExpired(s: MatchSummary): MatchSummary = s.copy(archiveExpired = true, publicUrl = None)

    def markExpired(m: Match): Match = m.copy(archiveExpired = true, playUrl = None, publicUrl = None)

    /** `url` with `archived=1` added to its query, before any fragment: tells the engine at once that the match is
      * archived and its live copy gone, so it reads the archive without missing in its own store first. A hint only —
      * the engine reads the archive without it too.
      */
    def markArchived(url: String): String = {
        val (beforeFragment, fragment) = url.indexOf('#') match {
            case -1 => (url, "")
            case i  => (url.substring(0, i), url.substring(i))
        }
        val separator =
            if (!beforeFragment.contains("?")) "?"
            else if (beforeFragment.endsWith("?") || beforeFragment.endsWith("&")) ""
            else "&"
        s"$beforeFragment${separator}archived=1$fragment"
    }

    /** A summary as a player is shown it: an expired archive's link gone, and an archived match's watch link marked as
      * one.
      */
    def forViewer(s: MatchSummary): MatchSummary =
        if (s.archiveExpired) markExpired(s)
        else if (s.archivedAt.isDefined) s.copy(publicUrl = s.publicUrl.map(markArchived))
        else s

    def forViewer(m: Match): Match =
        if (m.archiveExpired) markExpired(m)
        else if (m.archivedAt.isDefined)
            m.copy(playUrl = m.playUrl.map(markArchived), publicUrl = m.publicUrl.map(markArchived))
        else m
}
