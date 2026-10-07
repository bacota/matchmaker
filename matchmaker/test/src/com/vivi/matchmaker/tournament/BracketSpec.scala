package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** The pools of every round of an elimination tournament, planned at its start. */
class BracketSpec extends ScalaCheckSuite {

    private case class Shape(entrants: Int, poolSize: Int, advance: Int)

    private val pairs: Gen[Shape] = Gen.choose(1, 70).map(Shape(_, 2, 1))

    private val shapes: Gen[Shape] =
        for {
            poolSize <- Gen.choose(2, 6)
            advance <- Gen.choose(1, poolSize - 1)
            entrants <- Gen.choose(1, 60)
        } yield Shape(entrants, poolSize, advance)

    private def plan(s: Shape): Bracket = Bracket.singleElimination(s.entrants, s.poolSize, s.advance)

    private def seeds(pool: PlannedPool): List[Int] = pool.slots.collect { case PlannedSource.Seed(n) => n }

    private def byes(pool: PlannedPool): Int = pool.slots.count(_ == PlannedSource.Bye)

    property("with pools of two, the first round has a power of two of pools, and the byes go to the top seeds") {
        forAll(pairs) { s =>
            val first = plan(s).rounds.head
            val withBye = first.filter(byes(_) == 1).flatMap(seeds).sorted
            Integer.bitCount(first.size) == 1 &&
            withBye == (1 to withBye.size).toList &&
            first.forall(byes(_) < 2)
        }
    }

    property("every seed is drawn into the first round exactly once, and no pool is all byes") {
        forAll(shapes) { s =>
            val first = plan(s).rounds.head
            first.flatMap(seeds).sorted == (1 to s.entrants).toList &&
            first.forall(p => seeds(p).nonEmpty && p.slots.size == s.poolSize)
        }
    }

    // A snake draft is the yardstick the plan names: the first round is drawn no worse than one.
    property("the first round's seed sums spread no wider than a snake draft's") {
        forAll(shapes) { s =>
            val first = plan(s).rounds.head
            val sums = first.map(seeds(_).sum)
            val pools = first.size
            val snakeSums = Bracket.snake((1 to s.entrants).toList, pools).map(_.sum)
            sums.max - sums.min <= snakeSums.max - snakeSums.min
        }
    }

    test("pools of two are drawn 1 against the last, 2 against the one before, and meet as a bracket") {
        val bracket = Bracket.singleElimination(8, 2, 1, consolation = false)
        assertEquals(bracket.rounds.head.map(seeds), List(List(1, 8), List(2, 7), List(3, 6), List(4, 5)))
        assertEquals(
          bracket.rounds(1).map(_.slots),
          List(
            List(PlannedSource.Winner(1, 1, 1), PlannedSource.Winner(1, 4, 1)),
            List(PlannedSource.Winner(1, 2, 1), PlannedSource.Winner(1, 3, 1))
          )
        )
        assertEquals(bracket.rounds.size, 3)
    }

    property("every bracket reaches a final of one pool, each round smaller than the one before") {
        forAll(shapes) { s =>
            val rounds = plan(s).rounds
            // The players each round is planned for: its slots that are not byes, the consolation pool aside.
            val players = rounds.zipWithIndex.map { (round, i) =>
                val main = if (i == rounds.size - 1) round.filter(_.position == 1) else round
                main.flatMap(_.slots).count(_ != PlannedSource.Bye)
            }
            rounds.last.count(_.position == 1) == 1 &&
            rounds.last.sizeIs <= 2 &&
            rounds.init.forall(_.sizeIs >= 2) &&
            players.zip(players.drop(1)).forall((before, after) => after < before)
        }
    }

    property("a later round's slots name pools of the round before, each finisher once, never deeper than the pool") {
        forAll(shapes) { s =>
            val rounds = plan(s).rounds
            rounds.zipWithIndex.drop(1).forall { (round, i) =>
                val before = rounds(i - 1).map(p => p.position -> p.slots.count(_ != PlannedSource.Bye)).toMap
                val named = round.flatMap(_.slots).collect { case w: PlannedSource.Winner => w }
                named.forall(w => w.round == i && before.get(w.position).exists(w.rank <= _)) &&
                named.distinct.size == named.size
            }
        }
    }

    property("a single-elimination final has a consolation pool beside it when there were two semi-final pools") {
        forAll(Gen.choose(4, 64)) { n =>
            val rounds = Bracket.singleElimination(n, 2, 1).rounds
            rounds.last.map(_.position) == List(1, 2) &&
            rounds.last(1).slots.forall {
                case PlannedSource.Winner(round, _, rank) => round == rounds.size - 1 && rank == 2
                case _                                    => false
            }
        }
    }

    test("three entrants have only one loser in the semi-finals, and so no consolation pool") {
        assertEquals(Bracket.singleElimination(3, 2, 1).rounds.last.map(_.position), List(1))
    }

    test("two entrants play one final, and one plays nobody") {
        assertEquals(Bracket.singleElimination(2, 2, 1).rounds.map(_.size), List(1))
        assertEquals(
          Bracket.singleElimination(1, 2, 1).rounds.head.head.slots,
          List(PlannedSource.Seed(1), PlannedSource.Bye)
        )
    }

    private val playoffs: Gen[Shape] =
        for {
            poolSize <- Gen.choose(3, 6)
            advance <- Gen.choose(1, poolSize - 1)
            entrants <- Gen.choose(1, 60)
        } yield Shape(entrants, poolSize, advance)

