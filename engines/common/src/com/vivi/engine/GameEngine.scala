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
        game.create(request, now()) match {
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

    def read(matchId: String): Either[Refusal, M] =
        store.get(matchId).toRight(Refusal.NotFound(s"no match '$matchId'"))

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
        read(matchId).map { m =>
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
      */
    def applyMove(matchId: String, cognitoId: String)(
        decide: (M, S, Instant) => Either[Refusal, (M, T)]
    ): Either[Refusal, MoveApplied[M, S, T]] = {
        val at = now()

        val outcome = store.modify(matchId) { current =>
            val decision =
                for {
                    seat <- seatOf(current, cognitoId)
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

        outcome.toRight(Refusal.NotFound(s"no match '$matchId'")).flatten.map { applied =>
            notify(applied)
            applied
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
      */
    private def notify(applied: MoveApplied[M, S, T]): Unit = {
        val m = applied.state
        val clock = game.clockStartedAt(m)

        m.moveCallbackUrl.foreach { url =>
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

        if (applied.finished)
            m.resultsCallbackUrl.foreach(url =>
                bestEffort(s"reporting the result of match '${m.matchId}'")(matchmaker.recordResults(url, resultsOf(m)))
            )
    }

    private def bestEffort(what: String)(call: => Unit): Unit =
        try call
        catch { case NonFatal(e) => Log.failure(e, what) }

    /** The finished match as matchmaker records it: rank 1 for the winner and 2 for the loser, or rank 1 for both in a
      * draw, which is what a rank means when nobody placed above anyone else.
      *
      * Every seat's scores carry its `outcome` (win/loss/draw), and whatever else the game keeps about it.
      */
    def resultsOf(m: M): MatchResults =
        MatchResults(
          game.seats(m).map { seat =>
              val outcome = game.outcome(m, seat)
              ResultEntry(
                participantId = seat.participantId,
                rank = if (outcome == Outcome.Loss) 2 else 1,
                scores = Map("outcome" -> ujson.Str(outcome.label)) ++ game.scores(m, seat),
                isWinner = outcome == Outcome.Win
              )
          },
          // Every turn, so that matchmaker records them with the results rather than relying on each
          // move callback having arrived. See `Protocol.MatchResults`.
          turns = Some(engineTurns(game.turns(m)))
        )

    private def engineTurns(turns: List[T]): List[EngineTurn] =
        turns.sortBy(_.takenAt).map(t => EngineTurn(t.participantId, t.takenAt, Some(t.startedAt)))
}
