package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.{Duration, Instant}
import java.util.UUID
import skunk.Session
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.model.tournament.{
    Clock,
    Env,
    MatchClock,
    MultipleEntries,
    Placing,
    RoundMatch,
    Tournament as Progress,
    TournamentDesign,
    TournamentGame,
    TournamentState
}
import com.vivi.matchmaker.persistence._

/** A tournament's rounds (new-design.md, Phase 5): beginning each, its seats choosing their roles, its matches being
  * created and played, and the tournament advancing past what they finish.
  *
  *   - A round is begun by its owner, or by its last match ending in a tournament with automatic rounds. Beginning it
  *     works out its matches from the tournament's state and writes them, every seat with its role or its turn to
  *     choose one.
  *   - A match whose roles are settled is due, and is created from the ending queue
  *     (`MatchEndings.tournamentMatchDue`), its engine's game made by [[GameEngineService.launchTournamentMatch]] with
  *     no transaction open across the call.
  *   - A match's end reaches the tournament through `EndingService.settle`, which every ending passes through, and is
  *     recorded in the state. The last match of a round completes it, and the tournament advances: to the next round at
  *     once with automatic rounds, and otherwise to waiting on its owner, who is mailed to say so.
  *
  * Every change is one transaction that locks the tournament first and then whichever of its matches it touches, in
  * that order, so that two of them cannot deadlock. The game is read plainly, as a reference table.
  */
