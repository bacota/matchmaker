package com.vivi.matchmaker.model.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class PoolsSpec extends ScalaCheckSuite {

    test("with brackets, the pools are the smallest power of n/a that holds everyone (Q1)") {
        // Single elimination: pools of 2, one advancing.
        assertEquals(Pools.poolCount(r = 16, n = 2, advancing = Some(1), brackets = true), 8)
        assertEquals(Pools.poolCount(r = 13, n = 2, advancing = Some(1), brackets = true), 8)
        assertEquals(Pools.poolCount(r = 9, n = 2, advancing = Some(1), brackets = true), 8)
        assertEquals(Pools.poolCount(r = 8, n = 2, advancing = Some(1), brackets = true), 4)
        // Pools of 4 with two advancing: a power of 2.
        assertEquals(Pools.poolCount(r = 9, n = 4, advancing = Some(2), brackets = true), 4)
        // Pools of 4 with one advancing: a power of 4.
        assertEquals(Pools.poolCount(r = 17, n = 4, advancing = Some(1), brackets = true), 16)
    }

    test("a stratum that fits one pool is one pool, and without brackets the pools are as few as hold everyone") {
        assertEquals(Pools.poolCount(r = 1, n = 2, advancing = Some(1), brackets = true), 1)
        assertEquals(Pools.poolCount(r = 2, n = 4, advancing = Some(1), brackets = true), 1)
        assertEquals(Pools.poolCount(r = 0, n = 2, advancing = Some(1), brackets = true), 0)
        assertEquals(Pools.poolCount(r = 10, n = 4, advancing = Some(1), brackets = false), 3)
        // Brackets with nobody demoted count as without.
        assertEquals(Pools.poolCount(r = 10, n = 4, advancing = None, brackets = true), 3)
    }

    test("the snake runs down the pools and back up") {
        assertEquals((0 until 8).map(Pools.snake(_, 4)).toList, List(0, 1, 2, 3, 3, 2, 1, 0))
        assertEquals((0 until 6).map(Pools.snake(_, 3)).toList, List(0, 1, 2, 2, 1, 0))
    }

    private def seeded(n: Int, stratum: Int = 0, from: Int = 1) =
        (from until from + n).toList.map(i => Seeded(entrant = i.toLong, seed = i, stratum = stratum))

    test("a bracket of 8 pairs 1-8, 2-7, 3-6 and 4-5") {
        val pools = Pools.form(
          participants = seeded(n = 8),
          n = 2,
          required = 2,
          advancing = Some(1),
          brackets = true,
          strict = true
        )
        assertEquals(pools.map(_.members.map(_.seed)).toSet, Set(List(1, 8), List(2, 7), List(3, 6), List(4, 5)))
    }

    test("byes go to the strongest seeds") {
        val pools = Pools.form(
          participants = seeded(n = 5),
          n = 2,
          required = 2,
          advancing = Some(1),
          brackets = true,
          strict = true
        )
        assertEquals(pools.size, 4)
        val byes = pools.filter(_.members.sizeIs == 1).flatMap(_.members.map(_.seed))
        assertEquals(byes.toSet, Set(1, 2, 3))
    }

    test("strict strata never mix, and a stratum too small for a match forms no pool") {
        val ps =
            seeded(n = 4, stratum = 0) ++ seeded(n = 3, stratum = -1, from = 5) ++ seeded(n = 1, stratum = -2, from = 8)
        val pools =
            Pools.form(participants = ps, n = 2, required = 2, advancing = Some(1), brackets = true, strict = true)
        assert(pools.forall(p => p.members.forall(_.stratum == p.stratum)))
        assertEquals(pools.filter(_.stratum == 0).size, 2)
        assertEquals(pools.filter(_.stratum == -1).map(_.members.size).sorted, List(1, 2))
        assert(!pools.exists(_.stratum == -2))
    }

    test("without strict strata, a short stratum's pool is filled from the nearest stratum below, weakest seed first") {
        val ps =
            seeded(n = 1, stratum = 0) ++ seeded(n = 3, stratum = -1, from = 2) ++ seeded(n = 2, stratum = -2, from = 5)
        val pools =
            Pools.form(participants = ps, n = 2, required = 2, advancing = Some(1), brackets = true, strict = false)
        val top = pools.find(_.stratum == 0).get
        assertEquals(top.members.map(_.entrant), List(1L, 4L))
        // What is left of stratum -1 forms its own pool, and -2 its own.
        assertEquals(pools.filter(_.stratum == -1).flatMap(_.members.map(_.entrant)).toSet, Set(2L, 3L))
        assertEquals(pools.filter(_.stratum == -2).flatMap(_.members.map(_.entrant)).toSet, Set(5L, 6L))
    }

    property("every participant of a stratum that can fill a match is in exactly one pool, and no pool is over size") {
        forAll(Gen.choose(2, 70), Gen.choose(2, 6), Gen.oneOf(true, false)) { (r, n, brackets) =>
            val pools = Pools.form(
              participants = seeded(n = r),
              n = n,
              required = 2,
              advancing = Some(1),
              brackets = brackets,
              strict = true
            )
            val placed = pools.flatMap(_.members.map(_.entrant))
            placed.sorted == (1 to r).map(_.toLong).toList && pools.forall(_.members.sizeIs <= n)
        }
    }

    property("pools differ in size by at most one") {
        forAll(Gen.choose(2, 70), Gen.choose(2, 6)) { (r, n) =>
            val sizes = Pools
                .form(
                  participants = seeded(n = r),
                  n = n,
                  required = 2,
                  advancing = Some(1),
                  brackets = false,
                  strict = true
                )
                .map(_.members.size)
            sizes.max - sizes.min <= 1
        }
    }
}
