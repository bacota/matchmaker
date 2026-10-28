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
import com.vivi.matchmaker.model.tournament.standard.{RoundRobin, SingleElimination}
import com.vivi.matchmaker.notify.RecordingNotifier
import com.vivi.matchmaker.persistence.{
    GameRepo,
    Generators,
    MatchRepo,
    ResultRepo,
    TestSession,
    TournamentMatchRepo,
    TournamentRepo,
    TournamentStateRepo
}

/** Tournaments played through their rounds, against the local database and a stub engine: rounds begun, roles chosen,
  * matches created and reported on, and the tournament advancing to its end.
  */
class TournamentRoundsSpec extends FunSuite with QuietTests {

    /** An engine that creates every game it is asked to, unless told to refuse the next few. `whileCreating` runs as it
      * makes each game, before it answers: what else happens while matchmaker waits on it. It answers with a cancel
      * url, and records the cancels it is sent there.
      */
    private class StubEngine extends GameEngineClient {
        val refusals = AtomicInteger(0)
        val created = AtomicInteger(0)
        @volatile var lastRequest: Option[CreateGameRequest] = None
        @volatile var whileCreating: CreateGameRequest => IO[Unit] = _ => IO.unit
        @volatile var cancelled: List[String] = Nil
        @volatile var status: GameStatusResponse = GameStatusResponse(isCompleted = false, participants = Nil)
        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO.defer {
                if (refusals.getAndUpdate(n => math.max(0, n - 1)) > 0)
                    IO.raiseError(new RuntimeException("engine down"))
                else {
                    created.incrementAndGet()
                    lastRequest = Some(request)
                    whileCreating(request).as(
                      CreateGameResponse(
                        statusUrl = s"https://engine/status/${request.matchId}",
                        playUrl = "https://engine/play",
                        publicUrl = None,
                        cancelUrl = Some(s"https://engine/cancel/${request.matchId}")
                      )
                    )
                }
            }
        override def cancel(cancelUrl: String, apiKey: Option[String]): IO[Unit] =
            IO(synchronized { cancelled = cancelled :+ cancelUrl })
        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO(status)
    }

