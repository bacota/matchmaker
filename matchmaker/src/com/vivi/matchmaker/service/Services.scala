package com.vivi.matchmaker.service

import cats.effect.{IO, Resource}
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
    characters: CharacterService[T],
    challenges: OpenChallengeService[T],
    acceptances: AcceptanceService,
    matches: MatchService,
    engine: GameEngineService[T],
    notifications: NotificationService,
    suppression: SuppressionService
)

object Services {

    /** Default pool size. Sized for one Lambda container, which handles a single request at a time, plus a little
      * headroom for the concurrency within a request.
      */
    val defaultPoolSize: Int = 4

    /** Opens a connection pool and nothing else, for a process that wants one but not the services.
      *
      * The one caller is the bounce consumer, which is a separate function built from the same jar: it records what SES
      * reported and touches one table, and building the whole graph would have it construct an engine client and a
      * queue notifier it will never call. That it needs its own pool rather than sharing one is not a loophole in
      * `DbSession` being private — it is a different process, with its own container and its own connections.
      */
    def poolResource(config: DbConfig, poolSize: Int = defaultPoolSize): Resource[IO, SessionPool] =
        DbSession.pooled(config, poolSize)

    /** Opens the connection pool and builds the services on top of it. Acquire once at startup: releasing this closes
      * every pooled connection.
      */
    def resource[T](
        config: DbConfig,
        poolSize: Int = defaultPoolSize,
        engineClient: GameEngineClient = HttpGameEngineClient.fromEnvironment(),
        callbackBaseUrl: Option[String] = Option(System.getenv("MATCHMAKER_BASE_URL")),
        notifier: Notifier = SqsNotifier.fromEnvironment(),
        mail: MailSettings = MailSettings.fromEnvironment()
    )(using
        codec: TextCodec[T]
    ): Resource[IO, Services[T]] =
        DbSession.pooled(config, poolSize).map(fromPool[T](_, engineClient, callbackBaseUrl, notifier, mail))

    /** Builds the services over an already-open pool.
      *
      * `engineClient` and `notifier` are parameters rather than things built here because both are remote systems:
      * tests pass a stub and a recorder, and only a deployment passes the HTTP client and the queue.
      */
    def fromPool[T](
        pool: SessionPool,
        engineClient: GameEngineClient = HttpGameEngineClient.fromEnvironment(),
        callbackBaseUrl: Option[String] = Option(System.getenv("MATCHMAKER_BASE_URL")),
        notifier: Notifier = SqsNotifier.fromEnvironment(),
        mail: MailSettings = MailSettings.fromEnvironment()
    )(using codec: TextCodec[T]): Services[T] = {
        val notifications = new Notifications(notifier, mail)

        /* Built before the services it is given to, because it is given to one of them: a challenge
         * offered as starting itself turns an acceptance into a start, and the acceptance is
         * `challenges`' to record while the start is this one's to carry out. Only the function is
         * shared, so neither service has to know about the other -- see
         * `OpenChallengeService.autoStart`. */
        val engine = new GameEngineService[T](pool, engineClient, callbackBaseUrl, notifications)

        Services(
          registration = new RegistrationService(pool),
          players = new PlayerService(pool),
          games = new GameService[T](pool),
          characters = new CharacterService[T](pool),
          // One `Notifications` for the four services that cause something worth an email. One
          // rather than one each, because who is told what does not depend on which service the
          // event came from -- that is the whole point of it being a class of its own.
          challenges = new OpenChallengeService[T](pool, notifications, engine.startIfReady(_, _, _, _).map(_.isMatch)),
          acceptances = new AcceptanceService(pool, notifications),
          matches = new MatchService(pool, notifications),
          engine = engine,
          notifications = new NotificationService(pool),
          suppression = new SuppressionService(pool)
        )
    }
}
