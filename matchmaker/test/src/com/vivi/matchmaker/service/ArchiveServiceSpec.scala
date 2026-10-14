package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Duration, Instant}
import munit.FunSuite
import skunk.implicits._
import skunk.codec.all.{int4, int8, text}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.archive.{ArchiveBucket, ArchiveStore, LocalArchiveStore}
import com.vivi.matchmaker.engine.{CreateGameRequest, CreateGameResponse, GameEngineClient, GameStatusResponse}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.TestMatches
import com.vivi.matchmaker.persistence.{
    ArchiveRepo,
    CharacterRepo,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    PlayerRepo,
    TestSession
}

/** Archiving a completed match: the engine's four calls, and what a player is shown of an archived match afterwards.
  *
  * The store is the local one, over a temporary directory: it holds an upload to the size and checksum it was signed
  * for, as S3 does, and a file deleted from it is an archive the friendly bucket has expired.
  */
class ArchiveServiceSpec extends FunSuite {
    TestMigration.ensure()

    private val dir = Files.createTempDirectory("archive-spec")
    private val store = LocalArchiveStore(dir = dir, baseUrl = "http://localhost:0")

    private object NoEngine extends GameEngineClient {
        def createGame(url: String, key: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.raiseError(new IllegalStateException("no engine in these tests"))
        def status(url: String, key: Option[String], since: Option[Instant]): IO[GameStatusResponse] =
            IO.raiseError(new IllegalStateException("no engine in these tests"))
    }

    private val services = TestServices.servicesWith(engine = NoEngine, archiveStore = store)
    private val archives = services.archives

    private val completedAt = Instant.parse("2026-10-03T12:00:00Z")

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    /** A player, a game whose engine is `engineId`, and one match of it — completed, unless told otherwise. */
    private case class Fixture(player: Player, game: Game, matchId: MatchId, character: Character[String])

    private def fixture(
        completed: Boolean = true,
        friendly: Boolean = true,
        cancelled: Boolean = false,
        isPublic: Boolean = false
    ): IO[Fixture] =
        TestSession.resource.use { session =>
            for {
                player <- services.registration.register(nickname = unique("archiver"), externalId = unique("sub"))
                game <- new GameRepo[String](session).create(
                  Game(
                    gameId = GameId.unassigned,
                    gameType = GameType.Character,
                    name = "stratego",
                    displayName = "Stratego",
                    description = "description",
                    url = "url",
                    isActive = true,
                    Seq(
                      GameRole(
                        gameRoleId = GameRoleId(0),
                        gameId = GameId.unassigned,
                        name = "only",
                        roleType = RoleType.Required,
                        displayName = "only"
                      )
                    ),
                    Seq.empty,
                    unique("engine")
                  )
                )
                character <- new CharacterRepo[String](session).create(
                  Character(
                    characterId = CharacterId(0),
                    gameId = game.gameId,
                    name = "character",
                    description = "description",
                    state = "",
                    playerId = Some(player.playerId)
                  )
                )
                matchId = com.vivi.matchmaker.persistence.Generators.newMatchId()
                _ <- TestMatches.writeMatch(
                  session = session,
                  m = Match(
                    gameId = game.gameId,
                    matchId = matchId,
                    tournamentId = TournamentId.unassigned,
                    description = "description",
                    completedAt = Option.when(completed)(completedAt),
                    start = Some(Instant.ofEpochSecond(1000)),
                    timeLimit = None,
                    settings = "{}",
                    isPublic = isPublic,
                    isCancelled = cancelled,
                    playUrl = Some("https://engine.example.com/matches/m/play"),
                    publicUrl = Option.when(isPublic)("https://engine.example.com/matches/m/board?view=1"),
                    isFriendly = friendly
                  ),
                  owner = player.playerId
                )
                _ <- TestMatches.writeSeat(
                  session = session,
                  p = CharacterParticipant(
                    participantId = ParticipantId(0),
                    gameId = game.gameId,
                    matchId = matchId,
                    playerId = player.playerId,
                    isPending = false,
                    isCompleted = completed || cancelled,
                    None,
                    character.characterId,
                    game.roles.head.gameRoleId
                  ),
                  eloStart = EloRating.initial
                )
            } yield Fixture(player = player, game = game, matchId = matchId, character = character)
        }

