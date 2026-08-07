package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model.MatchSummary
import com.vivi.matchmaker.persistence.{MatchRepo, PlayerRepo}

/** Lists a player's matches. Every list is scoped to the caller's own player id — there is no
  * way to ask for someone else's matches, so no authorization rule beyond identifying the
  * caller is needed.
  */
class MatchService(sessionPool: SessionPool) {

  /** Matches in which it is the caller's turn. */
  def due(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listDueForPlayer(playerId))

  /** Matches the caller is in that are still being played. */
  def active(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listForPlayer(playerId, completed = false))

  /** Matches the caller has finished playing. */
  def completed(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listForPlayer(playerId, completed = true))

  private def forCaller(
      callerExternalId: String
  )(query: (MatchRepo, com.vivi.matchmaker.model.PlayerId) => IO[List[MatchSummary]]): IO[List[MatchSummary]] =
    sessionPool.use { session =>
      for {
        player <- resolveCaller(session, callerExternalId)
        result <- query(new MatchRepo(session), player.playerId)
      } yield result
    }

  private def resolveCaller(session: Session[IO], callerExternalId: String) =
    new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
      case Some(player) => IO.pure(player)
      case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
    }
}
