package com.vivi.tictactoe

import java.time.Instant
import Protocol._

/** Why a request was refused. Transport-independent so that the local server and the Lambda
  * handler map it to a status code the same way.
  */
enum Refusal(val status: Int, val message: String) {
  case NotFound(what: String) extends Refusal(404, what)
  case NotYours(what: String) extends Refusal(403, what)
  case Invalid(what: String) extends Refusal(400, what)
}

/** What a successful move produced, for the caller to answer with and for the callbacks below. */
case class MoveApplied(state: TicTacToeMatch, moved: Seat, next: Option[Seat], finished: Boolean)

/** The game itself: the four exchanges of `interaction-design.txt` from the engine's side.
  *
  * Knows nothing about HTTP — [[Routes]] is what turns requests into these calls — and nothing
  * about where matches are kept or how matchmaker is reached, which is what lets the whole thing
  * be played through in a test with a map and a recorder.
  *
  * @param baseUrl the engine's own public base url, which is what the urls handed back to
  *                matchmaker in step 1 are built from. The engine cannot infer it: behind API
  *                Gateway the request's host is the gateway's, and matchmaker must be given a url
  *                that it and the players can actually reach.
  * @param announce called once with each new match, which is how the local server prints the
  *                 board's url and who is seated where.
  */
class Engine(
    store: MatchStore,
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: TicTacToeMatch => Unit = _ => ()
) {

  private val base = baseUrl.stripSuffix("/")

  /** Step 1: create a game. The urls handed back are where matchmaker checks status, where the
    * players play, and — for a public game — where anyone may watch.
    *
    * One play url serves both players: it names the match and nothing else, and the engine works
    * out whose seat it is from whoever signed in. So matchmaker can hand the same url to
    * everyone in the match, and a url that leaks is not a seat that leaks.
    */
  def createGame(request: CreateGameRequest): Either[Refusal, CreateGameResponse] =
    TicTacToeMatch.create(request, now()) match {
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

  def playUrl(m: TicTacToeMatch): String = s"$base/matches/${m.matchId}/play"

  def read(matchId: String): Either[Refusal, TicTacToeMatch] =
    store.get(matchId).toRight(Refusal.NotFound(s"no match '$matchId'"))

  /** The signed-in player's seat in this match.
    *
    * Not found is a 403 rather than a 404: the caller is somebody, just not somebody playing this
    * match, and a spectator asking for a player's view is refused rather than told the match does
    * not exist.
    */
  def seatOf(m: TicTacToeMatch, cognitoId: String): Either[Refusal, Seat] =
    m.seatFor(cognitoId).toRight(Refusal.NotYours(s"'$cognitoId' has no seat in match '${m.matchId}'"))

  /** Step 4's other half: what matchmaker asks for when a participant hits refresh.
    *
    * `pending` is the seat whose turn it is, and `prevMoveAt` is when the move before it was made
    * — matchmaker turns that into a deadline using the match's own time limit. A seat that has
    * not been reached yet reports the match's creation time, so the first player's clock starts
    * when the game was created rather than never.
    */
  def status(matchId: String): Either[Refusal, GameStatusResponse] =
    read(matchId).map { m =>
      val over = m.isOver
      GameStatusResponse(
        completed = over,
        participants = m.seats.map { seat =>
          EngineParticipantStatus(
            participantId = seat.participantId,
            pending = !over && m.turn == seat.mark,
            completed = over,
            prevMoveAt = Some(m.lastMoveAt.getOrElse(m.createdAt))
          )
        }
      )
    }

  /** A player's move. Decides against the stored board — atomically, so that two players moving
    * at once cannot both be told they were first — and then, having committed, calls matchmaker.
    *
    * The callbacks are made after the write rather than inside it: the store may run the decision
    * more than once under contention, and a callback is not something to make twice. The cost is
    * that a crash between the two leaves matchmaker behind, which is exactly what its `refresh`
    * exists to repair — step 4 is the engine's permission to be imperfect here.
    */
  def move(matchId: String, cognitoId: String, cell: Int): Either[Refusal, MoveApplied] = {
    val at = now()

    val outcome = store.modify(matchId) { current =>
      val decision =
        for {
          seat <- seatOf(current, cognitoId)
          _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
          _ <- Either.cond(current.turn == seat.mark, (), Refusal.Invalid(s"it is ${current.turn}'s turn, not ${seat.mark}'s"))
          board <- current.board.place(cell, seat.mark).left.map(Refusal.Invalid.apply)
        } yield {
          val played = current.copy(board = board, turn = seat.mark.other, lastMoveAt = Some(at))
          val finished = played.isOver
          // `completed` is stored so a finished match stays finished even though it is also
          // derivable — it is what the results callback keys off, and it is written once.
          val settled = played.copy(completed = finished)
          MoveApplied(settled, seat, Option.unless(finished)(settled.seatOf(settled.turn)).flatten, finished)
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

  /** Steps 2 and 3, in that order: every move is reported, and the move that ends the match is
    * followed by the results.
    *
    * Sending the move callback for the last move too — with nobody in `next` — is deliberate:
    * matchmaker clears the mover's pending flag from it, and the results callback that follows
    * completes every seat. A results callback alone would leave the sequence uneven for no gain.
    */
  private def notify(applied: MoveApplied): Unit = {
    val m = applied.state

    m.moveCallbackUrl.foreach { url =>
      matchmaker.recordMove(
        url,
        MoveNotification(
          participantId = applied.moved.participantId,
          next = applied.next.map(_.participantId).toList,
          prevMoveAt = m.lastMoveAt
        )
      )
    }

    if (applied.finished) m.resultsCallbackUrl.foreach(url => matchmaker.recordResults(url, resultsOf(m)))
  }

  /** The finished match as matchmaker records it: rank 1 for the winner and 2 for the loser, or
    * rank 1 for both in a draw, which is what a rank means when nobody placed above anyone else.
    */
  def resultsOf(m: TicTacToeMatch): MatchResults =
    MatchResults(
      m.seats.map { seat =>
        val won = m.winner.contains(seat.mark)
        val drew = m.isDraw
        ResultEntry(
          participantId = seat.participantId,
          rank = if (won || drew) 1 else 2,
          scores = Map(
            "outcome" -> ujson.Str(if (won) "win" else if (drew) "draw" else "loss"),
            "moves" -> ujson.Num(m.moveCount(seat.mark).toDouble),
            "mark" -> ujson.Str(seat.mark.toString)
          ),
          isWinner = won
        )
      }
    )

  /** The state a board page renders. `seat` is the viewer's own, absent on the public board. */
  def stateOf(m: TicTacToeMatch, seat: Option[Seat]): StateResponse =
    StateResponse(
      matchId = m.matchId,
      board = m.board.encoded,
      turn = Option.unless(m.isOver)(m.turn.toString),
      you = seat.map(_.mark.toString),
      completed = m.isOver,
      winner = m.winner.map(_.toString),
      draw = m.isDraw,
      winningLine = m.board.winningLine,
      players = m.seats.map(s => SeatView(s.mark.toString, s.cognitoId, s.participantId, m.moveCount(s.mark)))
    )
}
