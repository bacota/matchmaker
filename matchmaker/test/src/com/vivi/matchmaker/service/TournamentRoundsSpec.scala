package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import munit.FunSuite
import skunk.implicits._
import skunk.codec.all.text
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.RecordingNotifier
import com.vivi.matchmaker.persistence.{GameRepo, Generators, MatchRepo, TestSession, TournamentMatchRepo}

/** Tournaments played through their rounds, against the local database and a stub engine: rounds begun, roles chosen,
  * matches created and reported on, and the tournament advancing to its end.
  */
class TournamentRoundsSpec extends FunSuite with QuietTests {

    /** An engine that creates every game it is asked to, unless told to refuse the next few. */
    private class StubEngine extends GameEngineClient {
        val refusals = AtomicInteger(0)
        val created = AtomicInteger(0)
        @volatile var lastRequest: Option[CreateGameRequest] = None
        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.defer {
                if (refusals.getAndUpdate(n => math.max(0, n - 1)) > 0)
                    IO.raiseError(new RuntimeException("engine down"))
                else {
                    created.incrementAndGet()
                    lastRequest = Some(request)
                    IO.pure(
                      CreateGameResponse(s"https://engine/status/${request.matchId}", "https://engine/play", None)
                    )
                }
            }
        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(GameStatusResponse(isCompleted = false, participants = Nil))
    }

    /** The queue, run in the caller's fiber, so that by the time a call returns everything it queued has been done. */
    private class SyncEndings extends MatchEndings {
        @volatile var services: Services[String] = null
        def ended(gameId: GameId, matchId: MatchId): IO[Unit] = services.ending.settle(gameId, matchId).void
        def ratingsChanged(gameId: GameId): IO[Unit] = services.ending.rank(gameId).void
        override def tournamentMatchDue(g: GameId, t: TournamentId, m: MatchId): IO[Unit] =
            services.rounds.createMatch(g, t, m).void
        override def tournamentRoundDue(g: GameId, t: TournamentId): IO[Unit] = services.rounds.autoStart(g, t).void
        override def tournamentRoundCheck(g: GameId, t: TournamentId, at: Instant): IO[Unit] =
            services.rounds.runCheck(g, t).void
    }

    private case class World(services: Services[String], engine: StubEngine, mail: RecordingNotifier)

    private def world(): World = {
        val engine = StubEngine()
        val endings = SyncEndings()
        val mail = RecordingNotifier()
        val services = TestServices.servicesWith(
          engine,
          notifier = mail,
          mail = TestServices.mailSettings,
          matchEndings = Some(endings)
        )
        endings.services = services
        World(services, engine, mail)
    }

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private case class Fixture(w: World, game: Game, owner: Player, players: List[Player], t: Tournament)

    /** A plain two-role game, `n` players entered in a tournament of `design` owned by a further player, started. */
    private def started(
        n: Int,
        design: String = "single-elimination",
        roles: Boolean = true,
        settings: TournamentSettings =
            TournamentSettings(isInvitational = false, roundDuration = Some(Duration.ofDays(2)))
    ): IO[Fixture] = {
        val w = world()
        val s = w.services
        for {
            game <- TestSession.resource.use(
              new GameRepo[String](_)
                  .create(Generators.genGame(GameType.Plain).sample.get.copy(hasUnimportantRoles = !roles))
            )
            // Only the owner has an address, so the only mail sent is to them.
            owner <- s.registration.register(unique("owner"), unique("sub"), Some(s"${unique("owner")}@example.com"))
            players <- (1 to n).toList.traverse(_ => s.registration.register(unique("p"), unique("sub")))
            model <- TestSession.resource.use(TournamentFixtures.model(_, game, design))
            t <- s.tournaments.create(
              game.gameId,
              TournamentRequest("Cup", model.tournamentModelId, settings = settings),
              owner.externalId
            )
            _ <- s.tournaments.open(game.gameId, t.tournamentId, owner.externalId)
            _ <- players.traverse(p => s.tournaments.enter(game.gameId, t.tournamentId, None, p.externalId))
            // Each entry was mailed to the owner. What these specs read is what the rounds send, so the record
            // starts again here.
            _ <- IO(w.mail.clear())
            running <- s.tournaments.start(game.gameId, t.tournamentId, owner.externalId)
        } yield Fixture(w, game, owner, players, running)
    }

