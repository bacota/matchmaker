package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.Instant
import org.scalacheck.Gen
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.RecordingNotifier
import com.vivi.matchmaker.persistence.{GameRepo, ParticipantRepo, TestSession}

/** Who is written to while a match is being played, and when it stops.
  *
  * What those mails say is `MatchMailSpec`, with no database. This is the deciding half: a real start, a real move
  * callback, a real result, and the rule that one event is one email per person.
  */
class MatchNotificationSpec extends PropertySuite {
    TestMigration.ensure()

    private val caseTimeout = 60.seconds

    /* An engine that answers whatever the test needs it to. `status` is asked once by `start` and
     * again by `refresh`, and what it says decides whose turn it is -- which is the difference
     * between a match that is being played and one that has quietly finished. */
    private class StubEngine(answer: () => GameStatusResponse = () => GameStatusResponse(false, Nil))
        extends GameEngineClient {
        def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
            IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

        def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] = IO(answer())
    }

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private def makeGame(seed: String, timeoutAction: TimeoutAction = TimeoutAction.Forfeit): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "Tic-Tac-Toe",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "attacker", optional = false),
                  GameRole(GameRoleId(0), GameId.unassigned, "defender", optional = false)
                ),
                Seq.empty,
                s"game-$seed",
                timeoutAction = timeoutAction
              )
            )
        }

    /** A started two-player match, with the record of what the setting-up sent already cleared.
      *
      * `seats` is in seat order, which is the order the two players were written in: the challenger first, since their
      * acceptance is created with the challenge.
      */
    private case class Fixture(
        services: Services[String],
        notifier: RecordingNotifier,
        game: Game,
        played: Match,
        challenger: Player,
        accepter: Player,
        seats: List[Participant]
    ) {
        def address(player: Player): String = player.email.get
        def seatOf(player: Player): ParticipantId = seats.find(_.playerId == player.playerId).get.participantId
    }

    private def fixture(
        seed: String,
        engine: GameEngineClient = new StubEngine(),
        timeLimit: Option[java.time.Duration] = None
    ): IO[Fixture] = {
        val notifier = new RecordingNotifier
        val services = TestServices.servicesWith(
          engine,
          callbackBaseUrl = Some("https://matchmaker.example.com"),
          notifier = notifier,
          mail = TestServices.mailSettings
        )

        for {
            game <- makeGame(seed)
            challenger <- services.registration
                .register(s"challenger-$seed", s"challenger-$seed", Some(s"challenger-$seed@example.com"))
            accepter <- services.registration
                .register(s"accepter-$seed", s"accepter-$seed", Some(s"accepter-$seed@example.com"))
            challenge <- services.challenges.create(
              PlainOpenChallenge(
                ChallengeId(0),
                challenger.playerId,
                "friendly game",
                start = None,
                timeLimit = timeLimit,
                settings = "{}",
                gameId = game.gameId,
                isPublic = true,
                gameRoleId = game.roles.head.gameRoleId,
                timeLimitKind = TimeLimitKind.PerTurn,
                timeLimitUnit = TimeLimitUnit.Minutes
              ),
              s"challenger-$seed"
            )
            _ <- services.challenges.accept(
              game.gameId,
              challenge.challengeId,
              None,
              game.roles(1).gameRoleId,
              s"accepter-$seed"
            )
            played <- services.engine.start(game.gameId, challenge.challengeId, s"challenger-$seed")
            seats <- TestSession.resource.use { session =>
                new ParticipantRepo(session).listForMatch(game.gameId, played.matchId).map(_.map((p, _, _) => p))
            }
            // The accept and the start have both sent mail of their own. Every property below is
            // about what happens next.
            _ <- IO(notifier.clear())
        } yield Fixture(services, notifier, game, played, challenger, accepter, seats)
    }

    private def move(f: Fixture, moved: ParticipantId, next: List[ParticipantId]): IO[Unit] =
        f.services.engine.recordMove(
          f.game.gameId,
          f.played.matchId,
          moved,
          next,
          takenAt = Instant.now(),
          startedAt = Instant.now().minusSeconds(30),
          callerExternalId = f.game.externalId
        )

    property("a move tells the player whose turn it now is, and not the player who made it") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                move(f, f.seatOf(f.challenger), List(f.seatOf(f.accepter))).map { _ =>
                    f.notifier.recipients == Set(f.address(f.accepter)) &&
                    f.notifier.messages.size == 1 &&
                    f.notifier.messages.head.subject == "It is your turn in your Tic-Tac-Toe match" &&
                    f.notifier.messages.head.body.contains(s"challenger-$seed has moved, and it is your turn")
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* A move is two reasons to write to whoever is up next -- somebody moved, and it is your turn --
     * and they are owed one mail. The plainer one is what everybody else would get; in a match of
     * two there is nobody else, which is why the count is the assertion. */
    property("the player whose turn it is gets one mail, not two") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                move(f, f.seatOf(f.challenger), List(f.seatOf(f.accepter))).map { _ =>
                    f.notifier.messages.count(_.recipient == f.address(f.accepter)) == 1
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Turning off "it is my turn" is not turning off "somebody moved": the player still hears that
    // the match has moved on, in the plainer mail. This is `NotificationPolicy.choose` end to end.
    //
    // Said after the match had started, so `applyToMatches` is what carries it into the seat -- the
    // seat holds its own answers now, and a change to the player's defaults reaches a match already
    // being played only because they asked for it to. Which makes this the cascade end to end as well.
    property("refusing your-turn still gets the plainer move notification") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- f.services.notifications.updateMine(
                      f.accepter.externalId,
                      NotificationPreferences.unset.updated(NotificationType.YourTurn, Some(false)),
                      applyToMatches = true
                    )
                    _ <- move(f, f.seatOf(f.challenger), List(f.seatOf(f.accepter)))
                } yield {
                    val mine = f.notifier.messages.filter(_.recipient == f.address(f.accepter))
                    mine.size == 1 &&
                    mine.head.subject == s"challenger-$seed has moved in your Tic-Tac-Toe match" &&
                    mine.head.body.contains(s"It is now accepter-$seed's turn.")
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The most specific level of the chain, and the one this is the whole point of: a player who has
    // muted this one match hears nothing about it while still hearing about everything else.
    property("a player who has muted one match is not written to about it") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- f.services.notifications.updateForMatch(
                      f.accepter.externalId,
                      f.game.gameId,
                      f.played.matchId,
                      NotificationDefaults.all(true).copy(yourTurn = false, turnTaken = false)
                    )
                    _ <- move(f, f.seatOf(f.challenger), List(f.seatOf(f.accepter)))
                } yield f.notifier.messages.isEmpty
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // A result is nobody in the match's doing, so everybody in it is told.
    property("a result tells everyone in the match") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                f.services.engine
                    .recordResults(
                      f.game.gameId,
                      f.played.matchId,
                      List(
                        ReportedResult(f.seatOf(f.challenger), 1, Map.empty, isWinner = true),
                        ReportedResult(f.seatOf(f.accepter), 2, Map.empty, isWinner = false)
                      ),
                      f.game.externalId
                    )
                    .map { _ =>
                        f.notifier.recipients == Set(f.address(f.challenger), f.address(f.accepter)) &&
                        f.notifier.messages.forall(_.subject == "Your Tic-Tac-Toe match is over") &&
                        f.notifier.messages.forall(_.body.contains("The game is over."))
                    }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The callback may be retried, and the write is idempotent. So is the mail: only the call that
    // actually ended the match is news.
    property("a repeated result callback does not write to anybody twice") {
        forAll(genUniqueString) { seed =>
            val results = (f: Fixture) =>
                f.services.engine.recordResults(
                  f.game.gameId,
                  f.played.matchId,
                  List(ReportedResult(f.seatOf(f.challenger), 1, Map.empty, isWinner = true)),
                  f.game.externalId
                )

            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- results(f)
                    sent = f.notifier.messages.size
                    _ <- results(f)
                } yield sent == 2 && f.notifier.messages.size == sent
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Cancelling is somebody in the match's doing, so that somebody is the one person not told.
    property("cancelling tells everyone but the creator who did it") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                f.services.matches.cancel(f.game.gameId, f.played.matchId, f.challenger.externalId).map { _ =>
                    f.notifier.recipients == Set(f.address(f.accepter)) &&
                    f.notifier.messages.head.body.contains("Its creator has called it off.")
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The recovery path: the engine finished the match and the results callback never arrived, so
     * nobody knew until somebody pressed Refresh. Without this the players would never be told at
     * all -- and with it, every later Refresh must not tell them again. */
    property("a completion discovered by refresh is news once and only once") {
        forAll(genUniqueString) { seed =>
            @volatile var completed = false
            val engine = new StubEngine(() => GameStatusResponse(completed, Nil))

            val result = fixture(seed, engine).flatMap { f =>
                for {
                    _ <- IO { completed = true }
                    _ <- f.services.engine.refresh(f.game.gameId, f.played.matchId, f.challenger.externalId)
                    afterFirst = f.notifier.messages.size
                    _ <- f.services.engine.refresh(f.game.gameId, f.played.matchId, f.challenger.externalId)
                } yield afterFirst == 2 &&
                    f.notifier.messages.size == afterFirst &&
                    f.notifier.recipients == Set(f.address(f.challenger), f.address(f.accepter))
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }
}
