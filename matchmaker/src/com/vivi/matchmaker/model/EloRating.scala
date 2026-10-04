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
  * result would do. The rounding is the last step and per player, so the changes add to zero only up to it.
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

    /** The chance Elo gives a player rated `mine` of beating one rated `theirs`, a draw counting as half. */
    def expected(mine: Int, theirs: Int): Double = 1.0 / (1.0 + math.pow(10.0, (theirs - mine) / 400.0))

    /** What the match did to each seat's rating, by seat, worked out from the ratings the seats began it at.
      *
      * A seat's opponents are the seats of *other* players, so that a player who held two seats does not play
      * themselves; their rating moves by the sum of their seats' deltas. Each delta is rounded on its own, so that the
      * deltas stored are exactly what the ratings moved by. A match with fewer than two players in it has nobody to
      * have beaten, and has no deltas at all.
      */
    def deltas(seats: Seq[Seat]): Map[ParticipantId, Int] =
        if (seats.map(_.player).distinct.size < 2) Map.empty
        else
            seats.map { seat =>
                val opponents = seats.filter(_.player != seat.player)
                val surprise = opponents.map { other =>
                    val scored =
                        if (seat.rank < other.rank) 1.0 else if (seat.rank == other.rank) 0.5 else 0.0
                    scored - expected(seat.rating, other.rating)
                }.sum
                seat.participant -> math.round(k * surprise / opponents.size).toInt
            }.toMap
}
