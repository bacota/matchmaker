package com.vivi.rps

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant

/** One player's seat in a match.
  *
  * `cognitoId` is who may throw in it — the same subject the player signs in as, which is how matchmaker named them and
  * how the engine recognises them. `participantId` is matchmaker's key for the seat and is what every callback quotes
  * back.
  */
case class Seat(side: Side, cognitoId: String, participantId: Long)

/** One throw that was made: by whom, what it was, when it was made, and when that player's clock started for it.
  *
  * A throw is this game's whole turn, so this is both the move and the turn record. `startedAt` is the match's creation
  * for both seats rather than the move before, because nobody here waits for anybody: both clocks start when the match
  * does.
  */
case class ThrowRecord(participantId: Long, shape: Shape, takenAt: Instant, startedAt: Instant)

/** A match in progress, and everything needed to answer for it or to call matchmaker back.
  *
  * The state is just the two seats and whatever has been thrown into it. There is no turn to store: whose turn it is,
  * in a game where both players move at once, is "everyone who has not moved" — so it is derived from `throws` rather
  * than kept beside them, where the two could disagree.
  *
  * The callback urls are stored per match rather than configured once because matchmaker sends them with the game: they
  * carry its game id and match id, and an engine serving several matchmaker installations would get different bases for
  * each.
  */
case class RpsMatch(
    matchId: String,
    seats: List[Seat],
    throws: List[ThrowRecord],
    isPublic: Boolean,
    completed: Boolean,
    createdAt: Instant,
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String]
) {

    def seatOf(side: Side): Option[Seat] = seats.find(_.side == side)

    /** The seat belonging to a signed-in player, if they have one in this match. */
    def seatFor(cognitoId: String): Option[Seat] = seats.find(_.cognitoId == cognitoId)

    def throwOf(seat: Seat): Option[ThrowRecord] = throws.find(_.participantId == seat.participantId)

    def hasThrown(seat: Seat): Boolean = throwOf(seat).isDefined

    /** The seats still being waited on — all of them at the start, and none once the match is over.
      *
      * This is the whole of "whose turn it is" here, and it is a list because it is genuinely more than one:
      * matchmaker's move callback and status response both take several pending seats, and this is the game that uses
      * them.
      */
    def pending: List[Seat] = if (isOver) Nil else seats.filterNot(hasThrown)

    /** Over the moment both players have thrown, which is the rule the game is: nobody waits for a turn, so there is
      * nothing to end but the pair being complete.
      */
    def isOver: Boolean = seats.forall(hasThrown)

    /** The winning seat, once there is one. `None` while a throw is still to come, and `None` for a draw — which
      * [[isDraw]] is how to tell apart from an unfinished match.
      */
    def winner: Option[Seat] =
        seats match {
            case a :: b :: Nil =>
                (throwOf(a), throwOf(b)) match {
                    case (Some(x), Some(y)) if x.shape.beats(y.shape) => Some(a)
                    case (Some(x), Some(y)) if y.shape.beats(x.shape) => Some(b)
                    case _                                            => None
                }
            case _ => None
        }

    def isDraw: Boolean = isOver && winner.isEmpty

    /** How the match came out for one seat. Only meaningful once it is over. */
    def outcomeFor(seat: Seat): Outcome =
        winner match {
            case Some(w) if w.participantId == seat.participantId => Outcome.Win
            case Some(_)                                          => Outcome.Loss
            case None                                             => Outcome.Draw
        }

}

object RpsMatch {

    /** Seats the players, honouring the roles matchmaker sent when it sent usable ones.
      *
      * A game configured in matchmaker with roles named `One` and `Two` gets exactly those seats. With no roles, or
      * roles this engine does not recognise, the first player named takes One — the engine still has to produce a
      * playable game, and refusing would make role configuration a prerequisite for trying it out. It costs nothing to
      * be relaxed about it here: unlike X and O, neither side of this game has any advantage over the other.
      */
    def seat(players: List[Protocol.EnginePlayer]): Either[String, List[Seat]] =
        if (players.sizeIs != 2) Left(s"rock-paper-scissors is a two-player game; ${players.size} player(s) were sent")
        else if (players.map(_.cognitoId).distinct.sizeIs != 2)
            // Both seats are found by the caller's subject, so one player holding both would make the
            // match unplayable in a way that is much harder to diagnose later than here — and in this
            // game it would also mean one person seeing both throws.
            Left("the two seats must belong to two different players")
        else {
            val requested = players.map(p => p.role.flatMap(Side.parse))
            val sides =
                if (requested.flatten.distinct.sizeIs == 2) requested.map(_.get)
                else List(Side.One, Side.Two)
            Right(players.zip(sides).map((p, side) => Seat(side, p.cognitoId, p.participantId)))
        }

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, RpsMatch] =
        seat(request.players).map { seats =>
            RpsMatch(
              matchId = request.matchId,
              seats = seats,
              throws = Nil,
              isPublic = request.isPublic,
              completed = false,
              createdAt = now,
              moveCallbackUrl = request.moveCallbackUrl,
              resultsCallbackUrl = request.resultsCallbackUrl
            )
        }

    // Stored as JSON, which is what both stores hold: the in-memory one keeps the object itself,
    // and DynamoDB keeps this string in one attribute rather than a modelled item — the engine
    // never queries by anything but the match id.
    given ReadWriter[Shape] = upickle.default.readwriter[String].bimap(_.toString, s => Shape.valueOf(s))
    given ReadWriter[Side] = upickle.default.readwriter[String].bimap(_.toString, s => Side.valueOf(s))
    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Seat] = macroRW
    given ReadWriter[ThrowRecord] = macroRW
    given ReadWriter[RpsMatch] = macroRW
}
