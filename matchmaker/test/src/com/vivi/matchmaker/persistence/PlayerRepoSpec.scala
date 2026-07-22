package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._

class PlayerRepoSpec extends ScalaCheckSuite {
  property("create then read returns the player just created") {
    forAll(Generators.genPlayer) { player =>
      TestSession.resource
        .use { session =>
          val repo = new PlayerRepo(session)
          for {
            created <- repo.create(player)
            found <- repo.read(created.playerId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
