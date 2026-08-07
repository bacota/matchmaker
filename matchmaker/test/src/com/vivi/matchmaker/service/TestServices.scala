package com.vivi.matchmaker.service

import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.persistence.TextCodec.given

/** One set of services over one connection pool, shared by every service spec.
  *
  * The pool is allocated once and never released: it lives for the duration of the test JVM,
  * which is also what makes it safe for specs to hold service instances as vals.
  */
object TestServices {
  private val config =
    DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))

  lazy val services: Services[String] = {
    TestMigration.ensure()
    Services.resource[String](config).allocated.unsafeRunSync()._1
  }
}
