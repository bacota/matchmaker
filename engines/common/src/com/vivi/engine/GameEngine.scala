package com.vivi.engine

import java.time.Instant
import scala.util.control.NonFatal
import Protocol._

/** What a successful move produced, for the caller to answer with and for the callbacks that report it.
  *
  * `turn` is the record the move just wrote — when it was made and when the mover's clock started for it. `next` is who
  * the move callback names, and `finished` means this move ended the match.
  */
case class MoveApplied[M, S, T](state: M, moved: S, turn: T, next: List[S], finished: Boolean)

/** A match as it stands once a read has recorded what it found — a turn that had run out ([[GameEngine.current]]), or a
  * player opening the board ([[GameEngine.opened]]). `changed` is whether this read is what recorded it, which is what
  * decides whether anybody watching is told.
  */
case class Settled[M](state: M, changed: Boolean)

/** The four exchanges of `interaction-design.txt` from an engine's side, for any [[Game]].
  *
  * Knows nothing about HTTP — an engine's `Routes` is what turns requests into these calls — and nothing about where
  * matches are kept or how matchmaker is reached, which is what lets a whole match be played through in a test with a
  * map and a recorder. What a move *is* belongs to the game: each engine decides its own moves through [[applyMove]].
  *
  * @param baseUrl
  *   the engine's own public base url, which is what the urls handed back to matchmaker in step 1 are built from. The
  *   engine cannot infer it: behind API Gateway the request's host is the gateway's, and matchmaker must be given a url
  *   that it and the players can actually reach.
  * @param announce
  *   called once with each new match, which is how the local server prints the play url and who is seated where.
  *
  * A live match (see [[Protocol.LiveTerms]]) differs in two ways, both kept here rather than in any game: no move is
  * reported to matchmaker, and its [[TurnClock]] is enforced. A player's clock starts only once they have opened the
  * board, which [[opened]] records. There is no timer to enforce it with — an engine runs only when asked something —
  * so a turn that has run out is noticed by the first request to look at the match: a read of any kind, matchmaker's
  * status call, or the late move itself. The play page counts down and asks again the moment its clock reaches nothing,
  * which is what makes "the first request" arrive on time while anybody is watching.
  *
  * A match created with roles still to choose ([[Protocol.RoleChoice]]) begins as a [[RoleChoosing]], kept in `roles`
  * rather than `store`, and becomes the game's match once every seat has a role. The choices are the match's first
  * turns: every sequence, turn list and role reported for the game's match afterwards includes them, which is why its
  * record is consulted beside the match. A match created with its roles settled has no such record, and is played
  * exactly as before.
  *
  * @param roles
  *   where matches choosing their roles are kept — see [[RoleChoosing]]
  */
