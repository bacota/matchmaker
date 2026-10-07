package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._
import Repechage.{Pool, Source}

/** The repechage for third place, once the final pair is known. */
class RepechageSpec extends ScalaCheckSuite {

    test("each finalist's victims play up their chain, earliest first, and the chains' winners play for third") {
        val pools = Repechage.plan(List(List("a1", "a2", "a3"), List("b1", "b2")), finalRound = 4)
        assertEquals(
          pools,
          List(
            Pool(4, 2, List(Source.Beaten("a1"), Source.Beaten("a2"))),
            Pool(5, 1, List(Source.WinnerOf(0), Source.Beaten("a3"))),
            Pool(4, 3, List(Source.Beaten("b1"), Source.Beaten("b2"))),
            Pool(6, 1, List(Source.WinnerOf(1), Source.WinnerOf(2)))
          )
        )
    }

    test("two semi-final losers alone play for third beside the final") {
        assertEquals(
          Repechage.plan(List(List("a"), List("b")), finalRound = 2),
          List(Pool(2, 2, List(Source.Beaten("a"), Source.Beaten("b"))))
        )
    }

    test("with nobody beaten on one side, the other chain's winner is third without a match for it") {
        assertEquals(Repechage.plan(List(Nil, List("b")), finalRound = 2), Nil)
        assertEquals(
          Repechage.plan(List(Nil, List("b1", "b2")), finalRound = 2),
          List(Pool(2, 2, List(Source.Beaten("b1"), Source.Beaten("b2"))))
        )
    }

    property("everybody beaten plays once on entering; every pool but the last is won into a later one") {
        val chains =
            Gen.listOfN(2, Gen.choose(0, 6)).map(_.zipWithIndex.map((n, c) => (1 to n).toList.map(i => s"$c-$i")))
        forAll(chains, Gen.choose(1, 8)) { (cs, finalRound) =>
            val pools = Repechage.plan(cs, finalRound)
            val beaten = pools.flatMap(_.sources).collect { case Source.Beaten(p) => p }
            val fed = pools.flatMap(_.sources).collect { case Source.WinnerOf(i) => i }
            val playing =
                cs.filter(_.sizeIs >= 2).flatten ++ (if (cs.forall(_.nonEmpty)) cs.filter(_.sizeIs == 1).flatten
                                                     else Nil)
            beaten.sorted == playing.sorted &&
            fed.distinct.size == fed.size &&
            fed.forall(i => pools.indices.contains(i)) &&
            pools.zipWithIndex.forall((p, i) =>
                p.sources.collect { case Source.WinnerOf(j) => pools(j).round }.forall(_ < p.round) &&
                    (fed.contains(i) || i == pools.size - 1 || cs.exists(_.isEmpty))
            ) &&
            pools.forall(p => p.round >= finalRound && p.sources.size == 2) &&
            pools
                .groupBy(_.round)
                .forall((r, ps) =>
                    ps.map(_.position).sorted == (1 to ps.size).map(_ + (if (r == finalRound) 1 else 0)).toList
                )
        }
    }
}