    /** Another completed, friendly, public match of `f`'s game for `f`'s player. */
    private def addCompletedMatch(f: Fixture): IO[MatchId] =
        TestSession.resource.use { session =>
            for {
                matchId <- IO(com.vivi.matchmaker.persistence.Generators.newMatchId())
                _ <- TestMatches.writeMatch(
                  session = session,
                  m = Match(
                    gameId = f.game.gameId,
                    matchId = matchId,
                    tournamentId = TournamentId.unassigned,
                    description = "description",
                    completedAt = Some(completedAt),
                    start = Some(Instant.ofEpochSecond(1000)),
                    timeLimit = None,
                    settings = "{}",
                    isPublic = true,
                    publicUrl = Some("https://engine.example.com/matches/m/board")
                  ),
                  owner = f.player.playerId
                )
                _ <- TestMatches.writeSeat(
                  session = session,
                  p = CharacterParticipant(
                    participantId = ParticipantId(0),
                    gameId = f.game.gameId,
                    matchId = matchId,
                    playerId = f.player.playerId,
                    isPending = false,
                    isCompleted = true,
                    None,
                    f.character.characterId,
                    f.game.roles.head.gameRoleId
                  ),
                  eloStart = EloRating.initial
                )
            } yield matchId
        }

    private val content = """{"matchId":"m","board":[1,2,3]}""".getBytes(StandardCharsets.UTF_8)

    /** Asks for an upload of `body`, and makes it as an engine would: to the url, with the headers. */
    private def upload(f: Fixture, body: Array[Byte] = content): IO[Unit] =
        archives
            .requestUpload(
              matchId = f.matchId,
              size = body.length.toLong,
              sha256 = ArchiveStore.sha256(body),
              formatVersion = Some("v1"),
              callerExternalId = f.game.externalId
            )
            .flatMap {
                case UploadAnswer.Upload(signed) =>
                    IO(store.put(path = signed.url.split("/local-archive/")(1), body = body, headers = signed.headers))
                        .flatMap {
                            case Right(())    => IO.unit
                            case Left(reason) => IO.raiseError(new IllegalStateException(reason))
                        }
                case other => IO.raiseError(new IllegalStateException(s"expected an upload url, got $other"))
            }

    private def archived(f: Fixture): IO[Fixture] = upload(f) *> archives.confirm(f.matchId, f.game.externalId).as(f)

    private def row(f: Fixture): IO[Option[ArchiveRepo.ArchiveRow]] =
        TestSession.resource.use(session => new ArchiveRepo(session).read(f.game.gameId, f.matchId))

    /** Backdates the archive by `days`, as if it had been made that long ago. */
    private def age(f: Fixture, days: Int): IO[Unit] =
        TestSession.resource.use { session =>
            session
                .execute(
                  sql"""UPDATE match SET archived_at = archived_at - make_interval(days => $int4)
                    WHERE game_id = $int4 AND match_id = $int8""".command
                )((days, f.game.gameId.value, f.matchId.value))
                .void
        }

    private def run[A](io: IO[A]): A = io.timeout(30.seconds).unsafeRunSync()

    private def refusal[A](io: IO[A]): Throwable =
        run(io.attempt).swap.getOrElse(fail("expected the call to be refused"))

    test("an engine uploads, confirms, and can then read its match back; a second request finds it archived") {
        val f = run(fixture())
        run(upload(f))
        val confirmed = run(archives.confirm(f.matchId, f.game.externalId))
        val stored = run(row(f)).get
        assertEquals(stored.archivedAt, Some(confirmed))
        assertEquals(stored.key, Some(s"stratego/2026-10-03/${f.matchId.value}.json"))

        val download = run(archives.download(f.matchId, f.game.externalId))
        assertEquals(store.get(download.url.split("/local-archive/")(1)).map(_.toSeq), Some(content.toSeq))

        // A lost confirm, retried from the top: the engine is told it may let its copy go.
        val again =
            run(
              archives.requestUpload(
                matchId = f.matchId,
                size = 3,
                sha256 = ArchiveStore.sha256("abc".getBytes),
                formatVersion = None,
                callerExternalId = f.game.externalId
              )
            )
        assertEquals(again, UploadAnswer.AlreadyArchived(confirmed))
        // And a repeated confirm answers the same.
        assertEquals(run(archives.confirm(f.matchId, f.game.externalId)), confirmed)
    }

