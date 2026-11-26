package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.time.Instant
import munit.FunSuite
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.{MailMessage, MailSettings, Notifications, RecordingNotifier}
import com.vivi.matchmaker.persistence.{GameAdminRepo, GameRepo, Repos, TestSession}
import com.vivi.matchmaker.util.{Site, Sites}

/** Mail sends everyone in a tournament to the game site it was made on, and calls the site by its heading; a tournament
  * made on the main domain sends them to `UI_BASE_URL` and says "matchmaker". See design/game-sites-plan.md, step 6b.
  *
  * What the mails say is `MatchMailSpec` and `ChallengeMailSpec`; this is which site each event's mail is given.
  */
class SiteMailSpec extends FunSuite {
    TestMigration.ensure()

    private val caseTimeout = 60.seconds

    private def run[A](io: IO[A]): A = io.timeout(caseTimeout).unsafeRunSync()

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private object StubEngine extends GameEngineClient {
        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.pure(
              CreateGameResponse(
                statusUrl = "https://engine/status/1",
                playUrl = "https://engine/play/1",
                publicUrl = None
              )
            )

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(GameStatusResponse(isCompleted = false, participants = Nil))
    }

    private val mainUrl = "https://matchmaker.example.com"
    private val siteOrigin = "https://ctf.example"
    private val siteHeading = "Capture the Flag"

