package com.vivi.matchmaker.tournament

/** A finished (or cancelled) match of a pool, as standings read it: each seat's member, its rank if it has one, and its
  * numeric score if the game keeps one (`game.score_key`). A cancelled match nobody ranked has no ranks at all.
  */
case class PlayedMatch[K](ranks: Map[K, Option[Int]], scores: Map[K, Double] = Map.empty[K, Double])

/** One member's line in a pool's standings. */
case class Standing[K](member: K, seed: Int, points: Int, differential: Double)

/** A pool's standings: points from every match's ranks (see [[Points]]), then the tiebreaker.
  *
  *   - `SCORE` orders members level on points by their total score differential.
  *   - `REMATCH` orders them by the points they took from the tie-break matches among them, once those are played.
  *
  * Whatever is still level after that goes by seed, best first, so the order is always total.
  */
object Standings {

    /** The pool's members, best first.
      *
      * `seeds` is every member of the pool with its seed, including any who played nothing. `rematches` are the
      * tie-break matches played among members level on points; with `byScore` false, they decide ties, and are
      * otherwise ignored.
      */
    def of[K](
        seeds: Map[K, Int],
        played: List[PlayedMatch[K]],
        byScore: Boolean,
        rematches: List[PlayedMatch[K]] = Nil
    ): List[Standing[K]] = {
        val lines = totals(seeds, played)
        val rematchPoints = totals(seeds, rematches).map(s => s.member -> s.points).toMap
        lines.sortBy(s =>
            (
              -s.points,
              if (byScore) -s.differential else 0.0,
              if (byScore) 0 else -rematchPoints.getOrElse(s.member, 0),
              s.seed
            )
        )
    }

    /** Every member's points and differential summed over `played`, in no particular order. */
    def totals[K](seeds: Map[K, Int], played: List[PlayedMatch[K]]): List[Standing[K]] = {
        val points = played.map(m => Points.of(m.ranks))
        val differentials = played.map(m => Points.differential(m.ranks, m.scores))
        seeds.toList.map((member, seed) =>
            Standing(
              member,
              seed,
              points.map(_.getOrElse(member, 0)).sum,
              differentials.map(_.getOrElse(member, 0.0)).sum
            )
        )
    }

    /** Under `REMATCH`, the groups of members level on points that a rematch has to separate: those whose tie straddles
      * `cutoff`, the last place that goes through — or, with a cutoff of 1, the tie for first.
      *
      * Members already separated by `rematches` played among them are not tied any more.
      */
    def tiesToBreak[K](
        seeds: Map[K, Int],
        played: List[PlayedMatch[K]],
        cutoff: Int,
        rematches: List[PlayedMatch[K]] = Nil
    ): List[List[K]] = {
        val ordered = totals(seeds, played).sortBy(s => (-s.points, s.seed))
        val rematchPoints = totals(seeds, rematches).map(s => s.member -> s.points).toMap
        ordered.zipWithIndex
            .groupBy(_._1.points)
            .values
            .map(_.sortBy(_._2))
            .filter(group => group.sizeIs > 1 && group.head._2 < cutoff && group.last._2 >= cutoff)
            .map(_.map(_._1))
            // A group the rematches have already split into distinct places needs no more.
            .filterNot(group => group.map(s => rematchPoints.getOrElse(s.member, 0)).distinct.sizeIs == group.size)
            .map(_.sortBy(_.seed).map(_.member))
            .toList
            .sortBy(group => seeds(group.head))
    }
}
