package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import com.vivi.matchmaker.model.{GameId, Match, MatchId, PlayerId, TournamentId}

class MatchRepoSpec extends PropertySuite {
    property("a match written then read is the match just written") {
        forAll(Generators.genMatchId) { mid =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndOwner <- Generators.gameWithOwner(session)
                        (createdGame, owner) = gameAndOwner
                        matchId = mid
                        created <- TestMatches.writeMatch(
                          session = session,
                          m = Generators.matchFrom(createdGame.gameId, matchId),
                          owner = owner
                        )
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    property("update then read returns the match as it was rewritten, its friendliness included") {
        forAll(Generators.genMatchId) { mid =>
            TestSession.resource
                .use { session =>
                    val matchRepo = new MatchRepo(session)
                    for {
                        gameAndOwner <- Generators.gameWithOwner(session)
                        (createdGame, owner) = gameAndOwner
                        matchId = mid
                        m <- TestMatches.writeMatch(
                          session = session,
                          m = Generators.matchFrom(createdGame.gameId, matchId),
                          owner = owner
                        )
                        rewritten = m.copy(isFriendly = !m.isFriendly, isPublic = !m.isPublic)
                        _ <- matchRepo.update(rewritten)
                        found <- matchRepo.read(createdGame.gameId, matchId)
                    } yield found == Some(rewritten)
                }
                .unsafeRunSync()
        }
    }

    test("a match is friendly unless it says otherwise") {
        val unsaid =
            Match(
              gameId = GameId(1),
              matchId = MatchId(1),
              tournamentId = TournamentId(1),
              description = "",
              completedAt = None,
              start = Some(java.time.Instant.EPOCH),
              timeLimit = None,
              settings = "{}"
            )
        assert(unsaid.isFriendly)
    }
}
