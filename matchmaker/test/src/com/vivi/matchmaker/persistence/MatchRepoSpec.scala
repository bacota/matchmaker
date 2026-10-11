package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.{ChallengeId, GameId, Match, MatchId}

class MatchRepoSpec extends PropertySuite {
    property("create then read returns the match just created") {
        forAll(Generators.genString) { matchIdStr =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndChallenge <- Generators.gameWithChallenge(session)
                        (createdGame, challengeId) = gameAndChallenge
                        matchId = MatchId(matchIdStr)
                        m <- IO.pure(Generators.genMatch(createdGame.gameId, matchId, challengeId).sample.get)
                        created <- matchRepo.create(m)
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    property("update then read returns the match as it was rewritten, its friendliness included") {
        forAll(Generators.genString) { matchIdStr =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndChallenge <- Generators.gameWithChallenge(session)
                        (createdGame, challengeId) = gameAndChallenge
                        matchId = MatchId(matchIdStr)
                        m <- IO.pure(Generators.genMatch(createdGame.gameId, matchId, challengeId).sample.get)
                        _ <- matchRepo.create(m)
                        rewritten = m.copy(friendly = !m.friendly, isPublic = !m.isPublic)
                        _ <- matchRepo.update(rewritten)
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(rewritten)
                }
                .unsafeRunSync()
        }
    }

    test("a match is friendly unless it says otherwise") {
        val unsaid = Match(GameId(1), MatchId("m"), ChallengeId(1), "", None, java.time.Instant.EPOCH, None, "{}")
        assert(unsaid.friendly)
    }
}
