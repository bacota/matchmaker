package com.vivi.engine

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal
import upickle.default.{read, write, ReadWriter}

/** A finished match whose archive has expired: a friendly match, kept for 30 days. Raised by a read, and answered by
  * the routes as a 410 — a page saying so, where a page was asked for.
  */
class ArchiveExpired(val matchId: String)
    extends RuntimeException(s"match '$matchId' was a friendly match, and its archive has expired")

/** Moving an archive's bytes to and from the urls matchmaker signs. No credentials: the signature is in the url and its
  * headers, and it is matchmaker's.
  */
trait ArchiveTransfer {

    /** Sends `body` as `upload` says. Raises unless the store accepted it. */
    def upload(upload: Protocol.ArchiveUpload, body: Array[Byte]): Unit

    /** The archive at `url`, or `None` if there is nothing there. Raises for any other failure. */
    def download(url: String): Option[Array[Byte]]
}

object ArchiveTransfer {

    /** The SHA-256 of `bytes`, base64 — the form matchmaker and S3 take it in. */
    def sha256(bytes: Array[Byte]): String =
        Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}

class HttpArchiveTransfer(
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    // One step of an archive, which ArchivingMatchStore's budget is counted in. See there.
    timeout: Duration = Duration.ofSeconds(10)
) extends ArchiveTransfer {

    /* The JDK client sets these itself, from the url and the body, and refuses to be told them. The
     * values it sets are the ones signed: the host is the url's, and the length is the body's, which
     * is the length the upload was asked for. */
    private val restricted = Set("host", "content-length", "connection", "expect", "upgrade")

    def upload(upload: Protocol.ArchiveUpload, body: Array[Byte]): Unit = {
        val builder = HttpRequest.newBuilder(URI.create(upload.url)).timeout(timeout)
        upload.headers.foreach { (name, value) =>
            if (!restricted(name.toLowerCase)) builder.header(name, value)
        }
        val request = builder.method(upload.method, HttpRequest.BodyPublishers.ofByteArray(body)).build()
        val response = send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode / 100 != 2)
            throw AwsError(s"${upload.method} of an archive returned ${response.statusCode}: ${response.body}")
    }

    def download(url: String): Option[Array[Byte]] = {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
        val response = send(request, HttpResponse.BodyHandlers.ofByteArray())
        response.statusCode match {
            case 200 => Some(response.body)
            case 404 => None
            case status =>
                throw AwsError(s"GET of an archive returned $status: ${String(response.body, StandardCharsets.UTF_8)}")
        }
    }

    private def send[T](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
        try httpClient.send(request, handler)
        catch { case e: Exception => throw AwsError(s"${request.method} of an archive failed: ${e.getMessage}", e) }
}

/** S3 in a map, for the tests and for a local engine with no matchmaker to archive through. Holds an upload to the
  * checksum it was signed for, as S3 does.
  */
class InMemoryArchiveTransfer extends ArchiveTransfer {
    private val objects = ConcurrentHashMap[String, Array[Byte]]()

    def urlOf(matchId: String): String = s"memory://archive/$matchId"

    def upload(upload: Protocol.ArchiveUpload, body: Array[Byte]): Unit = {
        upload.headers.get("x-amz-checksum-sha256").foreach { expected =>
            if (expected != ArchiveTransfer.sha256(body)) throw AwsError("the body does not match the signed checksum")
        }
        objects.put(upload.url, body)
    }

    def download(url: String): Option[Array[Byte]] = Option(objects.get(url))

    def bytesAt(url: String): Option[Array[Byte]] = Option(objects.get(url))

    /** Removes an archive, as the friendly bucket's lifecycle rule does after 30 days. */
    def expire(matchId: String): Unit = objects.remove(urlOf(matchId))
}

