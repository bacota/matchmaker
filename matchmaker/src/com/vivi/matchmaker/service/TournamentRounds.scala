package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.{Duration, Instant}
import skunk.Session
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.model.tournament.{
    Clock,
    Env,
    MatchClock,
    MultipleEntries,
    Tournament as Progress,
    TournamentDesign,
    TournamentGame,
    TournamentState
}
import com.vivi.matchmaker.model.tournament.standard.Challenge
import com.vivi.matchmaker.persistence._
import com.vivi.matchmaker.persistence.TournamentStateRepo.Loaded

/** A tournament's rounds (`design/new-tournament-plan.md`, Phase 6): beginning each, its seats choosing their roles,
  * its matches being created and played, and the tournament advancing past what they finish.
  *
  * A tournament's matches are `match` rows from the moment their pools are formed, scheduled until a round takes them,
  * and their seats `participant` rows:
  *
  *   - A round is begun by its owner, or by its last match ending in a tournament with automatic rounds. Beginning it
  *     gives the next slot of each pool's schedule its round, settles who forfeits before play, and starts the first
  *     seat choosing where roles are to be chosen.
  *   - A match whose roles are settled is due, and is created from the ending queue
  *     (`MatchEndings.tournamentMatchDue`): it begins under its own id, its seats rated as they are then, and its
  *     engine's game is made by [[GameEngineService.launchTournamentMatch]] with no transaction open across the call.
  *     An engine that could not be reached leaves it due under the same id, which the engine answers as the game it
  *     already made if it did.
  *   - A match's end reaches the tournament through `EndingService.settle`, which every ending passes through. Its
  *     results are its outcome, so recording it is reading it: the last match of a round completes the round, the
  *     tournament advances, and the round is marked complete once, which is when automatic rounds begin the next or the
  *     owner is mailed.
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

    /** The owner beginning the next round, for `duration` if they give one and the tournament's round duration if not.
      * Its matches whose roles are settled are queued to be created at once; the rest wait on their seats' choices. The
      * number of the round begun, or none if that finished the tournament.
      */
    def startRound(
        gameId: GameId,
        tournamentId: TournamentId,
        callerExternalId: String,
        duration: Option[Duration] = None
    ): IO[Option[Int]] =
        sessionPool.use { session =>
            for {
                begun <- session.transaction.use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId)(
                          UnauthorizedError("only the tournament's owner may start a round")
                        )
                        _ <- IO.raiseUnless(t.status == TournamentStatus.Running)(
                          ConflictError(s"this tournament is not under way; it is ${t.status.label.toLowerCase}")
                        )
                        _ <- IO.raiseWhen(duration.exists(d => d.isZero || d.isNegative))(
                          ValidationError("a round duration must be positive")
                        )
                        begun <- begin(session = session, t = t, duration = duration)
                    } yield begun
                }
                (round, due) = begun
                _ <- due.traverse_(endings.tournamentMatchDue(gameId, tournamentId, _))
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
                        case Some(t)
                            if t.status == TournamentStatus.Running && t.settings.hasAutomaticRounds && !t.isStopped =>
                            stateRepo(session).isRoundComplete(gameId, tournamentId).flatMap { isComplete =>
                                if (isComplete) begin(session = session, t = t, duration = None).map(_._2)
                                else IO.pure(Nil)
                            }
                        case _ => IO.pure(Nil)
                    }
                }
                _ <- due.traverse_(endings.tournamentMatchDue(gameId, tournamentId, _))
            } yield Settlement.Settled
        }

    /** Begins the next round, under the tournament's lock, which the caller holds: advances the state past the last and
      * gives the next slot of every pool's schedule the new round, then seats each of its matches. The round's number
      * and the matches due to be created now. A tournament that advancing finishes is marked finished, and has no
      * round.
      */
    private def begin(
        session: Session[IO],
        t: Tournament,
        duration: Option[Duration]
    ): IO[(Option[Int], List[MatchId])] =
        for {
            _ <- IO.raiseWhen(t.isStopped)(ConflictError("this tournament has been stopped, and begins no more rounds"))
            game <- requireGame(session, t.gameId)
            open <- requireOpenLoaded(session, t)
            _ <- IO.raiseUnless(Progress.isRoundComplete(open.state))(
              ConflictError("the round being played is not finished")
            )
            env <- envFor(session = session, t = t, game = game, design = open.design)
            worked <- workedOut(session = session, t = t, open = open)(Progress.nextRound(_, env)._1)
            (loaded, next) = worked
            result <-
                if (next.isFinished) finish(session = session, t = t, loaded = loaded, state = next).as((None, Nil))
                else
                    for {
                        _ <- stateRepo(session).save(
                          game = t.gameId,
                          tournament = t.tournamentId,
                          before = loaded,
                          after = next
                        )
                        at = now()
                        _ <- stateRepo(session).beginRound(
                          game = t.gameId,
                          tournament = t.tournamentId,
                          round = next.round,
                          at = at,
                          deadline = duration.orElse(t.settings.roundDuration).map(at.plus)
                        )
                        matches <- new TournamentMatchRepo(session).listRound(
                          game = t.gameId,
                          tournament = t.tournamentId,
                          round = next.round
                        )
                        players <- new EntrantRepo(session).players(t.gameId, t.tournamentId)
                        seeds <- seedsIn(session, t)
                        due <- matches.flatTraverse(m =>
                            seat(
                              session = session,
                              game = game,
                              state = next,
                              players = players,
                              seeds = seeds,
                              m = m,
                              at = at
                            )
                        )
                    } yield (Some(next.round), due)
        } yield result

    /* Each member's seed in the pool their match is in, for who keeps their seat when one player holds two. Read
     * again, now that the round's pools have their real ids; only the runs being played, which are the round's. */
    private def seedsIn(session: Session[IO], t: Tournament): IO[Map[(Int, Long), Int]] =
        requireOpenLoaded(session, t).map { loaded =>
            Progress.allPools(loaded.state.root).flatMap(p => p.members.map(m => (p.id, m.entrant) -> m.seedIn)).toMap
        }

    /** Seats a match of the round as it begins: the seats that forfeit before play — an entrant who has withdrawn, a
      * second entry of a player already seated, or a character with nobody to play it — and the first seat's turn to
      * choose. The match, if it is due to be created now.
      */
    private def seat(
        session: Session[IO],
        game: Game,
        state: TournamentState,
        players: Map[EntrantId, Option[PlayerId]],
        seeds: Map[(Int, Long), Int],
        m: TournamentMatch,
        at: Instant
    ): IO[List[MatchId]] = {
        val owners = m.seats.map(s => (s.entrantId.value, players.get(s.entrantId).flatten))
        val doubled = MultipleEntries.forfeits(owners.collect { case (e, Some(p)) =>
            (e, p.value, seeds.getOrElse((m.poolId, e), Int.MaxValue))
        })
        def forfeits(e: Long) = state.withdrawn(e) || doubled(e) || owners.exists((x, p) => x == e && p.isEmpty)
        val marked =
            m.seats.map(s => if (forfeits(s.entrantId.value)) s.copy(isForfeit = true, chooseOrder = None) else s)
        val settled =
            TournamentRounds.settleChoices(seats = marked, allowed = allowedRoles(game, m.seats.size), at = at)
        val status = TournamentRounds.statusOf(settled, game.requiredSeats)
        val matches = new TournamentMatchRepo(session)
        settled.filterNot(m.seats.contains).traverse_(matches.updateSeat(m.gameId, _)) *>
            matches
                .setStatus(game = m.gameId, id = m.matchId, to = status)
                .as(Option.when(status == TournamentMatchStatus.Due)(m.matchId).toList)
    }

    // ---- choosing roles --------------------------------------------------------------------

    /** The seat whose turn it is choosing its role. The last seat left to choose is given the last role left; a match
      * whose roles are then all settled is due, and queued to be created.
      */
    def chooseRole(
        gameId: GameId,
        tournamentId: TournamentId,
        matchId: MatchId,
        role: GameRoleId,
        callerExternalId: String
    ): IO[TournamentMatchView] =
        sessionPool.use { session =>
            for {
                chosen <- session.transaction.use { _ =>
                    val matches = new TournamentMatchRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        tm <- requireMatchForUpdate(session = session, t = t, matchId = matchId)
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
                        settled = TournamentRounds.settleChoices(seats = picked, allowed = allowed, at = at)
                        _ <- settled.filterNot(tm.seats.contains).traverse_(matches.updateSeat(gameId, _))
                        status = TournamentRounds.statusOf(settled, game.requiredSeats)
                        _ <- matches.setStatus(game = gameId, id = matchId, to = status)
                    } yield tm.copy(seats = settled, status = status)
                }
                _ <- IO.whenA(chosen.status == TournamentMatchStatus.Due)(
                  endings.tournamentMatchDue(gameId = gameId, tournamentId = tournamentId, matchId = matchId)
                )
            } yield TournamentService.view(chosen)
        }

    /** The roles a match of `seats` seats hands out: the game's required roles, then its recommended ones, as many as
      * there are seats.
      */
    private def allowedRoles(game: Game, seats: Int): List[GameRoleId] =
        TournamentGame.of(game).seatingRoles.take(seats).map(r => GameRoleId(r.id))

    /** Every seat whose turn it is to choose a role, across the caller's tournaments, with when their time runs out.
      * Its matches, their tournaments and their games are each read once for the whole list.
      */
    def choicesFor(callerExternalId: String): IO[List[TournamentChoice]] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                waiting <- new TournamentMatchRepo(session).choosingFor(caller.playerId)
                tournaments <- new TournamentRepo(session).readFor(
                  waiting.map(tm => TournamentKey(tm.gameId, tm.tournamentId)).distinct
                )
                // The few games they are in, read plainly, as everywhere here: see requireGame.
                games <- waiting
                    .map(_.gameId)
                    .distinct
                    .traverse(g => new GameRepo[T](session).read(g).map(_.map(g -> _)))
                    .map(_.flatten.toMap)
            } yield waiting.flatMap { tm =>
                for {
                    t <- tournaments.get(TournamentKey(tm.gameId, tm.tournamentId))
                    game <- games.get(tm.gameId)
                } yield TournamentChoice(
                  gameId = tm.gameId,
                  tournamentId = tm.tournamentId,
                  tournamentName = t.name,
                  gameName = game.displayName,
                  matchId = tm.matchId,
                  chooseBy = TournamentRounds.chooseBy(t = t, game = game, tm = tm)
                )
            }
        }

    // ---- creating a match ------------------------------------------------------------------

    /** Creates a due match's engine game: begins it under its claim, its seats rated as they are now, then makes the
      * game with no transaction open. A match whose seats are too few to play once the forfeits are taken out is
      * decided there and then. Owed, and so tried again, if the engine could not be reached; anything not due is
      * settled already.
      */
    def createMatch(gameId: GameId, tournamentId: TournamentId, matchId: MatchId): IO[Settlement] =
        sessionPool.use { session =>
            for {
                prepared <- session.transaction.use(_ =>
                    prepare(session = session, gameId = gameId, tournamentId = tournamentId, matchId = matchId)
                )
                settled <- prepared match {
                    case Prepared.Nothing               => IO.pure(Settlement.Settled)
                    case Prepared.Decided(t, roundDone) => afterRecording(t, roundDone).as(Settlement.Settled)
                    case Prepared.Launch(saved, game, players, tiesAllowed) =>
                        engine
                            .launchTournamentMatch(
                              session = session,
                              saved = saved,
                              game = game,
                              players = players,
                              tiesAllowed = tiesAllowed,
                              undo = undo(session = session, gameId = gameId, matchId = matchId)
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
            canTie: Boolean
        )
    }

    private def prepare(
        session: Session[IO],
        gameId: GameId,
        tournamentId: TournamentId,
        matchId: MatchId
    ): IO[Prepared] =
        new TournamentRepo(session).readForUpdate(gameId, tournamentId).flatMap {
            case None => IO.pure(Prepared.Nothing)
            case Some(t) =>
                new TournamentMatchRepo(session).readForUpdate(gameId, matchId).flatMap {
                    case Some(tm) if tm.status == TournamentMatchStatus.Due =>
                        seatsFor(session = session, t = t, tm = tm)
                    case _ => IO.pure(Prepared.Nothing)
                }
        }

    private def seatsFor(session: Session[IO], t: Tournament, tm: TournamentMatch): IO[Prepared] =
        for {
            game <- requireGame(session, t.gameId)
            players <- new EntrantRepo(session).players(t.gameId, t.tournamentId)
            active = tm.seats.filter { s =>
                val isSeated = players.get(s.entrantId).flatten.isDefined
                // A seat of a game without roles has no role to wait for.
                !s.isForfeit && (s.gameRoleId.isDefined || game.isRoleless) && isSeated
            }
            prepared <-
                if (active.sizeIs < math.max(game.requiredSeats, 2) || active.sizeIs < tm.seats.count(!_.isForfeit)) {
                    // Too few left to play: those still seated share first place, and everyone else is last.
                    val ranks =
                        tm.seats.map(s => s -> (if (active.exists(_.entrantId == s.entrantId)) 1 else active.size + 1))
                    decideUnplayed(session = session, t = t, game = game, tm = tm, ranks = ranks).map(done =>
                        Prepared.Decided(t, done)
                    )
                } else launch(session = session, t = t, game = game, players = players, tm = tm, active = active)
        } yield prepared

    private def launch(
        session: Session[IO],
        t: Tournament,
        game: Game,
        players: Map[EntrantId, Option[PlayerId]],
        tm: TournamentMatch,
        active: List[TournamentSeat]
    ): IO[Prepared] = {
        val clock = TournamentRounds.clockOf(t = t, game = game, tm = tm)
        val at = now()
        val matches = new TournamentMatchRepo(session)
        val roleName = game.roles.map(r => r.gameRoleId -> r.name).toMap
        // Roles nobody plays, for the seats that forfeit: a seat of a match being played always has one, in a game
        // that has roles.
        val spare = allowedRoles(game, tm.seats.size).filterNot(active.flatMap(_.gameRoleId).toSet)
        val forfeited = tm.seats.filterNot(s => active.exists(_.participantId == s.participantId))
        for {
            entries <- new EntrantRepo(session).list(t.gameId, t.tournamentId)
            characterOf = entries.map(e => e.entrantId -> e.characterId).toMap
            _ <- matches.launch(
              game = t.gameId,
              id = tm.matchId,
              at = at,
              // A challenge is one match, which its tournament's name and round would only describe as such.
              description = if (t.tournamentModelId == Challenge.modelId) "" else s"${t.name}, round ${tm.round}",
              limit = clock.map(_.limit),
              kind = clock.map(_.kind).getOrElse(TimeLimitKind.PerTurn),
              unit = TournamentRounds.unitFor(clock.map(_.limit)),
              friendly = t.settings.isFriendly,
              settings = t.settings.gameSettings,
              // Live only with a clock for the engine to run, which a live tournament always has.
              live = t.settings.isLive && clock.isDefined
            )
            seatsOf = active.map(s => (players(s.entrantId).get, s.gameRoleId))
            starting <- EloRatingService.startingRatings(
              session = session,
              gameId = t.gameId,
              seats = seatsOf,
              byRole = !game.isRoleless
            )
            _ <- active.traverse_ { s =>
                val player = players(s.entrantId).get
                matches.launchSeat(
                  game = t.gameId,
                  seat = s.participantId,
                  player = player,
                  eloStart = Some(starting.overall(player)),
                  eloRoleStart = starting.inRole(player, s.gameRoleId)
                )
            }
            // A forfeit is a last place, the worst any seat could finish, recorded now: the engine reports only the
            // seats it was told of.
            _ <- forfeited.zipWithIndex.traverse_ { (s, i) =>
                matches.retireSeat(game = t.gameId, seat = s.participantId, role = spare.lift(i)) *>
                    new ResultRepo(session).createManual(
                      gameId = t.gameId,
                      id = s.participantId,
                      rank = tm.seats.size,
                      isWinner = false
                    )
            }
            // What choosing cost each seat is a turn of theirs, charged to their clock like any other.
            _ <- active.traverse_ { s =>
                (s.chooseStartedAt, s.chosenAt).tupled.traverse_((from, to) =>
                    new TurnRepo(session).create(
                      Turn(
                        gameId = t.gameId,
                        matchId = tm.matchId,
                        participantId = s.participantId,
                        takenAt = to,
                        startedAt = from
                      )
                    )
                )
            }
            saved <- new MatchRepo(session).read(t.gameId, tm.matchId).flatMap {
                case Some(m) => IO.pure(m)
                case None    => IO.raiseError(NotFoundError(s"no match ${tm.matchId.value}"))
            }
            seated <- new ParticipantRepo(session).listForMatch(t.gameId, tm.matchId)
            enginePlayers <- active.traverse { s =>
                val participant = seated.map(_._1).find(_.participantId == s.participantId).get
                new PlayerRepo(session).read(participant.playerId).flatMap {
                    case Some(player) =>
                        engine.tournamentPlayer(
                          session = session,
                          participant = participant,
                          player = player,
                          roleName = s.gameRoleId.map(roleName),
                          characterId = characterOf.get(s.entrantId).flatten
                        )
                    case None => IO.raiseError(NotFoundError(s"no player ${participant.playerId}"))
                }
            }
        } yield Prepared.Launch(saved = saved, game = game, players = enginePlayers, canTie = tm.canTie)
    }

    /** Puts back a match whose engine would not create it, so that it is due again under the same id: its turns and its
      * forfeited seats' last places gone, as [[launch]] will write them again. Only while it is still being launched,
      * read under its lock: one its owner called off or decided meanwhile keeps what that wrote. Swallowed: the
      * engine's failure is the news.
      */
    private def undo(session: Session[IO], gameId: GameId, matchId: MatchId): IO[Unit] =
        session.transaction
            .use { _ =>
                new MatchRepo(session).readForUpdate(gameId, matchId).flatMap {
                    case Some(m) if m.status == MatchStatus.InProgress =>
                        new TurnRepo(session).deleteForMatch(gameId, matchId) *>
                            new ResultRepo(session).deleteManualForMatch(gameId, matchId) *>
                            new TournamentMatchRepo(session).backToDue(gameId, matchId)
                    case _ => IO.unit
                }
            }
            .handleError(_ => ())

    // ---- a match's end ---------------------------------------------------------------------

    /** A tournament match's end, reaching its tournament: its results are its outcome, so the tournament is read with
      * them and advanced past them if they complete its round. Nothing, for a match that is not a tournament's.
      */
    def recordEnding(gameId: GameId, matchId: MatchId): IO[Unit] =
        sessionPool.use { session =>
            new TournamentMatchRepo(session).tournamentOf(gameId, matchId).flatMap {
                case None => IO.unit
                case Some(tournamentId) =>
                    session.transaction
                        .use { _ =>
                            for {
                                t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                                done <-
                                    if (t.status != TournamentStatus.Running) IO.pure(None)
                                    else requireGame(session, gameId).flatMap(settle(session, t, _))
                            } yield done.map(t -> _)
                        }
                        .flatMap {
                            case Some((t, round)) => afterRecording(t, Some(round))
                            case None             => IO.unit
                        }
            }
        }

    /** Reads the tournament with every result in, under its lock, and if the round being played is complete advances it
      * and finishes it if that ends it, or if its owner has stopped it. The round's number, if this is what completed
      * it and the tournament goes on: a round is marked complete once, however many endings find it so.
      */
    private def settle(session: Session[IO], t: Tournament, game: Game): IO[Option[Int]] =
        // Asked of the round's rows first: most endings leave the round going, and reading the whole tournament under
        // its lock to find that out made each ending cost as much as the tournament had matches.
        stateRepo(session).isRoundComplete(t.gameId, t.tournamentId).flatMap { isComplete =>
            if (isComplete) settleLoaded(session = session, t = t, game = game) else IO.pure(None)
        }

    private def settleLoaded(session: Session[IO], t: Tournament, game: Game): IO[Option[Int]] =
        requireOpenLoaded(session, t).flatMap { open =>
            if (!Progress.isRoundComplete(open.state) || open.state.round == 0) IO.pure(None)
            else
                for {
                    env <- envFor(session = session, t = t, game = game, design = open.design)
                    worked <- workedOut(session = session, t = t, open = open)(Progress.advance(_, env))
                    (loaded, advanced) = worked
                    first <- stateRepo(session).markRoundComplete(
                      game = t.gameId,
                      tournament = t.tournamentId,
                      round = loaded.state.round
                    )
                    finished <-
                        if (advanced.isFinished || t.isStopped)
                            finish(session = session, t = t, loaded = loaded, state = advanced).as(true)
                        else
                            stateRepo(session)
                                .save(game = t.gameId, tournament = t.tournamentId, before = loaded, after = advanced)
                                .as(false)
                } yield Option.when(first && !finished)(loaded.state.round)
        }

    /** A match decided without being played, under the tournament's and the match's locks: each seat ranked as given,
      * as a result marked manual, which moves nobody's rating, and the match over -- called off, since it was never
      * played. The round's number, if this completed it.
      */
    private def decideUnplayed(
        session: Session[IO],
        t: Tournament,
        game: Game,
        tm: TournamentMatch,
        ranks: List[(TournamentSeat, Int)]
    ): IO[Option[Int]] = {
        val best = ranks.map(_._2).minOption.getOrElse(1)
        val alone = ranks.count(_._2 == best) == 1
        for {
            _ <- ranks.traverse_((s, rank) =>
                new ResultRepo(session).createManual(
                  gameId = t.gameId,
                  id = s.participantId,
                  rank = rank,
                  isWinner = alone && rank == best
                )
            )
            _ <- callOff(session = session, gameId = t.gameId, matchId = tm.matchId)
            done <- settle(session = session, t = t, game = game)
        } yield done
    }

    /* A tournament match over without being played to an end: called off, its seats finished with. */
    private def callOff(session: Session[IO], gameId: GameId, matchId: MatchId): IO[Unit] =
        new MatchRepo(session).readForUpdate(gameId, matchId).flatMap {
            case Some(m) if !m.isCancelled && !m.isCompleted =>
                new MatchRepo(session).update(m.copy(isCancelled = true)) *>
                    new ParticipantRepo(session).completeForMatch(gameId, matchId)
            case _ => IO.unit
        }

    /** After a round is completed and committed: in a tournament with automatic rounds the next begins, and in one
      * whose owner starts them the owner is told it is waiting on them.
      */
    private def afterRecording(t: Tournament, roundDone: Option[Int]): IO[Unit] =
        roundDone.traverse_ { round =>
            if (t.settings.hasAutomaticRounds) endings.tournamentRoundDue(t.gameId, t.tournamentId)
            else sessionPool.use(notifications.tournamentRoundDone(_, t, round))
        }

    private def finish(session: Session[IO], t: Tournament, loaded: Loaded, state: TournamentState): IO[Unit] =
        stateRepo(session).save(game = t.gameId, tournament = t.tournamentId, before = loaded, after = state) *>
            new TournamentRepo(session).update(t.copy(status = TournamentStatus.Finished, endedAt = Some(now())))

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
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
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
            .flatMap(at => endings.tournamentRoundCheck(gameId = gameId, tournamentId = tournamentId, checkedAt = at))

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
                          engine.enforceNow(gameId, tm.matchId).attempt.void
                      case TournamentMatchStatus.Due =>
                          endings.tournamentMatchDue(gameId = gameId, tournamentId = tournamentId, matchId = tm.matchId)
                      case TournamentMatchStatus.Choosing =>
                          forfeitChooser(gameId = gameId, tournamentId = tournamentId, matchId = tm.matchId)
                      case TournamentMatchStatus.Done => IO.unit
                  }
              }
            )
            .as(Settlement.Settled)

    /** The seat whose time to choose has run out forfeits, and the choosing moves on without it. Whether its time has
      * run out is decided here, under the match's lock: a seat that has chosen meanwhile, or whose turn has only just
      * begun, is left alone.
      */
    private def forfeitChooser(gameId: GameId, tournamentId: TournamentId, matchId: MatchId): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val matches = new TournamentMatchRepo(session)
                    for {
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        tm <- requireMatchForUpdate(session = session, t = t, matchId = matchId)
                        game <- requireGame(session, gameId)
                        at = now()
                        late = TournamentRounds.chooseBy(t = t, game = game, tm = tm).exists(!at.isBefore(_))
                        status <-
                            if (tm.status != TournamentMatchStatus.Choosing || !late) IO.pure(tm.status)
                            else {
                                val out = TournamentRounds.nextChooser(tm.seats).map(_.entrantId)
                                val forfeited = tm.seats.map(s =>
                                    if (out.contains(s.entrantId)) s.copy(isForfeit = true, chooseOrder = None) else s
                                )
                                val settled =
                                    TournamentRounds.settleChoices(
                                      seats = forfeited,
                                      allowed = allowedRoles(game, tm.seats.size),
                                      at = at
                                    )
                                val status = TournamentRounds.statusOf(settled, game.requiredSeats)
                                settled.filterNot(tm.seats.contains).traverse_(matches.updateSeat(gameId, _)) *>
                                    matches.setStatus(game = gameId, id = matchId, to = status).as(status)
                            }
                    } yield status
                }
                .flatMap(status =>
                    IO.whenA(status == TournamentMatchStatus.Due)(
                      endings.tournamentMatchDue(gameId = gameId, tournamentId = tournamentId, matchId = matchId)
                    )
                )
        }

    /** The owner stopping a tournament, so that no match of it is scheduled from now on.
      *
      * One that has not started has no match, and is deleted, entries and all. One under way is marked stopped: its
      * matches already scheduled are played out, no further round is begun, and it finishes as the round being played
      * completes ([[settle]]) -- at once, if that round is already over. Its matches of later rounds are left scheduled
      * and never played, as are those of every tournament that finishes before its pools do. A tournament that is over
      * cannot be stopped, and neither can one stopped already.
      */
    def stop(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                    _ <- IO.raiseUnless(t.owner == caller.playerId)(
                      UnauthorizedError("only the tournament's owner may stop it")
                    )
                    _ <- t.status match {
                        case TournamentStatus.Draft | TournamentStatus.Open =>
                            new TournamentRepo(session).delete(gameId, tournamentId)
                        case TournamentStatus.Running if t.isStopped =>
                            IO.raiseError(ConflictError("this tournament has been stopped already"))
                        case TournamentStatus.Running =>
                            new TournamentRepo(session).stop(gameId, tournamentId) *>
                                requireLoaded(session, t).flatMap { loaded =>
                                    IO.whenA(Progress.isRoundComplete(loaded.state))(
                                      requireGame(session, gameId)
                                          .flatMap(game =>
                                              envFor(session = session, t = t, game = game, design = loaded.design)
                                          )
                                          .flatMap(env =>
                                              finish(
                                                session = session,
                                                t = t,
                                                loaded = loaded,
                                                state = Progress.advance(loaded.state, env)
                                              )
                                          )
                                    )
                                }
                        case _ => IO.raiseError(ConflictError("this tournament is already over"))
                    }
                } yield ()
            }
        }

    /** The owner asking a stage that repeats until stopped to stop, after the run being played. */
    def stopRepeating(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                    _ <- IO.raiseUnless(t.owner == caller.playerId)(
                      UnauthorizedError("only the tournament's owner may stop it")
                    )
                    _ <- IO.raiseUnless(t.status == TournamentStatus.Running)(
                      ConflictError("this tournament is not under way")
                    )
                    loaded <- requireLoaded(session, t)
                    _ <- stateRepo(session).save(
                      game = gameId,
                      tournament = tournamentId,
                      before = loaded,
                      after = Progress.requestStop(loaded.state)
                    )
                } yield ()
            }
        }

    /** The owner deciding a match of the round by hand — a player who never turned up — with a rank for each of its
      * seats (new-design.md, D12). A match being played is called off, and one not yet created simply decided; either
      * way each seat's rank is a result marked manual, which moves nobody's rating. A match played to an end keeps its
      * result.
      */
    def decide(
        gameId: GameId,
        tournamentId: TournamentId,
        matchId: MatchId,
        ranks: Map[EntrantId, Int],
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId)(
                          UnauthorizedError("only the tournament's owner may decide a match")
                        )
                        tm <- requireMatchForUpdate(session = session, t = t, matchId = matchId)
                        _ <- IO.raiseUnless(
                          ranks.keySet == tm.seats.map(_.entrantId).toSet && ranks.values.forall(_ >= 1)
                        )(
                          ValidationError("give every seat of the match a rank of 1 or more, and no one else")
                        )
                        game <- requireGame(session, gameId)
                        m <- new MatchRepo(session).readForUpdate(gameId, matchId)
                        _ <- IO.raiseWhen(m.exists(_.isCompleted))(
                          ConflictError("this match was played to an end; its result stands")
                        )
                        resultRepo = new ResultRepo(session)
                        byParticipant <- resultRepo.forMatch(gameId, matchId).map(_.map(r => r.participantId -> r).toMap)
                        found = tm.seats.map(s => byParticipant.get(s.participantId))
                        // A cancel's forfeits are no result to the tournament, and the owner's ranks replace them.
                        cancelForfeit = (r: ResultRepo.RatedResultRow) => m.exists(_.isCancelled) && r.isForfeit
                        recorded = found.map(_.filterNot(cancelForfeit))
                        _ <- IO.raiseWhen(m.exists(_.isCancelled) && recorded.forall(_.isDefined))(
                          ConflictError("this match's result is in, and stands")
                        )
                        best = ranks.values.min
                        alone = ranks.values.count(_ == best) == 1
                        // Begun, and so to be told to its engine as called off; otherwise there is no engine game.
                        begun = tm.status == TournamentMatchStatus.Started
                        // Every seat with no result, or only a cancel's forfeit; a seat with a result keeps it.
                        _ <- resultRepo.setManual(
                          gameId = gameId,
                          ranks = tm.seats.zip(recorded).collect { case (s, None) =>
                              val rank = ranks(s.entrantId)
                              ResultRepo.ManualRank(
                                participantId = s.participantId,
                                rank = rank,
                                isWinner = alone && rank == best
                              )
                          }
                        )
                        _ <- callOff(session = session, gameId = gameId, matchId = matchId)
                        done <- settle(session = session, t = t, game = game)
                    } yield (t, Option.when(begun)(matchId), done)
                }
                .flatMap { (t, cancelled, done) =>
                    // The engine is told of the cancel by the ending, which finds the match recorded already.
                    cancelled.traverse_(endings.ended(gameId, _)) *> afterRecording(t, done)
                }
        }

    // ---- shared ----------------------------------------------------------------------------

    private def stateRepo(session: Session[IO]) = new TournamentStateRepo(session)

    /** Everyone's rating now, by entrant: a character entry's owner's, and the starting rating for anyone with none. */
    private def envFor(session: Session[IO], t: Tournament, game: Game, design: TournamentDesign): IO[Env] =
        for {
            ratings <- new EntrantRepo(session).ratingsNow(t.gameId, t.tournamentId)
        } yield Env(
          design = design,
          game = TournamentGame.of(game, t.settings.playersPerMatch).copy(hasRoleChoice = t.settings.hasRoleChoice),
          ratings = ratings.map((id, r) => id.value -> r),
          defaultPoolSize = Some(t.defaultPoolSize),
          hasConcurrentRounds = t.settings.hasConcurrentRounds
        )

    private def requireLoaded(session: Session[IO], t: Tournament): IO[Loaded] =
        requireState(t)(stateRepo(session).load(t.gameId, t.tournamentId))

    /* The state without the runs its (sub)tournaments have completed (`TournamentStateRepo.loadOpen`): what a round's
     * end and the next round's start read, rather than every match the tournament has played. */
    private def requireOpenLoaded(session: Session[IO], t: Tournament): IO[Loaded] =
        requireState(t)(stateRepo(session).loadOpen(t.gameId, t.tournamentId))

    private def requireState(t: Tournament)(read: IO[Option[Loaded]]): IO[Loaded] =
        IO.raiseWhen(t.startedAt.isEmpty)(ConflictError("this tournament has not started")) *>
            read.flatMap {
                case Some(l) => IO.pure(l)
                case None    => IO.raiseError(NotFoundError(s"no tournament ${t.tournamentId} in game ${t.gameId}"))
            }

    /* The rules' next state worked out from `open`, and the state to save it against -- or, where that finished a part
     * of the tournament whose completed runs `open` left out, which is ranked from every run it played, both worked
     * out again from the whole state: once for each (sub)tournament, as it ends. */
    private def workedOut(session: Session[IO], t: Tournament, open: Loaded)(
        next: TournamentState => TournamentState
    ): IO[(Loaded, TournamentState)] = {
        val fromOpen = next(open.state)
        if (!TournamentStateRepo.isWholeLoadNeeded(open, fromOpen)) IO.pure((open, fromOpen))
        else requireLoaded(session, t).map(whole => (whole, next(whole.state)))
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

    private def requireMatchForUpdate(session: Session[IO], t: Tournament, matchId: MatchId): IO[TournamentMatch] =
        new TournamentMatchRepo(session).readForUpdate(t.gameId, matchId).flatMap {
            case Some(m) if m.tournamentId == t.tournamentId => IO.pure(m)
            case _ => IO.raiseError(NotFoundError(s"no match ${matchId.value} in a round of this tournament"))
        }
}

