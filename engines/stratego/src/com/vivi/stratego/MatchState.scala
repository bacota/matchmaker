package com.vivi.stratego

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant
import com.vivi.engine.{Game, MatchLike, Outcome, ResultText, SeatLike, TurnClock, TurnLike}

/** One player's seat in a match.
  *
  * `cognitoId` is who may move in it — the same subject the player signs in as, which is how matchmaker named them and
  * how the engine recognises them. `participantId` is matchmaker's key for the seat and is what every callback quotes
  * back. `nickname` is what the player is shown as, as matchmaker named them when the match was created; a match stored
  * before it was sent has none, and shows the subject instead.
  */
case class Seat(side: Side, cognitoId: String, participantId: Long, nickname: Option[String] = None) extends SeatLike

/** One turn that was taken: a side's deployment when `step` is empty, a move of one piece otherwise — with the battle
  * it caused, if it attacked — or, with `concession`, a side giving the match up.
  *
  * A setup is a turn like any other as far as matchmaker is concerned: it is what the player was pending on, and its
  * time is charged to them. So is a concession, which is the conceding player's last act in the match, and may be made
  * whether or not it was their turn. `concession` defaults to false, so a match stored before there were any reads as
  * it was written.
  *
  * `setup` is the army a deployment put down — its ranks in the order of [[Side.homeSquares]], as it was submitted — so
  * that the match can be replayed from its opening position. It is never shown to the opponent while the match is being
  * played. A match deployed before it was kept has `None`, and nothing reads it to decide anything.
  */
case class MoveRecord(
    participantId: Long,
    side: Side,
    takenAt: Instant,
    startedAt: Instant,
    step: Option[Step] = None,
    battle: Option[Battle] = None,
    concession: Boolean = false,
    setup: Option[List[Rank]] = None
) extends TurnLike {
    def isSetup: Boolean = step.isEmpty && !concession
}

/** How a finished match ended, as the scores and the page say it. */
enum Ending(val label: String) {
    case FlagTaken extends Ending("flag")
    case NoMoves extends Ending("no-moves")
    case MoveCap extends Ending("cap")
    case Forfeit extends Ending("forfeit")
    case Conceded extends Ending("conceded")
}

/** A match in progress, and everything needed to answer for it or to call matchmaker back.
  *
  * One board holds both armies, ranks and all: what each viewer may see of it is decided when it is read, by
  * [[Engine.stateOf]], never by what is stored. Everything else — the phase, whose turn it is, whether the match is
  * over — is derived from the board and the turns rather than kept beside them, where the two could disagree.
  *
  * `maxMoves` is how many piece moves, by both sides together, end the match as a draw. It has no default, so it is
  * always stored: a match keeps the cap it was created with, whatever the default becomes later. `clock` is a live
  * match's turn clock, and `None` for every other match.
  *
  * `noTie` is a match matchmaker asked to end with somebody ahead: the cap does not apply to it, and it runs until a
  * flag is taken, a side cannot move, a side concedes, or a clock runs out. Defaulted, so a match stored before it was
  * kept reads back capped, as it was played.
  */
