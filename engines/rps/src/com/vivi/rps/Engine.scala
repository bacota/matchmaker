package com.vivi.rps

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

/** What a successful throw produced, for the caller to answer with and for the callbacks below.
  *
  * `finished` means this throw was the second one, so the match resolved on it — which is the
  * only way a match here ever ends. `turn` is the throw itself, which the move callback reports:
  * what it was, when it was made, and when this player's clock started for it.
  */
case class MoveApplied(state: RpsMatch, moved: Seat, finished: Boolean, turn: ThrowRecord)

/** The game itself: the four exchanges of `interaction-design.txt` from the engine's side.
  *
  * Knows nothing about HTTP — [[Routes]] is what turns requests into these calls — and nothing
  * about where matches are kept or how matchmaker is reached, which is what lets the whole thing
  * be played through in a test with a map and a recorder.
  *
  * What this engine exists to exercise, and tic-tac-toe does not, is a game with no turn order.
  * Both seats are pending from the moment the match is created; either player may throw first;
  * neither is told what the other threw until both have; and the match resolves on the second
  * throw rather than on anybody's move in particular. Matchmaker already models that — its move
  * callback takes a *list* of seats to make pending and leaves unnamed seats alone, and
  * `MatchSummary.whoseTurn` is a list — so this is the game that proves those are real.
  *
  * @param baseUrl the engine's own public base url, which is what the urls handed back to
  *                matchmaker in step 1 are built from. The engine cannot infer it: behind API
  *                Gateway the request's host is the gateway's, and matchmaker must be given a url
  *                that it and the players can actually reach.
  * @param announce called once with each new match, which is how the local server prints the
  *                 play url and who is seated where.
  */