class GameEngine[M <: MatchLike, S <: SeatLike, T <: TurnLike](
    game: Game[M, S, T],
    store: MatchStore[M],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: M => Unit = (_: M) => (),
    roles: MatchStore[RoleChoosing] = InMemoryMatchStore[RoleChoosing]()
) {

    private val base = baseUrl.stripSuffix("/")

    /** Step 1: create a game. The urls handed back are where matchmaker checks status, where the players play, and —
      * for a public game — where anyone may watch.
      *
      * One play url serves every player: it names the match and nothing else, and the engine works out whose seat it is
      * from whoever signed in. So matchmaker can hand the same url to everyone in the match, and a url that leaks is
      * not a seat that leaks — which matters all the more in a game where a seat is also the right to see a move the
      * other player cannot.
      */
    def createGame(request: CreateGameRequest): Either[Refusal, CreateGameResponse] =
        if (request.roleChoice.isDefined) createChoosing(request)
        else
            (for {
                clock <- TurnClock.of(request)
                made <- game.create(request, now())
            } yield clock.fold(made)(game.withClock(made, _))) match {
                case Left(why) => Left(Refusal.Invalid(why))
                case Right(made) =>
                    store.create(made)
                    announce(made)
                    Right(created(made.matchId, made.isPublic))
            }

    private def created(matchId: String, isPublic: Boolean): CreateGameResponse =
        CreateGameResponse(
          statusUrl = s"$base/matches/$matchId/status",
          playUrl = s"$base/matches/$matchId/play",
          publicUrl = Option.when(isPublic)(s"$base/matches/$matchId/board"),
          cancelUrl = Some(s"$base/matches/$matchId/cancel")
        )

    /** Step 1 for a match whose roles are still to be chosen: kept as a [[RoleChoosing]] until they are.
      *
      * The game is asked to create the match it would make with the choosers given free roles in order, and the answer
      * thrown away: what it would refuse — a fighter not built, too many players — is refused now, rather than once
      * everybody has chosen.
      */
    private def createChoosing(request: CreateGameRequest): Either[Refusal, CreateGameResponse] = {
        val at = now()
        (for {
            clock <- TurnClock.of(request)
            choosing <- RoleChoosing.start(request, at, clock)
            _ <- game.create(RoleChoosing.provisional(request), at)
        } yield choosing) match {
            case Left(why) => Left(Refusal.Invalid(why))
            case Right(choosing) =>
                roles.create(choosing)
                // Nothing to choose between, as when the one chooser has one role left: the game begins at once.
                if (choosing.settled) begin(choosing.matchId)
                Right(created(choosing.matchId, request.isPublic))
        }
    }

    def playUrl(m: M): String = s"$base/matches/${m.matchId}/play"

    /** Matchmaker has cancelled match `matchId`: it will never be played or archived, and is dropped. Answered the same
      * whether or not there was anything to drop, so that matchmaker retrying a cancel it did not hear answered is
      * harmless.
      */
    def cancel(matchId: String): Unit = {
        store.delete(matchId)
        roles.delete(matchId)
    }

    // ---- choosing roles ---------------------------------------------------------------------

    /** The match, while it is choosing its roles or if it ended doing so; `None` for a match whose game has begun, and
      * for every match that never chose.
      *
      * A live chooser whose clock has run out is recorded here, as [[current]] records a turn that has, and the result
      * reported. And a match whose roles are all chosen but whose game was never created — the engine stopped between
      * the two — has it created now.
      */
    def choosing(matchId: String): Option[RoleChoosing] =
        roles.get(matchId).flatMap { stored =>
            if (stored.finished) Option.when(stored.ended)(stored)
            else if (stored.settled) { begin(matchId); None }
            else Some(settleChoosing(stored))
        }

    /** The choosing match with a run-out chooser recorded, if the clock says one has run out. */
    private def settleChoosing(stored: RoleChoosing): RoleChoosing = {
        lazy val at = now()
        if (stored.clock.isEmpty || choosingTimedOut(stored, at).isEmpty) stored
        else
            roles
                .modify(stored.matchId) { latest =>
                    choosingTimedOut(latest, at) match {
                        case Some(ended) => (Some(ended), Some(ended))
                        case None        => (None, None)
                    }
                }
                .flatten
                .map { ended =>
                    reportChoosing(ended)
                    ended
                }
                .getOrElse(roles.get(stored.matchId).getOrElse(stored))
    }

    /** The choosing match ended by its clock, if the chooser has run out of time at `at`. */
    private def choosingTimedOut(c: RoleChoosing, at: Instant): Option[RoleChoosing] =
        for {
            clock <- c.clock
            chooser <- c.chooser
            deadline <- clock.deadlineFor(chooser, c.turnStartedAt, c.choices)
            if !at.isBefore(deadline)
        } yield c.copy(clock = Some(clock.copy(timedOut = List(chooser))), finished = true)

    /** A player choosing a role, or conceding, in a match choosing its roles — atomically, as a move is. The choice is
      * reported to matchmaker as a move, and the one that settles the last role begins the game.
      */
    def choose(matchId: String, cognitoId: String, request: ChooseRequest): Either[Refusal, RoleChoosing] = {
        val at = now()

        def decide(stored: RoleChoosing): Either[Refusal, RoleChoosing] =
            for {
                seat <- stored.request.players
                    .find(_.cognitoId == cognitoId)
                    .toRight(Refusal.NotYours(s"'$cognitoId' has no ${game.seatName} in match '$matchId'"))
                id = seat.participantId
                _ <- Either.cond(!stored.ended, (), Refusal.Invalid("this match is already over"))
                _ <- Either.cond(!stored.finished, (), Refusal.Invalid("the roles have been chosen"))
                // Choosing is done from the board, so a chooser not yet seen to open it has now.
                clock = stored.clock.map(_.opening(id, at))
                decided <-
                    if (request.concede)
                        Either.cond(
                          game.concedes,
                          stored.copy(clock = clock, conceded = Some(id), finished = true),
                          Refusal.Invalid("this game cannot be conceded")
                        )
                    else
                        for {
                            role <- request.role.toRight(Refusal.Invalid("say which role to choose"))
                            _ <- Either.cond(
                              stored.chooser.contains(id),
                              (),
                              Refusal.Invalid(
                                stored.chooser
                                    .map(c => s"it is ${stored.nameOf(c)}'s turn to choose a role")
                                    .getOrElse("there is no role to choose")
                              )
                            )
                            _ <- Either.cond(stored.free.contains(role), (), Refusal.Invalid(s"'$role' is not free"))
                        } yield {
                            val started = TurnClock.turnStart(clock, id, stored.turnStartedAt)
                            val next = stored.copy(choices = stored.choices :+ RoleChosen(id, role, at, started))
                            next.copy(clock = clock, autoAssigned = next.lastRole)
                        }
            } yield decided

        val outcome = roles.modify(matchId) { stored =>
            choosingTimedOut(stored, at) match {
                case Some(ended) =>
                    (Some(ended), Left(Refusal.TimedOut("the time to choose ran out; the match is over by forfeit")))
                case None =>
                    decide(stored) match {
                        case Right(next)   => (Some(next), Right(next))
                        case Left(refusal) => (None, Left(refusal))
                    }
            }
        }

        outcome.toRight(Refusal.NotFound(s"no match '$matchId'")).flatten match {
            case Right(next) if next.ended =>
                reportChoosing(next)
                Right(next)
            case Right(next) =>
                val begun = if (next.settled) begin(matchId) else None
                val latest = roles.get(matchId).getOrElse(next)
                notifyChoice(latest, latest.choices.last, begun)
                Right(latest)
            case Left(refusal: Refusal.TimedOut) =>
                roles.get(matchId).foreach(reportChoosing)
                Left(refusal)
            case Left(refusal) => Left(refusal)
        }
    }

    /** The game's match, created from a choosing match whose roles are settled — once, however many callers race to it.
      *
      * Created as of the last choice, so the first move's clock starts there. A live match's clock comes with it, with
      * what each chooser spent carried onto their chess-clock budget. The record is marked finished only after the game
      * exists, so that an engine stopping between the two leaves it to be done again by [[choosing]].
      */
    private def begin(matchId: String): Option[M] =
        roles.get(matchId).filter(c => c.settled && !c.ended).flatMap { c =>
            val existing = store.get(matchId)
            val made = existing.orElse {
                game.create(c.roled, c.turnStartedAt) match {
                    case Left(why) =>
                        Log.failure(
                          IllegalStateException(why),
                          s"beginning match '$matchId' once its roles were chosen"
                        )
                        None
                    case Right(m) =>
                        val carried = c.order.map(id => Carried(id, c.spentBy(id).toMillis)).filter(_.millis > 0)
                        val clocked = c.clock.fold(m)(clock => game.withClock(m, clock.copy(carried = carried)))
                        try {
                            store.create(clocked)
                            announce(clocked)
                            Some(clocked)
                        } catch { case _: ConcurrentModification => store.get(matchId) }
                }
            }
            made.foreach(_ =>
                roles.modify(matchId)(latest =>
                    (Some(latest.copy(finished = true, autoAssigned = latest.autoAssigned.orElse(latest.lastRole))), ())
                )
            )
            made
        }

    /** A seated player has opened the board of a match choosing its roles: in a live match, when their clock may start.
      * Whether this call recorded it, as [[opened]].
      */
    def openedChoosing(matchId: String, participantId: Long): Boolean = {
        lazy val at = now()
        def opening(c: RoleChoosing): Option[RoleChoosing] =
            for {
                clock <- c.clock
                if !c.finished && !clock.hasOpened(participantId)
            } yield c.copy(clock = Some(clock.opening(participantId, at)))
        roles.get(matchId).flatMap(opening).isDefined &&
        roles.modify(matchId)(latest => opening(latest).fold((None, false))(seen => (Some(seen), true))).contains(true)
    }

    /** What the choosing page shows a viewer — `viewer` their seat, absent on the public board. */
    def choosingView(c: RoleChoosing, viewer: Option[Long]): ChoosingView =
        ChoosingView(
          choosing = true,
          you = viewer,
          chooser = c.chooser,
          free = c.free.map(r => RoleOffer(r, c.displayName(r))),
          seats = c.request.players.map(p =>
              ChoosingSeat(
                p.participantId,
                c.nameOf(p.participantId),
                c.roles.get(p.participantId).map(c.displayName),
                c.chooser.contains(p.participantId)
              )
          ),
          done = c.finished && !c.ended,
          ended = Option.when(c.ended)(choosingSummary(c, html = false)),
          clock = c.clock.map(clock =>
              clockViewOf(
                clock,
                over = c.ended || c.finished,
                waiting = c.chooser.toSet,
                seats = c.request.players.map(_.participantId),
                turnStartedAt = c.turnStartedAt,
                turns = c.choices
              )
          ),
          canConcede = game.concedes && viewer.isDefined && !c.ended && !c.finished
        )

    /** The seat a signed-in player holds in a match choosing its roles. */
    def choosingSeatOf(c: RoleChoosing, cognitoId: String): Either[Refusal, Long] =
        c.request.players
            .find(_.cognitoId == cognitoId)
            .map(_.participantId)
            .toRight(Refusal.NotYours(s"'$cognitoId' has no ${game.seatName} in match '${c.matchId}'"))

    /** Matchmaker's status call, for a match choosing its roles: the chooser pending, and the choices as turns. */
    def choosingStatus(c: RoleChoosing, since: Option[Instant] = None): GameStatusResponse =
        GameStatusResponse(
          completed = c.ended,
          participants = c.request.players.map(p =>
              EngineParticipantStatus(
                participantId = p.participantId,
                pending = c.chooser.contains(p.participantId),
                completed = c.ended,
                prevMoveAt = Some(c.turnStartedAt),
                role = c.roles.get(p.participantId)
              )
          ),
          turns = engineTurns(c.choices.filter(t => since.forall(at => t.takenAt.isAfter(at)))),
          sequence = Some(c.choices.size.toLong)
        )

    /** A choice, reported as a move. `begun` is the game it began, if it was the last: who is pending then is the
      * game's.
      */
    private def notifyChoice(c: RoleChoosing, chosen: RoleChosen, begun: Option[M]): Unit =
        c.request.moveCallbackUrl.filter(_ => c.clock.isEmpty).foreach { url =>
            val (pending, since) = begun match {
                case Some(m) => (game.pending(m).map(_.participantId), game.clockStartedAt(m))
                case None    => (c.chooser.toList, c.turnStartedAt)
            }
            bestEffort(s"reporting a role chosen in match '${c.matchId}'")(
              matchmaker.recordMove(
                url,
                MoveNotification(
                  participantId = chosen.participantId,
                  next = pending,
                  takenAt = chosen.takenAt,
                  startedAt = chosen.startedAt,
                  state = Some(MoveState(c.choices.size.toLong, pending.map(PendingSeat(_, since)), c.seatRoles))
                )
              )
            )
        }

    /** The results of a match that ended while its roles were being chosen. */
    def choosingResults(c: RoleChoosing): MatchResults =
        MatchResults(
          c.request.players.map { p =>
              val outcome = c.outcomeOf(p.participantId).getOrElse(Outcome.Draw)
              ResultEntry(
                participantId = p.participantId,
                rank = if (outcome == Outcome.Loss) 2 else 1,
                scores = Map("outcome" -> ujson.Str(outcome.label)),
                isWinner = outcome == Outcome.Win,
                forfeit = c.ranOut,
                role = c.roles.get(p.participantId)
              )
          },
          turns = Some(engineTurns(c.choices)),
          summary = Some(choosingSummary(c, html = true))
        )

    private def choosingSummary(c: RoleChoosing, html: Boolean): String = {
        def who(id: Long) = if (html) ResultText.name(Some(c.nameOf(id)), "a player") else c.nameOf(id)
        (c.clock.toList.flatMap(_.timedOut), c.conceded) match {
            case (late :: _, _)  => s"${who(late)} ran out of time choosing a role."
            case (_, Some(gone)) => s"${who(gone)} conceded before the game began."
            case _               => "The match ended before the game began."
        }
    }

    private def reportChoosing(c: RoleChoosing): Unit =
        c.request.resultsCallbackUrl.foreach(url =>
            bestEffort(s"reporting the result of match '${c.matchId}'")(
              matchmaker.recordResults(url, choosingResults(c))
            )
        )

    /** The choosing that came before the game's match, if it had one. */
    private def chosenBefore(m: M): Option[RoleChoosing] = roles.get(m.matchId)

    /** The match as it stands now, with any turn that has run out recorded. `archived` is a request's word that the
      * match has been archived, which reads the archive first — see [[MatchStore.getArchived]].
      */
    def read(matchId: String, archived: Boolean = false): Either[Refusal, M] = current(matchId, archived).map(_.state)

    /** The match as it stands now: if this is a live match whose pending turn has run out, the forfeit is recorded —
      * atomically, so that it and a move racing it cannot both land — and the result reported to matchmaker.
      *
      * The answer says whether this call is what recorded it, because that is news to whoever else is watching the
      * match, and telling them is the caller's: see `EngineRoutes`.
      */
    def current(matchId: String, archived: Boolean = false): Either[Refusal, Settled[M]] = {
        // The time is asked for only of a live match: nothing else depends on it.
        lazy val at = now()
        (if (archived) store.getArchived(matchId) else store.get(matchId)) match {
            case None => Left(Refusal.NotFound(s"no match '$matchId'"))
            case Some(m) if game.clock(m).isEmpty || timedOut(m, at).isEmpty => Right(Settled(m, changed = false))
            case Some(_) =>
                store
                    .modify(matchId) { latest =>
                        // Decided again against the stored match: a move may have landed since the read above,
                        // and a request racing this one may have recorded the same forfeit already.
                        timedOut(latest, at) match {
                            case Some(ended) => (Some(ended), Settled(ended, changed = true))
                            case None        => (None, Settled(latest, changed = false))
                        }
                    }
                    .toRight(Refusal.NotFound(s"no match '$matchId'"))
                    .map { settled =>
                        if (settled.changed) reportResults(settled.state)
                        settled
                    }
        }
    }

    /** The match ended by its clock, if it is a live match in which some pending seat has run out of time at `at`.
      *
      * The match ended the moment the first clock ran out, however much later this is noticed — nothing runs to notice
      * it sooner. So the seat whose deadline came first loses, and every other seat wins: one whose clock ran out after
      * that moment, one still running, and one that has not started because its player has not opened the board. Two
      * seats that ran out at the same instant both lose.
      */
    private def timedOut(m: M, at: Instant): Option[M] =
        for {
            clock <- game.clock(m)
            if !game.isOver(m)
            due = game.pending(m).flatMap(seat => deadlineOf(m, clock, seat).map(seat -> _))
            first <- due.map(_._2).minOption
            if !at.isBefore(first)
            late = due.collect { case (seat, d) if d == first => seat }
        } yield game.markCompleted(game.withClock(m, clock.copy(timedOut = late.map(_.participantId))))

    /** When a seat's clock runs out on the turn now being played; `None` while its player has not opened the board. */
    private def deadlineOf(m: M, clock: TurnClock, seat: S): Option[Instant] =
        clock.deadlineFor(seat.participantId, game.clockStartedAt(m), game.turns(m))

    /** A seated player has opened the board of match `matchId`: in a live match, the moment their clock may start.
      *
      * Recorded once, the first time, atomically with any other change; a match that is not live, is over, or has seen
      * this player already is answered as it stands. Like [[current]], the answer says whether this call recorded it,
      * since a clock starting is news to the other players watching.
      */
    def opened(matchId: String, seat: S): Either[Refusal, Settled[M]] = {
        lazy val at = now()
        def opening(m: M): Option[M] =
            for {
                clock <- game.clock(m)
                if !game.isOver(m) && !clock.hasOpened(seat.participantId)
            } yield game.withClock(m, clock.opening(seat.participantId, at))

        store.get(matchId) match {
            case None                          => Left(Refusal.NotFound(s"no match '$matchId'"))
            case Some(m) if opening(m).isEmpty => Right(Settled(m, changed = false))
            case Some(_) =>
                store
                    .modify(matchId) { latest =>
                        opening(latest) match {
                            case Some(seen) => (Some(seen), Settled(seen, changed = true))
                            case None       => (None, Settled(latest, changed = false))
                        }
                    }
                    .toRight(Refusal.NotFound(s"no match '$matchId'"))
        }
    }

    /** The clock of a live match as its play page shows it, as of now; `None` for a match that is not live. */
    def clockView(m: M): Option[ClockView] =
        game.clock(m).map { clock =>
            val over = game.isOver(m)
            clockViewOf(
              clock,
              over,
              waiting = if (over) Set.empty[Long] else game.pending(m).map(_.participantId).toSet,
              seats = game.seats(m).map(_.participantId),
              turnStartedAt = game.clockStartedAt(m),
              turns = game.turns(m)
            )
        }

    /** A clock as a page shows it: of the game's match, or of a match choosing its roles. */
    private def clockViewOf(
        clock: TurnClock,
        over: Boolean,
        waiting: Set[Long],
        seats: List[Long],
        turnStartedAt: Instant,
        turns: List[TurnLike]
    ): ClockView = {
        // Every seat under a chess clock, whose budget is worth showing running or not; only the seats being
        // waited on under a per-turn clock, since everyone else's next turn will get the whole limit anyway.
        val shown =
            if (over) Nil
            else if (clock.kind == ClockKind.Total) seats
            else seats.filter(waiting)
        lazy val at = now()
        def millis(d: java.time.Duration) = math.max(0L, d.toMillis)
        ClockView(
          limitSeconds = clock.limitSeconds,
          kind = clock.kind.code,
          seats = shown.map { id =>
              val started = Option.when(waiting(id))(clock.startedFor(id, turnStartedAt)).flatten
              val deadline = Option.when(waiting(id))(clock.deadlineFor(id, turnStartedAt, turns)).flatten
              SeatClock(
                participantId = id,
                waiting = waiting(id),
                running = deadline.isDefined,
                startedAt = started,
                remainingMillis = deadline
                    .map(d => millis(java.time.Duration.between(at, d)))
                    .orElse(Option.when(clock.kind == ClockKind.Total)(millis(clock.allowance(id, turns))))
              )
          },
          timedOut = clock.timedOut
        )
    }

    /** The signed-in player's seat in this match.
      *
      * Not found is a 403 rather than a 404: the caller is somebody, just not somebody playing this match, and a
      * spectator asking for a player's view is refused rather than told the match does not exist.
      */
    def seatOf(m: M, cognitoId: String): Either[Refusal, S] =
        game
            .seats(m)
            .find(_.cognitoId == cognitoId)
            .toRight(Refusal.NotYours(s"'$cognitoId' has no ${game.seatName} in match '${m.matchId}'"))

    /** Step 4's other half: what matchmaker asks for when a participant hits refresh.
      *
      * Every seat the game says is pending is reported so — one in a game of turns, both at once in a game where nobody
      * waits — and `prevMoveAt` is when the clock started for them, which matchmaker turns into a deadline using the
      * match's own time limit.
      *
      * `since` is the last turn matchmaker has recorded; the moves made after it come back in `turns`. That is how a
      * chess-clock limit is charged — matchmaker needs every move's cost, not just the current one — and it is also how
      * a move callback that was lost is recovered as more than a corrected deadline.
      */
    def status(matchId: String, since: Option[Instant] = None): Either[Refusal, GameStatusResponse] =
        read(matchId).map(statusOf(_, since))

    /** [[status]], of a match already in hand. */
    def statusOf(m: M, since: Option[Instant] = None): GameStatusResponse = {
        val over = game.isOver(m)
        val pending = game.pending(m).map(_.participantId).toSet
        // The roles chosen before the game began, if they were: its first turns, and every seat's role.
        val before = chosenBefore(m)
        val turns: List[TurnLike] = before.toList.flatMap(_.choices) ++ game.turns(m)
        GameStatusResponse(
          completed = over,
          participants = game.seats(m).map { seat =>
              EngineParticipantStatus(
                participantId = seat.participantId,
                pending = pending.contains(seat.participantId),
                completed = over,
                prevMoveAt = Some(game.clockStartedAt(m)),
                role = before.flatMap(_.roles.get(seat.participantId))
              )
          },
          // Strictly after `since`, so the turn matchmaker already has is not sent again — it
          // would be discarded there anyway, and the point of asking is to send what was missed.
          // No `since` means the whole game, which is what a matchmaker with nothing recorded for
          // this match is asking for.
          turns = engineTurns(turns.filter(t => since.forall(at => t.takenAt.isAfter(at)))),
          sequence = Some(sequenceOf(m, before))
        )
    }

    /** The match's sequence as matchmaker counts it: the game's moves, after any choices of role before them. */
    private def sequenceOf(m: M, before: Option[RoleChoosing]): Long =
        game.sequence(m) + before.map(_.choices.size.toLong).getOrElse(0L)

    /** A player's move, decided by `decide` against the stored match — atomically, so that two players moving at once
      * cannot both be told they were first — and then, having committed, reported to matchmaker.
      *
      * `decide` is given the match, the caller's seat in it and the time of the move, and answers with the match as the
      * move leaves it and the record of the move, or why the move is refused. It must decide and nothing else: the
      * store may run it more than once under contention, which is the whole point of re-reading.
      *
      * The callbacks are made after the write rather than inside it, since a callback is not something to make twice.
      * The cost is that a crash between the two leaves matchmaker behind, which is exactly what its `refresh` exists to
      * repair — step 4 is the engine's permission to be imperfect here.
      *
      * In a live match, a move made once the turn has run out is refused with [[Refusal.TimedOut]], and the forfeit it
      * found is recorded in its place — in the same write, so that a move and the clock cannot both win.
      */
    def applyMove(matchId: String, cognitoId: String)(
        decide: (M, S, Instant) => Either[Refusal, (M, T)]
    ): Either[Refusal, MoveApplied[M, S, T]] = {
        val at = now()

        def attempt(stored: M): (Option[M], Either[Refusal, MoveApplied[M, S, T]]) = {
            val decision =
                for {
                    seat <- seatOf(stored, cognitoId)
                    // A move is made from the board, so a mover who had not been seen to open it has now.
                    current = game
                        .clock(stored)
                        .fold(stored)(c => game.withClock(stored, c.opening(seat.participantId, at)))
                    decided <- decide(current, seat, at)
                } yield {
                    val (played, turn) = decided
                    val finished = game.isOver(played)
                    val settled = if (finished) game.markCompleted(played) else played
                    MoveApplied(settled, seat, turn, next(current, settled, seat), finished)
                }

            decision match {
                case Right(applied) => (Some(applied.state), Right(applied))
                case Left(refusal)  => (None, Left(refusal))
            }
        }

        val outcome = store.modify(matchId) { current =>
            timedOut(current, at) match {
                case Some(ended) =>
                    (
                      Some(ended),
                      Left(Refusal.TimedOut("the turn ran out before this move was made; the match is over by forfeit"))
                    )
                case None => attempt(current)
            }
        }

        outcome.toRight(Refusal.NotFound(s"no match '$matchId'")).flatten match {
            case Right(applied) =>
                notify(applied)
                Right(applied)
            case Left(refusal: Refusal.TimedOut) =>
                // Read back rather than carried out of the write: the match is over, so nothing can have
                // changed it since, and the results are what the forfeit owes matchmaker.
                store.get(matchId).foreach(reportResults)
                Left(refusal)
            case Left(refusal) => Left(refusal)
        }
    }

    /** Who a move callback names, from the pending seats before the move and after it.
      *
      * Matchmaker clears the mover, makes everyone named pending from the move, and leaves everyone else alone. So a
      * seat is named when it has to be for matchmaker to arrive at the game's own answer: the mover, if it is to move
      * again; a seat that was not waiting before; and a seat whose clock has started again. A seat that was already
      * waiting, on a clock that has not restarted, keeps it by not being named.
      *
      * That is the next player in a game of turns, nobody on the first throw of a simultaneous game, and both corners
      * when a round resolves and the next begins. It is decided from the pending lists and the clock rather than from
      * the clock alone, since on a clock that does not move — as in a test — a move made at the instant the match was
      * created would otherwise look like one that restarted everybody's.
      */
    private def next(before: M, after: M, mover: S): List[S] = {
        val waiting = game.pending(before).map(_.participantId).toSet
        val restarted = game.clockStartedAt(after) != game.clockStartedAt(before)
        game
            .pending(after)
            .filter(s => s.participantId == mover.participantId || !waiting.contains(s.participantId) || restarted)
    }

    /** Steps 2 and 3, in that order: every move is reported, and the move that ends the match is followed by the
      * results.
      *
      * The move callback is sent for the last move too — with nobody in `next` — since matchmaker clears the mover's
      * pending flag from it, and the results callback that follows completes every seat.
      *
      * Neither callback may fail the move. It is committed by the time they are sent, so a failure that escaped here
      * would answer the player with a 500 for a move that stands — and their retry would then be refused. A lost
      * callback is what matchmaker's `refresh` repairs, so each is logged and dropped on its own: the results must
      * still be sent when the move before them could not be.
      *
      * A live match reports no moves at all, whatever url it was given: its turns are the engine's to run, and
      * matchmaker hears about it once it is over. The url is kept all the same, since boxing finds matchmaker by it.
      */
    private def notify(applied: MoveApplied[M, S, T]): Unit = {
        val m = applied.state
        val clock = game.clockStartedAt(m)

        m.moveCallbackUrl.filter(_ => game.clock(m).isEmpty).foreach { url =>
            val before = chosenBefore(m)
            bestEffort(s"reporting a move in match '${m.matchId}'")(
              matchmaker.recordMove(
                url,
                MoveNotification(
                  participantId = applied.moved.participantId,
                  next = applied.next.map(_.participantId),
                  takenAt = applied.turn.takenAt,
                  startedAt = applied.turn.startedAt,
                  // The whole of who is to move now, numbered: what lets matchmaker ignore this callback
                  // if it lands after a later one. See `Protocol.MoveState`.
                  state = Some(
                    MoveState(
                      sequenceOf(m, before),
                      game.pending(m).map(s => PendingSeat(s.participantId, clock)),
                      before.toList.flatMap(_.seatRoles)
                    )
                  )
                )
              )
            )
        }

        if (applied.finished) reportResults(m)
    }

    /** Step 3, for a match that has just ended — by a move, or by its clock — and then its archive.
      *
      * In that order, and the archive only after the results have been sent: matchmaker archives only a match it knows
      * is over. A results callback that failed leaves the archive refused too, and both are repaired later — the
      * results by matchmaker's refresh, the archive by [[archiveIfFinished]] when matchmaker next asks for the status.
      */
    private def reportResults(m: M): Unit = {
        m.resultsCallbackUrl.foreach(url =>
            bestEffort(s"reporting the result of match '${m.matchId}'")(matchmaker.recordResults(url, resultsOf(m)))
        )
        ArchivingMatchStore.bestEffort(m.matchId)(finish(m.matchId))
    }

    /** Archives a finished match, and drops the record of its choosing, if it had one: everything matchmaker is owed
      * about it has been sent with its results.
      */
    private def finish(matchId: String): Unit = {
        store.finished(matchId)
        roles.delete(matchId)
    }

    /** Archives `m` if it is over and still has a live copy here — for a status call, which is how matchmaker prompts
      * an engine whose archiving failed when the match ended. Nothing, for a match still being played or already
      * archived.
      */
    def archiveIfFinished(m: M): Unit =
        if (game.isOver(m)) ArchivingMatchStore.bestEffort(m.matchId)(finish(m.matchId))

    private def bestEffort(what: String)(call: => Unit): Unit =
        try call
        catch { case NonFatal(e) => Log.failure(e, what) }

    /** The finished match as matchmaker records it: each seat ranked by its [[Game.placing]] — for a game of two sides,
      * rank 1 for the winner and 2 for the loser, or rank 1 for both in a draw.
      *
      * Every seat's scores carry its `outcome` (win/loss/draw), and whatever else the game keeps about it. A match
      * ended by its clock is a forfeit on every seat, as matchmaker records its own.
      */
    def resultsOf(m: M): MatchResults = {
        val forfeit = game.clock(m).exists(_.ranOut)
        val before = chosenBefore(m)
        MatchResults(
          game.seats(m).map { seat =>
              val outcome = game.outcome(m, seat)
              ResultEntry(
                participantId = seat.participantId,
                rank = game.placing(m, seat),
                scores = Map("outcome" -> ujson.Str(outcome.label)) ++ game.scores(m, seat),
                isWinner = outcome == Outcome.Win,
                forfeit = forfeit,
                role = before.flatMap(_.roles.get(seat.participantId))
              )
          },
          // Every turn, so that matchmaker records them with the results rather than relying on each
          // move callback having arrived — the choices of role before the game included. See
          // `Protocol.MatchResults`.
          turns = Some(engineTurns(before.toList.flatMap(_.choices) ++ game.turns(m))),
          summary = game.summary(m)
        )
    }

    private def engineTurns(turns: List[TurnLike]): List[EngineTurn] =
        turns.sortBy(_.takenAt).map(t => EngineTurn(t.participantId, t.takenAt, Some(t.startedAt)))
}