    test("a friendly match goes to the friendly bucket, and any other to the permanent one") {
        val friendly = run(fixture(friendly = true).flatMap(archived))
        val permanent = run(fixture(friendly = false).flatMap(archived))
        val key = (f: Fixture) => run(row(f)).flatMap(_.key).get
        assert(run(store.head(ArchiveBucket.Friendly, key(friendly))).isDefined)
        assert(run(store.head(ArchiveBucket.Permanent, key(friendly))).isEmpty)
        assert(run(store.head(ArchiveBucket.Permanent, key(permanent))).isDefined)
    }

    test("only a completed match of the caller's own game is archived") {
        val running = run(fixture(completed = false))
        assert(refusal(upload(running)).isInstanceOf[ConflictError])

        val cancelled = run(fixture(completed = false, cancelled = true))
        assert(refusal(upload(cancelled)).isInstanceOf[ConflictError])

        val theirs = run(fixture())
        val other = run(fixture())
        val sha = ArchiveStore.sha256(content)
        assert(
          refusal(
            archives.requestUpload(
              matchId = theirs.matchId,
              size = 10,
              sha256 = sha,
              formatVersion = None,
              callerExternalId = other.game.externalId
            )
          )
              .isInstanceOf[NotFoundError]
        )
    }

    test("an upload request is held to a sane size and a real checksum") {
        val f = run(fixture())
        val sha = ArchiveStore.sha256(content)
        assert(
          refusal(
            archives.requestUpload(
              matchId = f.matchId,
              size = 0,
              sha256 = sha,
              formatVersion = None,
              callerExternalId = f.game.externalId
            )
          ).isInstanceOf[ValidationError]
        )
        assert(
          refusal(
            archives.requestUpload(
              matchId = f.matchId,
              size = ArchiveService.MaxSize + 1,
              sha256 = sha,
              formatVersion = None,
              callerExternalId = f.game.externalId
            )
          )
              .isInstanceOf[ValidationError]
        )
        assert(
          refusal(
            archives.requestUpload(
              matchId = f.matchId,
              size = 10,
              sha256 = "not-a-digest",
              formatVersion = None,
              callerExternalId = f.game.externalId
            )
          )
              .isInstanceOf[ValidationError]
        )
    }

    test("a confirm with nothing uploaded, or something other than what was asked for, is refused") {
        val f = run(fixture())
        assert(refusal(archives.confirm(f.matchId, f.game.externalId)).isInstanceOf[ConflictError])

        run(
          archives.requestUpload(
            matchId = f.matchId,
            size = 10,
            sha256 = ArchiveStore.sha256(content),
            formatVersion = None,
            callerExternalId = f.game.externalId
          )
        )
        assert(refusal(archives.confirm(f.matchId, f.game.externalId)).isInstanceOf[ConflictError])

        // The store refuses a body that is not the one whose checksum was signed.
        val sha = ArchiveStore.sha256(content)
        val UploadAnswer.Upload(signed) =
            run(
              archives.requestUpload(
                matchId = f.matchId,
                size = 3,
                sha256 = sha,
                formatVersion = None,
                callerExternalId = f.game.externalId
              )
            ): @unchecked
        assert(
          store
              .put(path = signed.url.split("/local-archive/")(1), body = "abc".getBytes, headers = signed.headers)
              .isLeft
        )
        assertEquals(run(row(f)).flatMap(_.archivedAt), None)
    }

    private def where(f: Fixture): (Boolean, Boolean) = {
        val key = run(row(f)).flatMap(_.key).get
        (
          run(store.head(ArchiveBucket.Friendly, key)).isDefined,
          run(store.head(ArchiveBucket.Permanent, key)).isDefined
        )
    }

    private def makeAdmin(f: Fixture): Unit =
        run(TestSession.resource.use(session => new PlayerRepo(session).update(f.player.copy(isAdmin = true))))

    private def notFriendly(f: Fixture): IO[Match] =
        services.matches.setFriendly(gameId = f.game.gameId, matchId = f.matchId, friendly = false, f.player.externalId)

    test("a completed match an admin says was not friendly after all has its archive moved to the permanent bucket") {
        val f = run(fixture(friendly = true).flatMap(archived))
        makeAdmin(f)
        run(notFriendly(f))
        assertEquals(run(row(f)).flatMap(_.isInFriendlyBucket), Some(false))
        assertEquals(where(f), (false, true))
        val download = run(archives.download(f.matchId, f.game.externalId))
        assertEquals(store.get(download.url.split("/local-archive/")(1)).map(_.toSeq), Some(content.toSeq))
    }