    private def round(f: Fixture): IO[List[TournamentMatch]] =
        TestSession.resource.use(session =>
            new TournamentMatchRepo(session).listUnfinished(f.game.gameId, f.t.tournamentId)
        )

    private def detail(f: Fixture) =
        f.w.services.tournaments.detail(f.game.gameId, f.t.tournamentId, f.owner.externalId)

    /** Every seat that is to choose, chooses the first role left, until no match of the round is choosing. */
    private def chooseAll(f: Fixture): IO[Unit] =
        round(f).flatMap { matches =>
            val choosing = matches.filter(_.status == TournamentMatchStatus.Choosing)
            if (choosing.isEmpty) IO.unit
            else
                choosing.traverse_ { m =>
                    for {
                        d <- detail(f)
                        entrant = com.vivi.matchmaker.service.TournamentRounds.nextChooser(m.seats).get.entrantId
                        player = d.entries.find(_.entrantId == entrant).flatMap(_.playerId)
                        who = f.players.find(p => player.contains(p.playerId)).get
                        taken = m.seats.flatMap(_.gameRoleId).toSet
                        role = f.game.roles.map(_.gameRoleId).find(r => !taken(r)).get
                        _ <- f.w.services.rounds.chooseRole(
                          f.game.gameId,
                          f.t.tournamentId,
                          m.matchId,
                          role,
                          who.externalId
                        )
                    } yield ()
                } *> chooseAll(f)
        }

    /** The engine reporting every started match of the round, the seat of the strongest seed winning. */
    private def reportAll(f: Fixture): IO[Unit] =
        for {
            matches <- round(f)
            d <- detail(f)
            seedOf = d.state.get.root.members.map(m => m.entrant -> m.initialSeed).toMap
            _ <- matches.filter(_.status == TournamentMatchStatus.Started).traverse_ { m =>
                val ordered = m.seats.sortBy(s => seedOf.getOrElse(s.entrantId.value, Int.MaxValue))
                val results = ordered.zipWithIndex.map((s, i) =>
                    ReportedResult(s.participantId, i + 1, Map("score" -> (10 - i).toDouble), isWinner = i == 0)
                )
                f.w.services.engine.recordResults(f.game.gameId, m.matchId, results, f.game.externalId)
            }
        } yield ()

    private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

