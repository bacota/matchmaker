package com.vivi.matchmaker.tournament

/** What one match is worth to each of its seats in a pool's standings, from the ranks the match ended with.
  *
  * Win, draw and loss do not describe a game of more than two seats, so points come from ranks. In a match of `n`
  * seats:
  *
  *   1. Each rank is inverted: `i = n − place + 1`, so first place is worth `n` and last place `1`.
  *   1. A seat placed alone scores `i² − 1`.
  *   1. Seats tied together each score `(r − m + 1)²`, where `m` is how many are tied and `r` the inverted place of the
  *      best of the places they cover — one more than a lone seat in the lowest of those places.
  *
  * Places come from the order of the reported ranks, not their values: seats are sorted by rank, equal ranks form a
  * tied group, and each group covers the next places in turn. So ranks 1, 1, 3 and 1, 1, 2 both mean "two tied first,
  * one third", whichever convention an engine uses. For two seats this is three for a win, one each for a draw, and
  * nothing for a loss.
  *
  * A seat with no rank — an engine that said nothing about it, or a cancelled match nobody ranked — scores nothing, and
  * does not count among the `n`.
  */
object Points {

    /** Each seat's points, from its rank where it has one. */
    def of[K](ranks: Map[K, Option[Int]]): Map[K, Int] = {
        val ranked = ranks.collect { case (k, Some(rank)) => k -> rank }
        val n = ranked.size
        // Groups of equal rank, best first, each covering the next `size` places.
        val groups = ranked.groupBy(_._2).toList.sortBy(_._1).map(_._2.keys.toList)
        val scored = groups
            .foldLeft((1, Map.empty[K, Int])) { case ((place, acc), group) =>
                val m = group.size
                val best = n - place + 1 // the inverted place of the best place the group covers
                val points = if (m == 1) best * best - 1 else (best - m + 1) * (best - m + 1)
                (place + m, acc ++ group.map(_ -> points))
            }
            ._2
        ranks.keys.map(k => k -> scored.getOrElse(k, 0)).toMap
    }

    /** Each seat's score differential for one match.
      *
      * With a numeric score for every ranked seat (the game's `score_key`), it is the seat's score less the mean of its
      * opponents'. Without one, ranks stand in: the number of opponents ranked below the seat less the number ranked
      * above. A seat with no rank has no differential.
      */
    def differential[K](ranks: Map[K, Option[Int]], scores: Map[K, Double]): Map[K, Double] = {
        val ranked = ranks.collect { case (k, Some(rank)) => k -> rank }
        val byScore = ranked.keys.forall(scores.contains) && ranked.sizeIs > 1
        ranks.keys.map { k =>
            val value = ranked.get(k) match {
                case None => 0.0
                case Some(rank) =>
                    val opponents = ranked.removed(k)
                    if (opponents.isEmpty) 0.0
                    else if (byScore) scores(k) - opponents.keys.toList.map(scores).sum / opponents.size
                    else (opponents.values.count(_ > rank) - opponents.values.count(_ < rank)).toDouble
            }
            k -> value
        }.toMap
    }
}