/** A match store that archives finished matches to matchmaker's S3, and reads them back from there once the live copy
  * is gone.
  *
  * Wraps the store an engine already has, so that no game changes: the live store keeps every match being played, as it
  * always did, and this adds two things.
  *
  *   - [[finished]]: once a match is over and matchmaker has its result, its stored JSON is uploaded, matchmaker
  *     confirms it arrived, and the live copy is deleted — at once, and only then. Anything short of the confirm leaves
  *     the live copy where it is, to be archived the next time something asks (matchmaker's sweep, through a status
  *     call).
  *   - [[get]]: a match not in the live store is read from the archive. An archived match is over and never changes, so
  *     what is read is decoded with the same codec that wrote it (the stored JSON is pinned by `StoredMatchSpec`) and
  *     kept for a few minutes: a play page polls its state every two seconds.
  *
  * Writes never reach the archive. [[modify]] reads only the live store, so a move on an archived match finds no match
  * — which is the answer a move on a finished one gets anyway.
  *
  * @param formatVersion
  *   this engine's name for the format of what it uploads, kept with each archive so that a later engine can still tell
  *   what it is reading
  */
class ArchivingMatchStore[M <: HasMatchId: ReadWriter](
    live: MatchStore[M],
    matchmaker: Matchmaker,
    matchmakerUrl: String,
    transfer: ArchiveTransfer,
    formatVersion: String = ArchivingMatchStore.FormatVersion,
    cacheFor: Duration = Duration.ofMinutes(5),
    now: () => Instant = () => Instant.now(),
    budget: Duration = ArchivingMatchStore.Budget
) extends MatchStore[M] {

    private val cache = ConcurrentHashMap[String, (Instant, M)]()

    def get(matchId: String): Option[M] = live.get(matchId).orElse(fromArchive(matchId))

    /** The archive first, for a request that says the match is archived (`archived=1`): its live copy is gone, and
      * asking the live store first would be a read that is certain to miss.
      */
    override def getArchived(matchId: String): Option[M] = fromArchive(matchId).orElse(live.get(matchId))

    def create(m: M): Unit = live.create(m)

    def modify[A](matchId: String)(f: M => (Option[M], A)): Option[A] = live.modify(matchId)(f)

    override def delete(matchId: String): Unit = {
        live.delete(matchId)
        cache.remove(matchId)
    }

    /** Archives the finished match `matchId`, if its live copy is still here, and then deletes the live copy.
      *
      * The copy uploaded is the live store's, read now: it is the match as it was last written, which for a finished
      * match is how it ended. Matchmaker answering that it already has the archive — a confirm that was lost, or a
      * second request racing this one — is as good as a confirm.
      *
      * Raises if any step fails, and leaves the live copy: the caller decides whether that matters. Every caller here
      * treats it as best-effort.
      *
      * Bounded by [[budget]], because it runs inside the request that made the final move — a player is waiting on the
      * answer, and the gateway in front gives up at 30 seconds. No step is started once the budget is spent, and each
      * is limited to ten seconds by its client, so the whole is at most the budget and one step more. One cut short
      * leaves the live copy, and matchmaker's daily sweep prompts the engine to finish it. The delete after a confirm
      * is not subject to it: once matchmaker has the archive, nothing would prompt the engine again, and the live copy
      * would stay for good.
      */
    override def finished(matchId: String): Unit =
        live.get(matchId).foreach { m =>
            val deadline = now().plus(budget)
            def withinBudget(step: String): Unit =
                if (!now().isBefore(deadline))
                    throw AwsError(s"archiving match '$matchId' ran out of time before $step; the sweep will finish it")
            val body = write(m).getBytes(StandardCharsets.UTF_8)
            val answer = matchmaker.requestArchiveUpload(
              matchmakerUrl,
              matchId,
              Protocol.ArchiveUploadRequest(body.length.toLong, ArchiveTransfer.sha256(body), Some(formatVersion))
            )
            answer.upload match {
                case None if answer.archivedAt.isDefined => ()
                case None => throw AwsError(s"matchmaker answered the archive request for '$matchId' with nothing")
                case Some(upload) =>
                    withinBudget("the upload")
                    transfer.upload(upload, body)
                    withinBudget("the confirm")
                    matchmaker.confirmArchive(matchmakerUrl, matchId)
            }
            live.delete(matchId)
        }

    /* The archived match, from the cache if it was read lately. A match matchmaker has no archive of
     * is not found; one whose archive has expired is raised as such, and one whose download finds
     * nothing is reported to matchmaker -- which decides whether that is an expiry -- before it is. */
    private def fromArchive(matchId: String): Option[M] = {
        val at = now()
        Option(cache.get(matchId)).filter((until, _) => at.isBefore(until)).map(_._2).orElse {
            matchmaker.readArchive(matchmakerUrl, matchId) match {
                case ArchiveLocation.NotArchived => None
                case ArchiveLocation.Expired     => throw ArchiveExpired(matchId)
                case ArchiveLocation.At(download) =>
                    transfer.download(download.url) match {
                        case Some(bytes) =>
                            val m = read[M](String(bytes, StandardCharsets.UTF_8))
                            remember(matchId, at, m)
                            Some(m)
                        case None =>
                            // Matchmaker checks before believing it, and refuses it outright for an archive
                            // that is permanent -- in which case this is a fault, and is raised as one.
                            matchmaker.reportArchiveExpired(matchmakerUrl, matchId)
                            throw ArchiveExpired(matchId)
                    }
            }
        }
    }

    private def remember(matchId: String, at: Instant, m: M): Unit = {
        // A handful of finished matches being looked at, per container. Cleared rather than evicted
        // one by one: a crude bound, and an archive read again costs one download.
        if (cache.size >= ArchivingMatchStore.CacheSize) cache.clear()
        cache.put(matchId, (at.plus(cacheFor), m))
    }
}

