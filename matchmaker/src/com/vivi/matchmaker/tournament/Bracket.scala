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

/** Every round of an elimination tournament, planned when it starts: `rounds(0)` is round 1. `reseed` are the rounds
  * whose seeds are recomputed from everybody's record as they start, and whose slots name seeds rather than finishers.
  */
case class Bracket(rounds: List[List[PlannedPool]], reseed: Set[Int] = Set.empty) {
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
        Bracket(eliminate(List(first), poolSize, advance, if (consolation) 0 else Int.MaxValue).map(_.map(_.pool)))
    }

    /** A playoff: a first round of pools of `poolSize`, each played round robin, of which the top `advance` go through;
      * then single elimination in pairs. The first elimination round reseeds the players who went through by their
      * record in the pools, and its pairs are drawn by those seeds — the top seeds given byes, so that its pools are a
      * power of two.
      */
    def playoff(entrants: Int, poolSize: Int, advance: Int, consolation: Boolean = true): Bracket = {
        require(entrants >= 1, "a tournament needs at least one entrant")
        require(poolSize > 2, "a playoff's pools hold more than two")
        require(advance >= 1 && advance < poolSize, "fewer than a whole pool must go through")

        val first = firstRound(entrants, poolSize).map(pool => Expected(pool, seedsOf(pool)))
        val through = first.map(e => if (e.seeds.sizeIs <= 1) e.seeds.size else math.min(advance, e.seeds.size - 1)).sum
        if (first.sizeIs <= 1 || through <= 1) Bracket(List(first.map(_.pool)))
        else {
            val pairs = firstRound(through, 2).map(p => p.copy(round = 2))
            val elimination = pairs.map(pool => Expected(pool, seedsOf(pool)))
            // The consolation pool is of pairs' losers, so never beside a final made straight from the pools.
            val planned = eliminate(List(first, elimination), 2, 1, if (consolation) 1 else Int.MaxValue)
            Bracket(planned.map(_.map(_.pool)), reseed = Set(2))
        }
    }

    /** A double elimination of `entrants` in pools of `poolSize`, one going through from each: nobody is out until they
      * have lost twice.
      *
      *   - **The winners' bracket** is single elimination of the pools' winners alone, without a consolation pool. Its
      *     pools are not filled from second places, which go to the losers' bracket instead; a short pool has byes.
      *   - **The losers' bracket** is fed by every winners' pool's second place (`Winner(…, rank = 2)`); anybody placed
      *     lower in a pool of more than two is out. Each winners' round's losers join the survivors of the losers'
      *     bracket so far, and play them, one going through from each pool; when there are more survivors than new
      *     losers, the survivors first play among themselves until there are not. With pools of two this is the usual
      *     losers' bracket, alternating rounds among survivors with rounds against the newly dropped.
      *   - **The grand final** is the winners' champion against the losers' champion. Nobody plays it twice.
      *
      * A pool is played in the round after the last of the pools it is filled from, so the losers' bracket runs on past
      * the winners' final, and rounds there hold losers' pools alone. In a round, the winners' pools come first.
      */
    def doubleElimination(entrants: Int, poolSize: Int): Bracket = {
        require(entrants >= 1, "a tournament needs at least one entrant")
        require(poolSize >= 2, "an elimination round needs pools of at least two")

        val first = firstRound(entrants, poolSize).map(pool => Expected(pool, seedsOf(pool)))
        @annotation.tailrec
        def upper(rounds: List[List[Expected]]): List[List[Expected]] =
            if (rounds.last.sizeIs <= 1) rounds
            else upper(rounds :+ winnersRound(rounds.size + 1, rounds.last, poolSize))
        val winners = upper(List(first))
        if (winners.flatten.forall(_.seeds.sizeIs < 2)) Bracket(winners.map(_.map(_.pool)))
        else {
            // A source in the losers' bracket: a winners' pool's finisher, or a losers' pool's winner (by its index).
            enum Ref {
                case Upper(source: PlannedSource.Winner)
                case Lower(pool: Int)
            }
            case class Lower(refs: List[(Ref, Int)])

            var lower = Vector.empty[Lower]
            def stage(survivors: List[(Ref, Int)]): List[(Ref, Int)] = {
                val pools = math.ceil(survivors.size.toDouble / poolSize).toInt
                snake(survivors.sortBy(_._2), pools).map { members =>
                    lower = lower :+ Lower(members)
                    (Ref.Lower(lower.size - 1), members.map(_._2).min)
                }
            }
            val afterRounds = winners.zipWithIndex.foldLeft(List.empty[(Ref, Int)]) { case (survivors, (pools, i)) =>
                val dropped = pools.filter(_.seeds.sizeIs >= 2).map { e =>
                    (Ref.Upper(PlannedSource.Winner(i + 1, e.pool.position, 2)), e.seeds(1))
                }
                @annotation.tailrec
                def thin(s: List[(Ref, Int)]): List[(Ref, Int)] =
                    if (s.sizeIs > math.max(1, dropped.size)) thin(stage(s)) else s
                val merged = thin(survivors) ++ dropped
                if (merged.sizeIs > 1) stage(merged) else merged
            }
            @annotation.tailrec
            def down(s: List[(Ref, Int)]): List[(Ref, Int)] = if (s.sizeIs > 1) down(stage(s)) else s
            val champion = down(afterRounds)

            // Each losers' pool is played in the round after the latest of its sources.
            val roundOf = lower.indices.foldLeft(Map.empty[Int, Int]) { (acc, i) =>
                val after = lower(i).refs.map {
                    case (Ref.Upper(w), _) => w.round
                    case (Ref.Lower(j), _) => acc(j)
                }.max
                acc + (i -> (after + 1))
            }
            val positionOf: Map[Int, Int] = lower.indices
                .groupBy(roundOf)
                .toList
                .flatMap { (round, ids) =>
                    val upper = winners.lift(round - 1).fold(0)(_.size)
                    ids.sorted.zipWithIndex.map((id, k) => id -> (upper + k + 1))
                }
                .toMap
            def source(ref: Ref): PlannedSource = ref match {
                case Ref.Upper(w) => w
                case Ref.Lower(j) => PlannedSource.Winner(roundOf(j), positionOf(j), 1)
            }
            val lowerPools = lower.indices.toList.map(i =>
                PlannedPool(roundOf(i), positionOf(i), lower(i).refs.map((ref, _) => source(ref)))
            )
            val finalRound = (roundOf.values.toList :+ winners.size).max + 1
            val grand = champion.headOption.map { (ref, _) =>
                PlannedPool(finalRound, 1, List(PlannedSource.Winner(winners.size, 1, 1), source(ref)))
            }
            val all = winners.flatten.map(_.pool) ++ lowerPools ++ grand.toList
            Bracket((1 to all.map(_.round).max).toList.map(r => all.filter(_.round == r).sortBy(_.position)))
        }
    }

    /** The next round of a winners' bracket: each pool's winner, drawn by expected seed into as few pools as hold them
      * — with pools of two, a power of two of them — and byes in the seats left over.
      */
    private def winnersRound(round: Int, last: List[Expected], poolSize: Int): List[Expected] = {
        val advancing = last.filter(_.seeds.nonEmpty).flatMap(e => finishersOf(e).take(1)).sortBy(_.seed)
        val wanted = math.max(1, math.ceil(advancing.size.toDouble / poolSize).toInt)
        val pools = if (poolSize == 2) powerOfTwo(wanted) else wanted
        snake(advancing, pools).zipWithIndex.map { (slots, i) =>
            val sources = slots.map(_.source) ++ List.fill(poolSize - slots.size)(PlannedSource.Bye)
            Expected(PlannedPool(round, i + 1, sources), slots.map(_.seed).sorted)
        }
    }

    /** `start`'s rounds, then elimination rounds after them until one pool is left; and the consolation pool beside the
      * final, made from the round before it, if that round is at index `consolationFrom` or later.
      */
    private def eliminate(
        start: List[List[Expected]],
        poolSize: Int,
        advance: Int,
        consolationFrom: Int
    ): List[List[Expected]] = {
        @annotation.tailrec
        def plan(rounds: List[List[Expected]]): List[List[Expected]] =
            if (rounds.last.sizeIs <= 1) rounds
            else plan(rounds :+ nextRound(rounds.size + 1, rounds.last, poolSize, advance))

        val planned = plan(start)
        // The consolation pool sits beside the final, made from the round before it.
        if (planned.sizeIs < 2 || planned.size - 2 < consolationFrom) planned
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