class TournamentRounds[T](
    sessionPool: SessionPool,
    engine: GameEngineService[T],
    endings: MatchEndings,
    notifications: Notifications = Notifications.disabled,
    now: () => Instant = () => Instant.now()
)(using codec: TextCodec[T]) {

    /** How long after one Check round another is refused, so that a burst of presses is one check. */
    val checkInterval: Duration = Duration.ofMinutes(1)

    // ---- beginning a round -----------------------------------------------------------------

    /** The owner beginning the next round. Its matches whose roles are settled are queued to be created at once; the
      * rest wait on their seats' choices. The number of the round begun, or none if that finished the tournament.
      */
    def startRound(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Option[Int]] =
        sessionPool.use { session =>
            for {
                begun <- session.transaction.use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId)(
                          UnauthorizedError("only the tournament's owner may start a round")
                        )
                        _ <- IO.raiseUnless(t.status == TournamentStatus.Running)(
                          ConflictError(s"this tournament is not under way; it is ${t.status.label.toLowerCase}")
                        )
                        begun <- begin(session, t)
                    } yield begun
                }
                (round, due) = begun
                _ <- due.traverse_((pool, no) => endings.tournamentMatchDue(gameId, tournamentId, pool, no))
            } yield round
        }

    /** The next round of a tournament with automatic rounds, begun because its last finished — or its first, when it
      * starts. Nothing, if its owner has turned automatic rounds off, or the round being played is not over.
      */
    def autoStart(gameId: GameId, tournamentId: TournamentId): IO[Settlement] =
        sessionPool.use { session =>
            for {
                due <- session.transaction.use { _ =>
                    new TournamentRepo(session).readForUpdate(gameId, tournamentId).flatMap {
                        case Some(t) if t.status == TournamentStatus.Running && t.settings.autoRounds =>
                            ready(session, t).flatMap(ok => if (ok) begin(session, t).map(_._2) else IO.pure(Nil))
                        case _ => IO.pure(Nil)
                    }
                }
                _ <- due.traverse_((pool, no) => endings.tournamentMatchDue(gameId, tournamentId, pool, no))
            } yield Settlement.Settled
        }

    /** Whether the round being played is over: every match of it recorded. */
    private def ready(session: Session[IO], t: Tournament): IO[Boolean] =
        (
          new TournamentRepo(session).readState(t.gameId, t.tournamentId),
          new TournamentMatchRepo(session).listUnfinished(t.gameId, t.tournamentId)
        ).mapN((state, unfinished) => state.exists(Progress.roundComplete) && unfinished.isEmpty)

    /** Begins the next round, under the tournament's lock, which the caller holds: advances the state past the last,
      * writes the new round's matches, and answers with the round's number and the matches due to be created now. A
      * tournament that advancing finishes is marked finished, and has no round.
      */
    private def begin(session: Session[IO], t: Tournament): IO[(Option[Int], List[(Int, Int)])] =
        for {
            game <- requireGame(session, t.gameId)
            state <- requireState(session, t)
            unfinished <- new TournamentMatchRepo(session).listUnfinished(t.gameId, t.tournamentId)
            _ <- IO.raiseUnless(Progress.roundComplete(state) && unfinished.isEmpty)(
              ConflictError("the round being played is not finished")
            )
            env <- envFor(session, t, game)
            (next, matches) = Progress.nextRound(state, env)
            result <-
                if (next.finished) finish(session, t, next).as((None, Nil))
                else
                    for {
                        players <- new EntrantRepo(session).players(t.gameId, t.tournamentId)
                        at = now()
                        rows = matches.map(m => seated(t, game, next, players, m, at))
                        _ <- new TournamentMatchRepo(session).insert(rows)
                        // Every match of a round lasts the tournament's round duration (V52).
                        longest = Option.when(matches.nonEmpty)(()).flatMap(_ => t.settings.roundDuration)
                        _ <- new TournamentRepo(session).update(
                          t.copy(roundStartedAt = Some(at), roundDeadline = longest.map(at.plus))
                        )
                        _ <- new TournamentRepo(session).writeState(t.gameId, t.tournamentId, next)
                    } yield (
                      Some(next.round),
                      rows.filter(_.status == TournamentMatchStatus.Due).map(r => (r.poolId, r.matchNo))
                    )
        } yield result

    /** A round's match as it is written: each seat with its role or its place in the order of choosing, and the seats
      * that forfeit before play — an entrant who has withdrawn, a second entry of a player already seated, or a
      * character with nobody to play it.
      */
    private def seated(
        t: Tournament,
        game: Game,
        state: TournamentState,
        players: Map[EntrantId, Option[PlayerId]],
        m: RoundMatch,
        at: Instant
    ): TournamentMatch = {
        val seedOf: Map[Long, Int] =
            Progress
                .allPools(state.root)
                .find(_.id == m.poolId)
                .toList
                .flatMap(_.members.map(p => p.entrant -> p.seedIn))
                .toMap
        val owners = m.planned.seats.map(s => (s.entrant, players.get(EntrantId(s.entrant)).flatten))
        val doubled = MultipleEntries.forfeits(owners.collect { case (e, Some(p)) =>
            (e, p.value, seedOf.getOrElse(e, Int.MaxValue))
        })
        def forfeits(e: Long) = state.withdrawn(e) || doubled(e) || owners.exists((x, p) => x == e && p.isEmpty)
        val seats = m.planned.seats.map { s =>
            TournamentSeat(
              EntrantId(s.entrant),
              s.role.map(GameRoleId(_)),
              if (forfeits(s.entrant)) None else s.chooseOrder,
              forfeit = forfeits(s.entrant)
            )
        }
        val settled = TournamentRounds.settleChoices(seats, allowedRoles(game, seats.size), at)
        TournamentMatch(
          t.gameId,
          t.tournamentId,
          m.poolId,
          m.planned.no,
          state.round,
          TournamentRounds.statusOf(settled, game.requiredSeats),
          m.tiesAllowed,
          None,
          settled
        )
    }

    // ---- choosing roles --------------------------------------------------------------------

    /** The seat whose turn it is choosing its role. The last seat left to choose is given the last role left; a match
      * whose roles are then all settled is due, and queued to be created.
      */
    def chooseRole(
        gameId: GameId,
        tournamentId: TournamentId,
        poolId: Int,
        matchNo: Int,
        role: GameRoleId,
        callerExternalId: String
    ): IO[TournamentMatchView] =
        sessionPool.use { session =>
            for {
                chosen <- session.transaction.use { _ =>
                    val matches = new TournamentMatchRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        tm <- requireMatchForUpdate(session, t, poolId, matchNo)
                        _ <- IO.raiseUnless(tm.status == TournamentMatchStatus.Choosing)(
                          ConflictError("this match is not choosing roles")
                        )
                        game <- requireGame(session, gameId)
                        players <- new EntrantRepo(session).players(gameId, tournamentId)
                        turn = TournamentRounds.nextChooser(tm.seats)
                        seat <- turn match {
                            case Some(s) if players.get(s.entrantId).flatten.contains(caller.playerId) => IO.pure(s)
                            case _ =>
                                IO.raiseError(UnauthorizedError("it is not your turn to choose a role in this match"))
                        }
                        allowed = allowedRoles(game, tm.seats.size)
                        taken = tm.seats.flatMap(_.gameRoleId).toSet
                        _ <- IO.raiseUnless(allowed.contains(role) && !taken(role))(
                          ValidationError("that role is not one left to choose in this match")
                        )
                        at = now()
                        picked = tm.seats.map(s =>
                            if (s.entrantId == seat.entrantId) s.copy(gameRoleId = Some(role), chosenAt = Some(at))
                            else s
                        )
                        settled = TournamentRounds.settleChoices(picked, allowed, at)
                        _ <- settled.traverse_(matches.updateSeat(gameId, tournamentId, poolId, matchNo, _))
                        status = TournamentRounds.statusOf(settled, game.requiredSeats)
                        _ <- matches.setStatus(gameId, tournamentId, poolId, matchNo, status, None)
                    } yield tm.copy(seats = settled, status = status)
                }
                _ <- IO.whenA(chosen.status == TournamentMatchStatus.Due)(
                  endings.tournamentMatchDue(gameId, tournamentId, poolId, matchNo)
                )
            } yield TournamentService.view(chosen)
        }

    /** The roles a match of `seats` seats hands out: the game's required roles, then its recommended ones, as many as
      * there are seats.
      */
    private def allowedRoles(game: Game, seats: Int): List[GameRoleId] =
        TournamentGame.of(game).seatingRoles.take(seats).map(r => GameRoleId(r.id))

    /** Every seat whose turn it is to choose a role, across the caller's tournaments, with when their time runs out. */
    def choicesFor(callerExternalId: String): IO[List[TournamentChoice]] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                waiting <- new TournamentMatchRepo(session).choosingFor(caller.playerId)
                choices <- waiting.traverse { (gameId, tournamentId, poolId, matchNo) =>
                    for {
                        t <- new TournamentRepo(session).read(gameId, tournamentId)
                        tm <- new TournamentMatchRepo(session).read(gameId, tournamentId, poolId, matchNo)
                        game <- new GameRepo[T](session).read(gameId)
                        state <- new TournamentRepo(session).readState(gameId, tournamentId)
                    } yield for {
                        t <- t
                        tm <- tm
                        game <- game
                    } yield TournamentChoice(
                      gameId,
                      tournamentId,
                      t.name,
                      game.displayName,
                      poolId,
                      matchNo,
                      state.flatMap(s => TournamentRounds.chooseBy(t, game, s, tm))
                    )
                }
            } yield choices.flatten
        }

    // ---- creating a match ------------------------------------------------------------------

    /** Creates a due match: writes it and its seats under its claim, then makes its engine's game with no transaction
      * open. A match whose seats are too few to play once the forfeits are taken out is decided there and then. Owed,
      * and so tried again, if the engine could not be reached; anything not due is settled already.
      */
    def createMatch(gameId: GameId, tournamentId: TournamentId, poolId: Int, matchNo: Int): IO[Settlement] =
        sessionPool.use { session =>
            for {
                prepared <- session.transaction.use(_ => prepare(session, gameId, tournamentId, poolId, matchNo))
                settled <- prepared match {
                    case Prepared.Nothing               => IO.pure(Settlement.Settled)
                    case Prepared.Decided(t, roundDone) => afterRecording(t, roundDone).as(Settlement.Settled)
                    case Prepared.Launch(saved, game, players, tiesAllowed) =>
                        engine
                            .launchTournamentMatch(
                              session,
                              saved,
                              game,
                              players,
                              tiesAllowed,
                              undo(session, gameId, tournamentId, poolId, matchNo, saved.matchId)
                            )
                            .attempt
                            .map {
                                case Right(_) => Settlement.Settled
                                case Left(e)  => Settlement.Owed(s"its engine could not create it: $e")
                            }
                }
            } yield settled
        }

    private enum Prepared {
        case Nothing
        case Decided(t: Tournament, roundDone: Option[Int])
        case Launch(
            saved: Match,
            game: Game,
            players: List[com.vivi.matchmaker.engine.EnginePlayer],
            tiesAllowed: Boolean
        )
    }

    private def prepare(
        session: Session[IO],
        gameId: GameId,
        tournamentId: TournamentId,
        poolId: Int,
        matchNo: Int
    ): IO[Prepared] =
        new TournamentRepo(session).readForUpdate(gameId, tournamentId).flatMap {
            case None => IO.pure(Prepared.Nothing)
            case Some(t) =>
                new TournamentMatchRepo(session).readForUpdate(gameId, tournamentId, poolId, matchNo).flatMap {
                    case Some(tm) if tm.status == TournamentMatchStatus.Due => seat(session, t, tm)
                    case _                                                  => IO.pure(Prepared.Nothing)
                }
        }

    private def seat(session: Session[IO], t: Tournament, tm: TournamentMatch): IO[Prepared] =
        for {
            game <- requireGame(session, t.gameId)
            state <- requireState(session, t)
            entries <- new EntrantRepo(session).list(t.gameId, t.tournamentId)
            players <- new EntrantRepo(session).players(t.gameId, t.tournamentId)
            active = tm.seats.filter(s =>
                !s.forfeit && s.gameRoleId.isDefined && players.get(s.entrantId).flatten.isDefined
            )
            prepared <-
                if (active.sizeIs < math.max(game.requiredSeats, 2) || active.sizeIs < tm.seats.count(!_.forfeit)) {
                    // Too few left to play: those still seated share first place, and everyone else is last.
                    val placings = tm.seats.map(s =>
                        Placing(
                          s.entrantId.value,
                          Some(if (active.exists(_.entrantId == s.entrantId)) 1 else active.size + 1)
                        )
                    )
                    record(session, t, game, tm.poolId, tm.matchNo, placings).map(done => Prepared.Decided(t, done))
                } else launch(session, t, game, state, entries, players, tm, active)
        } yield prepared

    private def launch(
        session: Session[IO],
        t: Tournament,
        game: Game,
        state: TournamentState,
        entries: List[Entrant],
        players: Map[EntrantId, Option[PlayerId]],
        tm: TournamentMatch,
        active: List[TournamentSeat]
    ): IO[Prepared] = {
        val matchId = MatchId(UUID.randomUUID().toString)
        val clock = TournamentRounds.clockOf(t, game, state, tm)
        val saved = Match(
          gameId = t.gameId,
          matchId = matchId,
          challengeId = None,
          // The tournament's owner as it is now: whose match it is, for the lists and the cancel.
          creator = t.owner,
          description = s"${t.name}, round ${tm.round}",
          completedAt = None,
          start = Some(now()),
          timeLimit = clock.map(_.limit),
          settings = "{}",
          timeLimitKind = clock.map(_.kind).getOrElse(TimeLimitKind.PerTurn),
          timeLimitUnit = TournamentRounds.unitFor(clock.map(_.limit)),
          friendly = t.settings.friendly
        )
        val characterOf = entries.map(e => e.entrantId -> e.characterId).toMap
        val roleName = game.roles.map(r => r.gameRoleId -> r.name).toMap
        for {
            _ <- new MatchRepo(session).createForTournament(saved, t.tournamentId)
            seatsOf = active.map(s => (players(s.entrantId).get, s.gameRoleId.get))
            starting <- EloRatingService.startingRatings(session, t.gameId, seatsOf, byRole = !game.unimportantRoles)
            made <- active.traverse { s =>
                val player = players(s.entrantId).get
                val role = s.gameRoleId.get
                val participant = characterOf.get(s.entrantId).flatten match {
                    case Some(c) =>
                        CharacterParticipant(ParticipantId(0), t.gameId, matchId, player, false, false, None, c, role)
                    case None => PlainParticipant(ParticipantId(0), t.gameId, matchId, player, false, false, None, role)
                }
                new ParticipantRepo(session)
                    .create(participant, starting.overall(player), starting.inRole(player, role))
                    .map(p => (s, p))
            }
            // What choosing cost each seat is a turn of theirs, charged to their clock like any other.
            _ <- made.traverse_ { (s, p) =>
                (s.chooseStartedAt, s.chosenAt).tupled.traverse_((from, to) =>
                    new TurnRepo(session).create(Turn(t.gameId, matchId, p.participantId, to, from))
                )
            }
            _ <- made.traverse_((s, p) =>
                new TournamentMatchRepo(session)
                    .updateSeat(
                      t.gameId,
                      t.tournamentId,
                      tm.poolId,
                      tm.matchNo,
                      s.copy(participantId = Some(p.participantId))
                    )
            )
            _ <- new TournamentMatchRepo(session)
                .setStatus(
                  t.gameId,
                  t.tournamentId,
                  tm.poolId,
                  tm.matchNo,
                  TournamentMatchStatus.Started,
                  Some(matchId)
                )
            enginePlayers <- made.traverse { (s, p) =>
                new PlayerRepo(session).read(p.playerId).flatMap {
                    case Some(player) =>
                        engine.tournamentPlayer(
                          session,
                          p,
                          player,
                          roleName(s.gameRoleId.get),
                          characterOf.get(s.entrantId).flatten
                        )
                    case None => IO.raiseError(NotFoundError(s"no player ${p.playerId}"))
                }
            }
        } yield Prepared.Launch(saved, game, enginePlayers, tm.tiesAllowed)
    }

    /** Takes back a match whose engine would not create it, so that it is due again. Swallowed, as a challenge's undo
      * is: the engine's failure is the news.
      */
    private def undo(
        session: Session[IO],
        gameId: GameId,
        tournamentId: TournamentId,
        poolId: Int,
        matchNo: Int,
        matchId: MatchId
    ): IO[Unit] =
        session.transaction
            .use { _ =>
                val matches = new TournamentMatchRepo(session)
                for {
                    tm <- matches.readForUpdate(gameId, tournamentId, poolId, matchNo)
                    _ <- tm.toList
                        .flatMap(_.seats)
                        .traverse_(s =>
                            matches.updateSeat(gameId, tournamentId, poolId, matchNo, s.copy(participantId = None))
                        )
                    _ <- matches.setStatus(gameId, tournamentId, poolId, matchNo, TournamentMatchStatus.Due, None)
                    _ <- new TurnRepo(session).deleteForMatch(gameId, matchId)
                    _ <- new ParticipantRepo(session).deleteForMatch(gameId, matchId)
                    _ <- new MatchRepo(session).delete(gameId, matchId)
                } yield ()
            }
            .handleError(_ => ())

    // ---- a match's end ---------------------------------------------------------------------

    /** A tournament match's end, recorded in its tournament: each seat's rank and score, from the match's results, with
      * the seats that forfeited before play last. A cancelled match the owner did not rank leaves every seat unranked.
      * Nothing, for a match that is not a tournament's, one not yet over, or one already recorded.
      */
    def recordEnding(gameId: GameId, matchId: MatchId): IO[Unit] =
        sessionPool.use { session =>
            new TournamentMatchRepo(session).findByMatch(gameId, matchId).flatMap {
                case None => IO.unit
                case Some((tournamentId, poolId, matchNo)) =>
                    session.transaction
                        .use { _ =>
                            for {
                                t <- requireTournamentForUpdate(session, gameId, tournamentId)
                                tm <- requireMatchForUpdate(session, t, poolId, matchNo)
                                m <- new MatchRepo(session).read(gameId, matchId)
                                done <-
                                    if (
                                      tm.status == TournamentMatchStatus.Done || !m
                                          .exists(x => x.completed || x.cancelled)
                                    )
                                        IO.pure(None)
                                    else
                                        for {
                                            game <- requireGame(session, gameId)
                                            results <- tm.seats
                                                .flatMap(_.participantId)
                                                .traverse(p => new ResultRepo(session).read(gameId, p).map(p -> _))
                                            byParticipant = results.toMap
                                            worst = results.flatMap(_._2.map(_.rank)).maxOption.getOrElse(tm.seats.size)
                                            placings = tm.seats.map { s =>
                                                s.participantId.flatMap(byParticipant.get).flatten match {
                                                    case Some(r) =>
                                                        Placing(
                                                          s.entrantId.value,
                                                          Some(r.rank),
                                                          TournamentRounds.scoreOf(r)
                                                        )
                                                    case None if s.forfeit =>
                                                        Placing(s.entrantId.value, Some(worst + 1))
                                                    case None => Placing(s.entrantId.value, None)
                                                }
                                            }
                                            done <- record(session, t, game, poolId, matchNo, placings)
                                        } yield done.map(t -> _)
                            } yield done
                        }
                        .flatMap {
                            case Some((t, round)) => afterRecording(t, Some(round))
                            case None             => IO.unit
                        }
            }
        }

    /** Records a match's placings in the state and marks it done, under the tournament's lock. Completes the round if
      * it was the last, advancing the tournament, and finishes the tournament if that ends it. The round's number, if
      * this completed it and the tournament goes on.
      */
    private def record(
        session: Session[IO],
        t: Tournament,
        game: Game,
        poolId: Int,
        matchNo: Int,
        placings: List[Placing]
    ): IO[Option[Int]] =
        for {
            state <- requireState(session, t)
            recorded = Progress.record(state, poolId, matchNo, placings)
            _ <- new TournamentMatchRepo(session).markDone(t.gameId, t.tournamentId, poolId, matchNo)
            roundDone = Progress.roundComplete(recorded)
            finished <-
                if (!roundDone) new TournamentRepo(session).writeState(t.gameId, t.tournamentId, recorded).as(false)
                else
                    envFor(session, t, game).flatMap { env =>
                        val advanced = Progress.advance(recorded, env)
                        if (advanced.finished) finish(session, t, advanced).as(true)
                        else new TournamentRepo(session).writeState(t.gameId, t.tournamentId, advanced).as(false)
                    }
        } yield Option.when(roundDone && !finished)(recorded.round)

    /** After a match is recorded and committed, a round it completed: in a tournament with automatic rounds the next
      * begins, and in one whose owner starts them the owner is told it is waiting on them.
      */
    private def afterRecording(t: Tournament, roundDone: Option[Int]): IO[Unit] =
        roundDone.traverse_ { round =>
            if (t.settings.autoRounds) endings.tournamentRoundDue(t.gameId, t.tournamentId)
            else sessionPool.use(notifications.tournamentRoundDone(_, t, round))
        }

    private def finish(session: Session[IO], t: Tournament, state: TournamentState): IO[Unit] =
        new TournamentRepo(session).writeState(t.gameId, t.tournamentId, state) *>
            new TournamentRepo(session).update(
              t.copy(status = TournamentStatus.Finished, endedAt = Some(now()), roundDeadline = None)
            )

    // ---- the owner's tools -----------------------------------------------------------------

    /** The owner's Check round. Records when it was asked for and queues the check itself ([[runCheck]]), which the
      * ending function carries out: each match may be a call to its engine, and a round of many could outlast the
      * request. Refused within [[checkInterval]] of the last check.
      */
    def checkRound(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Unit] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId)(
                          UnauthorizedError("only the tournament's owner may check a round")
                        )
                        _ <- IO.raiseUnless(t.status == TournamentStatus.Running)(
                          ConflictError("this tournament is not under way")
                        )
                        at = now()
                        _ <- IO.raiseWhen(t.checkedAt.exists(c => at.isBefore(c.plus(checkInterval))))(
                          ConflictError("this round was checked moments ago; its matches are still being looked at")
                        )
                        _ <- new TournamentRepo(session).update(t.copy(checkedAt = Some(at)))
                    } yield at
                }
            }
            .flatMap(at => endings.tournamentRoundCheck(gameId, tournamentId, at))

    /** A queued Check round: enforces the clocks of the round's matches being played, forfeits any seat whose time to
      * choose a role has run out, and queues again any match left due. Nothing, for a tournament no longer under way.
      *
      * Each match is its own transaction, under the locks every change takes, and re-decides there what it acts on: the
      * list read here is only where to look, and a message the queue delivers twice must not forfeit twice.
      */
    def runCheck(gameId: GameId, tournamentId: TournamentId): IO[Settlement] =
        sessionPool
            .use { session =>
                // Read plainly: nothing here is written from it. Each match is re-read under lock below.
                new TournamentRepo(session).read(gameId, tournamentId).flatMap {
                    case Some(t) if t.status == TournamentStatus.Running =>
                        new TournamentMatchRepo(session).listUnfinished(gameId, tournamentId)
                    case _ => IO.pure(Nil)
                }
            }
            .flatMap(
              _.traverse_ { tm =>
                  tm.status match {
                      case TournamentMatchStatus.Started =>
                          // One match whose engine cannot be reached does not stop the rest being looked at.
                          tm.matchId.traverse_(engine.enforceNow(gameId, _)).attempt.void
                      case TournamentMatchStatus.Due =>
                          endings.tournamentMatchDue(gameId, tournamentId, tm.poolId, tm.matchNo)
                      case TournamentMatchStatus.Choosing =>
                          forfeitChooser(gameId, tournamentId, tm.poolId, tm.matchNo)
                      case TournamentMatchStatus.Done => IO.unit
                  }
              }
            )
            .as(Settlement.Settled)

    /** The seat whose time to choose has run out forfeits, and the choosing moves on without it. Whether its time has
      * run out is decided here, under the match's lock: a seat that has chosen meanwhile, or whose turn has only just
      * begun, is left alone.
      */
    private def forfeitChooser(gameId: GameId, tournamentId: TournamentId, poolId: Int, matchNo: Int): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val matches = new TournamentMatchRepo(session)
                    for {
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        tm <- requireMatchForUpdate(session, t, poolId, matchNo)
                        game <- requireGame(session, gameId)
                        state <- requireState(session, t)
                        at = now()
                        late = TournamentRounds.chooseBy(t, game, state, tm).exists(!at.isBefore(_))
                        status <-
                            if (tm.status != TournamentMatchStatus.Choosing || !late) IO.pure(tm.status)
                            else {
                                val out = TournamentRounds.nextChooser(tm.seats).map(_.entrantId)
                                val forfeited = tm.seats.map(s =>
                                    if (out.contains(s.entrantId)) s.copy(forfeit = true, chooseOrder = None) else s
                                )
                                val settled =
                                    TournamentRounds.settleChoices(forfeited, allowedRoles(game, tm.seats.size), at)
                                val status = TournamentRounds.statusOf(settled, game.requiredSeats)
                                settled.traverse_(matches.updateSeat(gameId, tournamentId, poolId, matchNo, _)) *>
                                    matches.setStatus(gameId, tournamentId, poolId, matchNo, status, None).as(status)
                            }
                    } yield status
                }
                .flatMap(status =>
                    IO.whenA(status == TournamentMatchStatus.Due)(
                      endings.tournamentMatchDue(gameId, tournamentId, poolId, matchNo)
                    )
                )
        }

    /** The owner asking a stage that repeats until stopped to stop, after the run being played. */
    def stop(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session, gameId, tournamentId)
                    _ <- IO.raiseUnless(t.owner == caller.playerId)(
                      UnauthorizedError("only the tournament's owner may stop it")
                    )
                    _ <- IO.raiseUnless(t.status == TournamentStatus.Running)(
                      ConflictError("this tournament is not under way")
                    )
                    state <- requireState(session, t)
                    _ <- new TournamentRepo(session).writeState(gameId, tournamentId, Progress.requestStop(state))
                } yield ()
            }
        }

    /** The owner deciding a match of the round by hand — a player who never turned up — with a rank for each of its
      * seats (new-design.md, D12). A match being played is called off, its seats given the owner's ranks as results
      * marked manual, which move nobody's rating; one not yet created is simply recorded. A match whose result is in
      * already keeps it.
      */
    def decide(
        gameId: GameId,
        tournamentId: TournamentId,
        poolId: Int,
        matchNo: Int,
        ranks: Map[EntrantId, Int],
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId)(
                          UnauthorizedError("only the tournament's owner may decide a match")
                        )
                        tm <- requireMatchForUpdate(session, t, poolId, matchNo)
                        _ <- IO.raiseWhen(tm.status == TournamentMatchStatus.Done)(
                          ConflictError("this match's result is in, and stands")
                        )
                        _ <- IO.raiseUnless(
                          ranks.keySet == tm.seats.map(_.entrantId).toSet && ranks.values.forall(_ >= 1)
                        )(
                          ValidationError("give every seat of the match a rank of 1 or more, and no one else")
                        )
                        game <- requireGame(session, gameId)
                        cancelled <- tm.matchId match {
                            case None => IO.pure(None)
                            case Some(id) =>
                                new MatchRepo(session).readForUpdate(gameId, id).flatMap {
                                    case Some(m) if m.completed =>
                                        IO.raiseError(
                                          ConflictError("this match was played to an end; its result stands")
                                        )
                                    case Some(m) =>
                                        val best = ranks.values.min
                                        val alone = ranks.values.count(_ == best) == 1
                                        for {
                                            _ <- IO.unlessA(m.cancelled)(
                                              new MatchRepo(session).update(m.copy(cancelled = true))
                                            )
                                            _ <- new ParticipantRepo(session).completeForMatch(gameId, id)
                                            _ <- tm.seats.traverse_(s =>
                                                s.participantId.traverse_(p =>
                                                    new ResultRepo(session).createManual(
                                                      gameId,
                                                      p,
                                                      ranks(s.entrantId),
                                                      alone && ranks(s.entrantId) == best
                                                    )
                                                )
                                            )
                                        } yield Option.unless(m.cancelled)(id)
                                    case None => IO.pure(None)
                                }
                        }
                        placings = tm.seats.map(s => Placing(s.entrantId.value, Some(ranks(s.entrantId))))
                        done <- record(session, t, game, poolId, matchNo, placings)
                    } yield (t, cancelled, done)
                }
                .flatMap { (t, cancelled, done) =>
                    // The engine is told of the cancel by the ending, which finds the match recorded already.
                    cancelled.traverse_(endings.ended(gameId, _)) *> afterRecording(t, done)
                }
        }

    // ---- shared ----------------------------------------------------------------------------

    /** Everyone's rating now, by entrant: a character entry's owner's, and the starting rating for anyone with none. */
    private def envFor(session: Session[IO], t: Tournament, game: Game): IO[Env] =
        for {
            entries <- new EntrantRepo(session).list(t.gameId, t.tournamentId)
            players <- new EntrantRepo(session).players(t.gameId, t.tournamentId)
            ratings <- entries.traverse { e =>
                players.get(e.entrantId).flatten match {
                    case Some(p) =>
                        new EloRatingRepo(session)
                            .read(t.gameId, p)
                            .map(r => e.entrantId.value -> r.fold(EloRating.initial)(_.rating))
                    case None => IO.pure(e.entrantId.value -> e.ratingAtEntry)
                }
            }
            design <- new StageRepo(session).design(t.gameId, t.tournamentId).map(_.getOrElse(TournamentDesign(Nil)))
        } yield Env(
          design,
          TournamentGame.of(game),
          ratings.toMap,
          Some(t.defaultPoolSize),
          t.settings.concurrentRounds
        )

    private def requireState(session: Session[IO], t: Tournament): IO[TournamentState] =
        new TournamentRepo(session).readState(t.gameId, t.tournamentId).flatMap {
            case Some(s) => IO.pure(s)
            case None    => IO.raiseError(ConflictError("this tournament has not started"))
        }

    private def requireCaller(session: Session[IO], externalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(externalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$externalId'"))
        }

    // Read plainly, as a reference table on the way to writes elsewhere: see the class's note.
    private def requireGame(session: Session[IO], gameId: GameId): IO[Game] =
        new GameRepo[T](session).read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game $gameId"))
        }

    private def requireTournamentForUpdate(session: Session[IO], gameId: GameId, id: TournamentId): IO[Tournament] =
        new TournamentRepo(session).readForUpdate(gameId, id).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no tournament $id in game $gameId"))
        }

    private def requireMatchForUpdate(
        session: Session[IO],
        t: Tournament,
        poolId: Int,
        matchNo: Int
    ): IO[TournamentMatch] =
        new TournamentMatchRepo(session).readForUpdate(t.gameId, t.tournamentId, poolId, matchNo).flatMap {
            case Some(m) => IO.pure(m)
            case None    => IO.raiseError(NotFoundError(s"no match $matchNo of pool $poolId in this tournament"))
        }
}

