package com.vivi.matchmaker.service

import scala.util.Random
import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import java.time.{Duration, Instant}
import java.util.UUID
import com.vivi.matchmaker.ending.{MatchDue, MatchEndings}
import com.vivi.matchmaker.engine.RoleChoice
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    EntryRepo,
    FixtureRepo,
    GameApiKeyRepo,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    PlayerRepo,
    TextCodec,
    TournamentMatchRepo,
    TournamentParticipantRepo,
    TournamentRepo
}
import com.vivi.matchmaker.persistence.TournamentMatchRepo.SeatRow
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.tournament.{
    Advancement,
    FinalRanks,
    PlannedMatch,
    PlayedMatch,
    PoolSchedule,
    RoleMode,
    RoleSpec,
    SeatRole,
    Seeding,
    Standing,
    Standings
}

/** Playing a tournament's rounds (tournament-plan Phase 4): starting a round, making its matches, checking them against
  * their clocks, and finding when it is over.
  *
  *   - **Start round** settles who fills each of the round's slots — a seed's holder, a pool's finisher, or the fill
  *     rule's choice — copies the tournament's `live` onto the round, and queues one [[MatchDue]] per match its pools
  *     will play. The matches are made by the ending listener, in parallel (D5).
  *   - **Making a match** works out the pool's schedule from its slots alone, so a message names a match by its pool
  *     and number; the pool's number is the claim that makes it once however often the message arrives (D4).
  *   - **Completion** is checked whenever one of the round's matches is settled (D6): once every pool has played its
  *     matches — and, under `REMATCH`, its tie-breaks — the round is stamped complete, each pool's seeds are handed out
  *     again in its finishing order, and the owner is told.
  *   - **Check round** queues a check of every match that may have run out (D7); **Resume** queues again whatever of
  *     the round has not been made.
  *
  * A pool plays every way of seating its members together (see [[PoolSchedule]]); a pool with fewer members than a
  * match has seats plays nothing, and its lone member goes through. A match one of whose players has withdrawn, and
  * that has not been made yet, is not made: its seat plays as a bye.
  */