object TournamentRounds {

    /** The seat whose turn it is to choose: the earliest in the order of choosing that has not, and has not forfeited.
      */
    def nextChooser(seats: List[TournamentSeat]): Option[TournamentSeat] =
        seats.filter(s => s.gameRoleId.isEmpty && !s.isForfeit && s.chooseOrder.isDefined).minByOption(_.chooseOrder)

    /** Seats after a choice: when one seat is left to choose and one role is left, it is given that role; otherwise the
      * next seat to choose has its clock started now.
      */
    def settleChoices(seats: List[TournamentSeat], allowed: List[GameRoleId], at: Instant): List[TournamentSeat] = {
        val left = seats.filter(s => s.gameRoleId.isEmpty && !s.isForfeit && s.chooseOrder.isDefined)
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
        if (nextChooser(seats).isEmpty || seats.count(!_.isForfeit) < math.max(required, 2)) TournamentMatchStatus.Due
        else TournamentMatchStatus.Choosing

    /** A match's clock: the tournament's round duration, spent as the game spends time (`Clock.forMatch`). `None` for a
      * tournament with no round duration.
      */
    def clockOf(t: Tournament, game: Game, tm: TournamentMatch): Option[MatchClock] =
        t.settings.roundDuration.map(d =>
            Clock.forMatch(
              game = TournamentGame.of(game, t.settings.playersPerMatch),
              d = d,
              seats = tm.seats.count(!_.isForfeit),
              tiesAllowed = tm.canTie,
              choosing = tm.seats.exists(_.chooseOrder.isDefined),
              t.settings.maxTurns
            )
        )

    /** When the seat whose turn it is to choose runs out of time to. */
    def chooseBy(t: Tournament, game: Game, tm: TournamentMatch): Option[Instant] =
        for {
            seat <- nextChooser(tm.seats)
            from <- seat.chooseStartedAt
            clock <- clockOf(t = t, game = game, tm = tm)
        } yield from.plus(Clock.toChoose(clock))

    /** The unit a tournament match's limit is shown in. */
    def unitFor(limit: Option[Duration]): TimeLimitUnit =
        limit match {
            case Some(d) if d.toHours >= 48 => TimeLimitUnit.Hours
            case _                          => TimeLimitUnit.Minutes
        }
}
