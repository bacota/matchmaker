package com.vivi.matchmaker.model.tournament

/** How one seat of a finished match came out: its rank, and the numeric score its engine reported, if any.
  *
  * `rank` is `None` for a seat with no result — a match cancelled without the owner ranking it — which scores nothing
  * and counts as below every ranked seat. `score` is the engine's `scores.score`, absent where the game reports none.
  */
case class Placing(entrant: Long, rank: Option[Int], score: Option[Double] = None)

/** One member's totals over a round-robin pool's matches. */
case class Standing(entrant: Long, points: Double, scoreDiff: Double, totalScore: Double)

/** What ranking a round-robin pool came to: each member's rank, their totals, and the rubber matches still needed
  * before the ranking can stand — each a pair of tied members, strongest seed first.
  */
case class PoolRanking(ranks: Map[Long, Int], standings: Map[Long, Standing], rubbers: List[(Long, Long)])

object Ranking {

    /** The points each seat of one match earns (`tournament-design.md`, "Ranking"): `points` for each opponent it
      * ranked above, `draw` for each it tied with, and half as much again for a seat ranked first when there is a
      * bonus. A seat with no rank earns nothing.
      */
    def points(placings: List[Placing], points: Int, draw: Int, bonus: Boolean): Map[Long, Double] = {
        val best = placings.flatMap(_.rank).minOption
        placings.map { p =>
            p.entrant -> (p.rank match {
                case None => 0.0
                case Some(r) =>
                    val others = placings.filterNot(_.entrant == p.entrant)
                    val below = others.count(o => o.rank.forall(_ > r))
                    val tied = others.count(_.rank.contains(r))
                    val base = (below * points + tied * draw).toDouble
                    if (bonus && best.contains(r)) base * 1.5 else base
            })
        }.toMap
    }

    /** A seat's score differential in one match: its score less the mean of its opponents', where every seat reported
      * one. Where some did not, ranks stand in for scores: opponents ranked below it less opponents ranked above.
      */
    def scoreDiff(placings: List[Placing], entrant: Long): Double = {
        val (mine, others) = placings.partition(_.entrant == entrant)
        if (others.isEmpty) 0.0
        else if (placings.forall(_.score.isDefined))
            mine.flatMap(_.score).sum - others.flatMap(_.score).sum / others.size
        else {
            val r = mine.headOption.flatMap(_.rank)
            def above(o: Placing) = (r, o.rank) match {
                case (Some(x), Some(y)) => y < x
                case (None, Some(_))    => true
                case _                  => false
            }
            def below(o: Placing) = (r, o.rank) match {
                case (Some(x), Some(y)) => y > x
                case (Some(_), None)    => true
                case _                  => false
            }
            (others.count(below) - others.count(above)).toDouble
        }
    }

    /** Ranks a round-robin pool from its finished matches.
      *
      * By points, then for members level on points by the stage's tiebreaker. A rubber settles a pair tied on points in
      * a game of two roles, and is asked for if it has not been played; anything else falls back to scores — total
      * score differential, then total score (Q7). A tie that still stands goes to the weaker seed in a pool of two,
      * whose match may not be drawn (Q6, [[Ties]]), and otherwise stays a tie. Ranks are shared by ties, the next rank
      * skipping past them: 1, 1, 3.
      *
      * `seedIn` is each member's seed entering the pool. `rubbers` are the rubber matches already played, by pair.
      */
    def rankPool(
        seedIn: Map[Long, Int],
        matches: List[List[Placing]],
        rubbers: Map[Set[Long], List[Placing]],
        stage: StageDesign,
        game: TournamentGame
    ): PoolRanking = {
        val members = seedIn.keys.toList
        val standings = members.map { e =>
            val mine = matches.filter(_.exists(_.entrant == e))
            e -> Standing(
              e,
              mine.map(m => points(m, stage.points, stage.pointsForDraw, stage.pointsBonus).getOrElse(e, 0.0)).sum,
              mine.map(scoreDiff(_, e)).sum,
              mine.flatMap(_.find(_.entrant == e).flatMap(_.score)).sum
            )
        }.toMap

        val useRubber = stage.rubberMatch && game.rubberApplies
        val tiesAllowed = Ties.allowed(members.size)
        var wanted = List.empty[(Long, Long)]

        // Members in groups that are still level, best group first; each group's order within it is decided below.
        val byPoints: List[List[Long]] =
            members.groupBy(e => standings(e).points).toList.sortBy(-_._1).map(_._2.sortBy(seedIn))

        def bySeedRule(group: List[Long]): List[List[Long]] =
            if (tiesAllowed) List(group)
            // The weaker seed — the higher number — takes the tie.
            else group.sortBy(e => -seedIn(e)).map(List(_))

        def byScores(group: List[Long]): List[List[Long]] =
            group
                .groupBy(e => (standings(e).scoreDiff, standings(e).totalScore))
                .toList
                .sortBy { case ((diff, total), _) => (-diff, -total) }
                .flatMap((_, g) => if (g.sizeIs == 1) List(g) else bySeedRule(g.sortBy(seedIn)))

        def broken(group: List[Long]): List[List[Long]] =
            if (group.sizeIs == 1) List(group)
            else if (useRubber && group.sizeIs == 2) {
                val pair = group.toSet
                rubbers.get(pair) match {
                    case None =>
                        wanted = wanted :+ (group.head, group(1))
                        List(group)
                    case Some(result) =>
                        val rankOf = result.map(p => p.entrant -> p.rank.getOrElse(Int.MaxValue)).toMap
                        val (a, b) = (group.head, group(1))
                        val (ra, rb) = (rankOf.getOrElse(a, Int.MaxValue), rankOf.getOrElse(b, Int.MaxValue))
                        if (ra < rb) List(List(a), List(b))
                        else if (rb < ra) List(List(b), List(a))
                        else bySeedRule(group)
                }
            } else byScores(group)

        val ordered = byPoints.flatMap(broken)
        PoolRanking(competition(ordered), standings, wanted)
    }

