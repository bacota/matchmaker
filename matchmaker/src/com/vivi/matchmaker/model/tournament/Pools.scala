package com.vivi.matchmaker.model.tournament

/** A participant as pools are formed from them: who, at what seed, in what stratum. A lower seed is a stronger one. */
case class Seeded(entrant: Long, seed: Int, stratum: Int)

/** A pool as it is formed, before anything is played in it. `stratum` is the stratum it was formed for — the highest of
  * its members', where strata are not strict and a short pool was filled from below. Members are in seed order.
  */
case class FormedPool(stratum: Int, members: List[Seeded])

/** Forming a stage's pools from its participants' seeds and strata (`tournament-design.md`, "Creating Pools from Seeds
  * and Strata").
  */
object Pools {

    /** How many pools `r` participants of one stratum make, in pools of `n`, `a` advancing from each.
      *
      * Without brackets, as few pools of `n` as hold everyone. With them, the smallest power of `n/a` pools that does —
      * which is the design's `(n/a)^ceiling((log r − log n) / (log n − log a))`, computed by counting rather than with
      * logarithms, so that an exact power is never pushed over by rounding. A stratum that fits one pool is one pool,
      * whatever the formula would make of it (Q1).
      */
    def poolCount(r: Int, n: Int, advancing: Option[Int], brackets: Boolean): Int =
        if (r <= 0) 0
        else if (r <= n) 1
        else
            advancing.filter(a => brackets && a > 0 && a < n && n % a == 0) match {
                case None => (r + n - 1) / n
                case Some(a) =>
                    val ratio = n / a
                    // The smallest power of ratio, p, with p pools of n holding r.
                    Iterator.iterate(1L)(_ * ratio).find(p => p * n >= r).get.toInt
            }

    /** The pool, of `npools`, that the participant at index `ix` of their stratum's seed order goes to: the design's
      * snake, verbatim. Down the pools, back up, down again — so the strongest and weakest of each lap share a pool.
      */
    def snake(ix: Int, npools: Int): Int = {
        val n = ix % npools
        val l = (ix / npools) % 2
        if (l == 0) n else npools - n - 1
    }

    /** A stage's pools, from the participants still in it.
      *
      * `n` is the pool size and `required` how many a match needs. A stratum smaller than a pool is one pool if it can
      * fill a match and none if it cannot (`tournament-design.md`; its members sit the stage out, neither promoted nor
      * demoted). With strict strata no pool mixes strata. Without, strata are taken from the highest down, and the
      * places a stratum's pools leave empty are filled from the strata below it, strongest seed first.
      */
    def form(
        participants: List[Seeded],
        n: Int,
        required: Int,
        advancing: Option[Int],
        brackets: Boolean,
        strict: Boolean
    ): List[FormedPool] = {
        val strata = participants.map(_.stratum).distinct.sorted.reverse
        def bySeed(ps: List[Seeded]) = ps.sortBy(p => (p.seed, p.entrant))

        if (strict)
            strata.flatMap { s =>
                val members = bySeed(participants.filter(_.stratum == s))
                val count = if (members.sizeIs < required) 0 else poolCount(members.size, n, advancing, brackets)
                deal(members, count).map(FormedPool(s, _))
            }
        else {
            // Strata from the highest down. Each takes what is left of its own stratum, then fills the
            // places its pools leave empty from the strata below, highest stratum and strongest seed first.
            val (pools, _) = strata.foldLeft((List.empty[FormedPool], Set.empty[Long])) { case ((made, used), s) =>
                val own = bySeed(participants.filter(p => p.stratum == s && !used(p.entrant)))
                if (own.isEmpty) (made, used)
                else {
                    val below = participants
                        .filter(p => p.stratum < s && !used(p.entrant))
                        .sortBy(p => (-p.stratum, p.seed, p.entrant))
                    val count = poolCount(own.size, n, advancing, brackets)
                    val fillers = below.take(count * n - own.size)
                    val members = own ++ fillers
                    if (members.sizeIs < required) (made, used)
                    else (made ++ deal(members, count).map(FormedPool(s, _)), used ++ members.map(_.entrant))
                }
            }
            pools
        }
    }

    /** Deals members, already in seed order, into `count` pools by the snake, dropping any pool left empty. */
    private def deal(members: List[Seeded], count: Int): List[List[Seeded]] =
        if (count <= 0) Nil
        else {
            val dealt = members.zipWithIndex.groupMap((_, ix) => snake(ix, count))(_._1)
            (0 until count).toList.flatMap(i => dealt.get(i)).filter(_.nonEmpty)
        }
}
