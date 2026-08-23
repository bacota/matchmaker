package com.vivi.matchmaker.service

import cats.effect.{IO, Resource}
import skunk.{SSL, Session}
import natchez.Trace.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as noopTracer
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as noopMeter

/** A borrowable session: acquiring it takes a connection from a pool built once at startup,
  * releasing it returns the connection to that pool rather than closing it.
  */
type SessionPool = Resource[IO, Session[IO]]

/** Builds the skunk sessions services use to talk to Postgres, from a `DbConfig`. */
private[service] object DbSession {

  /** Opens a connection pool of at most `max` sessions. The outer `Resource` owns the pool and
    * should be acquired once for the lifetime of the process; the inner one is the `SessionPool`
    * handed to services, and is acquired and released per operation.
    *
    * Pooling matters beyond saving handshakes: skunk's default recycler validates a session as
    * it is returned and evicts it if it is no longer usable, which is what keeps a long-lived
    * pool honest across connection drops.
    */
  def pooled(config: DbConfig, max: Int): Resource[IO, SessionPool] =
    Session.pooled[IO](
      host = config.host,
      port = config.port,
      user = config.user,
      database = config.database,
      password = config.password,
      ssl = SSL.Trusted,
      max = max
    )

  /** A single, unpooled session. Retained for callers that want one connection and no pool. */
  def resource(config: DbConfig): SessionPool =
    Session.single[IO](
      host = config.host,
      port = config.port,
      user = config.user,
      database = config.database,
      password = config.password,
      ssl = SSL.Trusted
    )
}
