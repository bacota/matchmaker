package com.vivi.matchmaker.persistence

import cats.effect.{IO, Resource}
import skunk._
import com.vivi.matchmaker.TestMigration

/** A session on the local Postgres instance with user/database/password all "matchmaker", per
  * the assumed local dev setup for these property tests.
  *
  * Borrowed from the pool the services already run on rather than opened per use. A fixture
  * takes one of these several times over — make a game, make a character, read back what a
  * service wrote — and `Session.single` paid a TCP connect and a SCRAM handshake for every one
  * of them, which is most of what a small property test spent its time doing.
  */
object TestSession {
  def resource: Resource[IO, Session[IO]] = {
    TestMigration.ensure()
    com.vivi.matchmaker.service.TestServices.pool
  }
}
