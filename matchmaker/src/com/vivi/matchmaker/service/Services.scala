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
    notifications: NotificationService
)

object Services {

    /** Default pool size. Sized for one Lambda container, which handles a single request at a time, plus a little
      * headroom for the concurrency within a request.
      */
    val defaultPoolSize: Int = 4

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

        Services(
          registration = new RegistrationService(pool),
          players = new PlayerService(pool),
          games = new GameService[T](pool),
          characters = new CharacterService[T](pool),
          // One `Notifications` for the four services that cause something worth an email. One
          // rather than one each, because who is told what does not depend on which service the
          // event came from -- that is the whole point of it being a class of its own.
          challenges = new OpenChallengeService[T](pool, notifications),
          acceptances = new AcceptanceService(pool, notifications),
          matches = new MatchService(pool, notifications),
          engine = new GameEngineService[T](pool, engineClient, callbackBaseUrl, notifications),
          notifications = new NotificationService(pool)
        )
    }
}
