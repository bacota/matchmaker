package com.vivi.matchmaker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{Generators, PlayerRepo, TestSession}

class GameAdminServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val admins = TestServices.services.gameAdmins
    private val registration = TestServices.services.registration
    private val games = TestServices.services.games

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def register(): IO[Player] = registration.register(unique("ga"), unique("ga-sub"))

    private def makeOverallAdmin(): IO[Player] =
        for {
            player <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(player.copy(isAdmin = true)))
        } yield player.copy(isAdmin = true)

    /** An overall admin, a fresh game they made, and a player who administers that game. */
    private case class Fixture(overall: Player, game: GameId, gameAdmin: Player)

    private def fixture(): IO[Fixture] =
        for {
            overall <- makeOverallAdmin()
            game <- IO(Generators.genGameWithRole.sample.get).flatMap(games.createOrUpdate(overall.externalId, _))
            gameAdmin <- register()
            _ <- admins.grant(game.gameId, gameAdmin.playerId, overall.externalId)
        } yield Fixture(overall, game.gameId, gameAdmin)

    private def listed(f: Fixture): IO[Set[PlayerId]] =
        admins.list(f.game, f.overall.externalId).map(_.map(_.player.playerId).toSet)

    private def refusal[A](io: IO[A]): IO[Throwable] =
        io.attempt.map(_.swap.getOrElse(fail("expected a refusal, but it was allowed")))

    test("an admin makes a player an admin of a game, and anybody registered sees it by nickname") {
        val result = for {
            f <- fixture()
            stranger <- register()
            seen <- admins.list(f.game, stranger.externalId)
        } yield (seen, f)
        val (seen, f) = result.unsafeRunSync()
        assertEquals(
          seen,
          List(
            GameAdmin(
              PublicPlayer(f.gameAdmin.playerId, f.gameAdmin.nickname),
              PublicPlayer(f.overall.playerId, f.overall.nickname)
            )
          )
        )
    }

    test("a game's admin makes another player an admin of the same game") {
        val result = for {
            f <- fixture()
            other <- register()
            _ <- admins.grant(f.game, other.playerId, f.gameAdmin.externalId)
            now <- listed(f)
        } yield now == Set(f.gameAdmin.playerId, other.playerId)
        assert(result.unsafeRunSync())
    }

    test("a game's admin may not make admins of a different game") {
        val result = for {
            f <- fixture()
            g <- fixture()
            other <- register()
            refused <- refusal(admins.grant(g.game, other.playerId, f.gameAdmin.externalId))
            now <- listed(g)
        } yield (refused, now)
        val (refused, now) = result.unsafeRunSync()
        assert(refused.isInstanceOf[UnauthorizedError], refused)
        assertEquals(now.size, 1)
    }

    test("a player who administers nothing may not make anybody an admin, themselves included") {
        val result = for {
            f <- fixture()
            player <- register()
            refused <- refusal(admins.grant(f.game, player.playerId, player.externalId))
            now <- listed(f)
        } yield (refused, now)
        val (refused, now) = result.unsafeRunSync()
        assert(refused.isInstanceOf[UnauthorizedError], refused)
        assertEquals(now.size, 1)
    }

    test("a game's admin takes back an admin they made, but not one somebody else made, themselves included") {
        val result = for {
            f <- fixture()
            mine <- register()
            _ <- admins.grant(f.game, mine.playerId, f.gameAdmin.externalId)
            // Made by somebody else: another admin of the same game.
            peer <- register()
            _ <- admins.grant(f.game, peer.playerId, f.overall.externalId)
            theirs <- register()
            _ <- admins.grant(f.game, theirs.playerId, peer.externalId)
            notTheirs <- refusal(admins.revoke(f.game, theirs.playerId, f.gameAdmin.externalId))
            notOwn <- refusal(admins.revoke(f.game, f.gameAdmin.playerId, f.gameAdmin.externalId))
            _ <- admins.revoke(f.game, mine.playerId, f.gameAdmin.externalId)
            now <- listed(f)
        } yield (notTheirs, notOwn, now == Set(f.gameAdmin.playerId, peer.playerId, theirs.playerId))
        val (notTheirs, notOwn, rightOnesLeft) = result.unsafeRunSync()
        assert(notTheirs.isInstanceOf[UnauthorizedError], notTheirs)
        assert(notOwn.isInstanceOf[UnauthorizedError], notOwn)
        assert(rightOnesLeft)
    }

    test("a game's admin who is no longer one cannot take back the admins they made") {
        val result = for {
            f <- fixture()
            mine <- register()
            _ <- admins.grant(f.game, mine.playerId, f.gameAdmin.externalId)
            _ <- admins.revoke(f.game, f.gameAdmin.playerId, f.overall.externalId)
            refused <- refusal(admins.revoke(f.game, mine.playerId, f.gameAdmin.externalId))
            now <- listed(f)
        } yield (refused, now == Set(mine.playerId))
        val (refused, kept) = result.unsafeRunSync()
        assert(refused.isInstanceOf[UnauthorizedError], refused)
        assert(kept)
    }

    test("making an admin again keeps who made them one first, and so who may take it back") {
        val result = for {
            f <- fixture()
            mine <- register()
            _ <- admins.grant(f.game, mine.playerId, f.gameAdmin.externalId)
            _ <- admins.grant(f.game, mine.playerId, f.overall.externalId)
            by <- admins
                .list(f.game, f.overall.externalId)
                .map(_.find(_.player.playerId == mine.playerId).map(_.grantedBy))
            _ <- admins.revoke(f.game, mine.playerId, f.gameAdmin.externalId)
            now <- listed(f)
        } yield (by, f.gameAdmin, now)
        val (by, gameAdmin, now) = result.unsafeRunSync()
        assertEquals(by, Some(PublicPlayer(gameAdmin.playerId, gameAdmin.nickname)))
        assertEquals(now, Set(gameAdmin.playerId))
    }

    test("an admin takes a player's admin of a game away, and a second revoke finds nothing to take") {
        val result = for {
            f <- fixture()
            _ <- admins.revoke(f.game, f.gameAdmin.playerId, f.overall.externalId)
            now <- listed(f)
            again <- refusal(admins.revoke(f.game, f.gameAdmin.playerId, f.overall.externalId))
            // And having lost it, they can no longer make admins.
            other <- register()
            lost <- refusal(admins.grant(f.game, other.playerId, f.gameAdmin.externalId))
        } yield (now, again, lost)
        val (now, again, lost) = result.unsafeRunSync()
        assertEquals(now, Set.empty[PlayerId])
        assert(again.isInstanceOf[NotFoundError], again)
        assert(lost.isInstanceOf[UnauthorizedError], lost)
    }

    test("making an admin of somebody who already is one changes nothing") {
        val result = for {
            f <- fixture()
            _ <- admins.grant(f.game, f.gameAdmin.playerId, f.overall.externalId)
            _ <- admins.grant(f.game, f.gameAdmin.playerId, f.gameAdmin.externalId)
            now <- listed(f)
        } yield (now, f.gameAdmin.playerId)
        val (now, gameAdmin) = result.unsafeRunSync()
        assertEquals(now, Set(gameAdmin))
    }

    test("an unknown player or game is not found, and an unregistered caller is refused") {
        val result = for {
            f <- fixture()
            noPlayer <- refusal(admins.grant(f.game, PlayerId(Long.MaxValue), f.overall.externalId))
            noGame <- refusal(admins.grant(GameId(Int.MaxValue), f.gameAdmin.playerId, f.overall.externalId))
            listNoGame <- refusal(admins.list(GameId(Int.MaxValue), f.overall.externalId))
            nobody <- refusal(admins.list(f.game, unique("nobody")))
        } yield (noPlayer, noGame, listNoGame, nobody)
        val (noPlayer, noGame, listNoGame, nobody) = result.unsafeRunSync()
        assert(noPlayer.isInstanceOf[NotFoundError], noPlayer)
        assert(noGame.isInstanceOf[NotFoundError], noGame)
        assert(listNoGame.isInstanceOf[NotFoundError], listNoGame)
        assert(nobody.isInstanceOf[UnauthorizedError], nobody)
    }
}