    test("a completed match an admin says was friendly after all keeps its archive in the permanent bucket, for good") {
        val f = run(fixture(friendly = false).flatMap(archived))
        makeAdmin(f)
        run(
          services.matches
              .setFriendly(gameId = f.game.gameId, matchId = f.matchId, friendly = true, f.player.externalId)
        )
        assertEquals(run(row(f)).flatMap(_.isInFriendlyBucket), Some(false))
        assertEquals(where(f), (false, true))
        // And it does not expire: past a friendly archive's 30 days, it is read without a check.
        run(age(f, 31))
        val download = run(archives.download(f.matchId, f.game.externalId))
        assert(download.url.contains("/local-archive/permanent/"), download.url)
    }

    test("a caller who may not make the change has nothing copied for them") {
        val f = run(fixture(friendly = true).flatMap(archived))
        assert(refusal(notFriendly(f)).isInstanceOf[UnauthorizedError])
        assertEquals(where(f), (true, false))
    }

    test("a change refused after the copy leaves the archive recorded, and read, where it was") {
        val f = run(fixture(friendly = true).flatMap(archived))
        makeAdmin(f)
        // A second seat for the same player: a match that can only be friendly, refused in the
        // transaction -- after the copy has been made.
        run(TestSession.resource.use { session =>
            TestMatches.writeSeat(
              session = session,
              p = CharacterParticipant(
                participantId = ParticipantId(0),
                gameId = f.game.gameId,
                matchId = f.matchId,
                playerId = f.player.playerId,
                isPending = false,
                isCompleted = true,
                None,
                f.character.characterId,
                f.game.roles.head.gameRoleId
              ),
              eloStart = EloRating.initial
            )
        })
        assert(refusal(notFriendly(f)).isInstanceOf[ConflictError])
        val stored = run(row(f)).get
        assertEquals((stored.isFriendly, stored.isInFriendlyBucket), (true, Some(true)))
        // The copy is left in the permanent bucket, unrecorded; the original is untouched and still read.
        assertEquals(where(f), (true, true))
        val download = run(archives.download(f.matchId, f.game.externalId))
        assert(download.url.contains("/local-archive/friendly/"), download.url)
    }

    test("a friendly archive its bucket expired before it could be moved is recorded as expired, and the change made") {
        val f = run(fixture(friendly = true).flatMap(archived))
        makeAdmin(f)
        store.delete(ArchiveBucket.Friendly, run(row(f)).flatMap(_.key).get)
        assertEquals(run(notFriendly(f)).isFriendly, false)
        assert(run(row(f)).flatMap(_.expiredAt).isDefined)
        assertEquals(where(f), (false, false))
    }

    test("an upload asked for in the friendly bucket and not yet made is sent to the permanent one instead") {
        val f = run(fixture(friendly = true))
        makeAdmin(f)
        run(
          archives.requestUpload(
            matchId = f.matchId,
            size = content.length.toLong,
            sha256 = ArchiveStore.sha256(content),
            formatVersion = None,
            callerExternalId = f.game.externalId
          )
        )
        run(notFriendly(f))
        assertEquals(run(row(f)).flatMap(_.isInFriendlyBucket), Some(false))
        val UploadAnswer.Upload(signed) = run(
          archives.requestUpload(
            matchId = f.matchId,
            size = content.length.toLong,
            sha256 = ArchiveStore.sha256(content),
            formatVersion = None,
            callerExternalId = f.game.externalId
          )
        ): @unchecked
        assert(signed.url.contains("/local-archive/permanent/"), signed.url)
    }

    test("a check that read the match before its archive was moved records nothing on finding the original gone") {
        val f = run(fixture(friendly = true).flatMap(archived))
        makeAdmin(f)
        run(notFriendly(f))
        // What such a check does next: the friendly bucket is empty, so it records the archive as gone.
        run(TestSession.resource.use(session => new ArchiveRepo(session).recordExpired(f.game.gameId, f.matchId)))
        val recorded = run(TestSession.resource.use { session =>
            session.unique(
              sql"SELECT archive_expired_at IS NOT NULL FROM match WHERE game_id = $int4 AND match_id = $int8"
                  .query(skunk.codec.all.bool)
            )((f.game.gameId.value, f.matchId.value))
        })
        assertEquals(recorded, false)
        run(age(f, 31))
        val download = run(archives.download(f.matchId, f.game.externalId))
        assert(download.url.contains("/local-archive/permanent/"), download.url)
    }