class TournamentPlayService[T](
    sessionPool: SessionPool,
    engines: GameEngineService[T],
    endings: MatchEndings,
    notifications: Notifications = Notifications.disabled
)(using codec: TextCodec[T]) {

    // ---- the owner's buttons ---------------------------------------------------------------------------

    /** The owner starting a round, with whatever they set for it over the tournament's settings. */
    def startRound(
        gameId: GameId,
        tournamentId: TournamentId,
        round: Int,
        overrides: RoundOverrides,
        callerExternalId: String
    ): IO[RoundWork] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    val fixtures = new FixtureRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournament(session, gameId, tournamentId, forUpdate = true)
                        _ <- requireOwner(t, caller)
                        settings <- IO.fromOption(t.elimination)(
                          ValidationError("a ladder's rounds are not started here")
                        )
                        _ <- IO.raiseUnless(t.started)(ConflictError("the tournament has not started"))
                        game <- requireGame(session, gameId)
                        // The first round may be laid out again with pools of another size; no later one may.
                        _ <- overrides.poolSize.filter(_ != settings.poolSize).traverse_ { size =>
                            if (round != 1)
                                IO.raiseError(ValidationError("a pool size can be set only for the first round"))
                            else relayOut(session, t, settings.copy(poolSize = size), game)
                        }
                        existing <- fixtures.readRoundForUpdate(gameId, tournamentId, round).flatMap {
                            case Some(r) => IO.pure(r)
                            case None    => IO.raiseError(NotFoundError(s"the tournament has no round $round"))
                        }
                        _ <- IO.raiseWhen(existing.started)(ConflictError(s"round $round has already started"))
                        previous <-
                            if (round == 1) IO.pure(None)
                            else fixtures.readRoundForUpdate(gameId, tournamentId, round - 1)
                        _ <- IO.raiseWhen(previous.exists(!_.completed))(
                          ConflictError(s"round ${round - 1} is not over yet")
                        )
                        _ <- validate(overrides, settings, round)
                        configured = existing.copy(
                          duration = overrides.duration.orElse(existing.duration),
                          rotations = overrides.rotations.orElse(existing.rotations),
                          minPoolAdvance = overrides.minPoolAdvance.orElse(existing.minPoolAdvance),
                          tiebreaker = overrides.tiebreaker.orElse(existing.tiebreaker)
                        )
                        _ <- fixtures.setRoundSettings(configured)
                        _ <- fill(session, t, settings, game, configured, previous)
                        _ <- fixtures.startRound(gameId, tournamentId, round, t.live)
                        started <- fixtures.readRoundForUpdate(gameId, tournamentId, round).map(_.get)
                        pools <- poolsOf(session, t, settings, game, started)
                    } yield pools.flatMap(p => p.owed.map(m => due(t, p.fixture, m.matchNo)))
                }
            }
            .flatMap(queue)
            // A round with nothing to play — every pool a lone player, or every match one a withdrawn player was in —
            // has no match whose ending would complete it, so it is looked at now.
            .flatTap(work => IO.whenA(work.queued == 0)(completeIfOver(gameId, tournamentId, round)))

    /** The owner asking for every match of a running round that may have run out to be checked against its clock (D7):
      * each one past a deadline, and every live one, whose deadlines matchmaker does not hold. A second press within a
      * minute of the last is refused.
      */
    def checkRound(gameId: GameId, tournamentId: TournamentId, round: Int, callerExternalId: String): IO[RoundWork] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    val fixtures = new FixtureRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournament(session, gameId, tournamentId, forUpdate = false)
                        _ <- requireOwner(t, caller)
                        r <- requireRunning(fixtures, gameId, tournamentId, round)
                        now <- new MatchRepo(session).now
                        _ <- IO.raiseWhen(r.checkedAt.exists(at => now.isBefore(at.plus(Duration.ofMinutes(1)))))(
                          ConflictError("the round is already being checked; try again in a minute")
                        )
                        _ <- fixtures.checkRound(gameId, tournamentId, round)
                        seats <- new TournamentMatchRepo(session).seatsOfRound(gameId, tournamentId, round)
                    } yield seats
                        .groupBy(_.matchId)
                        .values
                        .filter(match_ => !match_.head.completed && !match_.head.cancelled)
                        .filter(match_ => match_.head.live || match_.exists(_.overdue))
                        .map(_.head.matchId)
                        .toList
                }
            }
            .flatMap(ids => ids.traverse_(endings.check(gameId, _)).as(RoundWork(ids.size)))

    /** The owner queueing again every match of a running round that has not been made: for a start that failed partway.
      */
    def resume(gameId: GameId, tournamentId: TournamentId, round: Int, callerExternalId: String): IO[RoundWork] =
        sessionPool
            .use { session =>
                val fixtures = new FixtureRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournament(session, gameId, tournamentId, forUpdate = false)
                    _ <- requireOwner(t, caller)
                    settings <- IO.fromOption(t.elimination)(ValidationError("a ladder's rounds are not resumed here"))
                    game <- requireGame(session, gameId)
                    r <- requireRunning(fixtures, gameId, tournamentId, round)
                    pools <- poolsOf(session, t, settings, game, r)
                } yield pools.flatMap(p => p.owed.map(m => due(t, p.fixture, m.matchNo)))
            }
            .flatMap(queue)

    /** How every started round stands, for the tournament page: the matches made, and each pool's standings. */
    def progress(gameId: GameId, tournamentId: TournamentId): IO[TournamentProgress] =
        sessionPool.use { session =>
            new TournamentRepo(session).read(gameId, tournamentId).flatMap {
                case Some(t) if t.elimination.isDefined =>
                    for {
                        game <- requireGame(session, gameId)
                        rounds <- new FixtureRepo(session).listRounds(gameId, tournamentId)
                        pools <- rounds
                            .filter(_.started)
                            .flatTraverse(r => poolsOf(session, t, t.elimination.get, game, r))
                    } yield TournamentProgress(
                      pools.flatMap(p =>
                          p.made.toList.sortBy(_._1).map { (no, seats) =>
                              TournamentMatchView(
                                p.fixture.fixtureId,
                                no,
                                seats.head.matchId,
                                seats.head.completed,
                                seats.head.cancelled,
                                seats.map(s => TournamentSeatView(s.participantId, s.occupant, s.rank, s.manual))
                              )
                          }
                      ),
                      pools.map(p =>
                          PoolStandings(
                            p.fixture.fixtureId,
                            p.standings.map(s => StandingLine(s.member, s.points, s.differential))
                          )
                      )
                    )
                case _ => IO.pure(TournamentProgress())
            }
        }

    // ---- the listener's work ---------------------------------------------------------------------------

    /** Makes one match of a running round, unless it has been made already or is not to be (D4, D5).
      *
      * Its rows are written in one transaction, on a borrowed connection that is given back before the engine is asked;
      * the engine's half is [[GameEngineService.createFixtureMatch]]. Owed — and so delivered again — only when the
      * engine could not be reached.
      */
    def createMatch(message: MatchDue): IO[Settlement] = {
        val gameId = GameId(message.gameId)
        val tournamentId = TournamentId(message.tournamentId)
        val fixtureId = FixtureId(message.fixtureId)
        sessionPool
            .use(session =>
                session.transaction.use(_ => prepare(session, gameId, tournamentId, fixtureId, message.matchNo))
            )
            .recover {
                // Made by another delivery of the same message, between the look and the insert: the pool's number is
                // the claim (D4), and the transaction that lost it has rolled back.
                case e: skunk.exception.PostgresErrorException if e.code == "23505" => None
            }
            .flatMap {
                case None => IO.pure(Settlement.Settled)
                case Some(made) =>
                    engines
                        .createFixtureMatch(made.saved, made.game, made.players, made.roleChoice, made.apiKey)
                        .as(Settlement.Settled)
                        .handleError(e => Settlement.Owed(s"the engine did not make the game: $e"))
            }
    }

    /** Checks one match against its clock (D7). Never owed: a check that cannot reach the engine is not retried. */
    def check(gameId: GameId, matchId: MatchId): IO[Settlement] =
        engines
            .enforce(gameId, matchId)
            .handleErrorWith(e => IO.blocking(System.err.println(s"checking match ${matchId.value} failed: $e")))
            .as(Settlement.Settled)

    /** A tournament match has ended (D6): if its round is now over, it is completed. For `EndingService.settle`. */
    def matchSettled(gameId: GameId, matchId: MatchId): IO[Settlement] =
        sessionPool
            .use { session =>
                new MatchRepo(session).read(gameId, matchId).flatMap {
                    case Some(m) if m.fixture.isDefined =>
                        val f = m.fixture.get
                        new FixtureRepo(session)
                            .listFixtures(gameId, f.tournamentId)
                            .map(_.find(_.fixtureId == f.fixtureId).map(fx => (f.tournamentId, fx.round)))
                    case _ => IO.pure(None)
                }
            }
            .flatMap {
                case None                      => IO.pure(Settlement.Settled)
                case Some((tournament, round)) => completeIfOver(gameId, tournament, round).as(Settlement.Settled)
            }

    /** A tournament's field has changed — somebody withdrew — so a running round may have nothing left to play. */
    def fieldChanged(gameId: GameId, tournamentId: TournamentId): IO[Unit] =
        sessionPool
            .use(session => new FixtureRepo(session).listRounds(gameId, tournamentId))
            .flatMap(
              _.filter(r => r.started && !r.completed).traverse_(r => completeIfOver(gameId, tournamentId, r.round))
            )

    /** Completes a round if every pool has played what it owes; or, under `REMATCH`, queues the tie-breaks a pool still
      * needs. Under the round's lock, and stamped once.
      */
    def completeIfOver(gameId: GameId, tournamentId: TournamentId, round: Int): IO[Unit] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    val fixtures = new FixtureRepo(session)
                    for {
                        t <- requireTournament(session, gameId, tournamentId, forUpdate = false)
                        settings <- IO.fromOption(t.elimination)(
                          ValidationError("a ladder's rounds are not completed here")
                        )
                        game <- requireGame(session, gameId)
                        locked <- fixtures.readRoundForUpdate(gameId, tournamentId, round)
                        outcome <- locked match {
                            case Some(r) if r.started && !r.completed =>
                                poolsOf(session, t, settings, game, r).flatMap { pools =>
                                    val owed = pools.flatMap(p => p.owed.map(m => due(t, p.fixture, m.matchNo)))
                                    if (pools.forall(_.over)) complete(session, t, round, pools).as((true, Nil))
                                    else IO.pure((false, owed.filter(d => pools.exists(_.rematchNumbers(d)))))
                                }
                            case _ => IO.pure((false, Nil))
                        }
                    } yield (t, outcome)
                }
            }
            .flatMap { case (t, (completed, rematches)) =>
                queue(rematches) *> IO.whenA(completed)(roundComplete(t, round))
            }

    /** Tells the owner a round is over. After the commit, and unable to fail anything. */
    private def roundComplete(t: Tournament, round: Int): IO[Unit] =
        sessionPool.use(session => notifications.tournamentRoundComplete(session, t, round)).handleError(_ => ())

    // ---- the round's pools ------------------------------------------------------------------------------

    /** One pool of a running round: its members best seed first, the matches it plays, and what has been made of them.
      *
      * `regular` is its schedule. `rematches` are the tie-breaks `REMATCH` asks for, once every regular match is over
      * and a tie straddles the cut; numbered on after the regular ones.
      */
    private case class Pool(
        fixture: Fixture,
        members: List[TournamentParticipantId],
        seeds: Map[TournamentParticipantId, Int],
        regular: List[PlannedMatch[TournamentParticipantId]],
        rematches: List[PlannedMatch[TournamentParticipantId]],
        made: Map[Int, List[SeatRow]],
        withdrawn: Set[TournamentParticipantId],
        byScore: Boolean,
        scoreKey: Option[String]
    ) {
        def all: List[PlannedMatch[TournamentParticipantId]] = regular ++ rematches

        /** A match not made and not to be: one of its players has withdrawn. */
        private def skipped(m: PlannedMatch[TournamentParticipantId]) =
            !made.contains(m.matchNo) && m.seats.exists(s => withdrawn.contains(s.member))

        private def finished(m: PlannedMatch[TournamentParticipantId]) =
            made.get(m.matchNo).exists(seats => seats.head.completed || seats.head.cancelled)

        /** What the pool still has to make. */
        def owed: List[PlannedMatch[TournamentParticipantId]] =
            all.filter(m => !made.contains(m.matchNo) && !skipped(m))

        def over: Boolean = all.forall(m => finished(m) || skipped(m))

        def rematchNumbers(d: MatchDue): Boolean =
            d.fixtureId == fixture.fixtureId.value && rematches.exists(_.matchNo == d.matchNo)

        private def played(
            ms: List[PlannedMatch[TournamentParticipantId]]
        ): List[PlayedMatch[TournamentParticipantId]] =
            ms.flatMap(m => made.get(m.matchNo)).filter(seats => seats.head.completed || seats.head.cancelled).map {
                seats =>
                    PlayedMatch(
                      seats.flatMap(s => s.occupant.map(_ -> s.rank)).toMap,
                      seats.flatMap(s => s.occupant.zip(scoreKey.flatMap(k => numeric(s.scores.get(k))))).toMap
                    )
            }

        def standings: List[Standing[TournamentParticipantId]] =
            Standings.of(seeds, played(regular), byScore, played(rematches))

        def regularPlayed: List[PlayedMatch[TournamentParticipantId]] = played(regular)
    }

    private def numeric(v: Option[Any]): Option[Double] = v.collect {
        case d: Double => d
        case i: Int    => i.toDouble
        case l: Long   => l.toDouble
    }

    /** The pools of a started round, worked out from their slots and the matches made for them. */
    private def poolsOf(
        session: Session[IO],
        t: Tournament,
        settings: EliminationSettings,
        game: Game,
        round: TournamentRound
    ): IO[List[Pool]] = {
        val fixtures = new FixtureRepo(session)
        for {
            pools <- fixtures.listFixtures(t.gameId, t.tournamentId, round.round)
            slots <- fixtures.listSlots(t.gameId, t.tournamentId, round.round)
            rounds <- fixtures.listRounds(t.gameId, t.tournamentId)
            participants <- new TournamentParticipantRepo(session).list(t.gameId, t.tournamentId)
            seats <- new TournamentMatchRepo(session).seatsOfRound(t.gameId, t.tournamentId, round.round)
        } yield {
            val seedOf = participants.map(p => p.tournamentParticipantId -> p.seed).toMap
            val withdrawn = participants.filter(_.withdrawn).map(_.tournamentParticipantId).toSet
            val roles = game.roles
                .filterNot(_.optional)
                .sortBy(_.gameRoleId.value)
                .map(r => RoleSpec(r.name, r.preferred))
                .toList
            val rotations =
                round.rotations.getOrElse(
                  if (pairs(settings, round.round)) settings.eliminationRotations else t.rotations
                )
            val mode =
                if (rotations > 0) RoleMode.Rotate(rotations)
                else if (game.unimportantRoles) RoleMode.AtRandom
                else if (game.choosesRoles) RoleMode.Choose
                else RoleMode.BySeed
            val tiebreaker = round.tiebreaker.getOrElse(settings.tiebreaker)
            val last = rounds.map(_.round).maxOption.forall(_ == round.round)
            val advance = advanceOf(settings, round)
            pools.map { fixture =>
                val members = slots
                    .filter(_.fixtureId == fixture.fixtureId)
                    .flatMap(_.occupant)
                    .sortBy(m => seedOf.getOrElse(m, Int.MaxValue))
                val seeds = members.map(m => m -> seedOf.getOrElse(m, Int.MaxValue)).toMap
                // Seeded by the pool's id, so the same pool deals its random roles the same way whenever it is asked.
                val regular = PoolSchedule.matches(members, roles, mode, Random(fixture.fixtureId.value))
                val made = seats.filter(_.fixtureId == fixture.fixtureId).groupBy(_.matchNo)
                val base = Pool(
                  fixture,
                  members,
                  seeds,
                  regular,
                  Nil,
                  made,
                  withdrawn,
                  tiebreaker == Tiebreaker.Score,
                  game.scoreKey
                )
                val regularOver = regular.forall(m =>
                    made.get(m.matchNo).exists(s => s.head.completed || s.head.cancelled) ||
                        (!made.contains(m.matchNo) && m.seats.exists(s => withdrawn.contains(s.member)))
                )
                if (tiebreaker != Tiebreaker.Rematch || !regularOver) base
                else {
                    val cutoff = if (last) 1 else math.max(1, math.min(advance, members.size - 1))
                    val ties = Standings.tiesToBreak(seeds, base.regularPlayed, cutoff)
                    val rematches = ties
                        .foldLeft((regular.size, List.empty[PlannedMatch[TournamentParticipantId]])) {
                            case ((after, acc), group) =>
                                val more =
                                    PoolSchedule.rematches(group, roles, mode, Random(fixture.fixtureId.value), after)
                                (after + more.size, acc ++ more)
                        }
                        ._2
                    base.copy(rematches = rematches)
                }
            }
        }
    }

    /** Settles who fills each of `round`'s slots: the previous round's finishers and the fill rule, or the seeds —
      * after reseeding everybody, if the round is marked `reseed`.
      */
    private def fill(
        session: Session[IO],
        t: Tournament,
        settings: EliminationSettings,
        game: Game,
        round: TournamentRound,
        previous: Option[TournamentRound]
    ): IO[Unit] = {
        val fixtures = new FixtureRepo(session)
        for {
            held <- new TournamentParticipantRepo(session).listForUpdate(t.gameId, t.tournamentId)
            slots <- fixtures.listSlots(t.gameId, t.tournamentId, round.round)
            standings <- previous.traverse(r => poolsOf(session, t, settings, game, r))
            finishers = standings.toList.flatten.map(p => p.fixture.fixtureId -> p.standings).toMap
            advance = previous.fold(settings.minPoolAdvance)(advanceOf(settings, _))
            participants <-
                if (round.reseed) reseed(session, t, settings, game, round, held, slots, finishers, advance)
                else IO.pure(held)
            resolved = Advancement.resolve(
              slots,
              finishers,
              advance,
              participants.map(p => p.seed -> p.tournamentParticipantId).toMap,
              participants.filter(_.withdrawn).map(_.tournamentParticipantId).toSet
            )
            _ <- slots.traverse_(s =>
                resolved.get(s.slotId).traverse_(fixtures.fill(t.gameId, t.tournamentId, s.fixtureId, s.slotId, _))
            )
        } yield ()
    }

    /** Gives everybody a new seed as a `reseed` round starts, from their record in every round before it (D8): the
      * players who went through in the previous round take seeds 1 to however many of the round's slots name a seed, in
      * the order of their records, and everybody else follows in the order of theirs. The round's slots name seeds, so
      * the ones who went through are the ones placed, drawn by how well they have played.
      *
      * Answers the field with its new seeds.
      */
    private def reseed(
        session: Session[IO],
        t: Tournament,
        settings: EliminationSettings,
        game: Game,
        round: TournamentRound,
        field: List[TournamentParticipant],
        slots: List[FixtureSlot],
        finishers: Map[FixtureId, List[Standing[TournamentParticipantId]]],
        advance: Int
    ): IO[List[TournamentParticipant]] = {
        val repo = new TournamentParticipantRepo(session)
        val withdrawn = field.filter(_.withdrawn).map(_.tournamentParticipantId).toSet
        val seats = slots.count(_.source match {
            case SlotSource.Seed(_) => true
            case _                  => false
        })
        for {
            rounds <- new FixtureRepo(session).listRounds(t.gameId, t.tournamentId)
            earlier <- rounds
                .filter(r => r.round < round.round && r.started)
                .flatTraverse(r => poolsOf(session, t, settings, game, r))
            entries <- new EntryRepo(session).listForTournament(t.gameId, t.tournamentId)
            ratings <- field.traverse { p =>
                entries
                    .find(_.entryId == p.entryId)
                    .traverse(TournamentService.ratingOf[T](session, t.gameId, _))
                    .map(r => p.tournamentParticipantId -> r.getOrElse(EloRating.initial))
            }
            lines = earlier.flatMap(_.standings).groupBy(_.member)
            ratingOf = ratings.toMap
            records = field.map { p =>
                val id = p.tournamentParticipantId
                val own = lines.getOrElse(id, Nil)
                Seeding.Record(id, p.seed, own.map(_.points).sum, own.map(_.differential).sum, ratingOf(id))
            }
            through = Advancement.through(finishers, advance, seats, withdrawn).toSet
            (ahead, behind) = records.partition(r => through.contains(r.member))
            seeds = Seeding.reseed(ahead) ++ Seeding.reseed(behind).view.mapValues(_ + ahead.size)
            // In place: the unique constraint on a seed waits for the commit (V53).
            _ <- field.traverse_(p =>
                repo.setSeed(t.gameId, t.tournamentId, p.tournamentParticipantId, seeds(p.tournamentParticipantId))
            )
        } yield field.map(p => p.copy(seed = seeds(p.tournamentParticipantId)))
    }

    /** A playoff's rounds after its first are pairs, single elimination with its own rotations. */
    private def pairs(settings: EliminationSettings, round: Int): Boolean =
        settings.tournamentType == TournamentType.Playoff && round > 1

    /** How many of each of a round's pools go through: the round's own say, or the tournament's — one, from a pair. */
    private def advanceOf(settings: EliminationSettings, round: TournamentRound): Int =
        round.minPoolAdvance.getOrElse(if (pairs(settings, round.round)) 1 else settings.minPoolAdvance)

    /** Stamps a round complete, and hands each pool's seeds out again in its finishing order. */
    private def complete(session: Session[IO], t: Tournament, round: Int, pools: List[Pool]): IO[Unit] = {
        val participants = new TournamentParticipantRepo(session)
        for {
            _ <- participants.listForUpdate(t.gameId, t.tournamentId)
            // In place, two at a time where seeds swap: the unique constraint on a seed waits for the commit (V53).
            _ <- pools
                .flatMap(p => Seeding.withinPool(p.standings).toList)
                .traverse_((member, seed) => participants.setSeed(t.gameId, t.tournamentId, member, seed))
            _ <- new FixtureRepo(session).completeRound(t.gameId, t.tournamentId, round)
            rounds <- new FixtureRepo(session).listRounds(t.gameId, t.tournamentId)
            _ <- IO.whenA(rounds.map(_.round).maxOption.contains(round))(finish(session, t, pools))
        } yield ()
    }

    /** The last round is over (Phase 5): everybody's final rank, and the tournament ended.
      *
      * A round robin ranks by its one pool's standings. Single elimination ranks the final's players first, then the
      * consolation pool's, then everybody else by the round they reached, sharing a rank with the rest who went out in
      * the same round — see [[com.vivi.matchmaker.tournament.FinalRanks]].
      */
    private def finish(session: Session[IO], t: Tournament, lastPools: List[Pool]): IO[Unit] = {
        val participants = new TournamentParticipantRepo(session)
        for {
            field <- participants.list(t.gameId, t.tournamentId)
            slots <- new FixtureRepo(session).listSlots(t.gameId, t.tournamentId)
            fixtures <- new FixtureRepo(session).listFixtures(t.gameId, t.tournamentId)
            roundOf = fixtures.map(f => f.fixtureId -> f.round).toMap
            reached = slots
                .flatMap(s => s.occupant.map(_ -> roundOf(s.fixtureId)))
                .groupMapReduce(_._1)(_._2)(math.max)
            ranks =
                if (t.elimination.exists(_.tournamentType == TournamentType.RoundRobin))
                    FinalRanks.roundRobin(lastPools.flatMap(_.standings))
                else
                    FinalRanks.singleElimination(
                      lastPools.find(_.fixture.position == 1).toList.flatMap(_.standings),
                      lastPools.find(_.fixture.position == 2).toList.flatMap(_.standings),
                      // Everybody seeded is somewhere: one who never filled a slot went out before the first round.
                      field.map(p => p.tournamentParticipantId -> reached.getOrElse(p.tournamentParticipantId, 0)).toMap
                    )
            _ <- field.traverse_(p =>
                participants.setFinalRank(
                  t.gameId,
                  t.tournamentId,
                  p.tournamentParticipantId,
                  ranks.get(p.tournamentParticipantId)
                )
            )
            _ <- new TournamentRepo(session).end(t.gameId, t.tournamentId)
        } yield ()
    }

    /** Lays the tournament out again with pools of another size, before its first round starts. */
    private def relayOut(session: Session[IO], t: Tournament, settings: EliminationSettings, game: Game): IO[Unit] =
        for {
            _ <- IO.raiseWhen(settings.poolSize < 2 || settings.poolSize < game.roles.count(!_.optional))(
              ValidationError("a pool must hold at least two players, and every role the game requires")
            )
            _ <- IO.raiseWhen(settings.tournamentType == TournamentType.Playoff && settings.poolSize <= 2)(
              ValidationError("a playoff's pools hold more than two")
            )
            advance = math.min(settings.minPoolAdvance, settings.poolSize - 1)
            entrants <- new TournamentParticipantRepo(session).list(t.gameId, t.tournamentId)
            _ <- new FixtureRepo(session).deleteLayout(t.gameId, t.tournamentId)
            _ <- TournamentLayout.layOut(
              session,
              t.gameId,
              t.tournamentId,
              TournamentLayout.bracket(settings.copy(minPoolAdvance = advance), entrants.size)
            )
        } yield ()

    // ---- making a match ----------------------------------------------------------------------------------

    private case class Prepared(
        saved: Match,
        game: Game,
        players: List[com.vivi.matchmaker.engine.EnginePlayer],
        roleChoice: Option[RoleChoice],
        apiKey: Option[String]
    )

    /** Writes the match `matchNo` of a pool, and its seats — or nothing, if it is made already, not to be made, or its
      * round is not running.
      */
    private def prepare(
        session: Session[IO],
        gameId: GameId,
        tournamentId: TournamentId,
        fixtureId: FixtureId,
        matchNo: Int
    ): IO[Option[Prepared]] =
        new TournamentMatchRepo(session).existing(gameId, tournamentId, fixtureId, matchNo).flatMap {
            case Some(_) => IO.pure(None)
            case None =>
                for {
                    t <- requireTournament(session, gameId, tournamentId, forUpdate = false)
                    settings <- IO.fromOption(t.elimination)(ValidationError("not an elimination tournament"))
                    game <- requireGame(session, gameId)
                    fixture <- new FixtureRepo(session)
                        .listFixtures(gameId, tournamentId)
                        .map(_.find(_.fixtureId == fixtureId))
                    // Held against its completion, so a match is not made for a round that has just ended.
                    round <- fixture
                        .flatTraverse(f => new FixtureRepo(session).readRoundForShare(gameId, tournamentId, f.round))
                    pools <- round
                        .filter(r => r.started && !r.completed)
                        .traverse(r => poolsOf(session, t, settings, game, r))
                    planned = pools.toList.flatten
                        .find(_.fixture.fixtureId == fixtureId)
                        .flatMap(p => p.owed.find(_.matchNo == matchNo).map(m => (p, m)))
                    made <- planned.flatTraverse((pool, m) => write(session, t, game, round.get, pool, m))
                } yield made
        }

    private def write(
        session: Session[IO],
        t: Tournament,
        game: Game,
        round: TournamentRound,
        pool: Pool,
        planned: PlannedMatch[TournamentParticipantId]
    ): IO[Option[Prepared]] = {
        val matchId = MatchId(UUID.randomUUID().toString)
        for {
            entries <- new EntryRepo(session).listForTournament(t.gameId, t.tournamentId)
            participants <- new TournamentParticipantRepo(session).list(t.gameId, t.tournamentId)
            slots <- new FixtureRepo(session).listSlots(t.gameId, t.tournamentId, round.round)
            // Who plays each seat now: the entry's player, or the owner of its character as it is today (D10).
            seats <- planned.seats.traverse { seat =>
                val participant = participants.find(_.tournamentParticipantId == seat.member).get
                val entry = entries.find(_.entryId == participant.entryId).get
                val slot =
                    slots.find(s => s.fixtureId == pool.fixture.fixtureId && s.occupant.contains(seat.member)).get
                for {
                    owner <- entry.characterId match {
                        case None    => IO.pure(Some(entry.playerId))
                        case Some(c) => new CharacterRepo[T](session).read(c).map(_.flatMap(_.playerId))
                    }
                    player <- owner.flatTraverse(new PlayerRepo(session).read)
                } yield (seat, participant, entry, slot, player)
            }
            result <-
                if (seats.exists(_._5.isEmpty)) IO.pure(None) // a character with no owner: nobody to play it
                else {
                    val players = seats.map(_._5.get)
                    val duration = round.duration.getOrElse(t.roundDuration)
                    val limit = Duration.ofSeconds(math.max(1L, duration.getSeconds / 2))
                    val roleIds = game.roles.map(r => r.name -> r.gameRoleId).toMap
                    val m = Match(
                      gameId = t.gameId,
                      matchId = matchId,
                      challengeId = None,
                      creator = t.owner,
                      description =
                          s"${t.name}: round ${round.round}, pool ${pool.fixture.position}, match ${planned.matchNo}",
                      completedAt = None,
                      start = Instant.now(),
                      timeLimit = Some(limit),
                      settings = "{}",
                      isPublic = t.isPublic,
                      timeLimitKind = TimeLimitKind.Total,
                      timeLimitUnit = TimeLimitUnit.Seconds,
                      live = round.live,
                      // A player in two seats makes a match that cannot be rated, whatever the tournament is.
                      friendly = t.friendly || !EloRating.playersOnce(players.map(_.playerId)),
                      noTie = planned.noTie,
                      fixture = Some(MatchFixture(t.tournamentId, pool.fixture.fixtureId, planned.matchNo))
                    )
                    def roleOf(r: SeatRole) = r match {
                        case SeatRole.Assigned(name) => roleIds.get(name)
                        case SeatRole.ToChoose       => None
                    }
                    (for {
                        saved <- new MatchRepo(session).create(m)
                        starting <- EloRatingService.startingRatingsOf(
                          session,
                          t.gameId,
                          seats.map((seat, _, _, _, player) => (player.get.playerId, roleOf(seat.role))),
                          byRole = !game.unimportantRoles
                        )
                        written <- seats.traverse { (seat, participant, entry, slot, player) =>
                            val p = player.get
                            val role = roleOf(seat.role)
                            val row: Participant = entry.characterId match {
                                case Some(c) =>
                                    CharacterParticipant(
                                      ParticipantId(0),
                                      t.gameId,
                                      matchId,
                                      p.playerId,
                                      false,
                                      false,
                                      None,
                                      c,
                                      role
                                    )
                                case None =>
                                    PlainParticipant(
                                      ParticipantId(0),
                                      t.gameId,
                                      matchId,
                                      p.playerId,
                                      false,
                                      false,
                                      None,
                                      role
                                    )
                            }
                            new ParticipantRepo(session)
                                .create(
                                  row,
                                  starting.overall(p.playerId),
                                  role.flatMap(starting.inRole(p.playerId, _)),
                                  Some(
                                    TournamentSeat(
                                      t.tournamentId,
                                      pool.fixture.fixtureId,
                                      slot.slotId,
                                      participant.seed
                                    )
                                  )
                                )
                                .flatMap(written =>
                                    engines
                                        .fixturePlayer(
                                          session,
                                          written,
                                          p.externalId,
                                          p.nickname,
                                          role.flatMap(id => game.roles.find(_.gameRoleId == id).map(_.name))
                                        )
                                        .map(seat.member -> _)
                                )
                        }
                        apiKey <- new GameApiKeyRepo(session).forGame(t.gameId)
                    } yield {
                        val byMember = written.toMap
                        val mandatory = game.roles.filterNot(_.optional).sortBy(_.gameRoleId.value)
                        val choice = Option.when(planned.chooseOrder.nonEmpty)(
                          RoleChoice(
                            planned.chooseOrder.map(byMember(_).participantId),
                            mandatory.map(_.name).toList,
                            mandatory.map(r => r.name -> r.displayName).toMap
                          )
                        )
                        Some(Prepared(saved, game, written.map(_._2), choice, apiKey))
                    })
                }
        } yield result
    }

    // ---- rules -------------------------------------------------------------------------------------------

    private def validate(o: RoundOverrides, settings: EliminationSettings, round: Int): IO[Unit] = {
        val poolSize = if (pairs(settings, round)) 2 else o.poolSize.getOrElse(settings.poolSize)
        val problems = List(
          Option.when(o.duration.exists(_.getSeconds < 2))("a round must last at least two seconds"),
          Option.when(o.rotations.exists(_ < 0))("rotations cannot be negative"),
          Option.when(o.minPoolAdvance.exists(a => a < 1 || a >= poolSize))(
            "between one player and one fewer than a whole pool must go through"
          )
        ).flatten
        problems.headOption.fold(IO.unit)(why => IO.raiseError(ValidationError(why)))
    }

    private def due(t: Tournament, fixture: Fixture, matchNo: Int): MatchDue =
        MatchDue(t.gameId.value, t.tournamentId.value, fixture.fixtureId.value, matchNo)

    /** Queues each match to be made, after the commit that settled it, and answers how many. */
    private def queue(dues: List[MatchDue]): IO[RoundWork] = dues.traverse_(endings.due).as(RoundWork(dues.size))

    private def requireRunning(
        fixtures: FixtureRepo,
        gameId: GameId,
        tournamentId: TournamentId,
        round: Int
    ): IO[TournamentRound] =
        fixtures.listRounds(gameId, tournamentId).map(_.find(_.round == round)).flatMap {
            case None                   => IO.raiseError(NotFoundError(s"the tournament has no round $round"))
            case Some(r) if !r.started  => IO.raiseError(ConflictError(s"round $round has not started"))
            case Some(r) if r.completed => IO.raiseError(ConflictError(s"round $round is over"))
            case Some(r)                => IO.pure(r)
        }

    private def requireCaller(session: Session[IO], callerExternalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }

    private def requireTournament(
        session: Session[IO],
        gameId: GameId,
        id: TournamentId,
        forUpdate: Boolean
    ): IO[Tournament] = {
        val repo = new TournamentRepo(session)
        (if (forUpdate) repo.readForUpdate(gameId, id) else repo.read(gameId, id)).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no tournament ${id.value} in game ${gameId.value}"))
        }
    }

    private def requireOwner(t: Tournament, caller: Player): IO[Unit] =
        IO.raiseUnless(t.owner == caller.playerId)(UnauthorizedError("only the tournament's owner may do that"))

    /* The game, read plainly: the reference-table exception in CLAUDE.md, as `GameEngineService.requireGame` says. */
    private def requireGame(session: Session[IO], gameId: GameId): IO[Game] =
        new GameRepo[T](session).read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }
}