case class StrategoMatch(
    matchId: String,
    seats: List[Seat],
    board: Board,
    turns: List[MoveRecord],
    isPublic: Boolean,
    completed: Boolean,
    createdAt: Instant,
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    maxMoves: Int,
    clock: Option[TurnClock] = None,
    // What the match's pages are titled by (`MatchTitle`). Defaulted, so a match stored before they
    // were kept reads back as it was, and is titled by the game alone.
    override val gameDisplayName: Option[String] = None,
    override val description: Option[String] = None,
    noTie: Boolean = false
) extends MatchLike {

    def seatOf(side: Side): Option[Seat] = seats.find(_.side == side)

    /** The seat belonging to a signed-in player, if they have one in this match. */
    def seatFor(cognitoId: String): Option[Seat] = seats.find(_.cognitoId == cognitoId)

    def hasDeployed(side: Side): Boolean = turns.exists(t => t.side == side && t.isSetup)

    /** Both armies are on the board, and the setup phase is over. */
    def inPlay: Boolean = Side.values.forall(hasDeployed)

    /** The piece moves, without the two setups or a concession. */
    def moves: List[MoveRecord] = turns.filter(_.step.isDefined)

    /** The side that gave the match up, if one did. */
    def conceded: Option[Side] = turns.find(_.concession).map(_.side)

    /** Red moves first, and the sides alternate. */
    def toMove: Side = if (moves.size % 2 == 0) Side.Red else Side.Blue

    /** A side's own moves, oldest first — what the two-square rule reads. */
    def history(side: Side): List[Step] = moves.filter(_.side == side).flatMap(_.step)

    def legalMoves(side: Side): List[(Int, Int)] = Rules.legalMoves(board, side, history(side))

    private def stuck(side: Side): Boolean = legalMoves(side).isEmpty

    /** Whether a live match's clock ended this one. */
    def ranOut: Boolean = clock.exists(_.ranOut)

    /** Why the match is over, or `None` while it is not.
      *
      * In order: the clock, which overrides the position; a concession, which may come at any point, setup included; a
      * flag taken; the side to move having nothing it may move; and the move cap, unless the match is `noTie`.
      *
      * Neither side being able to move stays a draw even in a `noTie` match: nothing in the position says who should
      * win it, and it is rare enough to leave to whatever asked for the match.
      */
    def ending: Option[Ending] =
        if (ranOut) Some(Ending.Forfeit)
        else if (conceded.isDefined) Some(Ending.Conceded)
        else if (!inPlay) None
        else if (Side.values.exists(s => !board.hasFlag(s))) Some(Ending.FlagTaken)
        else if (stuck(toMove)) Some(Ending.NoMoves)
        else if (!noTie && moves.sizeIs >= maxMoves) Some(Ending.MoveCap)
        else None

    def isOver: Boolean = ending.isDefined

    /** Whoever took the flag, or whoever's opponent could not move — unless neither side could, which is a draw. In a
      * match the clock ended, whoever did not run out; in one conceded, whoever did not concede.
      */
    def winner: Option[Side] =
        ending match {
            case Some(Ending.Forfeit) =>
                seats.find(s => clock.flatMap(_.outcomeOf(s.participantId)).contains(Outcome.Win)).map(_.side)
            case Some(Ending.Conceded)                        => conceded.map(_.other)
            case Some(Ending.FlagTaken)                       => Side.values.find(board.hasFlag)
            case Some(Ending.NoMoves) if !stuck(toMove.other) => Some(toMove.other)
            case _                                            => None
        }

    /** Not a match the clock ended with nobody winning: two players who both ran out both lost. */
    def isDraw: Boolean = isOver && !ranOut && winner.isEmpty

    /** How many enemy pieces `side` has taken: every battle it won, and every one where both pieces fell. */
    def captured(side: Side): Int =
        moves.flatMap(m => m.battle.map(m.side -> _)).count { (attacker, battle) =>
            battle.result match {
                case Result.AttackerWins => attacker == side
                case Result.DefenderWins => attacker != side
                case Result.BothLost     => true
            }
        }

    /** The board as the two armies were deployed, before any piece moved — where a replay starts — or `None` until both
      * are down.
      *
      * From the armies the setups kept, when both did. A match deployed before they were kept is rebuilt instead, which
      * it can always be: a piece's id is its place in its side's [[Side.homeSquares]], so where each started is known,
      * and its rank is either on the board still or was told by the battle that took it — every piece that fell, fell
      * fighting. `None` too if the turns do not account for every piece, which a match this engine played cannot do.
      */
    def opening: Option[Board] =
        if (!inPlay) None
        else {
            val kept = turns.filter(_.isSetup).flatMap(t => t.setup.map(t.side -> _)).toMap
            if (kept.sizeIs == 2)
                Side.values.foldLeft(Option(Board.empty))((b, side) => b.flatMap(_.deploy(side, kept(side)).toOption))
            else rebuiltOpening
        }

    private def rebuiltOpening: Option[Board] = {
        def sideOf(id: Int) = if (id < 40) Side.Red else Side.Blue
        def homeOf(id: Int) = sideOf(id).homeSquares(id % 40)
        val standing = board.cells.flatten.map(p => p.id -> p.rank).toMap
        // Who stands where, by id, followed move by move — which is how a battle's defender is known —
        // and the rank each fallen piece was shown to have.
        val (_, fallen) = moves.foldLeft((Map.from((0 until 80).map(id => homeOf(id) -> id)), Map.empty[Int, Rank])) {
            case ((at, ranks), MoveRecord(_, _, _, _, Some(step), battle, _, _)) =>
                val defender = at.get(step.to)
                val left = at - step.from
                battle match {
                    case None => (left.updated(step.to, step.pieceId), ranks)
                    case Some(b) =>
                        val told = ranks.updated(step.pieceId, b.attacker) ++ defender.map(_ -> b.defender)
                        b.result match {
                            case Result.AttackerWins => (left.updated(step.to, step.pieceId), told)
                            case Result.DefenderWins => (left, told)
                            case Result.BothLost     => (left - step.to, told)
                        }
                }
            case (acc, _) => acc
        }
        val ranks = fallen ++ standing
        Option.when((0 until 80).forall(ranks.contains))(
          Board((0 until 80).foldLeft(Board.empty.cells) { (cells, id) =>
              cells.updated(homeOf(id), Some(Piece(id, sideOf(id), ranks(id))))
          })
        )
    }

    /** The ranks `side` has lost, highest first. Every one of them was revealed in the battle that took it, so these
      * are safe to show anyone.
      */
    def lost(side: Side): List[Rank] =
        if (!hasDeployed(side)) Nil
        else {
            val standing = board.pieces(side).map(_._2.rank).groupMapReduce(identity)(_ => 1)(_ + _)
            Rank.values.toList.reverse.flatMap(r => List.fill(r.count - standing.getOrElse(r, 0))(r))
        }
}

