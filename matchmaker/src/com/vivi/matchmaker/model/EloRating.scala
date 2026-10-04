package com.vivi.matchmaker.model

/** A player's Elo rating in one game (V42), as anybody may see it: the number, and how many rated matches moved it.
  */
case class EloRating(player: PublicPlayer, rating: Int, matches: Int)

/** How a match that is not friendly moves its players' ratings.
  *
  * Every pair of seats is a game of its own, decided by rank — the lower rank won, and equal ranks drew — and a seat's
  * change is `k` times what it scored against the others less what it was expected to, averaged over them. With two
  * seats that is Elo exactly; with more, a seat that beat everybody gains what it would have for beating one player of
  * their average strength, rather than a multiple of it.
  *
  * Pure, so that the arithmetic can be tested without a database, and in the model so that a screen could say what a
  * result would do. The rounding is the last step and per seat, so the changes add to zero only up to it.
  */
object EloRating {

    /** What a player's first rated match in a game takes as their rating before it moves. */
    val initial: Int = 1500

    /** How far one match can move a rating: the most a seat can gain, against a field it was certain to lose to. */
    val k: Double = 32.0

    /** The range an admin may set a rating to — far wider than play will ever reach, and still not a typo's worth. */
    val minimum: Int = 0
    val maximum: Int = 9999

    /** One seat of a finished match, as rating sees it: whose it was, what they were rated when it began, and where it
      * finished.
      */
    case class Seat(participant: ParticipantId, player: PlayerId, rating: Int, rank: Int)

    /** Whether every seat is a different player's — which a match that is not friendly requires (V42): a player in two
      * of its seats would be rated against themselves, and the deltas would no longer add up to nothing.
      */
    def playersOnce(players: Seq[PlayerId]): Boolean = players.distinct.size == players.size

    /** The chance Elo gives a player rated `mine` of beating one rated `theirs`, a draw counting as half. */
    def expected(mine: Int, theirs: Int): Double = 1.0 / (1.0 + math.pow(10.0, (theirs - mine) / 400.0))

    /** What the match did to each seat's rating, by seat, worked out from the ratings the seats began it at.
      *
      * Each delta is rounded on its own, so that the deltas stored are exactly what the ratings moved by; before the
      * rounding they add up to nothing. That needs every seat to be a different player's — see [[playersOnce]] — and a
      * match that is not has no deltas at all, as has one with fewer than two seats: there is nobody to have beaten.
      */
    def deltas(seats: Seq[Seat]): Map[ParticipantId, Int] =
        if (seats.size < 2 || !playersOnce(seats.map(_.player))) Map.empty
        else
            seats.map { seat =>
                val opponents = seats.filter(_ != seat)
                val surprise = opponents.map { other =>
                    val scored =
                        if (seat.rank < other.rank) 1.0 else if (seat.rank == other.rank) 0.5 else 0.0
                    scored - expected(seat.rating, other.rating)
                }.sum
                seat.participant -> math.round(k * surprise / opponents.size).toInt
            }.toMap
}
