package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen

class GameRepoSpec extends PropertySuite {
  property("create then read returns the game just created") {
    forAll(Gen.oneOf(true, false).flatMap(Generators.genGameWithRole)) { game =>
      TestSession.resource
        .use { session =>
          val repo = new GameRepo[String](session)
          for {
            created <- repo.create(game)
            found <- repo.read(created.gameId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
