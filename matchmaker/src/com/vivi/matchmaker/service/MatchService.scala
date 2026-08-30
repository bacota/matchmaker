package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model.{GameId, Match, MatchId, MatchSummary, ParticipantResult}
import com.vivi.matchmaker.persistence.{MatchRepo, OpenChallengeRepo, PlayerRepo, ResultRepo}

/** Lists a player's matches, and lets the creator of one call it off.
  *
  * Every list is scoped to the caller's own player id — there is no way to ask for someone
  * else's matches, so no authorization rule beyond identifying the caller is needed. [[cancel]]
  * is the exception: it names a match, so it has a rule of its own.
  */
class MatchService(sessionPool: SessionPool) {

  /** Matches in which it is the caller's turn. */
  def due(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listDueForPlayer(playerId))

  /** Matches the caller is in that are still being played. */
  def active(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listForPlayer(playerId, over = false))

  /** Matches the caller has finished playing. */
  def completed(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listForPlayer(playerId, over = true))

  /** How the caller's finished matches turned out: every seat, the winner first.
    *
    * Scoped to the caller's own participation like the three lists above, so it needs no
    * authorization rule of its own — there is no parameter that could ask for anyone else's.
    * One call covers the whole completed list; see `ResultRepo.listForPlayer`.
    */
  def results(callerExternalId: String): IO[List[ParticipantResult]] =
    sessionPool.use { session =>
      for {
        caller <- resolveCaller(session, callerExternalId)
        results <- new ResultRepo(session).listForPlayer(caller.playerId)
      } yield results
    }

  /** Calls a match off, at the request of the player who created it.
    *
    * The creator is the challenger of the challenge the match was started from — matchmaker has
    * no separate notion of one, and the challenge is kept for exactly this reason. A participant
    * who merely accepted cannot cancel: they agreed to play, which is not the same as having
    * called the match into being.
    *
    * Cancelling is refused once the match is over, in either sense. A completed match has a
    * result and cancelling it would contradict a fact the engine reported; a cancelled one is
    * already cancelled, and saying so is more useful than silently doing nothing.
    *
    * The game engine is not told, because the engine API has no operation for it: the four
    * exchanges of `interaction-design.txt` are create, move, results and status, and none of
    * them retracts a game. The engine's board therefore stays playable after a cancel, and it is
    * matchmaker that stops listening — [[GameEngineService]] refuses the move and result
    * callbacks for a cancelled match, and refuses to refresh it. Telling the engine would need a
    * fifth exchange on both sides of the protocol.
    *
    * Under the match's row lock, so that a cancel racing a result callback resolves one way or
    * the other rather than both writing.
    */
  def cancel(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
    sessionPool.use { session =>
      val matchRepo = new MatchRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)

      session.transaction.use { _ =>
        for {
          caller <- resolveCaller(session, callerExternalId)
          existing <- matchRepo.readForUpdate(gameId, matchId).flatMap {
            case Some(m) => IO.pure(m)
            case None    => IO.raiseError(NotFoundError(s"no match with id ${matchId.value} in game ${gameId.value}"))
          }
          creator <- challengeRepo.challengerOf(gameId, existing.challengeId).flatMap {
            case Some(playerId) => IO.pure(playerId)
            // The foreign key makes this unreachable; it is a NotFoundError rather than a crash
            // because a match whose challenge has gone is a broken row, not a bad request.
            case None =>
              IO.raiseError(NotFoundError(s"match ${matchId.value} has no challenge ${existing.challengeId.value}"))
          }
          _ <- IO.raiseUnless(creator == caller.playerId)(
            UnauthorizedError(s"caller '$callerExternalId' did not create match ${matchId.value} and may not cancel it")
          )
          _ <- IO.raiseWhen(existing.completed)(
            ConflictError(s"match ${matchId.value} is completed and can no longer be cancelled")
          )
          _ <- IO.raiseWhen(existing.cancelled)(ConflictError(s"match ${matchId.value} is already cancelled"))
          cancelled = existing.copy(cancelled = true)
          _ <- matchRepo.update(cancelled)
        } yield cancelled
      }
    }

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
