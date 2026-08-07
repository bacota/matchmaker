package com.vivi.matchmaker.service

import cats.effect.{IO, Resource}
import com.vivi.matchmaker.persistence.TextCodec

/** Every service, sharing one connection pool.
  *
  * This is the entry point for anything outside the `service` package: `DbSession` is private,
  * so callers cannot open their own sessions and are steered into reusing the pool instead.
  */
case class Services[T](
    registration: RegistrationService,
    players: PlayerService,
    games: GameService[T],
    characters: CharacterService[T],
    challenges: OpenChallengeService[T],
    acceptances: AcceptanceService,
    matches: MatchService
)

object Services {

  /** Default pool size. Sized for one Lambda container, which handles a single request at a
    * time, plus a little headroom for the concurrency within a request.
    */
  val defaultPoolSize: Int = 4

  /** Opens the connection pool and builds the services on top of it. Acquire once at startup:
    * releasing this closes every pooled connection.
    */
  def resource[T](config: DbConfig, poolSize: Int = defaultPoolSize)(using
      codec: TextCodec[T]
  ): Resource[IO, Services[T]] =
    DbSession.pooled(config, poolSize).map(fromPool[T])

  /** Builds the services over an already-open pool. */
  def fromPool[T](pool: SessionPool)(using codec: TextCodec[T]): Services[T] =
    Services(
      registration = new RegistrationService(pool),
      players = new PlayerService(pool),
      games = new GameService[T](pool),
      characters = new CharacterService[T](pool),
      challenges = new OpenChallengeService[T](pool),
      acceptances = new AcceptanceService(pool),
      matches = new MatchService(pool)
    )
}
