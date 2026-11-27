package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import munit.FunSuite
import skunk.implicits._
import skunk.codec.all.{bool, bytea, int8}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, HandOffRepo, TestSession}

/** A sign-in handed to an engine's page by a one-time code (design/sign-in-handoff.md): what is stored, who may redeem
  * it, and that it is redeemed once.
  */
class HandOffServiceSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    /** A test whose body is an `IO`, run to a future munit waits on. */
    private def check(options: munit.TestOptions)(body: => IO[Unit]): Unit =
        test(options)(IO.defer(body).unsafeToFuture())

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    /** What the service logged, so a test can see that no token or code went there. */
    private class Logged {
        @volatile var lines: List[String] = Nil
        val log: String => IO[Unit] = line => IO { lines = lines :+ line }
    }

    /** A service whose clock is `clock` and whose log is `logged`. */
    private def service(logged: Logged, clock: () => Instant = () => Instant.now()): HandOffService =
        new HandOffService(sessionPool = TestServices.pool, now = clock, log = logged.log)

    /** An unsigned ID token whose `sub` is `sub`. */
    private def idTokenOf(sub: String): String = {
        def part(json: String) = Base64.getUrlEncoder.withoutPadding.encodeToString(json.getBytes(UTF_8))
        part("""{"alg":"none"}""") + "." + part(s"""{"sub":"$sub","email":"p@example.com"}""") + ".sig"
    }

    /** A game whose engine identity is returned beside it. */
    private def game(isTrusted: Boolean): IO[(GameId, String)] = {
        val engine = unique("engine")
        TestSession.resource.use { session =>
            new GameRepo[String](session)
                .create(
                  Game(
                    gameId = GameId.unassigned,
                    gameType = GameType.Plain,
                    name = unique("handoff"),
                    displayName = "Hand-off",
                    description = "description",
                    url = "url",
                    isActive = true,
                    roles = Seq(
                      GameRole(
                        gameRoleId = GameRoleId(0),
                        gameId = GameId.unassigned,
                        name = "only",
                        roleType = RoleType.Required,
                        displayName = "only"
                      )
                    ),
                    parameters = Seq.empty,
                    externalId = engine,
                    isTrusted = isTrusted
                  )
                )
                .map(g => (g.gameId, engine))
        }
    }

    private def isStored(code: String): IO[Boolean] =
        TestSession.resource.use(
          _.unique(sql"SELECT EXISTS (SELECT 1 FROM sign_in_handoff WHERE code_hash = $bytea)".query(bool))(
            HandOffService.hashOf(code)
          )
        )

    /** Whether `code`'s row holds a refresh token, or `None` if there is no row. */
    private def hasStoredRefreshToken(code: String): IO[Option[Boolean]] =
        TestSession.resource.use(
          _.option(
            sql"SELECT refresh_token_sealed IS NOT NULL FROM sign_in_handoff WHERE code_hash = $bytea".query(bool)
          )(HandOffService.hashOf(code))
        )

    /** `code`'s row as Postgres writes it out as text, with its sealed columns read byte for byte as well. */
    private def storedRowText(code: String): IO[String] =
        TestSession.resource
            .use(
              _.unique(
                sql"""SELECT h::text, id_token_sealed, refresh_token_sealed
                      FROM sign_in_handoff h WHERE code_hash = $bytea"""
                    .query(skunk.codec.all.text *: bytea *: bytea.opt)
              )(HandOffService.hashOf(code))
            )
            .map((row, id, refresh) =>
                row + new String(
                  id ++ refresh.getOrElse(Array.empty[Byte]),
                  java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )

    private def refused[A](io: IO[A]): IO[Unit] =
        io.attempt.map {
            case Left(e: NotFoundError) => assertEquals(e.getMessage, com.vivi.matchmaker.ErrorText.handOff.unknown)
            case other                  => fail(s"not refused as unknown: $other")
        }

    check("a code redeems once, for the tokens it was made with, and a second redeem is refused") {
        val logged = Logged()
        val handOffs = service(logged)
        val sub = unique("sub")
        val token = idTokenOf(sub)
        for {
            (gameId, engine) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = token, refreshToken = Some("r-1"))
            handed <- handOffs.redeem(made.code, engine)
            _ = assertEquals(handed, HandedOff(idToken = token, refreshToken = Some("r-1")))
            _ <- refused(handOffs.redeem(made.code, engine))
        } yield ()
    }

    check("the code is long, random and url-safe, and expires a minute after it is made") {
        val at = Instant.parse("2030-01-01T00:00:00Z")
        val handOffs = service(Logged(), () => at)
        val sub = unique("sub")
        for {
            (gameId, _) <- game(isTrusted = true)
            a <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            b <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
        } yield {
            assertEquals(a.expiresAt, at.plusSeconds(60))
            assertNotEquals(a.code, b.code)
            assertEquals(a.code.length, 43)
            assert(a.code.forall(c => c.isLetterOrDigit || c == '-' || c == '_'), a.code)
        }
    }

    check("only the code's hash is stored") {
        val sub = unique("sub")
        for {
            (gameId, _) <- game(isTrusted = true)
            made <- service(Logged()).create(
              cognitoId = sub,
              gameId = gameId,
              idToken = idTokenOf(sub),
              refreshToken = None
            )
            asText <- TestSession.resource.use(
              _.unique(
                sql"""SELECT count(*) FROM sign_in_handoff
                      WHERE convert_from(code_hash, 'LATIN1') LIKE ${skunk.codec.all.text}"""
                    .query(int8)
              )("%" + made.code + "%")
            )
            stored <- isStored(made.code)
        } yield {
            assert(stored)
            assertEquals(asText, 0L)
        }
    }

    // Only the code's hash is stored, so the tokens beside it must be of no use without the code either.
    check("the tokens are not stored as they are") {
        val handOffs = service(Logged())
        val sub = unique("sub")
        val token = idTokenOf(sub)
        for {
            (gameId, _) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = token, refreshToken = Some("r-secret"))
            text <- storedRowText(made.code)
            hasRefresh <- hasStoredRefreshToken(made.code)
        } yield {
            assertEquals(hasRefresh, Some(true))
            assert(!text.contains(token), text)
            assert(!text.contains(token.split('.')(1)), text)
            assert(!text.contains("r-secret"), text)
        }
    }

    test("a seal opens only with the code and the purpose it was made with") {
        val sealedId = HandOffService.seal(code = "c0de", purpose = HandOffService.IdTokenPurpose, token = "id-1")
        assertEquals(
          HandOffService.open(code = "c0de", purpose = HandOffService.IdTokenPurpose, sealedToken = sealedId),
          "id-1"
        )
        intercept[javax.crypto.AEADBadTagException](
          HandOffService.open(code = "other", purpose = HandOffService.IdTokenPurpose, sealedToken = sealedId)
        )
        intercept[javax.crypto.AEADBadTagException](
          HandOffService.open(code = "c0de", purpose = HandOffService.RefreshTokenPurpose, sealedToken = sealedId)
        )
        // A fresh nonce each time: the same token sealed twice does not look the same.
        assert(
          !HandOffService
              .seal(code = "c0de", purpose = HandOffService.IdTokenPurpose, token = "id-1")
              .sameElements(sealedId)
        )
    }

    check("redeeming a code deletes those already expired") {
        var at = Instant.parse("2030-01-01T00:00:00Z")
        val handOffs = service(Logged(), () => at)
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = true)
            old <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            _ = at = at.plusSeconds(30)
            fresh <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            _ = at = at.plusSeconds(31)
            _ <- handOffs.redeem(fresh.code, engine)
            isOldStored <- isStored(old.code)
        } yield assert(!isOldStored)
    }

    check("an expired code is refused, and leaves no row".tag(Quiet)) {
        var at = Instant.parse("2030-01-01T00:00:00Z")
        val handOffs = service(Logged(), () => at)
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            _ = at = at.plusSeconds(61)
            _ <- refused(handOffs.redeem(made.code, engine))
            stored <- isStored(made.code)
        } yield assert(!stored)
    }

    check("another game's engine is refused, and the code is spent for the right one too".tag(Quiet)) {
        val handOffs = service(Logged())
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = true)
            (_, otherEngine) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            _ <- refused(handOffs.redeem(made.code, otherEngine))
            stored <- isStored(made.code)
            _ = assert(!stored)
            _ <- refused(handOffs.redeem(made.code, engine))
        } yield ()
    }

    check("a made-up code is refused as a spent one is".tag(Quiet)) {
        for {
            (_, engine) <- game(isTrusted = true)
            _ <- refused(service(Logged()).redeem("made-up", engine))
        } yield ()
    }

    check("an untrusted game's code keeps no refresh token") {
        val handOffs = service(Logged())
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = false)
            made <- handOffs.create(
              cognitoId = sub,
              gameId = gameId,
              idToken = idTokenOf(sub),
              refreshToken = Some("r-1")
            )
            stored <- hasStoredRefreshToken(made.code)
            _ = assertEquals(stored, Some(false))
            handed <- handOffs.redeem(made.code, engine)
        } yield assertEquals(handed.refreshToken, None)
    }

    check("a game whose trust is withdrawn after the code is made is not handed the refresh token") {
        val handOffs = service(Logged())
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = true)
            made <- handOffs.create(
              cognitoId = sub,
              gameId = gameId,
              idToken = idTokenOf(sub),
              refreshToken = Some("r-1")
            )
            _ <- TestSession.resource.use(
              _.execute(sql"UPDATE game SET is_trusted = false WHERE game_id = ${skunk.codec.all.int4}".command)(
                gameId.value
              )
            )
            handed <- handOffs.redeem(made.code, engine)
        } yield assertEquals(handed.refreshToken, None)
    }

    check("an ID token that is not the caller's is refused, and nothing is stored".tag(Quiet)) {
        val handOffs = service(Logged())
        for {
            (gameId, _) <- game(isTrusted = true)
            outcome <- handOffs
                .create(
                  cognitoId = unique("sub"),
                  gameId = gameId,
                  idToken = idTokenOf("someone-else"),
                  refreshToken = Some("r-1")
                )
                .attempt
        } yield outcome match {
            case Left(_: UnauthorizedError) => ()
            case other                      => fail(s"not refused: $other")
        }
    }

    check("a token that is no JWT at all is refused".tag(Quiet)) {
        for {
            (gameId, _) <- game(isTrusted = true)
            outcome <- service(Logged())
                .create(cognitoId = "sub", gameId = gameId, idToken = "garbage", refreshToken = None)
                .attempt
        } yield assert(outcome.left.exists(_.isInstanceOf[UnauthorizedError]), outcome)
    }

    check("a game that does not exist is refused".tag(Quiet)) {
        val sub = unique("sub")
        service(Logged())
            .create(cognitoId = sub, gameId = GameId(Int.MaxValue), idToken = idTokenOf(sub), refreshToken = None)
            .attempt
            .map(outcome => assert(outcome.left.exists(_.isInstanceOf[NotFoundError]), outcome))
    }

    check("making a code deletes those already expired") {
        var at = Instant.parse("2030-01-01T00:00:00Z")
        val handOffs = service(Logged(), () => at)
        val sub = unique("sub")
        for {
            (gameId, _) <- game(isTrusted = true)
            old <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            _ = at = at.plusSeconds(61)
            fresh <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            isOldStored <- isStored(old.code)
            isFreshStored <- isStored(fresh.code)
        } yield {
            assert(!isOldStored)
            assert(isFreshStored)
        }
    }

    check("two redeems of one code at once: exactly one gets the tokens".tag(Quiet)) {
        val handOffs = service(Logged())
        val sub = unique("sub")
        for {
            (gameId, engine) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = idTokenOf(sub), refreshToken = None)
            outcomes <- List.fill(8)(handOffs.redeem(made.code, engine).attempt).parSequence
        } yield {
            assertEquals(outcomes.count(_.isRight), 1)
            assert(outcomes.collect { case Left(e) => e }.forall(_.isInstanceOf[NotFoundError]), outcomes)
        }
    }

    // A refused insert's message lists the statement's arguments, and an error that is not a ServiceError is logged
    // with it. The tokens must not be among them.
    check("a refused insert's error does not carry the tokens") {
        val sub = unique("sub")
        val sealedId = HandOffService.seal(code = "c0de", purpose = HandOffService.IdTokenPurpose, token = "id")
        val sealedRefresh =
            HandOffService.seal(code = "c0de", purpose = HandOffService.RefreshTokenPurpose, token = "r-secret")
        def hex(bytes: Array[Byte]) = bytes.map(b => f"$b%02x").mkString
        TestSession.resource
            .use(session =>
                new HandOffRepo(session)
                    .insert(
                      HandOffRepo.NewHandOff(
                        codeHash = HandOffService.hashOf("c0de"),
                        gameId = GameId(Int.MaxValue),
                        cognitoId = sub,
                        idTokenSealed = sealedId,
                        refreshTokenSealed = Some(sealedRefresh),
                        expiresAt = Instant.now()
                      )
                    )
                    .attempt
            )
            .map {
                case Left(e) =>
                    assert(!e.getMessage.contains(hex(sealedId)), e.getMessage)
                    assert(!e.getMessage.contains(hex(sealedRefresh)), e.getMessage)
                case Right(_) => fail("a hand-off for no game was stored")
            }
    }

    check("nothing logged carries a token or the code") {
        val logged = Logged()
        val handOffs = service(logged)
        val sub = unique("sub")
        val token = idTokenOf(sub)
        for {
            (gameId, engine) <- game(isTrusted = true)
            made <- handOffs.create(cognitoId = sub, gameId = gameId, idToken = token, refreshToken = Some("r-secret"))
            _ <- handOffs.redeem(made.code, engine)
        } yield {
            assert(logged.lines.nonEmpty)
            logged.lines.foreach { line =>
                assert(!line.contains(made.code), line)
                assert(!line.contains(token), line)
                assert(!line.contains("r-secret"), line)
            }
        }
    }
}