    test(
      "a single elimination of four is played to its end: roles chosen, matches created and reported, rounds begun"
    ) {
        val (firstRound, afterChoosing, finalDetail, request) = run(for {
            f <- started(4)
            first <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            firstRound <- round(f)
            _ <- chooseAll(f)
            afterChoosing <- round(f)
            _ <- reportAll(f)
            second <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- chooseAll(f)
            _ <- reportAll(f)
            last <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId).attempt
            d <- detail(f)
        } yield ((first, firstRound), afterChoosing, (second, d, last), f.w.engine.lastRequest))
        val (first, matches) = firstRound
        assertEquals(first, Some(1))
        // Two matches, each choosing: the stronger seed chooses first.
        assertEquals(matches.size, 2)
        assert(matches.forall(_.status == TournamentMatchStatus.Choosing))
        // Choosing one role gives the other seat the last: every match is then created.
        assert(afterChoosing.forall(_.status == TournamentMatchStatus.Started))
        val (second, d, last) = finalDetail
        assertEquals(second, Some(2))
        assertEquals(d.summary.tournament.status, TournamentStatus.Finished)
        val ranking = d.state.get.root.ranking.get
        assertEquals(ranking.values.toList.sorted, List(1, 2, 3, 3))
        assert(last.left.exists(_.isInstanceOf[ConflictError]))
        // The elimination matches are not to be drawn, and each seat has a chess clock of half the round.
        assertEquals(request.flatMap(_.canTie), Some(false))
        assertEquals(request.flatMap(_.timeLimitSeconds), Some(Duration.ofDays(1).getSeconds))
    }

    test("the owner is mailed when every match of a round is over, and its summary says the round waits on them") {
        val (afterFirst, summaryAfterFirst, afterLast, ownerAddress) = run(for {
            f <- started(4)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- chooseAll(f)
            _ <- reportAll(f)
            afterFirst = f.w.mail.messages
            d <- detail(f)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- chooseAll(f)
            _ <- reportAll(f)
        } yield (afterFirst, d.summary, f.w.mail.messages, f.owner.email))
        assertEquals(afterFirst.map(_.recipient), ownerAddress.toList)
        assertEquals(afterFirst.map(_.subject), List("Round 1 of Cup is over"))
        assert(summaryAfterFirst.isRoundOver)
        // The last round finished the tournament, which waits on nobody.
        assertEquals(afterLast, afterFirst)
    }

    test("an owner who has turned the round mail off is not sent it") {
        val sent = run(for {
            f <- started(4, roles = false)
            _ <- f.w.services.notifications.updateMine(
              f.owner.externalId,
              NotificationPreferences(isNotifiedOfTournamentRoundDone = Some(false))
            )
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- reportAll(f)
            d <- detail(f)
        } yield (f.w.mail.messages, d.summary.tournament.status))
        assertEquals(sent, (Nil, TournamentStatus.Running))
    }

    test("a round cannot begin before the last is finished, and only its owner may begin one") {
        val (stranger, early) = run(for {
            f <- started(4, roles = false)
            stranger <- f.w.services.rounds
                .startRound(f.game.gameId, f.t.tournamentId, f.players.head.externalId)
                .attempt
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            early <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId).attempt
        } yield (stranger, early))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(early.left.exists(_.isInstanceOf[ConflictError]))
    }

    test("where roles do not matter nobody chooses, and with automatic rounds the next begins as the last ends") {
        val (statuses, finalState) = run(for {
            f <- started(
              4,
              roles = false,
              settings = TournamentSettings(
                isInvitational = false,
                hasAutomaticRounds = true,
                roundDuration = Some(Duration.ofHours(6))
              )
            )
            // Automatic: starting the tournament began its first round.
            first <- round(f)
            _ <- reportAll(f)
            // The second round began as the first's last match ended.
            _ <- reportAll(f)
            d <- detail(f)
        } yield (first.map(_.status), (d, f.w.mail.messages)))
        val (d, sent) = finalState
        assert(statuses.nonEmpty && statuses.forall(_ == TournamentMatchStatus.Started), statuses.toString)
        assertEquals(d.summary.tournament.status, TournamentStatus.Finished)
        // Its rounds start themselves, so its owner is waited on for nothing.
        assertEquals(sent, Nil)
    }

    test("only the seat whose turn it is may choose, and only a role still free") {
        val (notTheirs, wrongRole) = run(for {
            f <- started(2)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            m <- round(f).map(_.head)
            d <- detail(f)
            chooser = com.vivi.matchmaker.service.TournamentRounds.nextChooser(m.seats).get.entrantId
            other = m.seats.map(_.entrantId).find(_ != chooser).get
            otherPlayer = f.players
                .find(p => d.entries.find(_.entrantId == other).exists(_.playerId.contains(p.playerId)))
                .get
            chooserPlayer = f.players
                .find(p => d.entries.find(_.entrantId == chooser).exists(_.playerId.contains(p.playerId)))
                .get
            notTheirs <- f.w.services.rounds
                .chooseRole(
                  f.game.gameId,
                  f.t.tournamentId,
                  m.matchId,
                  f.game.roles.head.gameRoleId,
                  otherPlayer.externalId
                )
                .attempt
            wrongRole <- f.w.services.rounds
                .chooseRole(
                  f.game.gameId,
                  f.t.tournamentId,
                  m.matchId,
                  GameRoleId(-1),
                  chooserPlayer.externalId
                )
                .attempt
        } yield (notTheirs, wrongRole))
        assert(notTheirs.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(wrongRole.left.exists(_.isInstanceOf[ValidationError]))
    }

    test("the owner decides a match by hand: the match is called off, its seats ranked, and the tournament moves on") {
        val (cancelled, d) = run(for {
            f <- started(2, roles = false)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            m <- round(f).map(_.head)
            loser = m.seats.head.entrantId
            winner = m.seats(1).entrantId
            _ <- f.w.services.rounds.decide(
              f.game.gameId,
              f.t.tournamentId,
              m.matchId,
              Map(winner -> 1, loser -> 2),
              f.owner.externalId
            )
            played <- TestSession.resource.use(new MatchRepo(_).read(f.game.gameId, m.matchId))
            d <- detail(f)
        } yield (played.exists(_.isCancelled), (d, winner)))
        assert(cancelled)
        val (detailNow, winner) = d
        assertEquals(detailNow.summary.tournament.status, TournamentStatus.Finished)
        assertEquals(detailNow.state.get.root.ranking.get(winner.value), 1)
    }

    test("a seat whose time to choose has run out forfeits at Check round, and the match is decided without it") {
        val d = run(for {
            f <- started(2)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            m <- round(f).map(_.head)
            // A week ago: long past a chooser's whole clock.
            _ <- TestSession.resource.use(
              _.execute(
                sql"UPDATE participant SET choose_started_at = now() - interval '7 days' WHERE choose_started_at IS NOT NULL AND tournament_id = ${skunk.codec.all.int8}".command
              )(
                f.t.tournamentId.value
              )
            )
            checked <- f.w.services.rounds.checkRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            again <- f.w.services.rounds.checkRound(f.game.gameId, f.t.tournamentId, f.owner.externalId).attempt
            d <- detail(f)
        } yield (m, checked, again, d))
        val (m, _, again, detailNow) = d
        assert(again.left.exists(_.isInstanceOf[ConflictError]), "a second check straight away is refused")
        val chooser = com.vivi.matchmaker.service.TournamentRounds.nextChooser(m.seats).get.entrantId
        val other = m.seats.map(_.entrantId).find(_ != chooser).get
        assertEquals(detailNow.state.get.root.ranking.map(_(other.value)), Some(1))
    }

    test("a check forfeits nobody whose time to choose has not run out, however often it is delivered") {
        val after = run(for {
            f <- started(4)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            _ <- f.w.services.rounds.runCheck(f.game.gameId, f.t.tournamentId)
            _ <- f.w.services.rounds.runCheck(f.game.gameId, f.t.tournamentId)
            matches <- TestSession.resource.use(
              new TournamentMatchRepo(_).listRound(f.game.gameId, f.t.tournamentId, 1)
            )
        } yield matches)
        assertEquals(after.size, 2)
        assert(after.forall(_.status == TournamentMatchStatus.Choosing), after.map(_.status).toString)
        assert(after.flatMap(_.seats).forall(!_.isForfeit))
    }

    test("only a tournament's owner may check its round") {
        val (stranger, owner) = run(for {
            f <- started(4, roles = false)
            stranger <- f.w.services.rounds
                .checkRound(f.game.gameId, f.t.tournamentId, f.players.head.externalId)
                .attempt
            ok <- f.w.services.rounds.checkRound(f.game.gameId, f.t.tournamentId, f.owner.externalId).attempt
        } yield (stranger, ok))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(owner.isRight)
    }

    test("an engine that cannot create the match leaves it due, and it is created when asked again") {
        val (owed, statusBefore, settled, statusAfter) = run(for {
            f <- started(2, roles = false)
            _ = f.w.engine.refusals.set(1)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            before <- round(f).map(_.head)
            settled <- f.w.services.rounds.createMatch(f.game.gameId, f.t.tournamentId, before.matchId)
            after <- round(f).map(_.head)
        } yield (f.w.engine.created.get, before.status, settled, after.status))
        assertEquals(statusBefore, TournamentMatchStatus.Due)
        assertEquals(settled, Settlement.Settled)
        assertEquals(statusAfter, TournamentMatchStatus.Started)
        assertEquals(owed, 1)
    }

    test("a withdrawn entrant's seat forfeits, and their opponent wins without a match") {
        val d = run(for {
            f <- started(2, roles = false)
            entries <- detail(f).map(_.entries)
            _ <- f.w.services.tournaments
                .withdraw(f.game.gameId, f.t.tournamentId, entries.head.entrantId, f.players.head.externalId)
            _ <- f.w.services.rounds.startRound(f.game.gameId, f.t.tournamentId, f.owner.externalId)
            d <- detail(f)
        } yield (entries, d, f.w.engine.created.get))
        val (entries, detailNow, created) = d
        assertEquals(detailNow.summary.tournament.status, TournamentStatus.Finished)
        assertEquals(detailNow.state.get.root.ranking.get(entries(1).entrantId.value), 1)
        // Decided without being played: the engine never made a game of it.
        assertEquals(created, 0)
    }
}