object ArchivingMatchStore {

    /** The format an engine's archives are in: its stored match JSON, as `StoredMatchSpec` pins it. A change to that
      * shape that an old archive could not be read under must change this too.
      */
    val FormatVersion: String = "stored-match-1"

    val CacheSize: Int = 64

    /** How long archiving may hold up the request that ended the match: see [[ArchivingMatchStore.finished]]. */
    val Budget: Duration = Duration.ofSeconds(15)

    /** Archiving around `live`, through matchmaker at `matchmakerUrl`; or `live` alone when there is no matchmaker to
      * archive through. Failures to reach it at construction cannot happen: nothing is asked until a match ends.
      */
    def around[M <: HasMatchId: ReadWriter](
        live: MatchStore[M],
        matchmaker: Matchmaker,
        matchmakerUrl: Option[String],
        transfer: => ArchiveTransfer = HttpArchiveTransfer()
    ): MatchStore[M] =
        matchmakerUrl.fold(live)(url => ArchivingMatchStore(live, matchmaker, url, transfer))

    /** Logs a failure to archive, which leaves the live copy in place and the match still readable. */
    private[engine] def bestEffort(matchId: String)(archive: => Unit): Unit =
        try archive
        catch { case NonFatal(e) => Log.failure(e, s"archiving match '$matchId'") }
}

/** What a player following a link to a finished friendly match is shown once its archive has gone. Every engine's,
  * since what happened is the same whatever the game. Escaped, though a match id is matchmaker's UUID.
  */
object ArchiveExpiredPage {
    def apply(matchId: String): String = {
        val id = matchId.flatMap {
            case '<' => "&lt;"
            case '>' => "&gt;"
            case '&' => "&amp;"
            case '"' => "&quot;"
            case c   => c.toString
        }
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>archive expired</title>
<style>
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         font: 16px/1.5 ui-sans-serif, system-ui, sans-serif; color-scheme: light dark; }
  main { max-width: 32rem; padding: 1rem; text-align: center; }
  .detail { opacity: 0.75; overflow-wrap: anywhere; }
</style>
</head>
<body>
<main>
<h1>This match is no longer available</h1>
<p>It was a friendly match, and friendly matches are kept for 30 days after they finish. Its record has expired.</p>
<p class="detail">Match $id</p>
</main>
</body>
</html>
"""
    }
}
