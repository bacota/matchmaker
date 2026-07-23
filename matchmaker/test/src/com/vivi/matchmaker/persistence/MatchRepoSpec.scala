package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.model.MatchId

class MatchRepoSpec extends PropertySuite {
  property("create then read returns the match just created") {
    forAll(Gen.oneOf(true, false), Generators.genString) { (playerMatch, matchIdStr) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val matchRepo = new MatchRepo(session)
          for {
            createdGame <- gameRepo.create(Generators.genGame(playerMatch).sample.get)
            matchId = MatchId(matchIdStr)
            m <- IO.pure(Generators.genMatch(createdGame.gameId, matchId, playerMatch).sample.get)
            created <- matchRepo.create(m)
            found <- matchRepo.read(createdGame.gameId, matchId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