    property("a playoff: pools of its size first, then a reseeded round of pairs by seed, then pairs to a final") {
        forAll(playoffs) { s =>
            val bracket = Bracket.playoff(s.entrants, s.poolSize, s.advance)
            val rounds = bracket.rounds
            val first = rounds.head
            if (first.sizeIs == 1) rounds.size == 1 && bracket.reseed.isEmpty
            else {
                // Each pool sends its top `advance` through, but never the whole of it.
                val through = first.map(p => seeds(p).size).map(n => if (n <= 1) n else math.min(s.advance, n - 1)).sum
                val pairs = rounds(1)
                first.flatMap(seeds).sorted == (1 to s.entrants).toList &&
                first.forall(_.slots.size == s.poolSize) &&
                bracket.reseed == Set(2) &&
                Integer.bitCount(pairs.size) == 1 &&
                pairs.forall(p => p.slots.size == 2 && byes(p) < 2) &&
                pairs.flatMap(seeds).sorted == (1 to through).toList &&
                rounds.drop(1).forall(_.forall(_.slots.size == 2)) &&
                rounds.drop(2).forall(_.forall(_.slots.forall(_.isInstanceOf[PlannedSource.Winner]))) &&
                rounds.last.count(_.position == 1) == 1
            }
        }
    }

    test("a playoff of eight in pools of four: two through from each, then semi-finals, a final and a consolation") {
        val bracket = Bracket.playoff(8, 4, 2)
        assertEquals(bracket.rounds.head.map(seeds), List(List(1, 4, 5, 8), List(2, 3, 6, 7)))
        assertEquals(bracket.rounds(1).map(seeds), List(List(1, 4), List(2, 3)))
        assertEquals(bracket.rounds(2).map(_.position), List(1, 2))
        assertEquals(bracket.reseed, Set(2))
    }

    test("a playoff that the pools alone decide has no consolation beside a final drawn straight from them") {
        // Two pools of three, one through from each: the final is the reseeded round itself.
        val bracket = Bracket.playoff(6, 3, 1)
        assertEquals(bracket.rounds.map(_.size), List(2, 1))
        assertEquals(bracket.rounds(1).head.slots, List(PlannedSource.Seed(1), PlannedSource.Seed(2)))
    }

    private val doubles: Gen[Shape] =
        for {
            poolSize <- Gen.frequency(3 -> Gen.const(2), 1 -> Gen.choose(3, 5))
            entrants <- Gen.choose(2, 64)
        } yield Shape(entrants, poolSize, 1)

    property(
      "double elimination: every pool's second place drops once, every losers' winner goes on once, to a final"
    ) {
        forAll(doubles) { s =>
            val bracket = Bracket.doubleElimination(s.entrants, s.poolSize)
            val rounds = bracket.rounds
            val pools = bracket.pools
            // How many players each pool is planned for: the slots that are not byes.
            val size = pools.map(p => (p.round, p.position) -> p.slots.count(_ != PlannedSource.Bye)).toMap
            val named = pools.flatMap(_.slots).collect { case w: PlannedSource.Winner => w }
            val grand = rounds.last
            val earlier = pools.filterNot(_.round == rounds.size)
            grand.size == 1 && grand.head.slots.size == 2 &&
            rounds.forall(_.nonEmpty) &&
            pools.forall(_.slots.size <= s.poolSize) &&
            // Every source is an earlier round's pool, and a place that pool has.
            named.forall(w => w.round < pools.find(_.slots.contains(w)).get.round) &&
            named.forall(w => size.get((w.round, w.position)).exists(w.rank <= _)) &&
            named.distinct.size == named.size &&
            // Every earlier pool's winner goes on, and so does its second place, if it has one, in the winners' bracket.
            earlier.forall(p => named.contains(PlannedSource.Winner(p.round, p.position, 1))) &&
            named.forall(_.rank <= 2)
        }
    }

    test(
      "double elimination of eight in pairs: the losers' bracket alternates, and runs two rounds past the winners'"
    ) {
        val bracket = Bracket.doubleElimination(8, 2)
        assertEquals(bracket.rounds.map(_.size), List(4, 4, 3, 1, 1, 1))
        // Round 2: the winners' semi-finals, then the first round's losers paired, the better seed first.
        assertEquals(
          bracket.rounds(1).drop(2).map(_.slots),
          List(
            List(PlannedSource.Winner(1, 4, 2), PlannedSource.Winner(1, 1, 2)),
            List(PlannedSource.Winner(1, 3, 2), PlannedSource.Winner(1, 2, 2))
          )
        )
        assertEquals(
          bracket.rounds.last.head.slots,
          List(PlannedSource.Winner(3, 1, 1), PlannedSource.Winner(5, 1, 1))
        )
    }

    test("double elimination of two: the loser of the first meeting gets a second") {
        val bracket = Bracket.doubleElimination(2, 2)
        assertEquals(
          bracket.rounds.map(_.map(_.slots)),
          List(
            List(List(PlannedSource.Seed(1), PlannedSource.Seed(2))),
            List(List(PlannedSource.Winner(1, 1, 1), PlannedSource.Winner(1, 1, 2)))
          )
        )
    }

    test("a round robin is one pool of everybody") {
        assertEquals(Bracket.roundRobin(5).pools.map(seeds), List(List(1, 2, 3, 4, 5)))
    }
}
