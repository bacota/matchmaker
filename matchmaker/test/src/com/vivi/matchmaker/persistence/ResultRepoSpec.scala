package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.ScalaCheckSuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.MatchId

class ResultRepoSpec extends ScalaCheckSuite {
  property("create then read returns the result just created") {
    forAll(Generators.genString, Generators.genPlayer) { (matchIdStr, player) =>
      TestSession.resource
        .use { session =>
          val gameRepo = new GameRepo[String](session)
          val matchRepo = new MatchRepo(session)
          val playerRepo = new PlayerRepo(session)
          val participantRepo = new ParticipantRepo(session)
          val resultRepo = new ResultRepo(session)

          for {
            createdGame <- gameRepo.create(Generators.genGameWithRole(true).sample.get)
            matchId = MatchId(matchIdStr)
            _ <- matchRepo.create(Generators.genMatch(createdGame.gameId, matchId, true).sample.get)
            createdPlayer <- playerRepo.create(player)
            createdParticipant <- participantRepo.create(
              Generators
                .genPlayerParticipant(createdGame.gameId, matchId, createdPlayer.playerId, createdGame.roles.head.gameRoleId)
                .sample
                .get
            )
            result <- IO.pure(Generators.genResult(createdParticipant.participantId).sample.get)
            created <- resultRepo.create(result)
            found <- resultRepo.read(created.participantId)
          } yield found == Some(created)
        }
        .unsafeRunSync()
    }
  }
}
