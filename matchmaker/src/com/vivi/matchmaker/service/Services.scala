package com.vivi.matchmaker.service

import cats.effect.{IO, Resource}
import com.vivi.matchmaker.archive.ArchiveStore
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.engine.{GameEngineClient, HttpGameEngineClient}
import com.vivi.matchmaker.notify.{MailSettings, Notifications, Notifier, SqsNotifier}
import com.vivi.matchmaker.persistence.TextCodec

/** Every service, sharing one connection pool.
  *
  * This is the entry point for anything outside the `service` package: `DbSession` is private, so callers cannot open
  * their own sessions and are steered into reusing the pool instead.
  */
case class Services[T](
    registration: RegistrationService,
    players: PlayerService,
    games: GameService[T],
    gameAdmins: GameAdminService[T],
    ratings: EloRatingService[T],
    characters: CharacterService[T],
    matches: MatchService,
    engine: GameEngineService[T],
    archives: ArchiveService,
    ending: EndingService,
    notifications: NotificationService,
    suppression: SuppressionService,
    tournaments: TournamentService[T],
    tournamentModels: TournamentModelService[T],
    rounds: TournamentRounds[T]
)

object Services {

    /** Default pool size. Sized for one Lambda container, which handles a single request at a time, plus a little
      * headroom for the concurrency within a request.
      */
    val defaultPoolSize: Int = 4

    /** Default most sessions lent at once: the pool's, and an overflow opened past it and closed as each is released
      * (see `DbSession.pooled`). A safety net for work that holds a session while it borrows another, not capacity.
      */
    val defaultPoolMax: Int = 20

    /** Opens a connection pool and nothing else, for a process that wants one but not the services.
      *
      * The one caller is the bounce consumer, which is a separate function built from the same jar: it records what SES
      * reported and touches one table, and building the whole graph would have it construct an engine client and a
      * queue notifier it will never call. That it needs its own pool rather than sharing one is not a loophole in
      * `DbSession` being private — it is a different process, with its own container and its own connections.
      *
      * It has no overflow unless `poolMax` asks for one: its connections are budgeted against the API's.
      */
    def poolResource(
        config: DbConfig,
        poolSize: Int = defaultPoolSize,
        poolMax: Option[Int] = None
    ): Resource[IO, SessionPool] =
        DbSession.pooled(config = config, size = poolSize, max = poolMax.getOrElse(poolSize))

    /** Opens the connection pool and builds the services on top of it. Acquire once at startup: releasing this closes
      * every pooled connection.
      */
    def resource[T](
        config: DbConfig,
        poolSize: Int = defaultPoolSize,
        poolMax: Int = defaultPoolMax,
        engineClient: GameEngineClient = new HttpGameEngineClient(),
        callbackBaseUrl: Option[String] = Option(System.getenv("MATCHMAKER_BASE_URL")),
        notifier: Notifier = SqsNotifier.fromEnvironment(),
        mail: MailSettings = MailSettings.fromEnvironment(),
        archiveStore: ArchiveStore = ArchiveStore.fromEnvironment()
    )(using
        codec: TextCodec[T]
    ): Resource[IO, Services[T]] =
        DbSession
            .pooled(config = config, size = poolSize, max = poolMax)
            .map(fromPool[T](_, engineClient, callbackBaseUrl, notifier, mail, archiveStore))

    /** Builds the services over an already-open pool.
      *
      * `engineClient` and `notifier` are parameters rather than things built here because both are remote systems:
      * tests pass a stub and a recorder, and only a deployment passes the HTTP client and the queue.
      */
    def fromPool[T](
        pool: SessionPool,
        engineClient: GameEngineClient = new HttpGameEngineClient(),
        callbackBaseUrl: Option[String] = Option(System.getenv("MATCHMAKER_BASE_URL")),
        notifier: Notifier = SqsNotifier.fromEnvironment(),
        mail: MailSettings = MailSettings.fromEnvironment(),
        archiveStore: ArchiveStore = ArchiveStore.fromEnvironment(),
        /** Where a match's end is said. `None` is the queue the environment names, or, with none, settling it inline.
          */
        matchEndings: Option[MatchEndings] = None
    )(using codec: TextCodec[T]): Services[T] = {
        val notifications = new Notifications(notifier, mail)
        val archives = new ArchiveService(sessionPool = pool, store = archiveStore)
        /* The ending, the queue and a tournament's rounds each need another of the three: a match's
         * end is recorded in its tournament, a round's matches are created from the queue, and the
         * queue -- where there is none -- settles endings and creates matches in this process. So
         * they are lazy, and each is handed the others as functions. */
        lazy val ending: EndingService =
            new EndingService(
              sessionPool = pool,
              engine = engineClient,
              tournamentEnding = (g, m) => rounds.recordEnding(g, m)
            )
        lazy val endings: MatchEndings = matchEndings.getOrElse(
          MatchEndings.fromEnvironment(
            MatchEndings.inline(
              settle = (gameId, matchId) => ending.settle(gameId, matchId).void,
              rank = ending.rank(_).void,
              matchDue = (g, t, m) => rounds.createMatch(gameId = g, tournamentId = t, matchId = m).void,
              roundDue = (g, t) => rounds.autoStart(g, t).void,
              roundCheck = (g, t) => rounds.runCheck(g, t).void
            )
          )
        )
        lazy val matches = new MatchService(
          sessionPool = pool,
          notifications = notifications,
          archives = Some(archives),
          endings = endings,
          recoverResults = engine.recoverResults,
          recoverEndings = engine.recoverEndings
        )

        lazy val engine: GameEngineService[T] =
            new GameEngineService[T](
              sessionPool = pool,
              engine = engineClient,
              callbackBaseUrl = callbackBaseUrl,
              notifications = notifications,
              archives = Some(archives),
              endings = endings
            )
        lazy val rounds: TournamentRounds[T] = new TournamentRounds[T](
          sessionPool = pool,
          engine = engine,
          endings = endings,
          notifications = notifications
        )

        Services(
          registration = new RegistrationService(pool),
          players = new PlayerService(pool),
          games = new GameService[T](pool),
          gameAdmins = new GameAdminService[T](pool),
          ratings = new EloRatingService[T](pool, endings),
          characters = new CharacterService[T](pool),
          matches = matches,
          engine = engine,
          archives = archives,
          ending = ending,
          notifications = new NotificationService(pool),
          suppression = new SuppressionService(pool),
          // A tournament with automatic rounds begins its first as it starts. One `Notifications` for every
          // service that causes something worth an email, because who is told what does not depend on which
          // service the event came from -- that is the whole point of it being a class of its own.
          tournaments = new TournamentService[T](
            sessionPool = pool,
            notifications = notifications,
            started = (g, t) => endings.tournamentRoundDue(g, t)
          ),
          tournamentModels = new TournamentModelService[T](pool),
          rounds = rounds
        )
    }
}
