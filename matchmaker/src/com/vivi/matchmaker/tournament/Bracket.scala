package com.vivi.matchmaker.tournament

/** How a planned slot is filled, naming an earlier pool by its round and its place in that round, since the pools have
  * no ids yet. The service writes these as `fixture_slot` rows.
  *
  * A `Winner` slot whose rank is beyond the round's `minPoolAdvance` is a fill slot: planned against the pool expected
  * to have the best such finisher, and filled when its round starts by the fill rule across every pool (see
  * [[Advancement]]).
  */
enum PlannedSource {
    case Bye
    case Seed(seed: Int)
    case Winner(round: Int, position: Int, rank: Int)
}

/** A planned pool: its round, its place in the round from 1, and its slots. */
case class PlannedPool(round: Int, position: Int, slots: List[PlannedSource])

/** Every round of an elimination tournament, planned when it starts: `rounds(0)` is round 1. */
case class Bracket(rounds: List[List[PlannedPool]]) {
    def pools: List[PlannedPool] = rounds.flatten
}

/** Plans the pools of every round, assuming the better seed always wins.
  *
  *   - **Round 1** is drawn by seed. With pools of two, the number of pools is raised to a power of two, and the slots
  *     left over are byes, which fall to the top seeds. Pools of more are drawn as a snake — seeds 1 to P across the P
  *     pools, the next P back again — which keeps the sums of seeds in each pool as level as a draw can, and spreads
  *     the byes one to a pool.
  *   - **Each later round** is filled by `Winner` slots from the round before: the top `advance` of each pool, but
  *     never the whole of a pool — a pool of two real players sends one through, however large `advance` is. The slots
  *     are drawn into pools the same way, by the seed each is expected to hold, so the sums stay level.
  *   - **Fill slots.** When the advancers do not fill the next round's pools, the next finishers are planned in to fill
  *     them, from the pools expected to have the best of them (see [[PlannedSource]]). With pools of two, a round has a
  *     power of two of them.
  *   - **The final** is the round with one pool. A single-elimination final also has a consolation pool, of the
  *     semi-final pools' best non-advancers, when there was more than one semi-final pool.
  *
  * Every round has fewer players than the one before it, so the bracket always reaches a final: a round that would not
  * have is cut to the most pools that would, and the advancers expected to be weakest are the ones left out.
  */
object Bracket {

    /** A round-robin tournament: one round, one pool, everybody in it. */
    def roundRobin(entrants: Int): Bracket =
        Bracket(List(List(PlannedPool(1, 1, (1 to entrants).toList.map(PlannedSource.Seed(_))))))

    /** A pool with the seeds it is expected to hold, best first. */
    private case class Expected(pool: PlannedPool, seeds: List[Int])

    /** A finisher of an earlier pool, with the seed expected to finish there. */
    private case class Finisher(source: PlannedSource.Winner, seed: Int)

    /** A single-elimination tournament of `entrants`, in pools of `poolSize` of which the top `advance` go through. */
    def singleElimination(entrants: Int, poolSize: Int, advance: Int, consolation: Boolean = true): Bracket = {
        require(entrants >= 1, "a tournament needs at least one entrant")
        require(poolSize >= 2, "an elimination round needs pools of at least two")
        require(advance >= 1 && advance < poolSize, "fewer than a whole pool must go through")

        val first = firstRound(entrants, poolSize).map(pool => Expected(pool, seedsOf(pool)))

        @annotation.tailrec
        def plan(rounds: List[List[Expected]]): List[List[Expected]] =
            if (rounds.last.sizeIs <= 1) rounds
            else plan(rounds :+ nextRound(rounds.size + 1, rounds.last, poolSize, advance))

        val planned = plan(List(first))
        // The consolation pool sits beside the final, made from the round before it.
        val withConsolation =
            if (!consolation || planned.sizeIs < 2) planned
            else {
                val semis = planned(planned.size - 2)
                val finalRound = planned.last
                val used = finalRound.flatMap(_.pool.slots).toSet
                val unused = semis.flatMap(finishersOf).filterNot(f => used.contains(f.source)).sortBy(_.seed)
                if (semis.sizeIs < 2 || unused.sizeIs < 2) planned
                else {
                    val pool = PlannedPool(planned.size, 2, unused.take(poolSize).map(_.source))
                    planned.init :+ (finalRound :+ Expected(pool, unused.take(poolSize).map(_.seed)))
                }
            }
        Bracket(withConsolation.map(_.map(_.pool)))
    }

