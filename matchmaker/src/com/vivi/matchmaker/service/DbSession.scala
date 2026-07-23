package com.vivi.matchmaker.service

import cats.effect.{IO, Resource}
import skunk.Session
import natchez.Trace.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as noopTracer
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as noopMeter

/** Builds the skunk `Session` a service uses to talk to Postgres, from a `DbConfig`. */
private[service] object DbSession {
  def resource(config: DbConfig): Resource[IO, Session[IO]] =
    Session.single[IO](
      host = config.host,
      port = config.port,
      user = config.user,
      database = config.database,
      password = config.password
    )
}
