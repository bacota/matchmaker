package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.{Duration, Instant}
import skunk.Session
import com.vivi.matchmaker.model.{
    CompletedPage,
    CompletedQuery,
    EloRating,
    GameId,
    GameMatch,
    Match,
    MatchId,
    MatchOutcome,
    MatchParameter,
    MatchSummary,
    ParticipantResult,
    PlayerClock,
    PlayerId,
    PublicPlayer,
    TimeLimitKind
}
import com.vivi.matchmaker.notify.{MatchEnding, Notifications}
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.util.ChallengeSettings
import com.vivi.matchmaker.persistence.{
    GameAdminRepo,
    MatchRepo,
    ChallengeRepo,
    ParticipantRepo,
    PlayerRepo,
    ResultRepo
}

/** Lists a player's matches, and lets the creator of one call it off.
  *
  * Every list is scoped to the caller's own player id — there is no way to ask for someone else's matches, so no
  * authorization rule beyond identifying the caller is needed. [[cancel]] is the exception: it names a match, so it has
  * a rule of its own.
  */
class MatchService(
    sessionPool: SessionPool,
    /* Silent by default, as in the other services that send mail: see `Notifications`. */
    notifications: Notifications = Notifications.disabled,
    /* For the finished lists: whether a friendly match's archive is still there to be watched. */
    archives: Option[ArchiveService] = None,
    /* For a cancel: saying the match has ended, so that its engine is told and can drop it. */
    endings: MatchEndings = MatchEndings.disabled
) {

    /** Matches in which it is the caller's turn. */
    def due(callerExternalId: String): IO[List[MatchSummary]] =
        forCaller(callerExternalId)((repo, playerId) => repo.listDueForPlayer(playerId).flatMap(summarised(repo, _)))

    /** Matches the caller is in that are still being played, each with what every seat has left of a chess clock where
      * the match is played under one.
      */
    def active(callerExternalId: String): IO[List[MatchSummary]] =
        forCaller(callerExternalId) { (repo, playerId) =>
            repo.listActiveForPlayer(playerId).flatMap(summarised(repo, _)).flatMap { summaries =>
                // Asked for at all only when one of these matches is played under a chess clock, which
                // most are not.
                if (summaries.exists(s => s.timeLimit.isDefined && s.timeLimitKind == TimeLimitKind.Total))
                    withClocks(repo, playerId, summaries)
                else IO.pure(summaries)
            }
        }

    /** One window of the matches the caller has finished: see [[CompletedQuery]] and [[CompletedPage]]. Never a
      * cancelled match.
      *
      * No clocks: a finished match's budgets are not something anybody can spend, and what each player *did* spend is
      * on the result rows instead.
      */
    def completed(callerExternalId: String, query: CompletedQuery = CompletedQuery()): IO[CompletedPage] =
        forCaller(callerExternalId) { (repo, playerId) =>
            completedPage(repo, query)(
              span => repo.listCompletedForPlayer(playerId, span),
              from => repo.hasCompletedBefore(playerId, query.gameId, from)
            )
        }.flatMap(viewedPage)

    /** The matches another player has marked public that are still running.
      *
      * The one kind of list here that is not about the caller, and so the one with a visibility rule: only matches
      * whose challenge was offered as public. That rule lives in the queries (`MatchRepo.listPublicActiveForPlayer` and
      * its counterparts) rather than here, so a private match is never read at all.
      *
      * The caller still has to be a registered player -- who plays here is for the people who play here -- but there is
      * nothing else to authorize: a public match is public to all of them, and the caller's own relationship to it
      * makes no difference to what this says.
      *
      * No clocks. A chess clock is a thing its owner spends, `withClocks` reads the caller's own budgets, and a
      * stranger's remaining seconds are not what somebody reading their page came for.
      */
    def publicActive(callerExternalId: String, playerId: PlayerId): IO[List[MatchSummary]] =
        forRegistered(callerExternalId)(repo => repo.listPublicActiveForPlayer(playerId).flatMap(summarised(repo, _)))
            .flatMap(viewed)

    /** One window of the public matches another player has finished, as [[completed]] is of the caller's own. */
    def publicCompleted(callerExternalId: String, playerId: PlayerId, query: CompletedQuery): IO[CompletedPage] =
        forRegistered(callerExternalId) { repo =>
            completedPage(repo, query)(
              span => repo.listPublicCompletedForPlayer(playerId, span),
              from => repo.hasPublicCompletedBefore(playerId, query.gameId, from)
            )
        }.flatMap(viewedPage)

    /* One window of a completed list, whoever's it is: the window the query names, the matches in
     * it, and whether the list holds anything older -- which is what says the oldest match the list
     * could ever show is already on screen.
     *
     * The window is measured back from `asOf`, which the server sets on a first ask from the
     * database's own clock, and which is then sent back for every later window of the same list.
     * Every window ends where the next newer one begins, the most recent included: a list paged
     * back and forth from one `asOf` shows the same windows each time. A match finished since then
     * is shown by asking afresh -- a refresh, or a change of frame -- which takes a new `asOf`. */
    private def completedPage(repo: MatchRepo, query: CompletedQuery)(
        rows: MatchRepo.CompletedSpan => IO[List[MatchRepo.MatchSeatRow]],
        olderThan: Instant => IO[Boolean]
    ): IO[CompletedPage] =
        for {
            asOf <- query.asOf.fold(repo.now)(IO.pure)
            until = query.until(asOf)
            from = query.from(asOf)
            found <- rows(MatchRepo.CompletedSpan(from, until, query.gameId))
            summaries <- summarised(repo, found)
            older <- olderThan(from)
        } yield CompletedPage(summaries, query.frame, query.page, asOf, from, until, older)

    private def viewedPage(page: CompletedPage): IO[CompletedPage] =
        viewed(page.matches.toList).map(matches => page.copy(matches = matches))

    /* A list that may be about anybody: the caller only has to be a registered player. */
    private def forRegistered[A](callerExternalId: String)(query: MatchRepo => IO[A]): IO[A] =
        sessionPool.use { session =>
            resolveCaller(session, callerExternalId).flatMap(_ => query(new MatchRepo(session)))
        }

    /** A finished list as a player is shown it: any friendly archive that may have expired checked, and the links of an
      * archived match marked as such — see [[ArchiveService.settle]].
      *
      * After the list's session is given back, not inside it: the check asks S3, and a pooled connection held across
      * that is one nobody else can use meanwhile — and the check borrows one of its own to record what it found.
      */
    private def viewed(summaries: List[MatchSummary]): IO[List[MatchSummary]] =
        archives
            .fold(IO.pure(summaries))(_.settle(summaries))
            .map(_.map(ArchiveService.forViewer))

    /** The summaries of these rows, each with the parameter values its match is played under.
      *
      * The values are resolved here, as `GameEngineService` resolved them for the engine: the challenger's choice where
      * the game still allows it, the default otherwise. Every parameter is listed, chosen or not — a match played at
      * the default is played under that value just the same, and "12 rounds" is what a player wants to see either way.
      */
    private def summarised(repo: MatchRepo, rows: List[MatchRepo.MatchSeatRow]): IO[List[MatchSummary]] =
        repo.parametersForGames(rows.map(_.gameId).toSet).map { parameters =>
            val byGame = parameters.groupBy(_.gameId)
            val settingsOf = rows.map(row => (row.gameId, row.matchId) -> row.settings).toMap
            summarise(rows).map { summary =>
                val ofGame = byGame.getOrElse(summary.gameId, Nil)
                val resolved = ChallengeSettings.resolve(
                  defaults = ofGame.map(p => p.name -> p.defaultValue.getOrElse("")).toMap,
                  allowed = ofGame.map(p => p.name -> p.values).toMap,
                  settings = settingsOf.getOrElse((summary.gameId, summary.matchId), "{}")
                )
                summary.copy(parameters =
                    ofGame
                        .map(p => MatchParameter(p.displayName, resolved.getOrElse(p.name, "")))
                        .filter(_.value.nonEmpty)
                )
            }
        }

    /** Folds one row per seat into one summary per match.
      *
      * This is where a list of matches is actually decided, rather than in the SQL that fetched it. What a row carries
      * is a fact -- this seat is pending, this is its deadline -- and what a summary carries is a reading of them: who
      * the match is waiting for, and when the turn being taken runs out. Those readings are rules, they will grow, and
      * they belong somewhere they can be read and changed without touching three queries.
      *
      * The rows arrive grouped by match and in the order the list wants, so this preserves both: `groupBy` would not,
      * and re-sorting afterwards would mean restating in Scala the ORDER BY the database has already applied.
      *
      * Grouped by the match alone, not by the match and the caller's seat, because a player has at most one seat in a
      * match: a participant comes from an acceptance, and `acceptance` is keyed by (game, challenge, player), so
      * accepting the same challenge twice is not something the schema permits.
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
                // The caller's seat and everyone else's: who they played, and where each finished.
                val (own, others) = seats.partition(_.seatParticipantId == first.callerParticipantId)
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
                  timeLimitUnit = first.timeLimitUnit,
                  live = first.live,
                  whoseTurn = onTheClock.map(_.seatNickname),
                  // The earliest, so a game where several move at once counts down to the first clock to
                  // run out, which is the first one anything happens on.
                  turnDue = onTheClock.flatMap(_.seatDue).minOption,
                  // A fact about the match, so it is the same on every row of it and comes off the
                  // first like the rest of them.
                  publicUrl = first.publicUrl,
                  friendly = first.friendly,
                  archivedAt = first.archivedAt,
                  archiveExpired = first.archiveExpired,
                  resultSummary = first.resultSummary,
                  opponents = others.map(seat => PublicPlayer(seat.seatPlayerId, seat.seatNickname)),
                  // Not for a match called off, whose results -- if any -- are not how it ended.
                  outcome =
                      if (first.cancelled) None
                      else MatchOutcome.of(own.headOption.flatMap(_.seatRank), seats.flatMap(_.seatRank)),
                  eloDelta = if (first.cancelled) None else own.headOption.flatMap(_.seatEloDelta)
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
      * Scoped to the caller's own participation like the three lists above, so it needs no authorization rule of its
      * own — there is no parameter that could ask for anyone else's. Two queries for the whole completed list, not two
      * per match: the rows of the table, and what each seat spent getting there.
      */
    def results(callerExternalId: String): IO[List[ParticipantResult]] =
        sessionPool.use { session =>
            val resultRepo = new ResultRepo(session)
            for {
                caller <- resolveCaller(session, callerExternalId)
                rows <- resultRepo.listForPlayer(caller.playerId)
                spent <- resultRepo.timeTakenForPlayer(caller.playerId)
            } yield {
                val byParticipant = spent.map(row => (row.gameId, row.participantId) -> row.timeTaken).toMap
                rows.map { row =>
                    ParticipantResult(
                      row.gameId,
                      row.matchId,
                      row.participantId,
                      row.nickname,
                      row.roleName,
                      row.rank,
                      row.scores,
                      row.isWinner,
                      row.forfeit,
                      // Absent means nothing was recorded against this seat, which reads as zero — both
                      // for a player who never moved and for a match played before turns were kept.
                      byParticipant.getOrElse((row.gameId, row.participantId), Duration.ZERO),
                      row.eloStart,
                      row.eloDelta
                    )
                }
            }
        }

    /** Calls a match off, at the request of the player who created it.
      *
      * The creator is the challenger of the challenge the match was started from — matchmaker has no separate notion of
      * one, and the challenge is kept for exactly this reason. A participant who merely accepted cannot cancel: they
      * agreed to play, which is not the same as having called the match into being.
      *
      * Cancelling is refused once the match is over, in either sense. A completed match has a result and cancelling it
      * would contradict a fact the engine reported; a cancelled one is already cancelled, and saying so is more useful
      * than silently doing nothing.
      *
      * The game engine is told afterwards, at the cancel url it gave when it created the game, so that it can drop the
      * match — by the listener the match's ending is queued to (`EndingService`), which tries again until it hears.
      * Whether or not it hears, matchmaker stops listening: [[GameEngineService]] refuses the move and result callbacks
      * for a cancelled match, and refuses to refresh it.
      *
      * Under the match's row lock, so that a cancel racing a result callback resolves one way or the other rather than
      * both writing.
      */
    def cancel(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
        sessionPool
            .use { session =>
                val matchRepo = new MatchRepo(session)
                val challengeRepo = new ChallengeRepo(session)
                val participantRepo = new ParticipantRepo(session)

                session.transaction
                    .use { _ =>
                        for {
                            caller <- resolveCaller(session, callerExternalId)
                            existing <- matchRepo.readForUpdate(gameId, matchId).flatMap {
                                case Some(m) => IO.pure(m)
                                case None =>
                                    IO.raiseError(
                                      NotFoundError(s"no match with id ${matchId.value} in game ${gameId.value}")
                                    )
                            }
                            creator <- challengeRepo.challengerOf(gameId, existing.challengeId).flatMap {
                                case Some(playerId) => IO.pure(playerId)
                                // The foreign key makes this unreachable; it is a NotFoundError rather than a crash
                                // because a match whose challenge has gone is a broken row, not a bad request.
                                case None =>
                                    IO.raiseError(
                                      NotFoundError(
                                        s"match ${matchId.value} has no challenge ${existing.challengeId.value}"
                                      )
                                    )
                            }
                            _ <- IO.raiseUnless(creator == caller.playerId)(
                              UnauthorizedError(
                                s"caller '$callerExternalId' did not create match ${matchId.value} and may not cancel it"
                              )
                            )
                            _ <- IO.raiseWhen(existing.completed)(
                              ConflictError(s"match ${matchId.value} is completed and can no longer be cancelled")
                            )
                            _ <- IO.raiseWhen(existing.cancelled)(
                              ConflictError(s"match ${matchId.value} is already cancelled")
                            )
                            cancelled = existing.copy(cancelled = true)
                            _ <- matchRepo.update(cancelled)
                            // And the seats, in the same lock: a cancelled match is over, so nobody's turn
                            // is pending in it and no clock is still running. Completion says this seat by
                            // seat as it records what each player scored; a cancellation has nothing to
                            // record, so it says the one thing true of every seat at once.
                            //
                            // Not merely tidiness. Anything asking "is this seat still in play" could
                            // otherwise only answer it by joining `match` for the cancelled flag, because
                            // the seat did not know -- see `NotificationRepo.restampParticipants`.
                            _ <- participantRepo.completeForMatch(gameId, matchId)
                        } yield (cancelled, caller)
                    }
                    .flatMap { (cancelled, caller) =>
                        /* After the commit and outside the lock, and unable to fail the cancel -- the same
                         * terms every notification in this codebase is sent on. Everyone in the match except
                         * the creator, who called it off and is looking at the answer. */
                        notifications
                            .matchEnded(session, cancelled, MatchEnding.Cancelled, except = Some(caller.playerId))
                            .as(cancelled)
                    }
            }
            // And the engine, by way of the match's ending: telling it is the listener's to do, and to try
            // again until it hears.
            .flatTap(cancelled => endings.ended(cancelled.gameId, cancelled.matchId))

    private def forCaller[A](
        callerExternalId: String
    )(query: (MatchRepo, PlayerId) => IO[A]): IO[A] =
        sessionPool.use { session =>
            for {
                player <- resolveCaller(session, callerExternalId)
                result <- query(new MatchRepo(session), player.playerId)
            } yield result
        }

    /** The game's matches, for its admins to manage them from: an overall admin's or the game's own admins' to read,
      * since these are matches they may have no seat in. The running ones first, and at most
      * [[MatchService.gameMatchLimit]] of them altogether, so that a game with a long history answers with what is
      * being played and what was played lately rather than with everything. Given a player, only the matches they have
      * a seat in: what an admin manages from that player's page.
      */
    def listForGame(gameId: GameId, callerExternalId: String, player: Option[PlayerId] = None): IO[List[GameMatch]] =
        sessionPool.use { session =>
            for {
                caller <- resolveCaller(session, callerExternalId)
                // Read plainly, like the list: nothing is written on the strength of it.
                allowed <-
                    if (caller.isAdmin) IO.pure(true)
                    else new GameAdminRepo(session).isAdmin(caller.playerId, gameId)
                _ <- IO.raiseUnless(allowed)(UnauthorizedError("only an admin of this game may list its matches"))
                matches <- new MatchRepo(session).listForGame(gameId, MatchService.gameMatchLimit, player)
            } yield matches
        }

    /** Says whether the match is friendly (V36): a game admin's to decide, or an overall admin's, and nobody else's —
      * not even the match's creator. Saying what it already is changes nothing.
      *
      * A completed match may change too, and is re-rated when it does — see [[EloRatingService.reclassify]], which
      * refuses a change that would cascade into another match's rating. A match with a player in two of its seats can
      * only be friendly, since rating it would rate that player against themselves.
      *
      * Under the match's row lock, which [[MatchRepo.update]] rewrites whole, and with the caller's admin held FOR
      * SHARE so that losing it waits for this to land.
      *
      * A completed match made not friendly has its archive moved from the friendly bucket, which expires it, to the
      * permanent one — as part of this request, and before the change: the archive is copied first
      * ([[ArchiveService.prepareMove]]), the copy is recorded in the transaction that makes the change
      * ([[ArchiveService.recordMove]]), and the original is deleted once that has committed
      * ([[ArchiveService.finishMove]]). If the copy fails, or the change does, the request fails and the archive is
      * where it was. The copy is not made inside the transaction: S3 is not a thing to hold a transaction open across.
      * A match made friendly keeps its archive where it is — moving it would only put it where it can expire.
      */
    def setFriendly(gameId: GameId, matchId: MatchId, friendly: Boolean, callerExternalId: String): IO[Match] =
        for {
            // Whether to copy, decided before the transaction and without its locks, so that a caller the
            // transaction would refuse has nothing copied for them. Everything here is asked again under
            // the lock, and an answer that has changed is handled there.
            wanted <-
                if (friendly) IO.pure(false)
                else sessionPool.use(session => mayMoveArchive(session, gameId, matchId, callerExternalId))
            prepared <-
                if (wanted) archives.flatTraverse(_.prepareMove(gameId, matchId))
                else IO.pure(Option.empty[PreparedMove])
            decided <- sessionPool.use { session =>
                val matchRepo = new MatchRepo(session)
                session.transaction.use { _ =>
                    for {
                        caller <- new PlayerRepo(session).readByExternalIdForShare(callerExternalId).flatMap {
                            case Some(player) => IO.pure(player)
                            case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
                        }
                        allowed <-
                            if (caller.isAdmin) IO.pure(true)
                            else new GameAdminRepo(session).isAdminForShare(caller.playerId, gameId)
                        _ <- IO.raiseUnless(allowed)(
                          UnauthorizedError("only an admin of this game may say whether its matches are friendly")
                        )
                        existing <- matchRepo.readForUpdate(gameId, matchId).flatMap {
                            case Some(m) => IO.pure(m)
                            case None =>
                                IO.raiseError(
                                  NotFoundError(s"no match with id ${matchId.value} in game ${gameId.value}")
                                )
                        }
                        changed = existing.friendly != friendly
                        // A match that is not friendly is rated (V42), and cannot rate a player against
                        // themselves. Its seats are written once, at its start, so read under the match's
                        // lock they are what they will be when it ends.
                        players <- new ParticipantRepo(session)
                            .listForMatch(gameId, matchId)
                            .map(_.map((p, _, _) => p.playerId))
                        _ <- IO.raiseWhen(changed && !friendly && !EloRating.playersOnce(players))(
                          ConflictError("a player holds more than one seat in this match, so it can only be friendly")
                        )
                        _ <- IO.whenA(changed && existing.completed)(
                          EloRatingService.reclassify(session, gameId, matchId, friendly)
                        )
                        moved <-
                            if (changed && existing.completed && !friendly)
                                archives.fold(IO.pure(false))(_.recordMove(session, prepared, gameId, matchId))
                            else IO.pure(false)
                        classified = existing.copy(friendly = friendly)
                        _ <- IO.whenA(changed)(matchRepo.update(classified))
                    } yield (classified, moved, changed && existing.completed)
                }
            }
            (classified, moved, rerated) = decided
            _ <- prepared.filter(_ => moved).traverse_(p => archives.traverse_(_.finishMove(p)))
            // Its players' ratings moved, and the leaderboard follows them in the listener, as it does a match's end.
            _ <- IO.whenA(rerated)(endings.ratingsChanged(gameId))
        } yield classified

    /* Whether this caller may make this match not friendly and it would then owe its archive a move:
     * read plainly, for deciding whether to copy before the transaction that decides everything again.
     * Whether the archive is in the friendly bucket is `ArchiveService.prepareMove`'s to ask. */
    private def mayMoveArchive(
        session: Session[IO],
        gameId: GameId,
        matchId: MatchId,
        callerExternalId: String
    ): IO[Boolean] =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case None => IO.pure(false)
            case Some(caller) =>
                for {
                    allowed <-
                        if (caller.isAdmin) IO.pure(true)
                        else new GameAdminRepo(session).isAdmin(caller.playerId, gameId)
                    found <- new MatchRepo(session).read(gameId, matchId)
                } yield allowed && found.exists(m => m.completed && m.friendly)
        }

    private def resolveCaller(session: Session[IO], callerExternalId: String) =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }
}

object MatchService {

    /** How many matches [[MatchService.listForGame]] answers with at most. */
    val gameMatchLimit: Int = 50
}