    private def seedsOf(pool: PlannedPool): List[Int] = pool.slots.collect { case PlannedSource.Seed(s) => s }.sorted

    private def finishersOf(e: Expected): List[Finisher] =
        e.seeds.zipWithIndex.map((seed, i) =>
            Finisher(PlannedSource.Winner(e.pool.round, e.pool.position, i + 1), seed)
        )

    private def firstRound(entrants: Int, poolSize: Int): List[PlannedPool] = {
        val pools =
            if (poolSize == 2) powerOfTwo(math.ceil(entrants / 2.0).toInt)
            else math.ceil(entrants.toDouble / poolSize).toInt
        val seats = pools * poolSize
        val drawn = if (poolSize == 2) fold(seats) else snake((1 to seats).toList, pools)
        drawn.zipWithIndex.map { (seeds, i) =>
            PlannedPool(1, i + 1, seeds.map(s => if (s > entrants) PlannedSource.Bye else PlannedSource.Seed(s)))
        }
    }

    /** The next round, from the pools of `last` and the seeds each is expected to hold. */
    private def nextRound(round: Int, last: List[Expected], poolSize: Int, advance: Int): List[Expected] = {
        val players = last.map(_.seeds.size).sum
        // The top `advance` of every pool, but never all of one: a pool of one real player sends it through.
        val (advancing, rest) = last.map { e =>
            val through = if (e.seeds.sizeIs <= 1) e.seeds.size else math.min(advance, e.seeds.size - 1)
            finishersOf(e).splitAt(through)
        }.unzip match { case (a, r) => (a.flatten, r.flatten.sortBy(_.seed)) }

        val wanted = seatsFor(advancing.size, poolSize)
        // A round must have fewer players than the one before, or the bracket would never reach a final.
        val seats = if (wanted < players) wanted else seatsBelow(players, poolSize)
        val chosen =
            if (seats >= advancing.size) advancing ++ rest.take(seats - advancing.size)
            else advancing.sortBy(_.seed).take(seats)
        val pools = seats / poolSize
        snake(chosen.sortBy(_.seed), pools).zipWithIndex.map { (slots, i) =>
            Expected(PlannedPool(round, i + 1, slots.map(_.source)), slots.map(_.seed).sorted)
        }
    }

    /** How many seats a round needs for `advancing` players in pools of `poolSize`: whole pools, and with pools of two,
      * a power of two of them.
      */
    private def seatsFor(advancing: Int, poolSize: Int): Int = {
        val pools = math.max(1, math.ceil(advancing.toDouble / poolSize).toInt)
        (if (poolSize == 2) powerOfTwo(pools) else pools) * poolSize
    }

    /** The most seats in whole pools, fewer than `players`; one pool at least. */
    private def seatsBelow(players: Int, poolSize: Int): Int = {
        val pools = math.max(1, (players - 1) / poolSize)
        (if (poolSize == 2) Integer.highestOneBit(pools) else pools) * poolSize
    }

    /** The least power of two at least `n`, and 1 for nothing. */
    def powerOfTwo(n: Int): Int = if (n <= 1) 1 else Integer.highestOneBit(n - 1) << 1

    /** Seeds 1 to `seats` paired from the outside in — 1 with the last, 2 with the one before it — so every pair's sum
      * is the same. The pairs are in order of their better seed.
      */
    private def fold(seats: Int): List[List[Int]] = (1 to seats / 2).toList.map(s => List(s, seats + 1 - s))

    /** `items`, best first, dealt across `pools` pools as a snake: the first row forwards, the next back, and so on. */
    def snake[A](items: List[A], pools: Int): List[List[A]] = {
        val dealt = items.zipWithIndex.map { (item, i) =>
            val row = i / pools
            val column = i % pools
            (if (row % 2 == 0) column else pools - 1 - column) -> item
        }
        (0 until pools).toList.map(p => dealt.collect { case (`p`, item) => item })
    }
}
