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
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

/** Who is written to when a challenge's roster changes, and which of the competing things they are told.
  *
  * What the mails say is `ChallengeMailSpec`, with no database. This is the other half: real acceptances, a real
  * withdrawal, and the rule that one event is one email per person however many reasons there are to write.
  */
class ChallengeNotificationSpec extends PropertySuite {
    TestMigration.ensure()

    private val caseTimeout = 60.seconds

    private class StubEngine(fail: Boolean = false) extends GameEngineClient {
        def createGame(gameUrl: String, request: CreateGameRequest): IO[CreateGameResponse] =
            if (fail) IO.raiseError(new RuntimeException("engine says no"))
            else IO.pure(CreateGameResponse("https://engine/status/1", "https://engine/play/1", None))

        def status(statusUrl: String, since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(GameStatusResponse(completed = false, participants = Nil))
    }

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    /* Three required roles, so that the roster can be half full: the challenger takes one at
     * creation, and it then takes two more acceptances to fill -- which is what makes "somebody
     * accepted" and "it is ready to start" two separate events to be told apart. */
    private def makeGame(seed: String): IO[Game] =
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
                  GameRole(GameRoleId(0), GameId.unassigned, "defender", optional = false),
                  GameRole(GameRoleId(0), GameId.unassigned, "healer", optional = false)
                ),
                Seq.empty,
                s"game-$seed"
              )
            )
        }

    /** A challenge in a new game with its challenger and two more registered players, all of them reachable.
      *
      * Nobody has accepted yet beyond the challenger, whose own acceptance is written with the challenge.
      */
    private case class Fixture(
        services: Services[String],
        notifier: RecordingNotifier,
        game: Game,
        challenge: OpenChallenge,
        challenger: Player,
        second: Player,
        third: Player
    ) {
        def address(player: Player): String = player.email.get
    }

    private def fixture(seed: String, autoStart: Boolean = false, engineFails: Boolean = false): IO[Fixture] = {
        val notifier = new RecordingNotifier
        val services = TestServices.servicesWith(
          new StubEngine(engineFails),
          callbackBaseUrl = Some("https://matchmaker.example.com"),
          notifier = notifier,
          mail = TestServices.mailSettings
        )

        for {
            game <- makeGame(seed)
            challenger <- services.registration
                .register(s"challenger-$seed", s"challenger-$seed", Some(s"challenger-$seed@example.com"))
            second <- services.registration
                .register(s"second-$seed", s"second-$seed", Some(s"second-$seed@example.com"))
            third <- services.registration
                .register(s"third-$seed", s"third-$seed", Some(s"third-$seed@example.com"))
            challenge <- services.challenges.create(
              PlainOpenChallenge(
                ChallengeId(0),
                challenger.playerId,
                "friendly game",
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                isPublic = true,
                gameRoleId = game.roles.head.gameRoleId,
                timeLimitKind = TimeLimitKind.PerTurn,
                timeLimitUnit = TimeLimitUnit.Minutes,
                autoStart = autoStart
              ),
              s"challenger-$seed"
            )
            // Creating a challenge tells nobody: there is nobody yet to tell.
            _ <- IO(notifier.clear())
        } yield Fixture(services, notifier, game, challenge, challenger, second, third)
    }

    private def accept(f: Fixture, who: Player, role: Int): IO[Acceptance] =
        f.services.challenges.accept(
          f.game.gameId,
          f.challenge.challengeId,
          None,
          f.game.roles(role).gameRoleId,
          who.externalId
        )

    property("an acceptance tells the challenger, and nobody else who is not in it yet") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                accept(f, f.second, 1).map { _ =>
                    f.notifier.recipients == Set(f.address(f.challenger)) &&
                    f.notifier.messages.size == 1 &&
                    f.notifier.messages.head.subject == s"second-$seed has accepted your Tic-Tac-Toe challenge" &&
                    // Two of the three roles are taken, so it is not ready and the mail says what is
                    // still missing rather than asking them to start it.
                    f.notifier.messages.head.body.contains("Still waiting for: healer.")
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The acceptance that fills the roster is news to everyone in it: the challenger can start it,
    // and the other acceptor is now waiting on them rather than on a missing player.
    property("the acceptance that fills the roster tells everyone but the player who made it") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                } yield {
                    val bySubject = f.notifier.messages.map(m => m.recipient -> m.subject).toMap
                    f.notifier.recipients == Set(f.address(f.challenger), f.address(f.second)) &&
                    bySubject(f.address(f.challenger)) == "Your Tic-Tac-Toe challenge is ready to start" &&
                    bySubject(f.address(f.second)) == "A Tic-Tac-Toe challenge you accepted is ready to start"
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The rule that one event is one email. Filling the last role is two things to tell the
     * challenger -- somebody accepted, and it can be started -- and they are owed one mail. */
    property("a player is written to once however many reasons there are") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                } yield f.notifier.messages.count(_.recipient == f.address(f.challenger)) == 1
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* And what "in the order of how much they say" buys: a challenger who does not want to be told
     * their challenge is ready still hears that somebody accepted, in the plainer mail. The
     * alternative -- pick the fullest reason, then test it -- would send them nothing. */
    property("refusing the fuller notification falls back to the plainer one") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- f.services.notifications.updateMine(
                      f.challenger.externalId,
                      NotificationPreferences.unset.updated(NotificationType.ChallengeReady, Some(false))
                    )
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                } yield {
                    val mine = f.notifier.messages.filter(_.recipient == f.address(f.challenger))
                    mine.size == 1 && mine.head.subject == s"third-$seed has accepted your Tic-Tac-Toe challenge"
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    property("a player who wants neither reason is not written to at all") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- f.services.notifications.updateMine(
                      f.challenger.externalId,
                      NotificationPreferences.unset
                          .updated(NotificationType.ChallengeReady, Some(false))
                          .updated(NotificationType.ChallengeAccepted, Some(false))
                    )
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                } yield f.notifier.recipients == Set(f.address(f.second))
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // Backing out is the same event in reverse, and is deliberately never "ready to start": a
    // roster that is still full because an optional role was freed is not news worth that sentence.
    property("backing out tells the others, and names the role it freed") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- accept(f, f.third, 2)
                    _ <- IO(f.notifier.clear())
                    _ <- f.services.acceptances.delete(
                      f.game.gameId,
                      f.challenge.challengeId,
                      f.third.playerId,
                      f.third.externalId
                    )
                } yield {
                    val bySubject = f.notifier.messages.map(m => m.recipient -> m.subject).toMap
                    f.notifier.recipients == Set(f.address(f.challenger), f.address(f.second)) &&
                    bySubject(f.address(f.challenger)) == s"third-$seed has backed out of your Tic-Tac-Toe challenge" &&
                    f.notifier.messages.forall(_.body.contains("Still waiting for: healer."))
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // A challenger may remove somebody else's acceptance. The news is the same -- a seat has opened
    // -- but it is not news to the person who did it.
    property("a challenger removing an acceptance is not told about their own doing") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- accept(f, f.third, 2)
                    _ <- IO(f.notifier.clear())
                    _ <- f.services.acceptances.delete(
                      f.game.gameId,
                      f.challenge.challengeId,
                      f.third.playerId,
                      f.challenger.externalId
                    )
                } yield f.notifier.recipients == Set(f.address(f.second))
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The game is the end of the chain here as everywhere else.
    property("a game that asks for silence about acceptances gets it") {
        forAll(genUniqueString) { seed =>
            val result = for {
                f <- fixture(seed)
                _ <- f.services.games.createOrUpdate(
                  adminOf(f),
                  f.game.copy(notifications = NotificationDefaults.all(false))
                )
                _ <- IO(f.notifier.clear())
                _ <- accept(f, f.second, 1)
            } yield f.notifier.messages.isEmpty
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* A challenge offered as starting itself, which changes both halves of this: the acceptance
     * that fills the roster is also the start, and what its players are told is about a match rather
     * than about a challenge somebody could choose to start. */
    property("a challenge that starts itself turns the last acceptance into a match") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed, autoStart = true).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                    claimed <- TestSession.resource.use(session =>
                        new com.vivi.matchmaker.persistence.OpenChallengeRepo(session)
                            .readForUpdate(f.game.gameId, f.challenge.challengeId)
                    )
                } yield {
                    val sent = f.notifier.messages
                    // The challenge is spent: something started it, and nobody pressed Start.
                    claimed.flatMap(_.startedMatchId).isDefined &&
                    // One mail each and no more. Counted rather than looked up by recipient, because
                    // the fault this is here to catch is a second mail to the same player: the
                    // acceptance that fills the roster is the match beginning, not two events.
                    sent.size == 3 &&
                    // Everyone in it, the challenger included -- on this path they are not the person
                    // who did it, so the mail about the match is theirs like anybody's.
                    sent.map(_.recipient).toSet ==
                        Set(f.address(f.challenger), f.address(f.second), f.address(f.third)) &&
                        // One mail saying both things, in the order they happened: who accepted, and then
                        // that the match is under way. The plain match-started wording would leave a
                        // player to work out who they are suddenly playing.
                        sent.forall(m =>
                            m.subject ==
                                s"third-$seed has accepted the Tic-Tac-Toe challenge, and the match has started" &&
                                m.body.contains(
                                  s"third-$seed has accepted the Tic-Tac-Toe challenge, so your match has"
                                ) &&
                                m.body.contains("Playing with you:")
                        )
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* An auto-start that does not happen, which is the case the ordinary mail must survive: the
     * challenge is still there and still startable by hand, so the challenger is owed the one
     * notification that says so. */
    property("an auto-start that fails leaves the acceptance to be notified as usual") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed, autoStart = true, engineFails = true).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- IO(f.notifier.clear())
                    _ <- accept(f, f.third, 2)
                    claimed <- TestSession.resource.use(session =>
                        new com.vivi.matchmaker.persistence.OpenChallengeRepo(session)
                            .readForUpdate(f.game.gameId, f.challenge.challengeId)
                    )
                } yield {
                    val bySubject = f.notifier.messages.map(m => m.recipient -> m.subject).toMap
                    // The failed start released its claim, so the challenge is startable again.
                    claimed.flatMap(_.startedMatchId).isEmpty &&
                    bySubject(f.address(f.challenger)) == "Your Tic-Tac-Toe challenge is ready to start" &&
                    bySubject(f.address(f.second)) == "A Tic-Tac-Toe challenge you accepted is ready to start"
                }
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* The race the answer's three cases exist for: a second call finds the challenge already claimed,
     * and must say so rather than saying "not started".
     *
     * Asked of `startIfReady` directly because that is where the two are told apart, and because the
     * race itself -- two acceptances filling the last two seats at the same instant -- cannot be
     * staged reliably. What it pins is the consequence: the second answer counts as a match, so the
     * acceptance that produced it sends no mail about a challenge that is waiting to start. */
    property("a start that loses to an existing claim is a match, not a challenge still open") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed, autoStart = true).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- accept(f, f.third, 2)
                    // The acceptance above has already started it; this is the loser of the race.
                    again <- TestSession.resource.use(session =>
                        f.services.engine.startIfReady(session, f.game.gameId, f.challenge.challengeId, f.third)
                    )
                } yield again == GameEngineService.AutoStart.AlreadyStarted && again.isMatch
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    // The default, stated as a test rather than left to the column's DEFAULT: filling the roster of
    // an ordinary challenge starts nothing, and its challenger is asked to.
    property("an ordinary challenge leaves the start to its challenger") {
        forAll(genUniqueString) { seed =>
            val result = fixture(seed).flatMap { f =>
                for {
                    _ <- accept(f, f.second, 1)
                    _ <- accept(f, f.third, 2)
                    claimed <- TestSession.resource.use(session =>
                        new com.vivi.matchmaker.persistence.OpenChallengeRepo(session)
                            .readForUpdate(f.game.gameId, f.challenge.challengeId)
                    )
                } yield claimed.flatMap(_.startedMatchId).isEmpty
            }
            result.timeout(caseTimeout).unsafeRunSync()
        }
    }

    /* Editing the game needs an admin, and the fixture's players are not. Registered here rather
     * than in the fixture because only one property needs one. */
    private def adminOf(f: Fixture): String = {
        val externalId = s"admin-${f.game.externalId}"
        TestSession.resource
            .use { session =>
                val repo = new com.vivi.matchmaker.persistence.PlayerRepo(session)
                repo.create(Player(PlayerId(0), s"admin-${f.game.externalId}", isAdmin = true, externalId, None))
            }
            .unsafeRunSync()
        externalId
    }
}
