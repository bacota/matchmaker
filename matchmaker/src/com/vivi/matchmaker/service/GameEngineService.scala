package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import scala.concurrent.duration._
import java.time.Instant
import skunk.Session
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.notify.{MatchEnding, Notifications}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence._
import com.vivi.matchmaker.util.ChallengeSettings

/** A seat that is to move, and when its clock started. */
case class SeatClock(participantId: ParticipantId, since: Instant)

/** Where a match stands after a move, as an engine that numbers its moves states it.
  *
  * `sequence` is the engine's number for the move, higher for every move it commits after this one; `pending` is every
  * seat that is to move now — a seat left out is not pending. Stated whole rather than as a change, so that a callback
  * which turns out to be older than one already applied can be ignored whole: see [[GameEngineService.recordMove]].
  */
case class MoveState(sequence: Long, pending: List[SeatClock])

/** One participant's outcome as the game engine reports it at the end of a match. `forfeit` is a live match the engine
  * ended because a turn ran out — the engine's own forfeit, recorded as matchmaker records its own.
  */
case class ReportedResult(
    participantId: ParticipantId,
    rank: Int,
    scores: Map[String, Any],
    isWinner: Boolean,
    forfeit: Boolean = false
)

/** The four exchanges between matchmaker and a game engine, as described in `interaction-design.txt`:
  *
  *   1. [[launchTournamentMatch]] — a tournament's match, its roles settled, is created in the engine, and the urls it
  *      answers with recorded. 2. [[recordMove]] — the engine calls back when a player has moved; matchmaker updates
  *      whose turn it is. 3. [[recordResults]] — the engine calls back when the match is over; matchmaker completes the
  *      match and its participants and writes the result rows. 4. [[refresh]] — a participant asks matchmaker to
  *      re-check with the engine, in case a callback was lost or the engine ended the match on a timer.
  *
  * The two callbacks are authorized as the *game*, not as a player: their `callerExternalId` is matched against
  * `game.external_id`, the shared secret that identifies a game engine to matchmaker — the same rule
  * `CharacterService.updateState` uses. `refresh` is an ordinary player-authorized call.
  */