    /** A two-player game of its own, under a name only it has, so that the site below lists it and no other spec's. */
    private def makeGame(): IO[Game] = {
        val handle = unique("ctf")
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                gameId = GameId.unassigned,
                gameType = GameType.Plain,
                name = handle,
                displayName = "Flags",
                description = "description",
                url = "https://engine.example.com/games",
                isActive = true,
                Seq(
                  GameRole(
                    gameRoleId = GameRoleId(0),
                    gameId = GameId.unassigned,
                    name = "red",
                    roleType = RoleType.Required,
                    displayName = "red"
                  ),
                  GameRole(
                    gameRoleId = GameRoleId(0),
                    gameId = GameId.unassigned,
                    name = "blue",
                    roleType = RoleType.Required,
                    displayName = "blue"
                  )
                ),
                Seq.empty,
                unique("game")
              )
            )
        }
    }

    private case class Fixture(
        game: Game,
        site: Site,
        mail: MailSettings,
        notifier: RecordingNotifier,
        services: Services[String],
        red: Player,
        blue: Player
    )

    private def fixture(): IO[Fixture] =
        for {
            game <- makeGame()
            site = Site(origin = siteOrigin, heading = siteHeading, games = List(game.name))
            mail = MailSettings(
              sender = Some("matchmaker@example.com"),
              uiBaseUrl = Some(mainUrl),
              sites = Sites(Map(site.origin -> site))
            )
            notifier = new RecordingNotifier
            services = TestServices.servicesInline(
              engine = StubEngine,
              callbackBaseUrl = Some(mainUrl),
              notifier = notifier,
              mail = mail
            )
            red <- register(services, "red")
            blue <- register(services, "blue")
        } yield Fixture(
          game = game,
          site = site,
          mail = mail,
          notifier = notifier,
          services = services,
          red = red,
          blue = blue
        )

    private def register(services: Services[String], prefix: String): IO[Player] = {
        val name = unique(prefix)
        services.registration.register(nickname = name, externalId = name, email = Some(s"$name@example.com"))
    }

    /** A challenge between the fixture's two players, started, on `site` or the main domain. */
    private def started(f: Fixture, site: Option[Site]): IO[TournamentId] =
        Challenges
            .startedMatch(
              services = f.services,
              game = f.game,
              seats = List(
                Challenges.Seat(player = f.red, role = f.game.roles(0).gameRoleId),
                Challenges.Seat(player = f.blue, role = f.game.roles(1).gameRoleId)
              ),
              site = site
            )
            .map(_._1)

    private def tournament(f: Fixture, id: TournamentId): IO[Tournament] =
        TestSession.resource.use(session => Repos(session).tournaments.read(f.game.gameId, id).map(_.get))

    private def isOnSite(message: MailMessage): Boolean =
        message.body.contains(s"Open $siteHeading: $siteOrigin") && !message.body.contains("matchmaker")

    private def isOnMain(message: MailMessage): Boolean =
        message.body.contains(s"Open matchmaker: $mainUrl") && !message.body.contains(siteHeading)

    private def startMails(f: Fixture): List[MailMessage] =
        f.notifier.messages.filter(_.subject.contains("has started"))

    test("a match in a challenge made on a site that shows its game sends every seat to that site") {
        val f = run(fixture())
        run(started(f, Some(f.site)))

        val starts = startMails(f)
        assertEquals(starts.map(_.recipient).toSet, Set(f.red.email.get, f.blue.email.get), f.notifier.messages)
        assert(f.notifier.messages.forall(isOnSite), f.notifier.messages.map(_.body).mkString("\n---\n"))
    }

    test("a match in a challenge made on the main domain sends every seat to matchmaker") {
        val f = run(fixture())
        run(started(f, None))

        val starts = startMails(f)
        assertEquals(starts.map(_.recipient).toSet, Set(f.red.email.get, f.blue.email.get), f.notifier.messages)
        assert(f.notifier.messages.forall(isOnMain), f.notifier.messages.map(_.body).mkString("\n---\n"))
    }

    test("a site that does not show the game is not where its mail goes") {
        val f = run(fixture())
        val elsewhere = Site(origin = siteOrigin, heading = siteHeading, games = List("some-other-game"))
        val id = run(started(f, Some(f.site)))
        f.notifier.clear()

        // The same tournament, told about by a deployment whose site has since stopped showing the game.
        val notifications = new Notifications(
          f.notifier,
          f.mail.copy(sites = Sites(Map(elsewhere.origin -> elsewhere)))
        )
        val t = run(tournament(f, id))
        run(TestSession.resource.use(session => notifications.tournamentRoundDone(Repos(session), t, 1)))

        assertEquals(f.notifier.messages.size, 1, f.notifier.messages)
        assert(isOnMain(f.notifier.messages.head), f.notifier.messages.head.body)
    }

    test("a player who moves between sites gets each challenge's mail with that challenge's site") {
        val f = run(fixture())
        run(started(f, Some(f.site)))
        val onSite = startMails(f).filter(_.recipient == f.blue.email.get)
        f.notifier.clear()
        run(started(f, None))
        val onMain = startMails(f).filter(_.recipient == f.blue.email.get)

        assertEquals(onSite.size, 1, onSite)
        assertEquals(onMain.size, 1, onMain)
        assert(isOnSite(onSite.head), onSite.head.body)
        assert(isOnMain(onMain.head), onMain.head.body)
    }

    test("a tournament made on a site sends its owner's round-over mail to that site") {
        val f = run(fixture())
        val id = run(started(f, Some(f.site)))
        f.notifier.clear()

        val notifications = new Notifications(f.notifier, f.mail)
        val t = run(tournament(f, id))
        run(TestSession.resource.use(session => notifications.tournamentRoundDone(Repos(session), t, 1)))

        val mails = f.notifier.messages
        assertEquals(mails.map(_.recipient), List(f.red.email.get), mails)
        assert(mails.head.body.contains(s"open the tournament in $siteHeading"), mails.head.body)
        assert(isOnSite(mails.head), mails.head.body)
    }

    test(
      "a match not created sends the owner to the tournament's site and an admin who is not the owner to the main site"
    ) {
        val f = run(fixture())
        val id = run(started(f, Some(f.site)))
        f.notifier.clear()

        val admin = run(register(f.services, "admin"))
        run(
          TestSession.resource.use(session =>
              new GameAdminRepo(session)
                  .grant(player = admin.playerId, game = f.game.gameId, grantedBy = admin.playerId)
          )
        )
        val notifications = new Notifications(f.notifier, f.mail)
        val t = run(tournament(f, id))
        run(TestSession.resource.use(session => notifications.matchNotCreated(Repos(session), t, f.game)))

        val toOwner = f.notifier.messages.filter(_.recipient == f.red.email.get)
        val toAdmin = f.notifier.messages.filter(_.recipient == admin.email.get)
        assertEquals(toOwner.size, 1, f.notifier.messages)
        assertEquals(toAdmin.size, 1, f.notifier.messages)
        assert(toOwner.head.body.contains(s"Open the tournament in $siteHeading"), toOwner.head.body)
        assert(isOnSite(toOwner.head), toOwner.head.body)
        assert(isOnMain(toAdmin.head), toAdmin.head.body)
    }
}