    test("a record of expiry about an archive in the permanent bucket is ignored by every read") {
        val f = run(fixture(friendly = true).flatMap(archived))
        makeAdmin(f)
        run(notFriendly(f))
        // However it came to be there.
        run(TestSession.resource.use { session =>
            session
                .execute(
                  sql"UPDATE match SET archive_expired_at = now() WHERE game_id = $int4 AND match_id = $int8".command
                )((f.game.gameId.value, f.matchId.value))
                .void
        })
        assertEquals(run(row(f)).flatMap(_.expiredAt), None)
        assertEquals(
          run(TestSession.resource.use(session => new MatchRepo(session).read(f.game.gameId, f.matchId)))
              .map(_.isArchiveExpired),
          Some(false)
        )
        val download = run(archives.download(f.matchId, f.game.externalId))
        assert(download.url.contains("/local-archive/permanent/"), download.url)
    }

    test("a friendly archive past its 30 days that has gone is recorded, and the engine is told 410") {
        val f = run(fixture(friendly = true).flatMap(archived))
        run(age(f, 31))
        store.delete(ArchiveBucket.Friendly, run(row(f)).flatMap(_.key).get)
        assert(refusal(archives.download(f.matchId, f.game.externalId)).isInstanceOf[GoneError])
        assert(run(row(f)).flatMap(_.expiredAt).isDefined)
    }

    test("one past its 30 days that S3 has not yet deleted is still read, and nothing is recorded") {
        val f = run(fixture(friendly = true).flatMap(archived))
        run(age(f, 31))
        run(archives.download(f.matchId, f.game.externalId))
        assertEquals(run(row(f)).flatMap(_.expiredAt), None)
    }

    test("a missing archive within its 30 days is not taken as expired by the download") {
        val f = run(fixture(friendly = true).flatMap(archived))
        store.delete(ArchiveBucket.Friendly, run(row(f)).flatMap(_.key).get)
        run(archives.download(f.matchId, f.game.externalId))
        assertEquals(run(row(f)).flatMap(_.expiredAt), None)
    }

    test("the engine's report of a missing archive is checked before it is recorded, and refused for a permanent one") {
        val present = run(fixture(friendly = true).flatMap(archived))
        assert(refusal(archives.reportExpired(present.matchId, present.game.externalId)).isInstanceOf[ConflictError])
        assertEquals(run(row(present)).flatMap(_.expiredAt), None)

        val gone = run(fixture(friendly = true).flatMap(archived))
        store.delete(ArchiveBucket.Friendly, run(row(gone)).flatMap(_.key).get)
        run(archives.reportExpired(gone.matchId, gone.game.externalId))
        assert(run(row(gone)).flatMap(_.expiredAt).isDefined)

        val permanent = run(fixture(friendly = false).flatMap(archived))
        store.delete(ArchiveBucket.Permanent, run(row(permanent)).flatMap(_.key).get)
        assert(
          refusal(archives.reportExpired(permanent.matchId, permanent.game.externalId)).isInstanceOf[ConflictError]
        )
        assertEquals(run(row(permanent)).flatMap(_.expiredAt), None)
    }

    test("a player's finished list marks an archived match's links, and drops an expired one's") {
        val kept = run(fixture(isPublic = true).flatMap(archived))
        val listed = run(services.matches.completed(kept.player.externalId).map(_.matches.toList))
        assertEquals(
          listed.map(_.publicUrl),
          List(Some("https://engine.example.com/matches/m/board?view=1&archived=1"))
        )
        assertEquals(listed.map(_.isArchiveExpired), List(false))

        val lost = run(fixture(isPublic = true).flatMap(archived))
        run(age(lost, 31))
        store.delete(ArchiveBucket.Friendly, run(row(lost)).flatMap(_.key).get)
        val after = run(services.matches.completed(lost.player.externalId).map(_.matches.toList))
        assertEquals(after.map(s => (s.isArchiveExpired, s.publicUrl)), List((true, None)))
        // Recorded, so the next list answers from the database without asking the store.
        assert(run(row(lost)).flatMap(_.expiredAt).isDefined)
    }

