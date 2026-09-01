package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model.{GameId, Match, MatchId, MatchSummary, ParticipantResult, PlayerClock, PlayerId, TimeLimitKind}
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
    forCaller(callerExternalId)((repo, playerId) => repo.listDueForPlayer(playerId).map(summarise))

  /** Matches the caller is in that are still being played, each with what every seat has left of
    * a chess clock where the match is played under one.
    */
  def active(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId) { (repo, playerId) =>
      repo.listForPlayer(playerId, over = false).map(summarise).flatMap { summaries =>
        // Asked for at all only when one of these matches is played under a chess clock, which
        // most are not.
        if (summaries.exists(s => s.timeLimit.isDefined && s.timeLimitKind == TimeLimitKind.Total))
          withClocks(repo, playerId, summaries)
        else IO.pure(summaries)
      }
    }

  /** Matches the caller has finished playing.
    *
    * No clocks: a finished match's budgets are not something anybody can spend, and what each
    * player *did* spend is on the result rows instead.
    */
  def completed(callerExternalId: String): IO[List[MatchSummary]] =
    forCaller(callerExternalId)((repo, playerId) => repo.listForPlayer(playerId, over = true).map(summarise))

  /** Folds one row per seat into one summary per match.
    *
    * This is where a list of matches is actually decided, rather than in the SQL that fetched
    * it. What a row carries is a fact -- this seat is pending, this is its deadline -- and what
    * a summary carries is a reading of them: who the match is waiting for, and when the turn
    * being taken runs out. Those readings are rules, they will grow, and they belong somewhere
    * they can be read and changed without touching three queries.
    *
    * The rows arrive grouped by match and in the order the list wants, so this preserves both:
    * `groupBy` would not, and re-sorting afterwards would mean restating in Scala the ORDER BY
    * the database has already applied.
    *
    * Grouped by the match alone, not by the match and the caller's seat, because a player has at
    * most one seat in a match: a participant comes from an acceptance, and `acceptance` is keyed
    * by (game, challenge, player), so accepting the same challenge twice is not something the
    * schema permits.
    */
  private def summarise(rows: List[MatchRepo.MatchSeatRow]): List[MatchSummary] =
    rows
      // Adjacent rows of the same match, which is what the queries' ORDER BY guarantees.
      .foldRight(List.empty[(MatchRepo.MatchSeatRow, List[MatchRepo.MatchSeatRow])]) {
        case (row, (head, seats) :: rest) if head.gameId == row.gameId && head.matchId == row.matchId =>
          (row, row :: seats) :: rest
        case (row, groups) => (row, List(row)) :: groups
      }
      .map { (first, seats) =>
        // Whose turn it is: every seat still waited on. Usually one, but a game where several
        // players move at once has several, and a match that is over has none.
        val onTheClock = seats.filter(seat => seat.seatPending && !seat.seatCompleted)
        MatchSummary(
          gameId = first.gameId,
          matchId = first.matchId,
          gameName = first.gameName,
          description = first.description,
          completedAt = first.completedAt,
          cancelled = first.cancelled,
          isCreator = first.isCreator,
          start = first.start,
          due = first.callerDue,
          pending = first.callerPending,
          participantId = first.callerParticipantId,
          characterId = first.callerCharacterId,
          timeLimit = first.timeLimit,
          timeLimitKind = first.timeLimitKind,
          whoseTurn = onTheClock.map(_.seatNickname),
          // The earliest, so a game where several move at once counts down to the first clock to
          // run out, which is the first one anything happens on.
          turnDue = onTheClock.flatMap(_.seatDue).minOption
        )
      }

  private def withClocks(repo: MatchRepo, playerId: PlayerId, summaries: List[MatchSummary]): IO[List[MatchSummary]] =
    repo.clocksForPlayer(playerId).map { rows =>
      val byMatch = rows
        .groupBy(row => (row.gameId, row.matchId))
        .view
        .mapValues(_.map(row => PlayerClock(row.nickname, row.remaining, row.due)))
        .toMap
      summaries.map(s => byMatch.get((s.gameId, s.matchId)).fold(s)(clocks => s.copy(clocks = clocks)))
    }

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
  )(query: (MatchRepo, PlayerId) => IO[List[MatchSummary]]): IO[List[MatchSummary]] =
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