object TournamentRounds {

    /** The seat whose turn it is to choose: the earliest in the order of choosing that has not, and has not forfeited.
      */
    def nextChooser(seats: List[TournamentSeat]): Option[TournamentSeat] =
        seats.filter(s => s.gameRoleId.isEmpty && !s.forfeit && s.chooseOrder.isDefined).minByOption(_.chooseOrder)

    /** Seats after a choice: when one seat is left to choose and one role is left, it is given that role; otherwise the
      * next seat to choose has its clock started now.
      */
    def settleChoices(seats: List[TournamentSeat], allowed: List[GameRoleId], at: Instant): List[TournamentSeat] = {
        val left = seats.filter(s => s.gameRoleId.isEmpty && !s.forfeit && s.chooseOrder.isDefined)
        val free = allowed.filterNot(seats.flatMap(_.gameRoleId).toSet)
        if (left.sizeIs == 1 && free.sizeIs == 1)
            seats.map(s =>
                if (s.entrantId == left.head.entrantId)
                    s.copy(
                      gameRoleId = Some(free.head),
                      chooseStartedAt = s.chooseStartedAt.orElse(Some(at)),
                      chosenAt = Some(at)
                    )
                else s
            )
        else
            nextChooser(seats) match {
                case Some(next) if next.chooseStartedAt.isEmpty =>
                    seats.map(s => if (s.entrantId == next.entrantId) s.copy(chooseStartedAt = Some(at)) else s)
                case _ => seats
            }
    }