class Engine(
    store: MatchStore,
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: RpsMatch => Unit = _ => ()
) {

  private val base = baseUrl.stripSuffix("/")

  /** Step 1: create a game. The urls handed back are where matchmaker checks status, where the
    * players play, and — for a public game — where anyone may watch.
    *
    * One play url serves both players: it names the match and nothing else, and the engine works
    * out whose seat it is from whoever signed in. So matchmaker can hand the same url to
    * everyone in the match, and a url that leaks is not a seat that leaks — which matters more
    * here than in a game played on an open board, since a seat is also the right to see a throw.
    */
  def createGame(request: CreateGameRequest): Either[Refusal, CreateGameResponse] =
    RpsMatch.create(request, now()) match {
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

  def playUrl(m: RpsMatch): String = s"$base/matches/${m.matchId}/play"

  def read(matchId: String): Either[Refusal, RpsMatch] =
    store.get(matchId).toRight(Refusal.NotFound(s"no match '$matchId'"))

  /** The signed-in player's seat in this match.
    *
    * Not found is a 403 rather than a 404: the caller is somebody, just not somebody playing this
    * match, and a spectator asking for a player's view is refused rather than told the match does
    * not exist.
    */
  def seatOf(m: RpsMatch, cognitoId: String): Either[Refusal, Seat] =
    m.seatFor(cognitoId).toRight(Refusal.NotYours(s"'$cognitoId' has no seat in match '${m.matchId}'"))

  /** Step 4's other half: what matchmaker asks for when a participant hits refresh.
    *
    * Every seat that has not thrown is pending, which at the start of a match is both of them —
    * this is the shape of status that a turn-taking game never produces, and matchmaker records
    * it as two seats on the clock at once.
    *
    * `prevMoveAt` is when the seat's clock started, and here that is the match's creation for
    * both seats however late either of them throws. Nobody is waiting for a predecessor: a player
    * who takes an hour to decide spends an hour of their own budget, and the other player's
    * deadline is not moved by it.
    *
    * `since` is the last turn matchmaker has recorded; the throws made after it come back in
    * `turns`. That is how a chess-clock limit is charged, and it is also how a move callback that
    * was lost is recovered as more than a corrected deadline.
    */
  def status(matchId: String, since: Option[Instant] = None): Either[Refusal, GameStatusResponse] =
    read(matchId).map { m =>
      val over = m.isOver
      GameStatusResponse(
        completed = over,
        participants = m.seats.map { seat =>
          EngineParticipantStatus(
            participantId = seat.participantId,
            pending = !over && !m.hasThrown(seat),
            completed = over,
            prevMoveAt = Some(m.createdAt)
          )
        },
        // Strictly after `since`, so the turn matchmaker already has is not sent again — it
        // would be discarded there anyway, and the point of asking is to send what was missed.
        // No `since` means the whole game, which is what a matchmaker with nothing recorded for
        // this match is asking for.
        turns = m.throws
          .filter(t => since.forall(at => t.takenAt.isAfter(at)))
          .sortBy(_.takenAt)
          .map(t => EngineTurn(t.participantId, t.takenAt, Some(t.startedAt)))
      )
    }

  /** A player's throw. Decides against the stored match — atomically, so that two players
    * throwing at the same moment cannot both be recorded as the first — and then, having
    * committed, calls matchmaker.
    *
    * Simultaneity is the whole game, so this is the one engine where the store's compare-and-set
    * earns its keep on the ordinary path rather than on a rare race: two players clicking at once
    * is what is *expected* to happen, and both throws must land.
    *
    * The callbacks are made after the write rather than inside it: the store may run the decision
    * more than once under contention, and a callback is not something to make twice. The cost is
    * that a crash between the two leaves matchmaker behind, which is exactly what its `refresh`
    * exists to repair — step 4 is the engine's permission to be imperfect here.
    */
  def move(matchId: String, cognitoId: String, shape: Shape): Either[Refusal, MoveApplied] = {
    val at = now()

    val outcome = store.modify(matchId) { current =>
      val decision =
        for {
          seat <- seatOf(current, cognitoId)
          _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
          // No "it is not your turn" here: it is always both players' turn. The only move a
          // player cannot make is a second one — changing a throw once it is in would let
          // whoever moved last win every match.
          _ <- Either.cond(
            !current.hasThrown(seat),
            (),
            Refusal.Invalid("you have already thrown; a throw cannot be taken back")
          )
        } yield {
          // Both clocks started when the match was created — see `status`.
          val record = ThrowRecord(seat.participantId, shape, at, current.createdAt)
          val played = current.copy(throws = current.throws :+ record)
          val finished = played.isOver
          // `completed` is stored so a finished match stays finished even though it is also
          // derivable — it is what the results callback keys off, and it is written once.
          MoveApplied(played.copy(completed = finished), seat, finished, record)
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

  /** Steps 2 and 3, in that order: every throw is reported, and the throw that resolves the match
    * is followed by the results.
    *
    * `next` is always empty, including on the first throw of the two. The seat still to throw has
    * been pending since the match was created and stays pending by not being named — matchmaker
    * leaves a participant it is told nothing about alone. Naming it would re-stamp its clock as
    * starting at the other player's throw, and in a game where nobody waits for anybody that
    * would charge the slower player for the faster one's thinking.
    */
  private def notify(applied: MoveApplied): Unit = {
    val m = applied.state

    m.moveCallbackUrl.foreach { url =>
      matchmaker.recordMove(
        url,
        MoveNotification(
          participantId = applied.moved.participantId,
          next = Nil,
          takenAt = applied.turn.takenAt,
          // The match's own start, for either seat: both clocks began there, and saying so is
          // what stops matchmaker charging the second thrower from the first one's throw.
          startedAt = applied.turn.startedAt
        )
      )
    }

    if (applied.finished) m.resultsCallbackUrl.foreach(url => matchmaker.recordResults(url, resultsOf(m)))
  }

  /** The finished match as matchmaker records it: rank 1 for the winner and 2 for the loser, or
    * rank 1 for both in a draw, which is what a rank means when nobody placed above anyone else.
    *
    * The throws are in the scores, because this is the first moment they may be told at all and
    * because a result nobody can read back is not much of a record.
    */
  def resultsOf(m: RpsMatch): MatchResults =
    MatchResults(
      m.seats.map { seat =>
        val outcome = m.outcomeFor(seat)
        ResultEntry(
          participantId = seat.participantId,
          rank = if (outcome == Outcome.Loss) 2 else 1,
          scores = Map(
            "outcome" -> ujson.Str(outcome.label),
            "throw" -> m.throwOf(seat).map(t => ujson.Str(t.shape.toString)).getOrElse(ujson.Null),
            "side" -> ujson.Str(seat.side.toString)
          ),
          isWinner = outcome == Outcome.Win
        )
      }
    )

  /** The state a play page renders. `seat` is the viewer's own, absent on the public board.
    *
    * The other player's throw is in the answer only once the match is over. Until then a viewer
    * is told that a seat *has* thrown and nothing more — which is as much as is safe to say and
    * as much as the page needs to show that it is waiting rather than broken.
    */
  def stateOf(m: RpsMatch, seat: Option[Seat]): StateResponse = {
    val over = m.isOver
    StateResponse(
      matchId = m.matchId,
      waitingFor = m.pending.map(_.side.toString),
      you = seat.map(_.side.toString),
      yourThrow = seat.flatMap(m.throwOf).map(_.shape.toString),
      completed = over,
      winner = Option.when(over)(m.winner.map(_.side.toString)).flatten,
      draw = m.isDraw,
      players = m.seats.map(s =>
        SeatView(
          side = s.side.toString,
          cognitoId = s.cognitoId,
          participantId = s.participantId,
          thrown = m.hasThrown(s),
          shape = Option.when(over)(m.throwOf(s).map(_.shape.toString)).flatten
        )
      )
    )
  }
}