/** Stratego as matchmaker sees it: a simultaneous setup, and then a game of alternating turns.
  *
  * While the armies are being deployed every seat that has not deployed is pending, and both clocks started when the
  * match did — as in `engines/rps`, nobody waits for anybody. Once both are down, Red moves first, and each player's
  * clock starts with the turn before theirs: the second setup, for Red's opening move.
  */
object StrategoMatch extends Game[StrategoMatch, Seat, MoveRecord] {

    /** The cap for a match created without the `maxMoves` parameter. Changing it changes only matches created after. */
    val defaultMaxMoves = 2000

    /** Seats the players, honouring the roles matchmaker sent when it sent usable ones.
      *
      * A game configured in matchmaker with roles named `Red` and `Blue` gets exactly those seats. With no roles, or
      * roles this engine does not recognise, the first player named takes Red — the engine still has to produce a
      * playable game, and refusing would make role configuration a prerequisite for trying it out.
      */
    def seat(players: List[Protocol.EnginePlayer]): Either[String, List[Seat]] =
        if (players.sizeIs != 2) Left(s"stratego is a two-player game; ${players.size} player(s) were sent")
        else if (players.map(_.cognitoId).distinct.sizeIs != 2)
            // Both seats are found by the caller's subject, so one player holding both would make the
            // match unplayable — and in this game it would also mean one person seeing both armies.
            Left("the two seats must belong to two different players")
        else {
            val requested = players.map(p => p.role.flatMap(Side.parse))
            val sides =
                if (requested.flatten.distinct.sizeIs == 2) requested.map(_.get)
                else List(Side.Red, Side.Blue)
            Right(players.zip(sides).map((p, side) => Seat(side, p.cognitoId, p.participantId, p.nickname)))
        }

    /** The `maxMoves` game parameter, if matchmaker sent one: a whole number of piece moves, at least one. */
    def maxMovesOf(parameters: Map[String, String]): Either[String, Int] =
        parameters.get("maxMoves").map(_.trim) match {
            case None => Right(defaultMaxMoves)
            case Some(raw) =>
                raw.toIntOption.filter(_ > 0).toRight(s"maxMoves must be a positive whole number, not '$raw'")
        }

    def seats(m: StrategoMatch): List[Seat] = m.seats

    def isOver(m: StrategoMatch): Boolean = m.isOver

    def markCompleted(m: StrategoMatch): StrategoMatch = m.copy(completed = true)

    def pending(m: StrategoMatch): List[Seat] =
        if (m.isOver) Nil
        else if (!m.inPlay) m.seats.filterNot(s => m.hasDeployed(s.side))
        else m.seatOf(m.toMove).toList

    /** The match's creation through the setup, and the turn before after it. */
    def clockStartedAt(m: StrategoMatch): Instant =
        if (!m.inPlay) m.createdAt else m.turns.lastOption.map(_.takenAt).getOrElse(m.createdAt)

    def turns(m: StrategoMatch): List[MoveRecord] = m.turns

    def sequence(m: StrategoMatch): Long = m.turns.size.toLong

