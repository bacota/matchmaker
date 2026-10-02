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
  */
class GameEngine[M <: MatchLike, S <: SeatLike, T <: TurnLike](
    game: Game[M, S, T],
    store: MatchStore[M],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: M => Unit = (_: M) => ()
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
        (for {
            clock <- TurnClock.of(request)
            made <- game.create(request, now())
        } yield clock.fold(made)(game.withClock(made, _))) match {
            case Left(why) => Left(Refusal.Invalid(why))
            case Right(created) =>
                store.create(created)
                announce(created)
                Right(
                  CreateGameResponse(
                    statusUrl = s"$base/matches/${created.matchId}/status",
                    playUrl = playUrl(created),
                    publicUrl = Option.when(created.isPublic)(s"$base/matches/${created.matchId}/board")
                  )
                )
        }

    def playUrl(m: M): String = s"$base/matches/${m.matchId}/play"

    /** The match as it stands now, with any turn that has run out recorded. */
    def read(matchId: String): Either[Refusal, M] = current(matchId).map(_.state)

    /** The match as it stands now: if this is a live match whose pending turn has run out, the forfeit is recorded —
      * atomically, so that it and a move racing it cannot both land — and the result reported to matchmaker.
      *
      * The answer says whether this call is what recorded it, because that is news to whoever else is watching the
      * match, and telling them is the caller's: see `EngineRoutes`.
      */
    def current(matchId: String): Either[Refusal, Settled[M]] = {
        // The time is asked for only of a live match: nothing else depends on it.
        lazy val at = now()
        store.get(matchId) match {
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
            val waiting = if (over) Set.empty[Long] else game.pending(m).map(_.participantId).toSet
            // Every seat under a chess clock, whose budget is worth showing running or not; only the seats being
            // waited on under a per-turn clock, since everyone else's next turn will get the whole limit anyway.
            val shown =
                if (over) Nil
                else if (clock.kind == ClockKind.Total) game.seats(m)
                else game.seats(m).filter(s => waiting(s.participantId))
            lazy val at = now()
            def millis(d: java.time.Duration) = math.max(0L, d.toMillis)
            ClockView(
              limitSeconds = clock.limitSeconds,
              kind = clock.kind.code,
              seats = shown.map { seat =>
                  val id = seat.participantId
                  val started = Option.when(waiting(id))(clock.startedFor(id, game.clockStartedAt(m))).flatten
                  val deadline = Option.when(waiting(id))(deadlineOf(m, clock, seat)).flatten
                  SeatClock(
                    participantId = id,
                    waiting = waiting(id),
                    running = deadline.isDefined,
                    startedAt = started,
                    remainingMillis = deadline
                        .map(d => millis(java.time.Duration.between(at, d)))
                        .orElse(
                          Option.when(clock.kind == ClockKind.Total)(millis(clock.allowance(id, game.turns(m))))
                        )
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
        GameStatusResponse(
          completed = over,
          participants = game.seats(m).map { seat =>
              EngineParticipantStatus(
                participantId = seat.participantId,
                pending = pending.contains(seat.participantId),
                completed = over,
                prevMoveAt = Some(game.clockStartedAt(m))
              )
          },
          // Strictly after `since`, so the turn matchmaker already has is not sent again — it
          // would be discarded there anyway, and the point of asking is to send what was missed.
          // No `since` means the whole game, which is what a matchmaker with nothing recorded for
          // this match is asking for.
          turns = engineTurns(game.turns(m).filter(t => since.forall(at => t.takenAt.isAfter(at)))),
          sequence = Some(game.sequence(m))
        )
    }

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
                  state =
                      Some(MoveState(game.sequence(m), game.pending(m).map(s => PendingSeat(s.participantId, clock))))
                )
              )
            )
        }

        if (applied.finished) reportResults(m)
    }

    /** Step 3, for a match that has just ended — by a move, or by its clock. */
    private def reportResults(m: M): Unit =
        m.resultsCallbackUrl.foreach(url =>
            bestEffort(s"reporting the result of match '${m.matchId}'")(matchmaker.recordResults(url, resultsOf(m)))
        )

    private def bestEffort(what: String)(call: => Unit): Unit =
        try call
        catch { case NonFatal(e) => Log.failure(e, what) }

    /** The finished match as matchmaker records it: rank 1 for the winner and 2 for the loser, or rank 1 for both in a
      * draw, which is what a rank means when nobody placed above anyone else.
      *
      * Every seat's scores carry its `outcome` (win/loss/draw), and whatever else the game keeps about it. A match
      * ended by its clock is a forfeit on every seat, as matchmaker records its own.
      */
    def resultsOf(m: M): MatchResults = {
        val forfeit = game.clock(m).exists(_.ranOut)
        MatchResults(
          game.seats(m).map { seat =>
              val outcome = game.outcome(m, seat)
              ResultEntry(
                participantId = seat.participantId,
                rank = if (outcome == Outcome.Loss) 2 else 1,
                scores = Map("outcome" -> ujson.Str(outcome.label)) ++ game.scores(m, seat),
                isWinner = outcome == Outcome.Win,
                forfeit = forfeit
              )
          },
          // Every turn, so that matchmaker records them with the results rather than relying on each
          // move callback having arrived. See `Protocol.MatchResults`.
          turns = Some(engineTurns(game.turns(m)))
        )
    }

    private def engineTurns(turns: List[T]): List[EngineTurn] =
        turns.sortBy(_.takenAt).map(t => EngineTurn(t.participantId, t.takenAt, Some(t.startedAt)))
}