    /** Due once every seat still playing has its role — or once too few are left playing to fill the game's `required`
      * roles, when there is nothing left to choose for and the match is decided as it is created. Choosing until then.
      */
    def statusOf(seats: List[TournamentSeat], required: Int): TournamentMatchStatus =
        if (nextChooser(seats).isEmpty || seats.count(!_.forfeit) < math.max(required, 2)) TournamentMatchStatus.Due
        else TournamentMatchStatus.Choosing

    /** A match's clock: the tournament's round duration, spent as the game spends time (`Clock.forMatch`). `None` for a
      * match the state does not know, or a tournament with no round duration.
      */
    def clockOf(t: Tournament, game: Game, state: TournamentState, tm: TournamentMatch): Option[MatchClock] =
        for {
            where <- Progress.findMatch(state, tm.poolId, tm.matchNo)
            d <- t.settings.roundDuration
        } yield Clock.forMatch(
          TournamentGame.of(game),
          d,
          tm.seats.count(!_.forfeit),
          tm.tiesAllowed,
          choosing = tm.seats.exists(_.chooseOrder.isDefined),
          t.settings.maxTurns
        )

    /** When the seat whose turn it is to choose runs out of time to. */
    def chooseBy(t: Tournament, game: Game, state: TournamentState, tm: TournamentMatch): Option[Instant] =
        for {
            seat <- nextChooser(tm.seats)
            from <- seat.chooseStartedAt
            clock <- clockOf(t, game, state, tm)
        } yield from.plus(Clock.toChoose(clock))

    /** The unit a tournament match's limit is shown in. */
    def unitFor(limit: Option[Duration]): TimeLimitUnit =
        limit match {
            case Some(d) if d.toHours >= 48 => TimeLimitUnit.Hours
            case _                          => TimeLimitUnit.Minutes
        }

    /** The number an engine reported as a seat's score (`scores.score`), if it reported one. */
    def scoreOf(r: Result): Option[Double] =
        r.scores.get("score").collect {
            case d: Double     => d
            case i: Int        => i.toDouble
            case l: Long       => l.toDouble
            case b: BigDecimal => b.toDouble
        }
}