    def outcome(m: StrategoMatch, seat: Seat): Outcome =
        m.clock
            .flatMap(_.outcomeOf(seat.participantId))
            .getOrElse(
              if (m.winner.contains(seat.side)) Outcome.Win else if (m.isDraw) Outcome.Draw else Outcome.Loss
            )

    def clock(m: StrategoMatch): Option[TurnClock] = m.clock

    def withClock(m: StrategoMatch, clock: TurnClock): StrategoMatch = m.copy(clock = Some(clock))

    /** "<strong>alice</strong> captured <strong>bob</strong>'s flag in 87 moves." — who won and how the match ended. */
    override def summary(m: StrategoMatch): Option[String] =
        m.ending.map { ending =>
            def who(side: Side) = ResultText.name(m.seats.find(_.side == side).flatMap(_.nickname), side.toString)
            def moves(n: Int) = if (n == 1) "1 move" else s"$n moves"
            (ending, m.winner) match {
                case (Ending.Forfeit, Some(w))  => s"${who(w)} won by forfeit: ${who(w.other)} ran out of time."
                case (Ending.Forfeit, None)     => "Both sides ran out of time. Nobody wins."
                case (Ending.Conceded, Some(w)) => s"${who(w.other)} surrendered to ${who(w)}."
                case (Ending.FlagTaken, Some(w)) =>
                    s"${who(w)} captured ${who(w.other)}'s flag in ${moves(m.moves.size)}."
                case (Ending.NoMoves, Some(w)) => s"${who(w.other)} had no piece left to move. ${who(w)} wins."
                case (Ending.NoMoves, None)    => "Neither side could move. A draw."
                case (Ending.MoveCap, _)       => s"A draw: the limit of ${moves(m.maxMoves)} was reached."
                case (_, Some(w))              => s"${who(w)} wins."
                case (_, None)                 => "A draw."
            }
        }

    def scores(m: StrategoMatch, seat: Seat): Map[String, ujson.Value] =
        Map(
          "side" -> ujson.Str(seat.side.toString),
          "moves" -> ujson.Num(m.moves.count(_.side == seat.side).toDouble),
          "captured" -> ujson.Num(m.captured(seat.side).toDouble),
          "ending" -> m.ending.map(e => ujson.Str(e.label)).getOrElse(ujson.Null)
        )

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, StrategoMatch] =
        for {
            seats <- seat(request.players)
            maxMoves <- maxMovesOf(request.parameters)
        } yield StrategoMatch(
          matchId = request.matchId,
          seats = seats,
          board = Board.empty,
          turns = Nil,
          isPublic = request.isPublic,
          completed = false,
          createdAt = now,
          moveCallbackUrl = request.moveCallbackUrl,
          resultsCallbackUrl = request.resultsCallbackUrl,
          maxMoves = maxMoves,
          gameDisplayName = request.gameDisplayName,
          description = request.description,
          noTie = request.tieForbidden
        )

    /** A piece as it is stored: on its square, since a board is mostly empty and storing 100 cells would mostly store
      * nothing.
      */
    case class Placed(square: Int, id: Int, side: Side, rank: Rank, revealed: Boolean, moved: Boolean)

    // Stored as JSON, which is what both stores hold: the in-memory one keeps the object itself,
    // and DynamoDB keeps this string in one attribute rather than a modelled item — the engine
    // never queries by anything but the match id.
    given ReadWriter[Side] = upickle.default.readwriter[String].bimap(_.toString, s => Side.valueOf(s))
    given ReadWriter[Rank] = upickle.default.readwriter[String].bimap(_.toString, s => Rank.valueOf(s))
    given ReadWriter[Result] = upickle.default.readwriter[String].bimap(_.toString, s => Result.valueOf(s))
    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Placed] = macroRW
    given ReadWriter[Board] = upickle.default
        .readwriter[List[Placed]]
        .bimap(
          board =>
              board.cells.indices.toList
                  .flatMap(i => board(i).map(p => Placed(i, p.id, p.side, p.rank, p.revealed, p.moved))),
          placed =>
              Board(
                placed.foldLeft(Board.empty.cells)((cells, p) =>
                    cells.updated(p.square, Some(Piece(p.id, p.side, p.rank, p.revealed, p.moved)))
                )
              )
        )
    given ReadWriter[Step] = macroRW
    given ReadWriter[Battle] = macroRW
    given ReadWriter[Seat] = macroRW
    given ReadWriter[MoveRecord] = macroRW
    given ReadWriter[StrategoMatch] = macroRW
}