    /** The queue, run in the caller's fiber, so that by the time a call returns everything it queued has been done. */
    private class SyncEndings extends MatchEndings {
        @volatile var services: Services[String] = null
        def ended(gameId: GameId, matchId: MatchId): IO[Unit] = services.ending.settle(gameId, matchId).void
        def ratingsChanged(gameId: GameId): IO[Unit] = services.ending.rank(gameId).void
        override def tournamentMatchDue(g: GameId, t: TournamentId, m: MatchId): IO[Unit] =
            services.rounds.createMatch(gameId = g, tournamentId = t, matchId = m).void
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
          engine = engine,
          notifier = mail,
          mail = TestServices.mailSettings,
          matchEndings = Some(endings)
        )
        endings.services = services
        World(services = services, engine = engine, mail = mail)
    }

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private case class Fixture(w: World, game: Game, owner: Player, players: List[Player], t: Tournament)

    /** A plain two-role game — or with `roles` false a two-player game without roles, whose seats have nothing to
      * choose — `n` players entered in a tournament of `design` owned by a further player, started. With `isMailed` the
      * players have addresses too, so that what they are mailed is recorded.
      */
    private def started(
        n: Int,
        design: String = "single-elimination",
        roles: Boolean = true,
        settings: TournamentSettings =
            TournamentSettings(isInvitational = false, roundDuration = Some(Duration.ofDays(2))),
        game: Option[Game] = None,
        poolSize: Option[Int] = None,
        isMailed: Boolean = false
    ): IO[Fixture] = {
        val w = world()
        val s = w.services
        for {
            game <- TestSession.resource.use(
              new GameRepo[String](_)
                  .create(
                    game.getOrElse(
                      if (roles) Generators.genGame(GameType.Plain).sample.get
                      else Generators.genRolelessGame(least = 2, most = Some(2))
                    )
                  )
            )
            // Only the owner has an address, so the only mail sent is to them.
            owner <- s.registration.register(
              nickname = unique("owner"),
              externalId = unique("sub"),
              email = Some(s"${unique("owner")}@example.com")
            )
            players <- (1 to n).toList.traverse(_ =>
                s.registration.register(
                  nickname = unique("p"),
                  externalId = unique("sub"),
                  email = Option.when(isMailed)(s"${unique("p")}@example.com")
                )
            )
            model <- TestSession.resource.use(TournamentFixtures.model(_, game, design))
            t <- s.tournaments.create(
              gameId = game.gameId,
              request = TournamentRequest(
                name = "Cup",
                tournamentModelId = model.tournamentModelId,
                settings = settings,
                defaultPoolSize = poolSize
              ),
              callerExternalId = owner.externalId
            )
            _ <- players.traverse(p =>
                s.tournaments.enter(
                  gameId = game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            )
            // Each entry was mailed to the owner. What these specs read is what the rounds send, so the record
            // starts again here.
            _ <- IO(w.mail.clear())
            running <- s.tournaments.start(
              gameId = game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = owner.externalId
            )
        } yield Fixture(w = w, game = game, owner = owner, players = players, t = running)
    }

    private def round(f: Fixture): IO[List[TournamentMatch]] =
        TestSession.resource.use(session =>
            new TournamentMatchRepo(session).listUnfinished(f.game.gameId, f.t.tournamentId)
        )

    private def detail(f: Fixture) =
        f.w.services.tournaments
            .detail(gameId = f.game.gameId, tournamentId = f.t.tournamentId, callerExternalId = f.owner.externalId)

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
                          gameId = f.game.gameId,
                          tournamentId = f.t.tournamentId,
                          matchId = m.matchId,
                          role = role,
                          callerExternalId = who.externalId
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
                    ReportedResult(
                      participantId = s.participantId,
                      rank = i + 1,
                      scores = Map("score" -> (10 - i).toDouble),
                      isWinner = i == 0
                    )
                )
                f.w.services.engine.recordResults(
                  gameId = f.game.gameId,
                  matchId = m.matchId,
                  results = results,
                  callerExternalId = f.game.externalId
                )
            }
        } yield ()

    private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

    test(
      "a single elimination of four is played to its end: roles chosen, matches created and reported, rounds begun"
    ) {
        val (firstRound, afterChoosing, finalDetail, request) = run(for {
            f <- started(n = 4)
            first <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            firstRound <- round(f)
            _ <- chooseAll(f)
            afterChoosing <- round(f)
            _ <- reportAll(f)
            second <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- chooseAll(f)
            _ <- reportAll(f)
            last <- f.w.services.rounds
                .startRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
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
            f <- started(n = 4)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- chooseAll(f)
            _ <- reportAll(f)
            afterFirst = f.w.mail.messages
            d <- detail(f)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- chooseAll(f)
            _ <- reportAll(f)
        } yield (afterFirst, d.summary, f.w.mail.messages, f.owner.email))
        assertEquals(afterFirst.map(_.recipient), ownerAddress.toList)
        assertEquals(afterFirst.map(_.subject), List("Round 1 of Cup is over"))
        assert(summaryAfterFirst.isRoundOver)
        assertEquals(summaryAfterFirst.round, Some(1))
        assertEquals(summaryAfterFirst.formatName, SingleElimination.name)
        // The last round finished the tournament, which waits on nobody.
        assertEquals(afterLast, afterFirst)
    }

    test("an owner who has turned the round mail off is not sent it") {
        val sent = run(for {
            f <- started(n = 4, roles = false)
            _ <- f.w.services.notifications.updateMine(
              callerExternalId = f.owner.externalId,
              preferences = NotificationPreferences(isNotifiedOfTournamentRoundDone = Some(false))
            )
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- reportAll(f)
            d <- detail(f)
        } yield (f.w.mail.messages, d.summary.tournament.status))
        assertEquals(sent, (Nil, TournamentStatus.Running))
    }

    test("a round cannot begin before the last is finished, and only its owner may begin one") {
        val (stranger, early) = run(for {
            f <- started(n = 4, roles = false)
            stranger <- f.w.services.rounds
                .startRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            early <- f.w.services.rounds
                .startRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
        } yield (stranger, early))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(early.left.exists(_.isInstanceOf[ConflictError]))
    }

    test("in a game without roles nobody chooses, and with automatic rounds the next begins as the last ends") {
        val (statuses, finalState) = run(for {
            f <- started(
              n = 4,
              roles = false,
              settings = TournamentSettings(
                isInvitational = false,
                isAutoStarted = true,
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

    test("a game without roles plays everyone in a pool as one match, its seats sent to the engine with no role") {
        val (request, sizes, d) = run(for {
            f <- started(
              n = 4,
              design = RoundRobin.id,
              game = Some(Generators.genRolelessGame(least = 2, most = None)),
              poolSize = Some(4)
            )
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            matches <- round(f)
            request = f.w.engine.lastRequest
            _ <- reportAll(f)
            d <- detail(f)
        } yield (request, matches.map(m => (m.status, m.seats.size)), d))
        // Due at once, with nothing to choose, and created in the engine as one match of all four.
        assertEquals(sizes, List((TournamentMatchStatus.Started, 4)))
        val players = request.map(_.players).getOrElse(fail("the match was never created in the engine"))
        assertEquals(players.size, 4)
        assert(players.forall(_.role.isEmpty), players.toString)
        assertEquals(d.summary.tournament.status, TournamentStatus.Finished)
        assertEquals(d.state.flatMap(_.root.ranking).map(_.size), Some(4))
    }

    /** The players a match of `game`, of two players entered, is sent to the engine with, once its round begins: with
      * nothing to choose, it is created in the engine as the round begins.
      */
    private def seatedWithoutChoosing(game: Game): (List[(TournamentMatchStatus, Int)], List[Option[String]]) =
        run(
          for {
              f <- started(n = 2, design = RoundRobin.id, game = Some(game), poolSize = Some(2))
              _ <- f.w.services.rounds.startRound(
                gameId = f.game.gameId,
                tournamentId = f.t.tournamentId,
                callerExternalId = f.owner.externalId
              )
              matches <- round(f)
              request = f.w.engine.lastRequest
          } yield (
            matches.map(m => (m.status, m.seats.size)),
            request.map(_.players.map(_.role)).getOrElse(fail("the match was never created in the engine"))
          )
        )

    test("a game whose roles are all optional starts its match with nobody choosing, every player without a role") {
        val base = Generators.genGame(GameType.Plain).sample.get
        val (sizes, roles) =
            seatedWithoutChoosing(base.copy(roles = base.roles.map(_.copy(roleType = RoleType.Optional))))
        assertEquals(sizes, List((TournamentMatchStatus.Started, 2)))
        assertEquals(roles, List(None, None))
    }

    test("a game with neither roles nor a range of players starts its match with every player without a role") {
        val game =
            Generators.genRolelessGame(least = 2, most = None).copy(minPlayers = None, moveType = MoveType.Sequential)
        val (sizes, roles) = seatedWithoutChoosing(game)
        assertEquals(sizes, List((TournamentMatchStatus.Started, 2)))
        assertEquals(roles, List(None, None))
    }

    test("only the seat whose turn it is may choose, and only a role still free") {
        val (notTheirs, wrongRole) = run(for {
            f <- started(n = 2)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
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
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  matchId = m.matchId,
                  role = f.game.roles.head.gameRoleId,
                  callerExternalId = otherPlayer.externalId
                )
                .attempt
            wrongRole <- f.w.services.rounds
                .chooseRole(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  matchId = m.matchId,
                  role = GameRoleId(-1),
                  callerExternalId = chooserPlayer.externalId
                )
                .attempt
        } yield (notTheirs, wrongRole))
        assert(notTheirs.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(wrongRole.left.exists(_.isInstanceOf[ValidationError]))
    }

    test("the owner decides a match by hand: the match is called off, its seats ranked, and the tournament moves on") {
        val (cancelled, d) = run(for {
            f <- started(n = 2, roles = false)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.head)
            loser = m.seats.head.entrantId
            winner = m.seats(1).entrantId
            _ <- f.w.services.rounds.decide(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              matchId = m.matchId,
              ranks = Map(winner -> 1, loser -> 2),
              callerExternalId = f.owner.externalId
            )
            played <- TestSession.resource.use(new MatchRepo(_).read(f.game.gameId, m.matchId))
            d <- detail(f)
        } yield (played.exists(_.isCancelled), (d, winner)))
        assert(cancelled)
        val (detailNow, winner) = d
        assertEquals(detailNow.summary.tournament.status, TournamentStatus.Finished)
        assertEquals(detailNow.state.get.root.ranking.get(winner.value), 1)
    }

    test("a match cancelled in a tournament is unranked there, and its owner may still rank it by hand") {
        val (unranked, decided) = run(for {
            f <- started(n = 4, roles = false)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.head)
            _ <- f.w.services.matches.cancel(
              gameId = f.game.gameId,
              matchId = m.matchId,
              callerExternalId = f.owner.externalId
            )
            unranked <- TestSession.resource.use(
              new TournamentStateRepo(_)
                  .load(f.game.gameId, f.t.tournamentId)
                  .map(
                    _.toList.flatMap(l =>
                        com.vivi.matchmaker.model.tournament.Tournament
                            .allPools(l.state.root)
                            .flatMap(_.matches)
                            .flatMap(_.outcome)
                    )
                  )
            )
            _ <- f.w.services.rounds.decide(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              matchId = m.matchId,
              ranks = Map(m.seats.head.entrantId -> 1, m.seats(1).entrantId -> 2),
              callerExternalId = f.owner.externalId
            )
            decided <- TestSession.resource.use(session =>
                m.seats.traverse(s => new ResultRepo(session).read(f.game.gameId, s.participantId))
            )
        } yield (unranked, decided))
        // The cancelled match is over in the tournament, with nobody ranked: its forfeits score nothing.
        assertEquals(unranked.map(_.map(_.rank)), List(List(None, None)))
        // The owner's ranks replace the forfeits.
        assertEquals(
          decided.map(_.map(r => (r.rank, r.isForfeit, r.isWinner))),
          List(Some((1, false, true)), Some((2, false, false)))
        )
    }

    test("a tournament stopped mid-round plays that round out and then finishes, beginning no other") {
        val (stopped, refused, finished) = run(for {
            // Taking late entries, so that it is the stop that refuses one.
            f <- started(
              n = 4,
              roles = false,
              settings = TournamentSettings(
                isInvitational = false,
                hasLateEntries = true,
                roundDuration = Some(Duration.ofDays(2))
              )
            )
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            stranger <- f.w.services.rounds
                .stop(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            _ <- f.w.services.rounds.stop(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            stopped <- detail(f)
            again <- f.w.services.rounds
                .stop(gameId = f.game.gameId, tournamentId = f.t.tournamentId, callerExternalId = f.owner.externalId)
                .attempt
            late <- f.w.services.registration.register(nickname = unique("late"), externalId = unique("sub"))
            entry <- f.w.services.tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  characterId = None,
                  callerExternalId = late.externalId
                )
                .attempt
            _ <- reportAll(f)
            next <- f.w.services.rounds
                .startRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
            d <- detail(f)
            created = f.w.engine.created.get()
        } yield (stopped, (stranger, again, entry, next), (d, created)))
        val (stranger, again, entry, next) = refused
        val (d, created) = finished
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        // Still under way while its round is played, and stopped: its matches had begun, so it is not deleted.
        assert(stopped.hasBegunMatch)
        assertEquals(stopped.summary.tournament.status, TournamentStatus.Running)
        assert(stopped.summary.tournament.isStopped)
        assert(again.left.exists(_.isInstanceOf[ConflictError]), again)
        assert(entry.left.exists(_.isInstanceOf[ConflictError]), entry)
        // Its round over, it finished rather than going on to the final.
        assertEquals(d.summary.tournament.status, TournamentStatus.Finished)
        assert(next.left.exists(_.isInstanceOf[ConflictError]), next)
        assertEquals(created, 2)
    }

    test("stopping a tournament that has not started deletes it, and only its owner may") {
        val (stranger, gone) = run {
            val s = world().services
            for {
                game <- TestSession.resource.use(
                  new GameRepo[String](_).create(Generators.genRolelessGame(least = 2, most = Some(2)))
                )
                owner <- s.registration.register(nickname = unique("owner"), externalId = unique("sub"))
                player <- s.registration.register(nickname = unique("p"), externalId = unique("sub"))
                model <- TestSession.resource.use(TournamentFixtures.model(_, game, "single-elimination"))
                t <- s.tournaments.create(
                  gameId = game.gameId,
                  request = TournamentRequest(
                    name = "Cup",
                    tournamentModelId = model.tournamentModelId,
                    settings = TournamentSettings(isInvitational = false, roundDuration = Some(Duration.ofDays(2)))
                  ),
                  callerExternalId = owner.externalId
                )
                _ <- s.tournaments.enter(
                  gameId = game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = player.externalId
                )
                stranger <- s.rounds
                    .stop(gameId = game.gameId, tournamentId = t.tournamentId, callerExternalId = player.externalId)
                    .attempt
                _ <- s.rounds.stop(
                  gameId = game.gameId,
                  tournamentId = t.tournamentId,
                  callerExternalId = owner.externalId
                )
                gone <- TestSession.resource.use(new TournamentRepo(_).read(game.gameId, t.tournamentId))
            } yield (stranger, gone)
        }
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assertEquals(gone, None)
    }

    test("stopping a tournament under way none of whose matches has begun deletes it, matches and all") {
        val (before, gone, matches, created) = run(for {
            // With roles, so that the round's matches wait on choosing and none is created.
            f <- started(n = 2)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            before <- detail(f)
            _ <- f.w.services.rounds.stop(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            gone <- TestSession.resource.use(new TournamentRepo(_).read(f.game.gameId, f.t.tournamentId))
            matches <- round(f)
        } yield (before, gone, matches, f.w.engine.created.get()))
        assertEquals(before.summary.tournament.status, TournamentStatus.Running)
        assert(before.round.nonEmpty)
        assert(!before.hasBegunMatch)
        assertEquals(gone, None)
        assertEquals(matches, Nil)
        assertEquals(created, 0)
    }

    test("a seat whose time to choose has run out forfeits at Check round, and the match is decided without it") {
        val d = run(for {
            f <- started(n = 2)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.head)
            // A week ago: long past a chooser's whole clock.
            _ <- TestSession.resource.use(
              _.execute(
                sql"UPDATE participant SET choose_started_at = now() - interval '7 days' WHERE choose_started_at IS NOT NULL AND tournament_id = ${skunk.codec.all.int8}".command
              )(
                f.t.tournamentId.value
              )
            )
            checked <- f.w.services.rounds.checkRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            again <- f.w.services.rounds
                .checkRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
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
            f <- started(n = 4)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- f.w.services.rounds.runCheck(f.game.gameId, f.t.tournamentId)
            _ <- f.w.services.rounds.runCheck(f.game.gameId, f.t.tournamentId)
            matches <- TestSession.resource.use(
              new TournamentMatchRepo(_).listRound(game = f.game.gameId, tournament = f.t.tournamentId, round = 1)
            )
        } yield matches)
        assertEquals(after.size, 2)
        assert(after.forall(_.status == TournamentMatchStatus.Choosing), after.map(_.status).toString)
        assert(after.flatMap(_.seats).forall(!_.isForfeit))
    }

    test("only a tournament's owner may check its round") {
        val (stranger, owner) = run(for {
            f <- started(n = 4, roles = false)
            stranger <- f.w.services.rounds
                .checkRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            ok <- f.w.services.rounds
                .checkRound(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
        } yield (stranger, ok))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(owner.isRight)
    }

    test("an engine that cannot create the match leaves it due, and it is created when asked again") {
        val (owed, statusBefore, settled, statusAfter) = run(for {
            f <- started(n = 2, roles = false)
            _ = f.w.engine.refusals.set(1)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            before <- round(f).map(_.head)
            settled <- f.w.services.rounds
                .createMatch(gameId = f.game.gameId, tournamentId = f.t.tournamentId, matchId = before.matchId)
            after <- round(f).map(_.head)
        } yield (f.w.engine.created.get, before.status, settled, after.status))
        assertEquals(statusBefore, TournamentMatchStatus.Due)
        assertEquals(settled, Settlement.Settled)
        assertEquals(statusAfter, TournamentMatchStatus.Started)
        assertEquals(owed, 1)
    }

    // A refusal is recorded on the match, for its owner to see, and mailed once; after the last try the queue stops,
    // and the owner's Check round is what tries again.
    test("a match its engine keeps refusing is shown to its owner, mailed once, and left to Check round".tag(Quiet)) {
        val (settlements, ownerSees, playerSees, subjects, afterCheck) = run(for {
            f <- started(n = 2, roles = false, isMailed = true)
            _ = f.w.engine.refusals.set(CreateFailure.maxTries)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.head)
            settlements <- List
                .fill(CreateFailure.maxTries - 1)(())
                .traverse(_ =>
                    f.w.services.rounds
                        .createMatch(gameId = f.game.gameId, tournamentId = f.t.tournamentId, matchId = m.matchId)
                )
            ownerSees <- detail(f).map(_.round.head.createFailure)
            playerSees <- f.w.services.tournaments
                .detail(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  callerExternalId = f.players.head.externalId
                )
                .map(_.round.head.createFailure)
            _ <- f.w.services.rounds.checkRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            afterCheck <- detail(f).map(_.round.head)
            // The owner's mail alone: any site admin another spec has made is written to as well.
            toOwner = f.w.mail.messages.filter(m => f.owner.email.contains(m.recipient)).map(_.subject)
        } yield (settlements, ownerSees, playerSees, toOwner, afterCheck))
        assert(settlements.init.forall(_.isInstanceOf[Settlement.Owed]), settlements)
        assertEquals(settlements.last, Settlement.Settled)
        assertEquals(ownerSees.map(_.tries), Some(CreateFailure.maxTries))
        assert(ownerSees.exists(f => f.isStopped && f.error == "engine down"), ownerSees)
        assertEquals(playerSees, None)
        assertEquals(subjects.count(_.contains("could not be started")), 1, subjects)
        assertEquals(afterCheck.status, TournamentMatchStatus.Started)
        assertEquals(afterCheck.createFailure, None)
    }

    /** A round of two begun and its match created, then stranded as a creation that died between its claim and the
      * engine's answer leaves it: begun `startedAgo`, with no urls. What the engine created, the match's status and
      * whether it has a status url, after a Check round.
      */
    private def strandedThenChecked(startedAgo: String): (Int, TournamentMatchStatus, Boolean) =
        run(for {
            f <- started(n = 2, roles = false)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- TestSession.resource.use(
              _.execute(
                sql"""UPDATE match SET status_url = NULL, play_url = NULL, start = now() - $text::interval
                      WHERE tournament_id = ${skunk.codec.all.int8}""".command
              )((startedAgo, f.t.tournamentId.value))
            )
            _ <- f.w.services.rounds.checkRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.head)
            saved <- TestSession.resource.use(new MatchRepo(_).read(f.game.gameId, m.matchId))
        } yield (f.w.engine.created.get, m.status, saved.exists(_.statusUrl.isDefined)))

    test("a begun match whose engine game was never recorded is created again at Check round") {
        val (created, status, hasStatusUrl) = strandedThenChecked(startedAgo = "1 hour")
        assertEquals(created, 2)
        assertEquals(status, TournamentMatchStatus.Started)
        assert(hasStatusUrl)
    }

    test("a match whose creation may still be under way is left alone at Check round") {
        val (created, status, hasStatusUrl) = strandedThenChecked(startedAgo = "1 minute")
        assertEquals(created, 1)
        assertEquals(status, TournamentMatchStatus.Started)
        assert(!hasStatusUrl)
    }

    // A launch records each forfeited seat's last place before it asks the engine; one the engine refuses must take
    // those back with it, or the next attempt finds them already there.
    test("a match with a forfeited seat that the engine could not create is created when asked again") {
        val (owedStatus, settled, startedStatus, created) = run(for {
            f <- started(
              n = 4,
              design = RoundRobin.id,
              game = Some(Generators.genRolelessGame(least = 2, most = None)),
              poolSize = Some(4)
            )
            entries <- detail(f).map(_.entries)
            _ <- f.w.services.tournaments.withdraw(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              entrantId = entries.head.entrantId,
              callerExternalId = f.players.head.externalId
            )
            _ = f.w.engine.refusals.set(1)
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            before <- round(f).map(_.head)
            settled <- f.w.services.rounds
                .createMatch(gameId = f.game.gameId, tournamentId = f.t.tournamentId, matchId = before.matchId)
            after <- round(f).map(_.head)
        } yield (before.status, settled, after.status, f.w.engine.created.get))
        assertEquals(owedStatus, TournamentMatchStatus.Due)
        assertEquals(settled, Settlement.Settled)
        assertEquals(startedStatus, TournamentMatchStatus.Started)
        assertEquals(created, 1)
    }

    // A forfeited seat's last place is recorded at launch, by hand, before the engine has said anything: it is not the
    // match's results, which are still owed when the match is completed by a status that carries none.
    test("a match with a forfeited seat completed without its results still takes them") {
        val (recovered, recorded) = run(for {
            f <- started(
              n = 4,
              design = RoundRobin.id,
              game = Some(Generators.genRolelessGame(least = 2, most = None)),
              poolSize = Some(4)
            )
            entries <- detail(f).map(_.entries)
            gone = entries.head.entrantId
            _ <- f.w.services.tournaments.withdraw(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              entrantId = gone,
              callerExternalId = f.players.head.externalId
            )
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            m <- round(f).map(_.find(_.seats.exists(_.entrantId == gone)).get)
            _ = f.w.engine.status = GameStatusResponse(isCompleted = true, participants = Nil)
            recovered <- f.w.services.engine.recoverResults(
              List((f.game.gameId, m.matchId, s"https://engine/status/${m.matchId.value}"))
            )
            played = m.seats.filterNot(_.entrantId == gone)
            _ <- f.w.services.engine.recordResults(
              gameId = f.game.gameId,
              matchId = m.matchId,
              results = played.zipWithIndex.map((s, i) =>
                  ReportedResult(participantId = s.participantId, rank = i + 1, scores = Map.empty, isWinner = i == 0)
              ),
              callerExternalId = f.game.externalId
            )
            recorded <- TestSession.resource.use(new ResultRepo(_).existsForMatch(f.game.gameId, m.matchId))
        } yield (recovered, recorded))
        assert(!recovered)
        assert(recorded)
    }

    // The engine is asked for its game with no transaction open, and the owner may call the match off meanwhile. The
    // cancel finds no cancel url yet, so it is the launch, recording the one the engine answers with, that has the
    // engine told -- and nobody is mailed a start for a match they were told was cancelled.
    test("a match cancelled while its engine is creating it has the engine told, and mails no start") {
        val (cancelled, engineTold, subjects, matchId) = run(for {
            f <- started(n = 2, roles = false, isMailed = true)
            _ = f.w.engine.whileCreating = request =>
                f.w.services.matches
                    .cancel(
                      gameId = f.game.gameId,
                      matchId = MatchId(request.matchId.toLong),
                      callerExternalId = f.owner.externalId
                    )
                    .void
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            matchId = f.w.engine.lastRequest.map(r => MatchId(r.matchId.toLong)).get
            played <- TestSession.resource.use(new MatchRepo(_).read(f.game.gameId, matchId))
        } yield (played.exists(_.isCancelled), f.w.engine.cancelled, f.w.mail.messages.map(_.subject), matchId))
        assert(cancelled)
        assertEquals(engineTold, List(s"https://engine/cancel/${matchId.value}"))
        assert(!subjects.exists(_.contains("has started")), subjects)
    }

    test("a withdrawn entrant's seat forfeits, and their opponent wins without a match") {
        val d = run(for {
            f <- started(n = 2, roles = false)
            entries <- detail(f).map(_.entries)
            _ <- f.w.services.tournaments
                .withdraw(
                  gameId = f.game.gameId,
                  tournamentId = f.t.tournamentId,
                  entrantId = entries.head.entrantId,
                  callerExternalId = f.players.head.externalId
                )
            _ <- f.w.services.rounds.startRound(
              gameId = f.game.gameId,
              tournamentId = f.t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            d <- detail(f)
        } yield (entries, d, f.w.engine.created.get))
        val (entries, detailNow, created) = d
        assertEquals(detailNow.summary.tournament.status, TournamentStatus.Finished)
        assertEquals(detailNow.state.get.root.ranking.get(entries(1).entrantId.value), 1)
        // Decided without being played: the engine never made a game of it.
        assertEquals(created, 0)
    }
}
