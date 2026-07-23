package com.vivi.matchmaker.persistence

import cats.effect.{IO, Resource}
import skunk._
import natchez.Trace.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as noopTracer
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as noopMeter
import com.vivi.matchmaker.TestMigration

/** Connects to a local Postgres instance with user/database/password all
  * "matchmaker", per the assumed local dev setup for these property tests.
  */
object TestSession {
  private val host = "localhost"
  private val port = 5432
  private val user = "matchmaker"
  private val database = "matchmaker"
  private val password = "matchmaker"

  def resource: Resource[IO, Session[IO]] = {
    TestMigration.ensure()
    Session.single[IO](
      host = host,
      port = port,
      user = user,
      database = database,
      password = Some(password)
    )
  }
}
