package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.Instant
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, MatchRepo, PlayerRepo, TestSession}

/** A game's admin offering a match they will not play in (a seatless challenge), and offering one that is not friendly
  * — both of which only a game's admin may do.
  */
class HostedChallengeSpec extends PropertySuite {
    TestMigration.ensure()

    /* A ceiling on a case that has hung, not on how long one should take: each case builds a game,
     * several registrations, a challenge, acceptances and a start. See CLAUDE.md. */
    private val caseTimeout = 60.seconds

    private class StubEngine extends GameEngineClient {
        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(GameStatusResponse(completed = false, participants = Nil))
    }

    private val services = TestServices.servicesWith(new StubEngine)

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def register(): IO[Player] = services.registration.register(unique("hc"), unique("hc-sub"))

    private def makeGame(gameType: GameType): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                gameType,
                "Duel",
                "Duel",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "first", optional = false, displayName = "First"),
                  GameRole(GameRoleId(0), GameId.unassigned, "second", optional = false, displayName = "Second")
                ),
                Seq.empty,
                unique("duel")
              )
            )
        }

    /** A plain game of two required roles, an admin of it, and two players to invite. */
    private case class Fixture(game: Game, host: Player, first: Player, second: Player)

    private def fixture(): IO[Fixture] =
        for {
            game <- makeGame(GameType.Plain)
            overall <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(overall.copy(isAdmin = true)))
            host <- register()
            _ <- services.gameAdmins.grant(game.gameId, host.playerId, overall.externalId)
            first <- register()
            second <- register()
        } yield Fixture(game, host, first, second)

    private def offer(f: Fixture, by: Player, seat: Option[GameRoleId], friendly: Boolean): PlainChallenge =
        PlainChallenge(
          ChallengeId(0),
          by.playerId,
          "a match for you two",
          start = None,
          timeLimit = None,
          settings = "{}",
          gameId = f.game.gameId,
          gameRoleId = seat,
          isOpen = false,
          friendly = friendly
        )

    private def invitations(f: Fixture): Seq[Invite] =
        Seq(
          Invite(f.first.playerId, Some(f.game.roles(0).gameRoleId)),
          Invite(f.second.playerId, Some(f.game.roles(1).gameRoleId))
        )

    test("a game's admin offers a match they will not play in, the invited players take it, and it starts unfriendly") {
        val result = for {
            f <- fixture()
            created <- services.challenges.create(
              offer(f, f.host, None, friendly = false),
              f.host.externalId,
              invitations(f)
            )
            _ <- services.challenges.accept(
              f.game.gameId,
              created.challengeId,
              None,
              f.game.roles(0).gameRoleId,
              f.first.externalId
            )
            _ <- services.challenges.accept(
              f.game.gameId,
              created.challengeId,
              None,
              f.game.roles(1).gameRoleId,
              f.second.externalId
            )
            // Full, and the host holds no seat in it: still theirs to see, or they could not start it.
            listed <- services.challenges.listByGame(f.game.gameId, f.host.externalId)
            started <- services.engine.start(f.game.gameId, created.challengeId, f.host.externalId)
            stored <- TestSession.resource.use(session => new MatchRepo(session).read(f.game.gameId, started.matchId))
            players <- TestSession.resource.use(session =>
                new PlayerRepo(session).listForMatch(f.game.gameId, started.matchId)
            )
        } yield (f, created, listed, stored, players)
        val (f, created, listed, stored, players) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(created.gameRoleId, None)
        assertEquals(
          listed.find(_.challenge.challengeId == created.challengeId).map(_.challenge.gameRoleId),
          Some(None)
        )
        assertEquals(stored.map(_.friendly), Some(false))
        assertEquals(players.map(_.playerId).toSet, Set(f.first.playerId, f.second.playerId))
    }

    test("a player who took a seat in a seatless challenge may still give it up") {
        val result = for {
            f <- fixture()
            created <- services.challenges.create(
              offer(f, f.host, None, friendly = true),
              f.host.externalId,
              invitations(f)
            )
            _ <- services.challenges.accept(
              f.game.gameId,
              created.challengeId,
              None,
              f.game.roles(0).gameRoleId,
              f.first.externalId
            )
            _ <- services.acceptances.delete(f.game.gameId, created.challengeId, f.first.playerId, f.first.externalId)
            listed <- services.challenges.listByGame(f.game.gameId, f.host.externalId)
        } yield listed.find(_.challenge.challengeId == created.challengeId).map(_.acceptances)
        assertEquals(result.timeout(caseTimeout).unsafeRunSync(), Some(0))
    }

    test("a game's admin may offer an unfriendly match they play in themselves") {
        val result = for {
            f <- fixture()
            created <- services.challenges.create(
              offer(f, f.host, Some(f.game.roles(0).gameRoleId), friendly = false),
              f.host.externalId,
              Seq(Invite(f.second.playerId, Some(f.game.roles(1).gameRoleId)))
            )
            _ <- services.challenges.accept(
              f.game.gameId,
              created.challengeId,
              None,
              f.game.roles(1).gameRoleId,
              f.second.externalId
            )
            started <- services.engine.start(f.game.gameId, created.challengeId, f.host.externalId)
        } yield started.friendly
        assertEquals(result.timeout(caseTimeout).unsafeRunSync(), false)
    }

    test("a player who administers nothing may offer neither a seatless match nor an unfriendly one") {
        val result = for {
            f <- fixture()
            seatless <- services.challenges
                .create(offer(f, f.first, None, friendly = true), f.first.externalId, invitations(f).tail)
                .attempt
            unfriendly <- services.challenges
                .create(
                  offer(f, f.first, Some(f.game.roles(0).gameRoleId), friendly = false),
                  f.first.externalId,
                  invitations(f).tail
                )
                .attempt
            // An ordinary challenge is still theirs to make.
            ordinary <- services.challenges
                .create(
                  offer(f, f.first, Some(f.game.roles(0).gameRoleId), friendly = true),
                  f.first.externalId,
                  invitations(f).tail
                )
                .attempt
        } yield (seatless, unfriendly, ordinary)
        val (seatless, unfriendly, ordinary) = result.timeout(caseTimeout).unsafeRunSync()
        assert(seatless.left.exists(_.isInstanceOf[UnauthorizedError]), seatless)
        assert(unfriendly.left.exists(_.isInstanceOf[UnauthorizedError]), unfriendly)
        assert(ordinary.isRight, ordinary)
    }

    test("an admin of another game may not offer a seatless match in this one") {
        val result = for {
            f <- fixture()
            other <- fixture()
            refused <- services.challenges
                .create(offer(f, other.host, None, friendly = true), other.host.externalId, invitations(f))
                .attempt
        } yield refused
        val refused = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
    }

    test("a character game has no seatless challenge, even for its admin") {
        val result = for {
            game <- makeGame(GameType.Character)
            overall <- register()
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(overall.copy(isAdmin = true)))
            character <- TestSession.resource.use(session =>
                new CharacterRepo[String](session).create(
                  Character(CharacterId(0), game.gameId, "boxer", "description", "", Some(overall.playerId))
                )
            )
            refused <- services.challenges
                .create(
                  CharacterChallenge(
                    ChallengeId(0),
                    overall.playerId,
                    "message",
                    None,
                    None,
                    "{}",
                    game.gameId,
                    character.characterId,
                    gameRoleId = None
                  ),
                  overall.externalId
                )
                .attempt
        } yield refused
        val refused = result.timeout(caseTimeout).unsafeRunSync()
        assert(refused.left.exists(_.isInstanceOf[ValidationError]), refused)
    }
}
