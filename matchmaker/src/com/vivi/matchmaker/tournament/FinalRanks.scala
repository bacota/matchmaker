package com.vivi.matchmaker.tournament

/** Where everybody finished, once the last round is over. */
object FinalRanks {

    /** A round robin: the pool's standings, in order. */
    def roundRobin[K](standings: List[Standing[K]]): Map[K, Int] =
        standings.zipWithIndex.map((s, i) => s.member -> (i + 1)).toMap

    /** Single elimination: the final's players first, in their standings there; then the consolation pool's, in theirs;
      * then everybody else by the round they reached, the deepest first, sharing a rank with everyone who went out in
      * the same round.
      *
      * @param reached
      *   the last round each player played in
      */
    def singleElimination[K](
        finalPool: List[Standing[K]],
        consolation: List[Standing[K]],
        reached: Map[K, Int]
    ): Map[K, Int] = {
        val top = (finalPool ++ consolation).map(_.member)
        val placed = top.zipWithIndex.map((k, i) => k -> (i + 1)).toMap
        val rest = reached.removedAll(top).toList.groupBy(_._2).toList.sortBy(-_._1).map(_._2.map(_._1))
        rest
            .foldLeft((top.size + 1, placed)) { case ((next, acc), group) =>
                (next + group.size, acc ++ group.map(_ -> next))
            }
            ._2
    }
}
