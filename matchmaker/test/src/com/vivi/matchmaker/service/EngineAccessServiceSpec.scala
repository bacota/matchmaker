package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.Instant
import skunk.implicits._
import skunk.codec.all._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{EngineAccessRepo, GameRepo, PlayerRepo, Repos, TestSession}

class EngineAccessServiceSpec extends PropertySuite {
    TestMigration.ensure()

    /* A ceiling on a case that has hung, not on how long one should take. See CLAUDE.md. */
    private val caseTimeout = 60.seconds

    private val services = TestServices.services
    private val access = services.engineAccess

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def register(): IO[Player] =
        services.registration.register(nickname = unique("ea"), externalId = unique("ea-sub"))

    private def role(name: String): GameRole =
        GameRole(
          gameRoleId = GameRoleId(0),
          gameId = GameId.unassigned,
          name = name,
          roleType = RoleType.Required,
          displayName = name
        )

    private def makeGame(gameType: GameType, engine: String): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                gameId = GameId.unassigned,
                gameType = gameType,
                name = unique("duel"),
                displayName = "Duel",
                description = "description",
                url = "https://engine.example.com/games",
                isActive = true,
                roles = if (gameType == GameType.Plain) Seq(role("first"), role("second")) else Seq.empty,
                parameters = Seq.empty,
                externalId = engine
              )
            )
        }

    /** A plain game, an overall admin, an admin of the game, and two players. */
    private case class Fixture(game: Game, overall: Player, host: Player, first: Player, second: Player)

    private def fixture(): IO[Fixture] =
        for {
            game <- makeGame(GameType.Plain, unique("engine"))
            overall <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(overall.copy(isAdmin = true)))
            host <- register()
            _ <- services.gameAdmins.grant(
              gameId = game.gameId,
              playerId = host.playerId,
              callerExternalId = overall.externalId
            )
            first <- register()
            second <- register()
        } yield Fixture(
          game = game,
          overall = overall.copy(isAdmin = true),
          host = host,
          first = first,
          second = second
        )

    /** A challenge the game's admin offers and does not play in, which anybody may enter. */
    private def openChallenge(f: Fixture): IO[Tournament] =
        Challenges.offeredIn(f.game) *> Challenges.create(
          tournaments = services.tournaments,
          game = f.game,
          owner = f.host,
          isPlaying = false,
          poolSize = Some(2)
        )

    private def enter(f: Fixture, t: Tournament, player: Player): IO[Entrant] =
        services.tournaments.enter(
          gameId = f.game.gameId,
          tournamentId = t.tournamentId,
          characterId = None,
          callerExternalId = player.externalId
        )

    private def restrict(f: Fixture): IO[Unit] =
        access.setRestricted(gameId = f.game.gameId, isRestricted = true, callerExternalId = f.host.externalId)

    private def refusal[A](io: IO[A]): IO[Throwable] =
        io.attempt.map(_.swap.getOrElse(fail("expected a refusal, but it was allowed")))

    private def run[A](io: IO[A]): A = io.timeout(caseTimeout).unsafeRunSync()

    test("an engine nobody has restricted is open to every player") {
        val entrant = run(for {
            f <- fixture()
            t <- openChallenge(f)
            e <- enter(f, t, f.first)
        } yield e)
        assert(entrant.playerId.isDefined)
    }

    test("a restricted engine refuses an unlisted player's entry, and takes it once its admin lets them in") {
        val (refused, entrant) = run(for {
            f <- fixture()
            _ <- restrict(f)
            t <- openChallenge(f)
            refused <- refusal(enter(f, t, f.first))
            _ <- access.grant(gameId = f.game.gameId, nickname = f.first.nickname, callerExternalId = f.host.externalId)
            entrant <- enter(f, t, f.first)
        } yield (refused, entrant))
        assert(refused.isInstanceOf[UnauthorizedError], refused)
        assert(refused.getMessage.contains("Duel"), refused.getMessage)
        assert(entrant.playerId.isDefined)
    }

    test("an overall admin and the game's admins play a restricted engine without being listed") {
        val entered = run(for {
            f <- fixture()
            _ <- restrict(f)
            t <- openChallenge(f)
            a <- enter(f, t, f.overall)
            b <- enter(f, t, f.host)
        } yield List(a, b))
        assertEquals(entered.size, 2)
    }

    test("making a challenge one plays in is an entry, and refused like one") {
        val refused = run(for {
            f <- fixture()
            _ <- restrict(f)
            _ <- Challenges.offeredIn(f.game)
            refused <- refusal(
              Challenges.create(tournaments = services.tournaments, game = f.game, owner = f.first, poolSize = Some(2))
            )
        } yield refused)
        assert(refused.isInstanceOf[UnauthorizedError], refused)
    }

    test("the list is the engine's: letting a player into one of its games lets them into the other") {
        val entrant = run(for {
            f <- fixture()
            other <- makeGame(GameType.Plain, f.game.externalId)
            _ <- restrict(f)
            _ <- access.grant(gameId = f.game.gameId, nickname = f.first.nickname, callerExternalId = f.host.externalId)
            g = f.copy(game = other)
            t <- openChallenge(g)
            e <- enter(g, t, f.first)
        } yield e)
        assert(entrant.playerId.isDefined)
    }

    test("a code lets in whoever redeems it, typed in any case and with or without its dashes") {
        val (redeemed, entrant, codes) = run(for {
            f <- fixture()
            _ <- restrict(f)
            made <- access.createCode(
              gameId = f.game.gameId,
              label = "club night",
              maxUses = None,
              expiresAt = None,
              callerExternalId = f.host.externalId
            )
            redeemed <- access.redeem(made.code.toLowerCase.replace("-", " "), f.first.externalId)
            t <- openChallenge(f)
            entrant <- enter(f, t, f.first)
            seen <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
        } yield (redeemed, entrant, seen))
        assertEquals(redeemed.games, List("Duel"))
        assert(entrant.playerId.isDefined)
        assertEquals(codes.liveCodes.codes.map(c => (c.label, c.uses)), List(("club night", 1)))
        assertEquals(codes.players.map(_.codeLabel), List(Some("club night")))
    }

    test("redeeming a code twice spends one use, and a used-up code lets nobody else in") {
        val (uses, refused) = run(for {
            f <- fixture()
            _ <- restrict(f)
            made <- access.createCode(
              gameId = f.game.gameId,
              label = "one seat",
              maxUses = Some(1),
              expiresAt = None,
              callerExternalId = f.host.externalId
            )
            _ <- access.redeem(made.code, f.first.externalId)
            _ <- access.redeem(made.code, f.first.externalId)
            refused <- refusal(access.redeem(made.code, f.second.externalId))
            seen <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
        } yield (seen.endedCodes.map(_.uses), refused))
        assertEquals(uses, List(1))
        assert(refused.isInstanceOf[ValidationError], refused)
    }

    private def makeCode(f: Fixture, label: String, maxUses: Option[Int] = None): IO[NewEngineAccessCode] =
        access.createCode(
          gameId = f.game.gameId,
          label = label,
          maxUses = maxUses,
          expiresAt = None,
          callerExternalId = f.host.externalId
        )

    test("the ended codes are the newest few, used up or revoked, and the live ones are listed apart") {
        val revokedCount = EngineAccessRepo.EndedCodesListed + 1
        val (seen, live, ended) = run(
          for {
              f <- fixture()
              oldestLive <- makeCode(f, "still open")
              revoked <- (1 to revokedCount).toList.traverse(n => makeCode(f, s"spent $n"))
              _ <- revoked.traverse(r => access.revokeCode(f.game.gameId, r.access.codeId, f.host.externalId))
              usedUp <- makeCode(f, "used up", maxUses = Some(1))
              _ <- access.redeem(usedUp.code, f.first.externalId)
              newestLive <- makeCode(f, "newest open")
              seen <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
          } yield (
            seen,
            List(newestLive, oldestLive).map(_.access.label),
            // Newest first: the used-up one, then the newest revoked ones, as many as are listed.
            (revoked :+ usedUp).map(_.access.label).reverse.take(EngineAccessRepo.EndedCodesListed)
          )
        )
        assertEquals(seen.liveCodes, EngineAccessCodePage(codes = seen.liveCodes.codes, hasMore = false))
        assertEquals(seen.liveCodes.codes.map(_.label), live)
        assertEquals(seen.endedCodes.map(_.label), ended)
    }

    test("a code whose expiry has passed is marked expired as the codes are listed, and listed with the ended") {
        val (seen, marks) = run(for {
            f <- fixture()
            expiring <- access.createCode(
              gameId = f.game.gameId,
              label = "until tonight",
              maxUses = None,
              expiresAt = Some(Instant.now().plusSeconds(3600)),
              callerExternalId = f.host.externalId
            )
            open <- makeCode(f, "open")
            id = expiring.access.codeId.value
            _ <- TestSession.resource.use(
              _.execute(
                sql"UPDATE engine_access_code SET expires_at = now() - interval '1 minute' WHERE code_id = $int8".command
              )(id)
            )
            seen <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
            marks <- TestSession.resource.use(
              _.unique(sql"SELECT is_expired FROM engine_access_code WHERE code_id = $int8".query(bool))(id)
            )
        } yield (seen, marks))
        assertEquals(seen.liveCodes.codes.map(_.label), List("open"))
        assertEquals(seen.endedCodes.map(_.label), List("until tonight"))
        assert(marks, "the expired code is marked")
    }

    test("the live codes come a page at a time, and a refresh reaches as far down as the list was paged") {
        val perPage = EngineAccessRepo.LiveCodesPerPage
        val (made, first, second, throughSecond, throughFirst) = run(for {
            f <- fixture()
            made <- (1 to perPage + 2).toList.traverse(n => makeCode(f, s"code $n"))
            // Revoked, so not live: no page shows it.
            _ <- access.revokeCode(f.game.gameId, made(perPage / 2).access.codeId, f.host.externalId)
            first <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
            second <- access.liveCodes(
              gameId = f.game.gameId,
              before = first.liveCodes.codes.last.codeId,
              callerExternalId = f.host.externalId
            )
            throughSecond <- access.access(
              gameId = f.game.gameId,
              codesThrough = Some(second.codes.last.codeId),
              callerExternalId = f.host.externalId
            )
            throughFirst <- access.access(
              gameId = f.game.gameId,
              codesThrough = Some(first.liveCodes.codes.last.codeId),
              callerExternalId = f.host.externalId
            )
        } yield (made, first.liveCodes, second, throughSecond.liveCodes, throughFirst.liveCodes))
        val live = made.patch(perPage / 2, Nil, 1).map(_.access.label).reverse
        assertEquals(first.codes.map(_.label), live.take(perPage))
        assert(first.hasMore)
        assertEquals(second, EngineAccessCodePage(codes = second.codes, hasMore = false))
        assertEquals(second.codes.map(_.label), live.drop(perPage))
        assertEquals(throughSecond.codes.map(_.label), live)
        assert(!throughSecond.hasMore)
        assertEquals(throughFirst.codes.map(_.label), live.take(perPage))
        assert(throughFirst.hasMore)
    }

    test("a revoked, an expired and an unknown code are refused alike") {
        val messages = run(for {
            f <- fixture()
            revoked <- access.createCode(
              gameId = f.game.gameId,
              label = "revoked",
              maxUses = None,
              expiresAt = None,
              callerExternalId = f.host.externalId
            )
            _ <- access.revokeCode(f.game.gameId, revoked.access.codeId, f.host.externalId)
            expired <- access.createCode(
              gameId = f.game.gameId,
              label = "expired",
              maxUses = None,
              expiresAt = Some(Instant.now().plusSeconds(3600)),
              callerExternalId = f.host.externalId
            )
            _ <- TestSession.resource.use(
              _.execute(
                sql"UPDATE engine_access_code SET expires_at = now() - interval '1 minute' WHERE code_id = $int8".command
              )(
                expired.access.codeId.value
              )
            )
            a <- refusal(access.redeem(revoked.code, f.first.externalId))
            b <- refusal(access.redeem(expired.code, f.first.externalId))
            c <- refusal(access.redeem("AAAAA-AAAAA-AAAAA-AAAAA-AAAAA", f.first.externalId))
        } yield List(a, b, c))
        assert(messages.forall(_.isInstanceOf[ValidationError]), messages)
        assertEquals(messages.map(_.getMessage).distinct.size, 1)
    }

    test("taking a player off the list refuses their next entry, and leaves the one they made") {
        val (refused, kept) = run(for {
            f <- fixture()
            _ <- restrict(f)
            _ <- access.grant(gameId = f.game.gameId, nickname = f.first.nickname, callerExternalId = f.host.externalId)
            t <- openChallenge(f)
            u <- openChallenge(f)
            _ <- enter(f, t, f.first)
            _ <- access.revoke(
              gameId = f.game.gameId,
              playerId = f.first.playerId,
              callerExternalId = f.host.externalId
            )
            refused <- refusal(enter(f, u, f.first))
            kept <- services.tournaments.detail(f.game.gameId, t.tournamentId, f.host.externalId)
        } yield (refused, kept))
        assert(refused.isInstanceOf[UnauthorizedError], refused)
        assertEquals(kept.entries.size, 1)
    }

    test("only an overall admin or an admin of the game may say who plays its engine") {
        val (byPlayer, byOtherAdmin, byHost) = run(for {
            f <- fixture()
            g <- fixture()
            byPlayer <- refusal(
              access.setRestricted(gameId = f.game.gameId, isRestricted = true, callerExternalId = f.first.externalId)
            )
            byOtherAdmin <- refusal(
              access.grant(gameId = f.game.gameId, nickname = f.second.nickname, callerExternalId = g.host.externalId)
            )
            byHost <- access
                .grant(gameId = f.game.gameId, nickname = f.second.nickname, callerExternalId = f.host.externalId)
                .attempt
        } yield (byPlayer, byOtherAdmin, byHost))
        assert(byPlayer.isInstanceOf[UnauthorizedError], byPlayer)
        assert(byOtherAdmin.isInstanceOf[UnauthorizedError], byOtherAdmin)
        assert(byHost.isRight, byHost)
    }

    test("a player's barred games are a restricted engine's they are not listed for, and an admin has none") {
        val (game, before, after, admin) = run(for {
            f <- fixture()
            _ <- restrict(f)
            before <- access.barred(f.first.externalId, GameScope.All)
            _ <- access.grant(gameId = f.game.gameId, nickname = f.first.nickname, callerExternalId = f.host.externalId)
            after <- access.barred(f.first.externalId, GameScope.All)
            admin <- access.barred(f.overall.externalId, GameScope.All)
        } yield (f.game.gameId, before, after, admin))
        assert(before.contains(game), before)
        assert(!after.contains(game), after)
        assertEquals(admin, Nil)
    }

    test("taking a player off the list waits for an entry that has already found them on it") {
        val (wasBlocked, isListedAfter) = run(for {
            f <- fixture()
            _ <- restrict(f)
            _ <- access.grant(gameId = f.game.gameId, nickname = f.first.nickname, callerExternalId = f.host.externalId)
            isDone <- cats.effect.Ref.of[IO, Boolean](false)
            wasBlocked <- TestSession.resource.use { session =>
                session.transaction.use { _ =>
                    for {
                        _ <- EngineAccessService.requireAllowed(
                          repos = Repos(session),
                          game = f.game,
                          player = f.first,
                          refusal = "refused"
                        )
                        revoking <- access
                            .revoke(
                              gameId = f.game.gameId,
                              playerId = f.first.playerId,
                              callerExternalId = f.host.externalId
                            )
                            .flatTap(_ => isDone.set(true))
                            .start
                        _ <- IO.sleep(500.millis)
                        doneWhileHeld <- isDone.get
                    } yield (!doneWhileHeld, revoking)
                }
            }
            _ <- wasBlocked._2.joinWithNever
            listed <- access.access(gameId = f.game.gameId, codesThrough = None, callerExternalId = f.host.externalId)
        } yield (wasBlocked._1, listed.players.nonEmpty))
        assert(wasBlocked, "the revoke ran while an entry held the player's listing")
        assert(!isListedAfter)
    }

    test("a restricted engine makes characters only for players it lets in, and gives them only to such players") {
        val (madeRefused, made, givenRefused) = run(for {
            engine <- IO.pure(unique("engine"))
            game <- makeGame(GameType.Character, engine)
            overall <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(overall.copy(isAdmin = true)))
            owner <- register()
            stranger <- register()
            _ <- access.setRestricted(gameId = game.gameId, isRestricted = true, callerExternalId = overall.externalId)
            madeRefused <- refusal(
              services.characters.create(
                name = "Brawler",
                description = "",
                ownerExternalId = owner.externalId,
                state = "{}",
                callerExternalId = engine
              )
            )
            _ <- access.grant(gameId = game.gameId, nickname = owner.nickname, callerExternalId = overall.externalId)
            made <- services.characters.create(
              name = "Brawler",
              description = "",
              ownerExternalId = owner.externalId,
              state = "{}",
              callerExternalId = engine
            )
            givenRefused <- refusal(
              services.characters.transfer(
                characterId = made.characterId,
                toNickname = stranger.nickname,
                ownerExternalId = owner.externalId,
                callerExternalId = engine
              )
            )
        } yield (madeRefused, made, givenRefused))
        assert(madeRefused.isInstanceOf[UnauthorizedError], madeRefused)
        assertEquals(made.name, "Brawler")
        assert(givenRefused.isInstanceOf[UnauthorizedError], givenRefused)
    }

    test("a code needs a label, a most of at least one, and an expiry still to come") {
        val refused = run(for {
            f <- fixture()
            make = (label: String, maxUses: Option[Int], expiresAt: Option[Instant]) =>
                access.createCode(
                  gameId = f.game.gameId,
                  label = label,
                  maxUses = maxUses,
                  expiresAt = expiresAt,
                  callerExternalId = f.host.externalId
                )
            blank <- refusal(make(" ", None, None))
            zero <- refusal(make("x", Some(0), None))
            past <- refusal(make("x", None, Some(Instant.now().minusSeconds(60))))
        } yield List(blank, zero, past))
        assert(refused.forall(_.isInstanceOf[ValidationError]), refused)
    }

    test("codes are typed easily and differ") {
        val codes = List.fill(50)(EngineAccessService.newCode())
        assertEquals(codes.distinct.size, 50)
        assert(codes.forall(_.matches("[A-Z2-7]{5}(-[A-Z2-7]{5}){4}")), codes.head)
        assertEquals(EngineAccessService.normalize(" abcde-fghij "), "ABCDEFGHIJ")
    }
}
