package com.vivi.matchmaker.service

import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.TestMigration
import com.vivi.matchmaker.engine.GameEngineClient
import com.vivi.matchmaker.persistence.TextCodec.given

/** One set of services over one connection pool, shared by every service spec.
  *
  * The pool is allocated once and never released: it lives for the duration of the test JVM,
  * which is also what makes it safe for specs to hold service instances as vals.
  */
object TestServices {
  private val config =
    DbConfig(host = "localhost", database = "matchmaker", user = "matchmaker", password = Some("matchmaker"))

  /** The pool every set of services in the tests is built over. Allocated once and never
    * released, for the reason above.
    */
  lazy val pool: SessionPool = {
    TestMigration.ensure()
    DbSession.pooled(config, Services.defaultPoolSize).allocated.unsafeRunSync()._1
  }

  lazy val services: Services[String] = Services.fromPool[String](pool)

  /** Services whose game-engine calls go to `engine` instead of over the network. A game engine
    * is a remote system no test can stand up, so the tests of the engine flow drive a stub.
    */
  def servicesWith(engine: GameEngineClient, callbackBaseUrl: Option[String] = None): Services[String] =
    Services.fromPool[String](pool, engine, callbackBaseUrl)
}