    /** Ranks from groups of equals, best group first: everyone in a group shares the rank of the first of them, and the
      * next group starts past them all.
      */
    def competition[A](groups: List[List[A]]): Map[A, Int] =
        groups
            .foldLeft((Map.empty[A, Int], 1)) { case ((ranks, next), group) =>
                (ranks ++ group.map(_ -> next), next + group.size)
            }
            ._1

    /** A (sub)tournament's final ranking, from its stage runs, oldest first (`tournament-design.md`, "Ranking").
      *
      * The final run is ranked first, then each run before it for the participants whose last run it was — every
      * participant eliminated, or withdrawn, in a run ranks below everyone who played on past it. Within a run: those
      * still in it after the run by the stratum the run left them in, highest first, then those it eliminated; within
      * each, by rank in their pool (Q5). Ties then go to the most recent tie-breaker stage they were ranked in, and any
      * tie that leaves stands.
      */
    def rankNode(runs: List[CompletedRun], tieBreakerRuns: Set[Int]): Map[Long, Int] = {
        val indexed = runs.zipWithIndex
        // The last run each participant was ranked in.
        val lastRun: Map[Long, Int] =
            indexed.flatMap((run, i) => run.placed.keys.map(_ -> i)).groupMapReduce(_._1)(_._2)(math.max)

        // Lower sorts first: still in the run before eliminated from it, then the higher stratum, then the better rank.
        def key(run: CompletedRun, e: Long): List[Int] =
            run.placed.get(e) match {
                case None                    => List(Int.MaxValue, Int.MaxValue, Int.MaxValue)
                case Some(p) if p.eliminated => List(1, -p.poolStratum, p.rank)
                case Some(p)                 => List(0, -p.stratumOut, p.rank)
            }
        // The tie-breaker runs, most recent first: where a participant placed in each settles a tie, in that order.
        val tieBreakers = indexed.filter((_, i) => tieBreakerRuns(i)).map(_._1).reverse
        val byKey = Ordering.Implicits.seqOrdering[List, Int]

        val groups: List[List[Long]] =
            indexed.reverse.flatMap { (run, i) =>
                val here = run.placed.keys.filter(lastRun(_) == i).toList
                here
                    .groupBy(e => key(run, e) ++ tieBreakers.flatMap(key(_, e)))
                    .toList
                    .sortBy(_._1)(byKey)
                    .map(_._2.sorted)
            }
        competition(groups)
    }
}

/** How one participant came out of a completed stage run, for ranking the tournament it is part of: the stratum of the
  * pool they played in, their rank in it, the stratum the run left them in, and whether it eliminated them (or they had
  * withdrawn).
  */
case class RunPlacing(poolStratum: Int, rank: Int, stratumOut: Int, eliminated: Boolean)

/** A completed stage run as [[Ranking.rankNode]] reads it. */
case class CompletedRun(placed: Map[Long, RunPlacing])
