package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import java.time.Duration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.standard.RoundRobin
import com.vivi.matchmaker.persistence.{GameAdminRepo, TestSession}

/** Challenges for specs: one offered, entered and started as players would, through the services, up to its match being
  * created in the engine.
  */
object Challenges {

    /** Offers challenges in `game`, as a sync followed by the game's creation would: the challenge model stored if it
      * is not, and offered. A game made straight into its table, as specs make them, offers no format of its own.
      */
    def offeredIn(game: Game): IO[Unit] =
        TestSession.resource.use(TournamentFixtures.model(_, game, RoundRobin.modelId.value)).void

    /** A player in a challenge: who, the role they will choose, and the character they enter with in a character game.
      */
    case class Seat(player: Player, role: GameRoleId, character: Option[CharacterId] = None)

    /** A challenge of `game` offered by the first of `seats`, entered by the rest, started, and its roles chosen as
      * each seat wants them, in the order its seats are to choose -- by rating, the strongest first. Its tournament,
      * and its match, which has then been created in the engine `services` talks to.
      *
      * `hostedBy` offers it instead, as a game's admin offering a match they will not play in: it is then closed to all
      * but `seats`, and starts by itself as the last of them enters. A rated one is offered by a game's admin, which
      * its offerer is made first.
      */
    def startedMatch(
        services: Services[String],
        game: Game,
        seats: List[Seat],
        friendly: Boolean = true,
        roundDuration: Option[Duration] = None,
        live: Boolean = false,
        isPublic: Boolean = false,
        settings: String = "{}",
        hostedBy: Option[Player] = None
    ): IO[(TournamentId, MatchId)] =
        hostedBy match {
            case None =>
                for {
                    t <- offered(
                      services = services,
                      game = game,
                      seats = seats,
                      friendly = friendly,
                      roundDuration = roundDuration,
                      live = live,
                      isPublic = isPublic,
                      settings = settings
                    )
                    started <- startAndChoose(services = services, game = game, t = t, seats = seats)
                } yield (t, started)
            case Some(host) =>
                for {
                    _ <- offeredIn(game)
                    _ <- TestSession.resource.use(session =>
                        new GameAdminRepo(session)
                            .grant(player = host.playerId, game = game.gameId, grantedBy = host.playerId)
                            .attempt
                            .void
                    )
                    t <- services.tournaments.createChallenge(
                      gameId = game.gameId,
                      request = ChallengeRequest(
                        isPlaying = false,
                        poolSize = Some(seats.size),
                        roundDuration = roundDuration,
                        gameSettings = settings,
                        isLive = live,
                        isFriendly = friendly,
                        isPublic = isPublic,
                        isAutoStarted = true,
                        isOpen = false,
                        invitations = seats.map(_.player.playerId)
                      ),
                      callerExternalId = host.externalId
                    )
                    _ <- seats.traverse_(s =>
                        services.tournaments.enter(
                          gameId = game.gameId,
                          tournamentId = t.tournamentId,
                          characterId = s.character,
                          callerExternalId = s.player.externalId
                        )
                    )
                    started <- choose(
                      services = services,
                      game = game,
                      t = t.tournamentId,
                      seats = seats,
                      viewer = host
                    )
                } yield (t.tournamentId, started)
        }

    /** A challenge offered by the first of `seats` and entered by the rest, not yet started. */
    def offered(
        services: Services[String],
        game: Game,
        seats: List[Seat],
        friendly: Boolean = true,
        roundDuration: Option[Duration] = None,
        live: Boolean = false,
        isPublic: Boolean = false,
        settings: String = "{}"
    ): IO[TournamentId] = {
        val host = seats.head
        for {
            _ <- offeredIn(game)
            _ <- IO.whenA(!friendly)(
              TestSession.resource.use(session =>
                  new GameAdminRepo(session)
                      .grant(player = host.player.playerId, game = game.gameId, grantedBy = host.player.playerId)
                      .attempt
                      .void
              )
            )
            t <- services.tournaments.createChallenge(
              gameId = game.gameId,
              request = ChallengeRequest(
                characterId = host.character,
                poolSize = Some(seats.size),
                roundDuration = roundDuration,
                gameSettings = settings,
                isLive = live,
                isFriendly = friendly,
                isPublic = isPublic
              ),
              callerExternalId = host.player.externalId
            )
            _ <- seats.tail.traverse_(s =>
                services.tournaments.enter(
                  gameId = game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = s.character,
                  callerExternalId = s.player.externalId
                )
            )
        } yield t.tournamentId
    }

    /** Starts an offered challenge and chooses its roles: its match, created once the last of them is chosen. */
    def startAndChoose(services: Services[String], game: Game, t: TournamentId, seats: List[Seat]): IO[MatchId] =
        services.tournaments.start(
          gameId = game.gameId,
          tournamentId = t,
          callerExternalId = seats.head.player.externalId
        ) *>
            choose(services = services, game = game, t = t, seats = seats, viewer = seats.head.player)

    /** Chooses a started challenge's roles, each seat as it wants, as its turn comes. Its match, once it has been
      * created in the engine.
      *
      * Waited for rather than read at once: a challenge's round is begun, and its match created, by the ending queue,
      * which here settles each on a fiber of its own that the call that set it off does not wait for. So this asks
      * again until each has happened, and gives up after a while -- a fixture that never gets there has a bug to
      * report, not a test to hang.
      */
    def choose(
        services: Services[String],
        game: Game,
        t: TournamentId,
        seats: List[Seat],
        viewer: Player,
        settled: TournamentMatchStatus => Boolean = status =>
            status == TournamentMatchStatus.Started || status == TournamentMatchStatus.Done
    ): IO[MatchId] = {
        def detail =
            services.tournaments.detail(gameId = game.gameId, tournamentId = t, callerExternalId = viewer.externalId)
        def step(remaining: Int): IO[MatchId] =
            if (remaining <= 0) IO.raiseError(new IllegalStateException(s"challenge $t never had its match created"))
            else
                detail.flatMap { d =>
                    d.round.headOption match {
                        // Its round has not begun yet.
                        case None => IO.sleep(50.millis) *> step(remaining - 1)
                        case Some(m) =>
                            m.seats.find(_.isChoosing) match {
                                case Some(chooser) if m.status == TournamentMatchStatus.Choosing =>
                                    val player = d.entries.find(_.entrantId == chooser.entrantId).flatMap(_.playerId)
                                    val seat = seats.find(s => player.contains(s.player.playerId)).get
                                    services.rounds.chooseRole(
                                      gameId = game.gameId,
                                      tournamentId = t,
                                      matchId = m.matchId,
                                      role = seat.role,
                                      callerExternalId = seat.player.externalId
                                    ) *>
                                        step(remaining - 1)
                                // Created in the engine, or decided without it, unless the caller waits for less.
                                case _ if settled(m.status) => IO.pure(m.matchId)
                                case _                      => IO.sleep(50.millis) *> step(remaining - 1)
                            }
                    }
                }
        step(600)
    }
}