class GameEngineService[T](
    sessionPool: SessionPool,
    engine: GameEngineClient,
    callbackBaseUrl: Option[String] = None,
    /* Four things that happen here are worth an email -- a match starting, a move, a result, a
     * forfeit -- and which players hear about any of them is `Notifications`' business rather than
     * this service's. Silent by default, as in the other services that send mail. */
    notifications: Notifications = Notifications.disabled,
    /* For [[read]]: whether a finished friendly match's archive is still there to be reviewed. */
    archives: Option[ArchiveService] = None,
    /* Where a match's end is said, once it has ended here, for what it owes afterwards to be settled: its
     * archive. Nothing by default. */
    endings: MatchEndings = MatchEndings.disabled
)(using codec: TextCodec[T]) {

    /** The engine half of a tournament match's start (new-design.md, Phase 5): `saved`, its participants and its
      * tournament's claim on it already written and committed by [[TournamentRounds]], are sent to the engine, and its
      * answer recorded — the urls, then whose turn it is, then the mail.
      *
      * The engine call sits between two transactions rather than inside one: holding a transaction open across a call
      * to another system would keep locks for as long as that system takes to answer, and no database transaction can
      * roll the engine's game back anyway. `undo` takes back what was written before, if the engine refuses: the match
      * goes back to being due, to be created again. After the engine has made its game there is no undoing, and a
      * failure past that point leaves the urlless match `refresh` reports.
      *
      * Whose turn it is first is the engine's to decide, and every seat was written not pending, so its status is asked
      * for at once: without it nobody's list of matches waiting on them would show this one. Best effort, as is the
      * mail after it: the match exists, and neither is a reason to fail its start.
      */
    private[service] def launchTournamentMatch(
        session: Session[IO],
        saved: Match,
        game: Game,
        players: List[EnginePlayer],
        tiesAllowed: Boolean,
        undo: IO[Unit]
    ): IO[Match] =
        for {
            apiKey <- new GameApiKeyRepo(session).forGame(game.gameId)
            request = CreateGameRequest(
              matchId = saved.matchId.value.toString,
              gameName = game.name,
              isPublic = saved.isPublic,
              parameters = ChallengeSettings.resolve(
                defaults = game.parameters
                    .map(p => p.name -> p.defaultValue.map(v => codec.encode(v.asInstanceOf[T])).getOrElse(""))
                    .toMap,
                allowed =
                    game.parameters.map(p => p.name -> p.values.map(v => codec.encode(v.value.asInstanceOf[T]))).toMap,
                settings = saved.settings
              ),
              settings = saved.settings,
              timeLimitSeconds = saved.timeLimit.map(_.getSeconds),
              players = players,
              // The limit again, with its kind, as the clock the engine itself enforces. A live tournament always
              // has one; `filter` rather than `get`, so that a match that somehow has none starts as an ordinary one.
              live = saved.timeLimit
                  .filter(_ => saved.live)
                  .map(limit => LiveTerms(limit.getSeconds, saved.timeLimitKind.code)),
              gameDisplayName = Some(game.displayName),
              description = Some(saved.description.trim).filter(_.nonEmpty),
              moveCallbackUrl =
                  callbackBaseUrl.map(base => s"$base/games/${game.gameId.value}/matches/${saved.matchId.value}/moves"),
              resultsCallbackUrl = callbackBaseUrl.map(base =>
                  s"$base/games/${game.gameId.value}/matches/${saved.matchId.value}/results"
              ),
              tiesAllowed = Option.when(!tiesAllowed)(false)
            )
            response <- engine.createGame(game.url, apiKey, request).onError(_ => undo)
            withUrls = saved.copy(
              statusUrl = Some(response.statusUrl),
              playUrl = Some(response.playUrl),
              publicUrl = response.publicUrl
            )
            started <- retrying(finish(session, withUrls, response.cancelUrl).as(withUrls))
            _ <- applyEngineStatus(session, saved.gameId, saved.matchId, response.statusUrl).attempt
            // Nobody pressed anything: everyone in the match hears that it has begun.
            _ <- notifications.matchStarted(session, started)
        } yield started

    /** A tournament seat as the engine is told it: who plays it, as what, with which character. */
    private[service] def tournamentPlayer(
        session: Session[IO],
        participant: Participant,
        player: Player,
        roleName: String,
        characterId: Option[CharacterId]
    ): IO[EnginePlayer] =
        characterId.flatTraverse(new CharacterRepo[T](session).read).map { c =>
            EnginePlayer(
              cognitoId = player.externalId,
              participantId = participant.participantId.value,
              role = Some(roleName),
              characterId = c.map(_.characterId.value),
              characterState = c.map(ch => codec.encode(ch.state)),
              nickname = Some(player.nickname),
              characterName = c.map(_.name)
            )
        }

    /** Enforces a tournament match's clock now, for its owner's Check round: the same enforcement a player's look at
      * the match would make, the engine asked first.
      */
    private[service] def enforceNow(gameId: GameId, matchId: MatchId): IO[Unit] =
        sessionPool.use { session =>
            new MatchRepo(session).read(gameId, matchId).flatMap {
                case Some(m) => enforceTimeouts(session, gameId, matchId, m).void
                case None    => IO.unit
            }
        }

    /* Asks the engine how a match stands and writes the answer onto its participants: whose turn
     * it is, when that turn is due, and who is finished — plus the match's own completed flag.
     *
     * Shared by `refresh`, which is this question asked again later, and by `start`, which asks it
     * once so that the first turn is recorded the moment the match exists. */
    private def applyEngineStatus(
        session: skunk.Session[IO],
        gameId: GameId,
        matchId: MatchId,
        statusUrl: String
    ): IO[Match] = {
        val matchRepo = new MatchRepo(session)
        val participantRepo = new ParticipantRepo(session)
        val turnRepo = new TurnRepo(session)
        // Asked outside the transaction, like the engine call it is an argument to: it is the point
        // the engine reports turns from, and a turn arriving between this read and the write below
        // is simply reported again by the next status call.
        // The key on the session already held, rather than one borrowed for the purpose.
        (turnRepo.latestTakenAt(gameId, matchId), new GameApiKeyRepo(session).forGame(gameId)).tupled
            .flatMap { (_, apiKey) =>
                engine.status(statusUrl, apiKey, None).flatMap { status =>
                    session.transaction.use { _ =>
                        for {
                            current <- requireMatchForUpdate(matchRepo, gameId, matchId)
                            // Re-read under the lock, and checked here rather than only by the callers:
                            // their check was made before the status call, and a cancel committing while
                            // the engine was answering must not be undone by the answer. Writing what the
                            // engine said would restamp the seats this match's cancel had finished and
                            // could complete a match its creator had called off.
                            outcome <-
                                if (current.cancelled) IO.pure((current, false))
                                else
                                    for {
                                        participants <- participantRepo.listForMatch(gameId, matchId)
                                        byId = participants.map((p, _, _) => p.participantId -> p).toMap
                                        // Before the deadlines below, which for a total limit are computed from what each seat
                                        // has spent — and what they have spent is these rows.
                                        _ <- recordTurns(
                                          session,
                                          current,
                                          status.turns.filter(t => byId.contains(ParticipantId(t.participantId))),
                                          since = None
                                        )
                                        used <- timeUsedIn(session, current)
                                        /* Whether the answer still describes this match, now that it is locked.
                                         *
                                         * Every caller asks only about a match it found not yet completed, so one
                                         * that is completed here was completed while the engine was answering --
                                         * by the results callback, or by a forfeit the engine knows nothing about.
                                         * An answer from before that may still say the match is running, and it
                                         * must not undo the ending. Likewise a numbered answer older than a move
                                         * already applied describes seats that move has since changed.
                                         *
                                         * Either way the answer's turns are recorded above, since they were really
                                         * taken, and nothing else of it is written. */
                                        applied <- matchRepo.moveSequenceForUpdate(gameId, matchId)
                                        // And a live match's turns are never written at all: they are the
                                        // engine's to run, nothing tells matchmaker when they change, and a
                                        // seat marked pending here would stay pending -- and due -- long after
                                        // the engine had moved on. Only its ending is news; see `liveEnded`.
                                        seatsCurrent = !current.completed && !current.live &&
                                            status.sequence.forall(seq => applied.forall(_ <= seq))
                                        // A live match the engine says is over, and matchmaker had not heard
                                        // was: its results callback went astray. Every seat is retired here,
                                        // as `recordResults` would have retired them, or the finished match
                                        // stays in everybody's active lists -- and no later refresh asks
                                        // again, since the match itself is now complete.
                                        liveEnded = current.live && status.completed && current.completedAt.isEmpty
                                        _ <- participants.filter(_ => liveEnded).traverse_ { (p, _, _) =>
                                            participantRepo
                                                .update(withTurn(p, pending = false, due = None, completed = true))
                                        }
                                        _ <- status.sequence.filter(_ => seatsCurrent).traverse_ { seq =>
                                            matchRepo.advanceMoveSequence(gameId, matchId, seq)
                                        }
                                        _ <- status.participants.filter(_ => seatsCurrent).traverse { reported =>
                                            byId.get(ParticipantId(reported.participantId)) match {
                                                case Some(p) =>
                                                    participantRepo.update(
                                                      withTurn(
                                                        p,
                                                        reported.pending,
                                                        dueFor(current, used)(p.participantId, reported.prevMoveAt),
                                                        reported.completed
                                                      )
                                                    )
                                                // The engine reporting a seat matchmaker does not have is the engine's
                                                // problem to explain, not a reason to abandon the seats it does have.
                                                case None => IO.unit
                                            }
                                        }
                                        // Set once by the database's clock and kept: a match that is already finished keeps
                                        // the time it finished, rather than being restamped by every later status the engine
                                        // answers with. Nothing else about the match changes here, so completion is the only
                                        // reason to write at all.
                                        //
                                        // Only ever towards completed. An answer saying a completed match is still running
                                        // is one from before it ended (see `seatsCurrent`), and reopening it would undo a
                                        // result or a forfeit that has already been recorded and mailed.
                                        completedAt <-
                                            if (status.completed && current.completedAt.isEmpty)
                                                matchRepo.complete(gameId, matchId).map(Some(_))
                                            else IO.pure(current.completedAt)
                                        updated = current.copy(completedAt = completedAt)
                                        // The first of those four cases, which is the one where this call is what ended
                                        // the match: the engine says it is over and matchmaker had not heard. It happens
                                        // when the results callback went astray and somebody pressed Refresh, and it is
                                        // the only way a player would ever learn their match had finished.
                                        endedHere = status.completed && current.completedAt.isEmpty
                                    } yield (updated, endedHere)
                        } yield outcome
                    }
                }
            }
            .flatMap { (updated, endedHere) =>
                /* Only on that transition, so a status call that merely confirms a finished match writes
                 * to nobody -- which is what stops every later Refresh from mailing the match's result
                 * round again. `recordResults` sends the same notification on the ordinary path, and the
                 * two cannot both fire: whichever of them completes the match leaves the other looking at
                 * a match that was already over. */
                val told = if (endedHere) notifications.matchEnded(session, updated, MatchEnding.Finished) else IO.unit
                // And its archive is owed: the engine is not told the match is over, it told matchmaker.
                told *> IO.whenA(endedHere)(endings.ended(gameId, matchId)).as(updated)
            }
    }

    /* Writes the turns an engine has just reported, oldest first.
     *
     * A turn costs the time between its own `takenAt` and the moment that player's clock started,
     * which is the move before it in the match. The engine may say so itself; when it does not,
     * the previous turn is taken from this batch, or — for the first turn of all — from the
     * match's start, which is when the first player's clock began.
     *
     * `since` is the latest turn already recorded, so it is the predecessor of the first turn in
     * the batch. Every insert is idempotent, which is what makes it safe for the ordinary case:
     * the move callback recorded this turn already, and this is the same turn arriving again. */
    private def recordTurns(
        session: skunk.Session[IO],
        m: Match,
        turns: List[EngineTurn],
        since: Option[Instant]
    ): IO[Unit] = {
        val turnRepo = new TurnRepo(session)
        turns
            .sortBy(_.takenAt)
            // A match reporting turns has begun, so it has a start; the first reported turn stands in for
            // one only so that this cannot fail on a row that somehow lacks it. With no turns it is unused.
            .foldLeft(
              IO.pure(since.orElse(m.start).orElse(turns.map(_.takenAt).minOption).getOrElse(Instant.EPOCH))
            ) { (previous, reported) =>
                previous.flatMap { prev =>
                    val startedAt = reported.startedAt.getOrElse(prev)
                    turnRepo
                        .create(
                          Turn(m.gameId, m.matchId, ParticipantId(reported.participantId), reported.takenAt, startedAt)
                        )
                        .as(reported.takenAt)
                }
            }
            .void
    }

    /* What each seat has spent on the turns it has finished — needed only by a total limit, and
     * not asked for otherwise: a per-turn deadline does not depend on the turns before it, and a
     * match with no limit at all has no deadline to compute. */
    private def timeUsedIn(session: skunk.Session[IO], m: Match): IO[Map[ParticipantId, java.time.Duration]] =
        if (m.timeLimit.isDefined && m.timeLimitKind == TimeLimitKind.Total)
            new TurnRepo(session).timeUsed(m.gameId, m.matchId)
        else IO.pure(Map.empty)

    /* The last step of a start: record the urls the engine gave back. The urls only: `withUrls` is
     * the match as it was written before the engine call, and the row may have changed since -- see
     * `MatchRepo.setUrls`. */
    private def finish(session: skunk.Session[IO], withUrls: Match, cancelUrl: Option[String]): IO[Unit] =
        new MatchRepo(session).setUrls(withUrls, cancelUrl)

    /* Retries a database action a few times before giving up. Used only for the work after the
     * engine call, where failing is not an option that leaves a sane state behind — everywhere
     * else a failure simply rolls its transaction back. */
    private def retrying[A](io: IO[A], attempts: Int = 3, delay: FiniteDuration = 100.millis): IO[A] =
        io.handleErrorWith { error =>
            if (attempts <= 1) IO.raiseError(error)
            else IO.sleep(delay) *> retrying(io, attempts - 1, delay * 2)
        }

    /** Step 2: a player has moved. `moved` is the participant who moved and is no longer pending; `next` are the
      * participants whose turn it now is, whose clock starts at `takenAt`.
      *
      * The engine decides turn order, so matchmaker takes what it is told rather than inferring it. A participant named
      * in neither list is left alone — a game where several players move at once reports only the seat that changed.
      *
      * As in [[refresh]], the engine reports when the move happened and matchmaker works out the deadline from the
      * match's `timeLimit` — the time limit came from the tournament, so it is not something the engine is in a
      * position to state.
      *
      * `startedAt` is when the mover's own clock started for this move, and it is the engine's to state for the same
      * reason turn order is: only the engine knows whether this player was waiting for the move before. See
      * `Json.MoveNotification` for why matchmaker no longer guesses at it.
      *
      * `state`, from an engine that numbers its moves, replaces `next`: it names every seat that is to move now, and
      * this move's number. An engine sends its callbacks after committing each move, from whichever request made it, so
      * two can arrive in the opposite order to the moves — and applied in arrival order the late one undoes the early
      * one, clearing a seat the later move had made pending again. So a move numbered no higher than the match's
      * `move_sequence` is older than what the seats already say: its turn is recorded, since the time was really spent,
      * and nothing else is touched or mailed. Without `state`, the callback is applied as it arrives, which is all
      * matchmaker could do before and is still right for an engine whose callbacks cannot overtake each other.
      */
    def recordMove(
        gameId: GameId,
        matchId: MatchId,
        moved: ParticipantId,
        next: List[ParticipantId],
        takenAt: Instant,
        startedAt: Instant,
        callerExternalId: String,
        state: Option[MoveState] = None
    ): IO[Unit] =
        sessionPool.use { session =>
            val gameRepo = new GameRepo[T](session)
            val matchRepo = new MatchRepo(session)
            val participantRepo = new ParticipantRepo(session)
            val turnRepo = new TurnRepo(session)
            session.transaction
                .use { _ =>
                    for {
                        // Reads the game unlocked; see `requireGame` for why a catalogue row is
                        // not locked on the way to writing `match` and `participant`.
                        _ <- authorizeGame(gameRepo, gameId, callerExternalId)
                        existing <- requireMatchForUpdate(matchRepo, gameId, matchId)
                        _ <- IO.raiseWhen(existing.completed)(
                          ValidationError(s"match ${matchId.value} is already completed")
                        )
                        // The engine has not been told the match was called off — there is no exchange that
                        // would tell it — so it will go on reporting moves made on a board matchmaker no
                        // longer recognises. Refusing them is what makes a cancel stick.
                        _ <- IO.raiseWhen(existing.cancelled)(
                          ConflictError(s"match ${matchId.value} was cancelled and is no longer accepting moves")
                        )
                        mover <- requireParticipant(participantRepo, gameId, matchId, moved)
                        // The move itself, recorded as a turn: when it was made and when the mover's clock
                        // started for it, both as the engine reported them.
                        _ <- turnRepo.create(Turn(gameId, matchId, moved, takenAt, startedAt))
                        // After the turn above is recorded, since under a total limit the next player's
                        // deadline is what is left of their budget — and the mover's turn has just spent some
                        // of theirs.
                        used <- timeUsedIn(session, existing)
                        // Read under the match's row lock, taken above, which every writer of this
                        // column holds: nothing can apply a later move between this read and the writes.
                        applied <- matchRepo.moveSequenceForUpdate(gameId, matchId)
                        // A live match's engine sends no move callbacks, so one arriving is an engine that
                        // does not know the match is live. Its turn is recorded, since the time was spent,
                        // and its seats are left as `applyEngineStatus` leaves them: never pending.
                        current = !existing.live && state.forall(st => applied.forall(_ < st.sequence))
                        _ <- (state, current) match {
                            case (_, false) => IO.unit
                            case (Some(st), true) =>
                                for {
                                    seats <- participantRepo.listForMatch(gameId, matchId)
                                    since = st.pending.map(c => c.participantId -> c.since).toMap
                                    _ <- seats.traverse { (p, _, _) =>
                                        val clock = since.get(p.participantId)
                                        participantRepo.update(
                                          withTurn(
                                            p,
                                            pending = clock.isDefined,
                                            due = clock.flatMap(at => dueFor(existing, used)(p.participantId, Some(at)))
                                          )
                                        )
                                    }
                                    _ <- matchRepo.advanceMoveSequence(gameId, matchId, st.sequence)
                                } yield ()
                            case (None, true) =>
                                for {
                                    _ <- participantRepo.update(withTurn(mover, pending = false, due = None))
                                    _ <- next.traverse { id =>
                                        requireParticipant(participantRepo, gameId, matchId, id)
                                            .flatMap(p =>
                                                participantRepo.update(
                                                  withTurn(
                                                    p,
                                                    pending = true,
                                                    due = dueFor(existing, used)(id, Some(takenAt))
                                                  )
                                                )
                                            )
                                    }
                                } yield ()
                        }
                    } yield (existing, current)
                }
                .flatMap { (played, current) =>
                    /* After the commit, because whose turn it is now and when it is due are what was just
                     * written -- and they are most of what the mail says. Outside the match's lock and
                     * unable to fail the callback, on the same terms as every other notification here:
                     * the engine has recorded the move and is owed a 204 whatever the queue is doing.
                     *
                     * Not for a move that arrived after a later one: the later one's mail has already said
                     * whose turn it is, and this one would say it again, wrongly. */
                    if (current) notifications.turnTaken(session, played, moved) else IO.unit
                }
        }

    /** Step 3: the match is over. Completes the match and every participant in it, and writes one result row per
      * reported participant.
      *
      * Idempotent in the only way that matters for a callback that may be retried: a match already completed is left
      * alone rather than double-writing its results.
      *
      * A cancelled match is refused rather than ignored. Its creator called it off, so a result arriving afterwards is
      * a real disagreement between the two systems — the engine let the game finish on a board matchmaker had stopped
      * following — and saying so is more useful than silently discarding it.
      *
      * `turns`, from an engine that sends them, is every turn of the match, recorded in the same transaction that
      * completes it: a turn whose move callback was lost lands with the results or not at all, and there is nothing to
      * retry. They are recorded on a repeated callback too, since each insert is idempotent. An engine that sends none
      * is asked for them once the results are committed — see `recoverTurns`, and its limits.
      */
    def recordResults(
        gameId: GameId,
        matchId: MatchId,
        results: List[ReportedResult],
        callerExternalId: String,
        turns: Option[List[EngineTurn]] = None,
        summary: Option[String] = None
    ): IO[Unit] =
        sessionPool.use { session =>
            val gameRepo = new GameRepo[T](session)
            val matchRepo = new MatchRepo(session)
            val participantRepo = new ParticipantRepo(session)
            val resultRepo = new ResultRepo(session)
            session.transaction
                .use { _ =>
                    for {
                        // Reads the game unlocked; see `requireGame` for why a catalogue row is
                        // not locked on the way to writing `match` and `participant`.
                        _ <- authorizeGame(gameRepo, gameId, callerExternalId)
                        existing <- requireMatchForUpdate(matchRepo, gameId, matchId)
                        _ <- IO.raiseWhen(existing.cancelled)(
                          ConflictError(s"match ${matchId.value} was cancelled and can have no result")
                        )
                        _ <- turns.traverse_ { reported =>
                            for {
                                seats <- participantRepo.listForMatch(gameId, matchId)
                                known = seats.map(_._1.participantId.value).toSet
                                strangers = reported.map(_.participantId).filterNot(known).distinct
                                _ <- IO.raiseUnless(strangers.isEmpty)(
                                  ValidationError(
                                    s"turn(s) by participant(s) ${strangers.mkString(", ")}, who are not in match ${matchId.value}"
                                  )
                                )
                                _ <- recordTurns(session, existing, reported, since = None)
                            } yield ()
                        }
                        /* Whether the results are already recorded, which is what makes a repeated
                         * callback a no-op. Not the same question as whether the match is completed: a
                         * match whose results callback went astray is completed by `applyEngineStatus`
                         * when somebody refreshes it, from a status that carries no ranks -- and the
                         * callback arriving after that is the first word on how it came out. It is
                         * recorded, and the match rated, as if it had arrived first; only the completion
                         * itself, and the news of it, are already done. Under the match's lock, so two
                         * deliveries of it cannot both find nothing recorded. */
                        recorded <-
                            if (existing.completed) resultRepo.existsForMatch(gameId, matchId) else IO.pure(false)
                        // Whether this call is what ended the match, rather than a late or repeated
                        // callback: only the first ending is news.
                        ended <-
                            if (recorded) IO.pure(false)
                            else
                                for {
                                    participants <- participantRepo.listForMatch(gameId, matchId)
                                    known = participants.map(_._1.participantId).toSet
                                    unknown = results.map(_.participantId).filterNot(known)
                                    _ <- IO.raiseUnless(unknown.isEmpty)(
                                      ValidationError(
                                        s"participant(s) ${unknown.map(_.value).mkString(", ")} are not in match ${matchId.value}"
                                      )
                                    )
                                    _ <- IO.unlessA(existing.completed)(
                                      participants.traverse_((participant, _, _) =>
                                          participantRepo.update(
                                            withTurn(participant, pending = false, due = None, completed = true)
                                          )
                                      )
                                    )
                                    // In this transaction, under the match's lock: the ratings move exactly
                                    // when the results are recorded, and a repeated callback, finding them
                                    // recorded, does not move them again. A friendly match does not move them.
                                    // Before the results, which carry what the match did to each rating.
                                    deltas <-
                                        if (existing.friendly) IO.pure(EloRatingService.EloDeltas.none)
                                        else
                                            EloRatingService.rate(
                                              session,
                                              gameId,
                                              matchId,
                                              results.map(r => r.participantId -> r.rank).toMap,
                                              // An engine's own forfeit: a live match it ended on a clock.
                                              forfeit = results.exists(_.forfeit)
                                            )
                                    _ <- results.traverse(r =>
                                        resultRepo.create(
                                          Result(
                                            gameId,
                                            r.participantId,
                                            r.rank,
                                            r.scores,
                                            r.isWinner,
                                            r.forfeit,
                                            deltas.overall.get(r.participantId),
                                            deltas.inRole.get(r.participantId)
                                          )
                                        )
                                    )
                                    // Once, under the lock — with the database's clock, not the lambda's. A
                                    // match `applyEngineStatus` completed keeps the time it did so.
                                    _ <- IO.unlessA(existing.completed)(matchRepo.complete(gameId, matchId).void)
                                    // Cleaned rather than refused: a summary that is unsafe or too long is the
                                    // engine's mistake, and the results it came with are still the results. What
                                    // is left of it is stored; nothing at all, and the table is shown instead.
                                    _ <- SummaryHtml
                                        .accept(summary)
                                        .traverse_(matchRepo.setResultSummary(gameId, matchId, _))
                                } yield !existing.completed
                    } yield (existing, ended)
                }
                .flatMap { (played, ended) =>
                    // Before the mail, so that anything it says about the match is said of every turn.
                    // Only for an engine that did not send its turns: one that did had them recorded above.
                    val reconciled = if (ended && turns.isEmpty) recoverTurns(session, played) else IO.unit
                    // Everyone in it, because nobody in it did this: the engine finished the game -- or,
                    // in a live match, ended it on a clock, which is told as a forfeit is.
                    val ending = if (results.exists(_.forfeit)) MatchEnding.Forfeited else MatchEnding.Finished
                    reconciled *> (if (ended) notifications.matchEnded(session, played, ending) else IO.unit) *>
                        /* Every time, and not only when this call ended the match: settling is idempotent, so
                         * a repeated callback saying it again costs nothing, and is the one way a match whose
                         * ending failed to be queued gets another chance at its archive. */
                        endings.ended(gameId, matchId)
                }
        }

    /* The last chance to record turns whose move callbacks never arrived, for an engine whose results
     * do not carry its turns. One that sends them has them recorded with the results, atomically, and
     * never comes here.
     *
     * A move callback is best-effort, and while a match is running a lost one is repaired by
     * `refresh`. Once the match is completed `refresh` no longer asks the engine anything, so a
     * move lost before the end would be lost for good -- and the one most exposed is the last: the
     * engine sends it and the results one after the other, and when only the results get through
     * the match completes without its final turn. The turns are what a player's time across the
     * match is summed from, so the result would be wrong without anything looking wrong.
     *
     * So the match's ending asks the engine once for every turn it has, and records them. Each
     * insert is idempotent, so the turns matchmaker already has are dropped by the database.
     *
     * After the results' transaction rather than inside it, per the rule on external calls: the
     * engine is asked with no transaction open, and the turns are written in a fresh one that takes
     * the match's lock again. Nothing here may fail the results, which are committed and are the
     * engine's due either way, so a failure is logged and dropped -- and is not retried: nothing in
     * matchmaker runs on a timer, and a completed match is not asked about again. That limit is why
     * an engine should send its turns with its results. */
    private def recoverTurns(session: skunk.Session[IO], played: Match): IO[Unit] =
        played.statusUrl
            .fold(IO.unit) { url =>
                new GameApiKeyRepo(session)
                    .forGame(played.gameId)
                    .flatMap(apiKey => engine.status(url, apiKey, None))
                    .flatMap { status =>
                        session.transaction.use { _ =>
                            for {
                                locked <- requireMatchForUpdate(new MatchRepo(session), played.gameId, played.matchId)
                                seats <- new ParticipantRepo(session).listForMatch(played.gameId, played.matchId)
                                known = seats.map(_._1.participantId.value).toSet
                                _ <- recordTurns(
                                  session,
                                  locked,
                                  status.turns.filter(t => known(t.participantId)),
                                  since = None
                                )
                            } yield ()
                        }
                    }
            }
            .handleErrorWith(error =>
                IO(System.err.println(s"could not recover the turns of match ${played.matchId.value}: $error"))
            )

    /** Step 4: re-check a running match with the engine, and apply whatever it says.
      *
      * Open to any participant in the match — it changes nothing a player could not already see, and it exists
      * precisely because a callback can go missing. A match with no `statusUrl` has not been created in the engine, and
      * there is nothing to ask.
      *
      * A cancelled match is returned as it stands, without asking the engine. The engine would answer — its game is
      * still there — and applying what it said would undo the cancel one turn at a time.
      */
    def refresh(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
        sessionPool.use { session =>
            val playerRepo = new PlayerRepo(session)
            val matchRepo = new MatchRepo(session)
            val participantRepo = new ParticipantRepo(session)

            for {
                prepared <- session.transaction.use { _ =>
                    for {
                        player <- requireCaller(playerRepo, callerExternalId)
                        existing <- requireBegunMatch(matchRepo, gameId, matchId)
                        participants <- participantRepo.listForMatch(gameId, matchId)
                        _ <- IO.raiseUnless(participants.exists(_._1.playerId == player.playerId))(
                          UnauthorizedError(s"caller '$callerExternalId' is not in match ${matchId.value}")
                        )
                        statusUrl <- existing.statusUrl match {
                            case Some(url) => IO.pure(url)
                            case None => IO.raiseError(ValidationError(s"match ${matchId.value} has no status url"))
                        }
                    } yield (existing, statusUrl)
                }
                (existing, statusUrl) = prepared

                // Already over: the engine has nothing left to tell us that matchmaker would act on, and
                // asking would be a network call per page view.
                result <-
                    if (existing.completed || existing.cancelled) IO.pure(existing)
                    else
                        // The engine's answer first, then the clock: a turn that looks overdue in
                        // matchmaker's copy may have been taken since, and the status call is what says so.
                        applyEngineStatus(session, gameId, matchId, statusUrl)
                            .flatMap(enforceTimeouts(session, gameId, matchId, _, recheck = false))
            } yield result
        }

    /** The match itself, for a player in it — which is how the UI gets the `playUrl` to send them to the game.
      */
    def read(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
        sessionPool
            .use { session =>
                val playerRepo = new PlayerRepo(session)
                val matchRepo = new MatchRepo(session)
                val participantRepo = new ParticipantRepo(session)
                for {
                    player <- requireCaller(playerRepo, callerExternalId)
                    existing <- requireBegunMatch(matchRepo, gameId, matchId)
                    participants <- participantRepo.listForMatch(gameId, matchId)
                    _ <- IO.raiseUnless(participants.exists(_._1.playerId == player.playerId))(
                      UnauthorizedError(s"caller '$callerExternalId' is not in match ${matchId.value}")
                    )
                    // This is the call the UI makes when a player goes to take their turn — it is where the
                    // `playUrl` comes from — so it is one of the two moments a run-out clock is noticed. A
                    // player must not be handed a board to play on in a match that has already been
                    // forfeited, and the player whose clock ran out must not be able to outrun it by
                    // clicking Play.
                    result <- enforceTimeouts(session, gameId, matchId, existing)
                } yield result
            }
            // This is also how a player reaches a finished match's board -- "Review game" -- so an
            // archived one's urls are marked as such, and an expired one's are not handed out at all.
            // After the session is given back: the check may ask S3.
            .flatMap(m => archives.fold(IO.pure(m))(_.settle(m)))
            .map(ArchiveService.forViewer)

    /** Applies the game's timeout action to any participant whose turn has run out.
      *
      * The two moments a clock is looked at are [[read]] — the call a player makes on their way to take a turn — and
      * [[refresh]], the one they make by pressing Refresh. There is no timer and no sweeper: a deadline that nobody is
      * waiting on has no effect anyone can see, and the first person to look is by definition someone it matters to.
      *
      * The engine is asked first, because matchmaker's copy of whose turn it is arrives by callback and a callback can
      * be lost. A turn that looks overdue here may have been taken minutes ago, and ending a match on that would be
      * ending it on a message that went astray. `recheck` is false only for [[refresh]], which has just asked.
      *
      * If the engine cannot be reached, nothing is enforced and the match is returned as it stands. A player is then
      * left able to play a turn they may have run out of, which is the lesser of the two errors: the other one ends
      * somebody's match on evidence matchmaker could not confirm.
      *
      * A match with no time limit has no deadline to miss, and one already over has nothing left to decide — both are
      * returned untouched without a query. Nor does a live match: its clock is the engine's, which ends the match
      * itself and reports the forfeit as its result.
      */
    private def enforceTimeouts(
        session: skunk.Session[IO],
        gameId: GameId,
        matchId: MatchId,
        current: Match,
        recheck: Boolean = true
    ): IO[Match] =
        if (current.completed || current.cancelled || current.timeLimit.isEmpty || current.live) IO.pure(current)
        else
            overdueIn(session, gameId, matchId).flatMap {
                case Nil => IO.pure(current)
                case _   =>
                    // `None` means the deadline could not be confirmed with the engine, which is not the
                    // same as confirming it: nothing is enforced on a match whose state matchmaker was
                    // unable to check.
                    val verified: IO[Option[Match]] =
                        if (!recheck) IO.pure(Some(current))
                        else
                            current.statusUrl match {
                                case Some(url) =>
                                    applyEngineStatus(session, gameId, matchId, url).attempt.map(_.toOption)
                                // Never created in the engine, so there is nothing to ask and no way to know
                                // whether the turn was taken.
                                case None => IO.pure(None)
                            }

                    verified.flatMap {
                        case None => IO.pure(current)
                        case Some(checked) =>
                            if (checked.completed || checked.cancelled) IO.pure(checked)
                            else
                                overdueIn(session, gameId, matchId).flatMap {
                                    // Taken since: the status call moved the turn on, and there is nobody left to
                                    // act against.
                                    case Nil => IO.pure(checked)
                                    case _   =>
                                        // The timeout action, read unlocked -- see `requireGame`.
                                        // What is written from it is the match and its seats, and an
                                        // admin changing it while a clock runs out is not a race.
                                        requireGame(new GameRepo[T](session), gameId).flatMap { game =>
                                            game.timeoutAction match {
                                                case TimeoutAction.Forfeit => forfeit(session, gameId, matchId)
                                            }
                                        }
                                }
                    }
            }

    /* The participants of a match whose turn it is and whose deadline has passed.
     *
     * Asked of the database, which compares `due` against its own now(). Whether a turn has run
     * out is not the lambda's to decide: its clock is not the one the deadline was written by nor
     * the one the completion will be stamped with, and two instances need not agree with each
     * other. One clock decides, and it is the same clock throughout. */
    private def overdueIn(session: skunk.Session[IO], gameId: GameId, matchId: MatchId): IO[List[Participant]] =
        new ParticipantRepo(session).listOverdueForMatch(gameId, matchId)

    /* Ends a match because somebody's clock ran out: the players who ran out lose, and everybody
     * else wins by forfeit.
     *
     * The result rows are matchmaker's own, not the engine's — the engine has not reported a
     * result and, having no notion of matchmaker's time limit, is not going to. They carry
     * `forfeit`, which is how a completed-match list can say "won by forfeit" rather than merely
     * naming a winner, and no scores: nothing was scored.
     *
     * Everything it acts on is read under the match's row lock, including *who* is out of time:
     * the check `enforceTimeouts` made to get here was made outside the lock, and a move callback
     * committing in between would leave it naming a player who has since moved. `recordMove` and
     * `recordResults` take the same lock, so re-reading under it is what makes the two orders —
     * move-then-forfeit and forfeit-then-move — resolve to one of them rather than to both.
     *
     * Finding nobody overdue at that point is therefore an ordinary outcome, not an error: the
     * move landed first, and the match carries on. A match where every seat is overdue at once
     * ends with no winner rather than with an arbitrary one. */
    private def forfeit(session: skunk.Session[IO], gameId: GameId, matchId: MatchId): IO[Match] = {
        val matchRepo = new MatchRepo(session)
        val participantRepo = new ParticipantRepo(session)
        val resultRepo = new ResultRepo(session)

        session.transaction
            .use { _ =>
                for {
                    locked <- requireMatchForUpdate(matchRepo, gameId, matchId)
                    // Read under the lock, so it reflects every move that committed before it was taken.
                    overdue <- overdueIn(session, gameId, matchId).map(_.map(_.participantId).toSet)
                    // Paired with whether this call is what ended the match, which is not the same
                    // question as whether it is over: finding nobody overdue under the lock is an
                    // ordinary outcome (the move landed first), and a match that was already finished
                    // was not finished here. Only the transition is news.
                    outcome <-
                        if (locked.completed || locked.cancelled || overdue.isEmpty) IO.pure((locked, false))
                        else
                            for {
                                participants <- participantRepo.listForMatch(gameId, matchId)
                                _ <- participants.traverse((p, _, _) =>
                                    participantRepo.update(withTurn(p, pending = false, due = None, completed = true))
                                )
                                ranks = participants
                                    .map((p, _, _) => p.participantId -> (if (overdue(p.participantId)) 2 else 1))
                                    .toMap
                                // A forfeit is a result like any other to a rating: the player who ran out
                                // of time lost. Rated as the engine's results are, in this transaction, and
                                // before the results that carry what it did to each rating.
                                deltas <-
                                    if (locked.friendly) IO.pure(EloRatingService.EloDeltas.none)
                                    else EloRatingService.rate(session, gameId, matchId, ranks, forfeit = true)
                                _ <- participants.traverse { (p, _, _) =>
                                    val lost = overdue.contains(p.participantId)
                                    resultRepo.create(
                                      Result(
                                        gameId = gameId,
                                        participantId = p.participantId,
                                        rank = ranks(p.participantId),
                                        scores = Map.empty,
                                        isWinner = !lost,
                                        forfeit = true,
                                        eloDelta = deltas.overall.get(p.participantId),
                                        eloRoleDelta = deltas.inRole.get(p.participantId)
                                      )
                                    )
                                }
                                completedAt <- matchRepo.complete(gameId, matchId)
                            } yield (locked.copy(completedAt = Some(completedAt)), true)
                } yield outcome
            }
            .flatMap { (updated, endedHere) =>
                /* Everyone in the match, including the player whose clock ran out: they are the one person
                 * who most needs telling, and unlike every other event here nobody pressed anything to
                 * cause it. After the commit and unable to fail the enforcement, as ever. */
                val told =
                    if (endedHere) notifications.matchEnded(session, updated, MatchEnding.Forfeited) else IO.unit
                told *> IO.whenA(endedHere)(endings.ended(gameId, matchId)).as(updated)
            }
    }

    /** When a turn that began at `prevMoveAt` — the moment the move before it was made — is due.
      *
      * The engine says when a participant's turn began; how long they get is the match's business, since the time limit
      * came from the tournament rather than from the engine. A match with no time limit has no deadline at all — there
      * is nothing to run out.
      *
      * Under a per-turn limit that is the whole calculation: every turn gets the limit, and what happened before it
      * does not matter. Under a total limit the player is spending one budget across the match, so what is left of it
      * is the limit minus what `used` says they have already spent — which is why the deadline is still a single
      * instant on the participant row, and why everything downstream (the overdue query, the forfeit, the database's
      * clock deciding both) is untouched by the difference.
      *
      * A player with nothing left is due at the moment their turn began, i.e. already overdue. Their clock ran out
      * while it was somebody else's move, and they are out of time whenever anybody next looks.
      */
    private def dueFor(m: Match, used: Map[ParticipantId, java.time.Duration])(
        participantId: ParticipantId,
        prevMoveAt: Option[Instant]
    ): Option[Instant] =
        for {
            at <- prevMoveAt
            limit <- m.timeLimit
        } yield m.timeLimitKind match {
            case TimeLimitKind.PerTurn => at.plus(limit)
            case TimeLimitKind.Total =>
                val remaining = limit.minus(used.getOrElse(participantId, java.time.Duration.ZERO))
                if (remaining.isNegative) at else at.plus(remaining)
        }

    private def withTurn(
        p: Participant,
        pending: Boolean,
        due: Option[Instant],
        completed: Boolean = false
    ): Participant =
        p match {
            case cp: CharacterParticipant =>
                cp.copy(pending = pending, due = due, completed = completed || cp.completed)
            case pp: PlainParticipant => pp.copy(pending = pending, due = due, completed = completed || pp.completed)
        }

    private def authorizeGame(gameRepo: GameRepo[T], gameId: GameId, callerExternalId: String): IO[Game] =
        requireGame(gameRepo, gameId).flatTap { game =>
            IO.raiseUnless(callerExternalId == game.externalId)(
              UnauthorizedError(s"invalid game externalId for game ${gameId.value}")
            )
        }

    /* The game, read plainly even inside a transaction that writes -- the reference-table exception
     * in CLAUDE.md. The catalogue is small and changes only when an admin edits it, while what is
     * written from it here is `match`, `participant` and `result`; taking the game's row lock on
     * every call would queue a whole game's traffic behind one row for a race nobody runs.
     *
     * The exception is about that asymmetry, not about being a read that does not matter: a caller
     * reading a game in order to rewrite *it* locks it (`GameService.createOrUpdate`). Where an
     * existence check has to outlive the insert that relies on it, `GameRepo.lockForShare` is the
     * middle course -- see `CharacterService.create`. */
    private def requireGame(gameRepo: GameRepo[T], gameId: GameId): IO[Game] =
        gameRepo.read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }

    private def requireCaller(playerRepo: PlayerRepo, callerExternalId: String): IO[Player] =
        playerRepo.readByExternalId(callerExternalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }

    private def requireMatch(repo: MatchRepo, gameId: GameId, matchId: MatchId): IO[Match] =
        repo.read(gameId, matchId).flatMap {
            case Some(m) => IO.pure(m)
            case None    => IO.raiseError(NotFoundError(s"no match '${matchId.value}' in game ${gameId.value}"))
        }

    /* A match a player may open: one that has begun. A tournament's match exists before that,
     * scheduled or with its seats still choosing roles, and is answered as no match at all -- there is
     * no board to send anyone to, and no listing names it. */
    private def requireBegunMatch(repo: MatchRepo, gameId: GameId, matchId: MatchId): IO[Match] =
        requireMatch(repo, gameId, matchId).flatMap { m =>
            if (m.status.begun) IO.pure(m)
            else IO.raiseError(NotFoundError(s"match '${matchId.value}' in game ${gameId.value} has not begun"))
        }

    private def requireMatchForUpdate(repo: MatchRepo, gameId: GameId, matchId: MatchId): IO[Match] =
        repo.readForUpdate(gameId, matchId).flatMap {
            case Some(m) => IO.pure(m)
            case None    => IO.raiseError(NotFoundError(s"no match '${matchId.value}' in game ${gameId.value}"))
        }

    private def requireParticipant(
        repo: ParticipantRepo,
        gameId: GameId,
        matchId: MatchId,
        participantId: ParticipantId
    ): IO[Participant] =
        repo.read(gameId, participantId).flatMap {
            case Some(p) if p.matchId == matchId => IO.pure(p)
            case _ => IO.raiseError(NotFoundError(s"no participant ${participantId.value} in match '${matchId.value}'"))
        }
}
