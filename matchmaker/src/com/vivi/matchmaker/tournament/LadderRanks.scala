package com.vivi.matchmaker.tournament

/** How a ladder's ranks move with each match (tournament-plan Phase 8).
  *
  * The design says a win is +1 and a loss −1, which does not say what a draw is, or what a match of more than two seats
  * does. Decided here:
  *
  *   - The winner — the one seat alone in first place — goes up one. Seats tied for first have not won, and stay.
  *   - Every seat in last place goes down one: each of them lost to everybody above.
  *   - Everybody between stays where they are, and so does everybody in a match where every seat is level: a draw is 0.
  *
  * For two seats this is exactly the design's win and loss. A seat with no rank — a cancelled match nobody ranked —
  * does not move, and does not count towards who is first or last.
  */
object LadderRanks {

    /** Each seat's move, from its rank where it has one. */
    def moves[K](ranks: Map[K, Option[Int]]): Map[K, Int] = {
        val ranked = ranks.collect { case (k, Some(rank)) => k -> rank }
        val best = ranked.values.minOption
        val worst = ranked.values.maxOption
        ranks.keys.map { k =>
            val move = ranked.get(k) match {
                case _ if best == worst                                          => 0
                case Some(r) if best.contains(r) && ranked.count(_._2 == r) == 1 => 1
                case Some(r) if worst.contains(r)                                => -1
                case _                                                           => 0
            }
            k -> move
        }.toMap
    }

    /** Everybody's rank after `played`: the sum of their moves, from 0. */
    def of[K](members: Iterable[K], played: List[Map[K, Option[Int]]]): Map[K, Int] = {
        val moved = played.map(moves)
        members.map(k => k -> moved.map(_.getOrElse(k, 0)).sum).toMap
    }
}
