package com.vivi.matchmaker.tournament

/** A ladder's matches for one round.
  *
  * Built rank by rank, from the highest. Within a rank, the lowest-rated player is matched against the highest-rated.
  * When a rank's players do not divide into matches, players are borrowed from the ranks below, the lowest-rated of the
  * next rank first. Whoever is left at the bottom, too few for a match, sits the round out.
  */
object LadderPairing {

    case class Rung[K](member: K, rank: Int, rating: Int)

    /** The round's matches, each a list of `seats` players, from the top rank down. */
    def matches[K](players: List[Rung[K]], seats: Int): List[List[K]] = {
        require(seats >= 1)
        val ranks = players.groupBy(_.rank).toList.sortBy(-_._1).map(_._2.sortBy(_.rating))

        /* Borrows `short` players for the rank above from `below`, taking each rank's lowest-rated first and moving down
         * a rank whenever one runs out. */
        @annotation.tailrec
        def borrow(short: Int, below: List[List[Rung[K]]], taken: List[Rung[K]]): (List[Rung[K]], List[List[Rung[K]]]) =
            below match {
                case _ if short == 0 => (taken, below)
                case Nil             => (taken, Nil)
                case next :: rest =>
                    val (now, kept) = next.splitAt(short)
                    val remaining = if (kept.isEmpty) rest else kept :: rest
                    borrow(short - now.size, remaining, taken ++ now)
            }

        @annotation.tailrec
        def go(ranks: List[List[Rung[K]]], acc: List[List[K]]): List[List[K]] =
            ranks match {
                case Nil => acc
                case here :: below =>
                    val (borrowed, left) = borrow((seats - here.size % seats) % seats, below, Nil)
                    val pool = (here ++ borrowed).sortBy(_.rating)
                    val usable = pool.size - pool.size % seats
                    go(left, acc ++ ends(pool.take(usable)).grouped(seats).map(_.map(_.member)))
            }

        go(ranks, Nil)
    }

    /** Lowest, highest, next lowest, next highest...: so each match runs from one end of the ratings to the other. */
    private def ends[A](sorted: List[A]): List[A] =
        if (sorted.isEmpty) Nil
        else if (sorted.sizeIs == 1) sorted
        else sorted.head :: sorted.last :: ends(sorted.tail.init)
}
