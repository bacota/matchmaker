package com.vivi.matchmaker.persistence

import cats.effect.{IO, Resource}
import skunk._
import natchez.Trace.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as noopTracer
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as noopMeter

/** Connects to a local Postgres instance with user/database/password all
  * "matchmaker", per the assumed local dev setup for these property tests.
  */
object TestSession {
  def resource: Resource[IO, Session[IO]] =
    Session.single[IO](
      host = "localhost",
      port = 5432,
      user = "matchmaker",
      database = "matchmaker",
      password = Some("matchmaker")
    )
}