    test("the match a player opens to review carries the marked play url, or none once expired") {
        val f = run(fixture().flatMap(archived))
        val opened = run(
          services.engine.read(gameId = f.game.gameId, matchId = f.matchId, callerExternalId = f.player.externalId)
        )
        assertEquals(opened.playUrl, Some("https://engine.example.com/matches/m/play?archived=1"))

        run(age(f, 31))
        store.delete(ArchiveBucket.Friendly, run(row(f)).flatMap(_.key).get)
        val expired = run(
          services.engine.read(gameId = f.game.gameId, matchId = f.matchId, callerExternalId = f.player.externalId)
        )
        assertEquals((expired.isArchiveExpired, expired.playUrl, expired.publicUrl), (true, None, None))
    }

    test("archived=1 goes into the query, before any fragment") {
        import ArchiveService.markArchived
        assertEquals(markArchived("https://e/m/play"), "https://e/m/play?archived=1")
        assertEquals(markArchived("https://e/m/play?seat=2"), "https://e/m/play?seat=2&archived=1")
        assertEquals(markArchived("https://e/m/play?"), "https://e/m/play?archived=1")
        assertEquals(markArchived("https://e/m/play#board"), "https://e/m/play?archived=1#board")
        assertEquals(markArchived("https://e/m/play?a=b#x"), "https://e/m/play?a=b&archived=1#x")
    }

    test("an archive's key is the game's folder, the day it completed, and the match id") {
        val at = Instant.parse("2026-01-31T23:59:59Z")
        assertEquals(
          ArchiveService.keyFor(gameName = "tictactoe", completedAt = at, matchId = MatchId(41)),
          "tictactoe/2026-01-31/41.json"
        )
        // A name that would not survive as a key as it is.
        assertEquals(
          ArchiveService.keyFor(gameName = "rock paper/scissors", completedAt = at, matchId = MatchId(1)),
          "rock%20paper%2Fscissors/2026-01-31/1.json"
        )
        assertEquals(
          ArchiveService.keyFor(gameName = "..", completedAt = at, matchId = MatchId(1)),
          "%2E%2E/2026-01-31/1.json"
        )
    }

    test("a game name too long for a key is cut short and hashed, without splitting an escape") {
        val at = Instant.parse("2026-01-31T00:00:00Z")
        val long = "é" * 300
        val key = ArchiveService.keyFor(gameName = long, completedAt = at, matchId = MatchId(1))
        val folder = key.split('/').head
        assert(folder.length <= ArchiveService.MaxSegment, folder)
        assert(folder.matches("(%[0-9A-F]{2})+-[0-9a-f]{16}"), folder)
        // Two names alike in what is kept still get folders of their own.
        assertNotEquals(
          ArchiveService.keyFor(gameName = long + "x", completedAt = at, matchId = MatchId(1)).split('/').head,
          folder
        )
        assert(key.getBytes("UTF-8").length < 1024)
        // A short name is untouched.
        assertEquals(
          ArchiveService.keyFor(gameName = "stratego", completedAt = at, matchId = MatchId(1)),
          "stratego/2026-01-31/1.json"
        )
    }

    test("a long history checks only so many archives per list, and the next list carries on") {
        val f = run(fixture(isPublic = true))
        // More expired archives in one player's history than one list will check.
        val extra = (1 to ArchiveService.ChecksPerRequest + 5).toList.map { _ =>
            run(addCompletedMatch(f))
        }
        val all = f.matchId :: extra
        all.foreach { id =>
            val g = f.copy(matchId = id)
            run(archived(g))
            run(age(g, 31))
            store.delete(ArchiveBucket.Friendly, run(row(g)).flatMap(_.key).get)
        }
        val first = run(services.matches.completed(f.player.externalId).map(_.matches.toList))
        assertEquals(first.count(_.isArchiveExpired), ArchiveService.ChecksPerRequest)
        val second = run(services.matches.completed(f.player.externalId).map(_.matches.toList))
        assertEquals(second.count(_.isArchiveExpired), all.size)
    }

    test("only a friendly archive older than its retention may have expired") {
        val now = Instant.parse("2026-10-03T00:00:00Z")
        val old = now.minus(Duration.ofDays(31))
        assert(ArchiveService.canHaveExpired(friendly = true, old, now))
        assert(!ArchiveService.canHaveExpired(friendly = false, old, now))
        assert(!ArchiveService.canHaveExpired(friendly = true, now.minus(Duration.ofDays(29)), now))
    }
}
