package com.vivi.matchmaker.model.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import Fixtures.*

class ScheduleSpec extends ScalaCheckSuite {

    private def members(n: Int) = (1 to n).toList.map(_.toLong)

    property("a two-player round robin plays every pair once, in n - 1 rounds for an even pool and n for an odd one") {
        forAll(Gen.choose(2, 16)) { n =>
            val planned = Schedule.roundRobin(members(n), twoPlayer, rotateRoles = false, concurrent = 1)
            val pairs = planned.map(_.seats.map(_.entrant).toSet)
            val slots = planned.map(_.slot).distinct.size
            val nobodyTwice = planned.groupBy(_.slot).values.forall { ms =>
                val who = ms.flatMap(_.seats.map(_.entrant))
                who.distinct.size == who.size
            }
            pairs.size == n * (n - 1) / 2 && pairs.distinct.size == pairs.size && nobodyTwice &&
            slots == (if (n % 2 == 0) n - 1 else n)
        }
    }

    property("with concurrency, nobody plays more matches in a slot than it allows") {
        forAll(Gen.choose(3, 10), Gen.choose(1, 3), Gen.choose(2, 3)) { (n, concurrent, r) =>
            val g = game(roles = r)
            val planned = Schedule.roundRobin(members(n), g, rotateRoles = false, concurrent)
            val within = planned.groupBy(_.slot).values.forall { ms =>
                ms.flatMap(_.seats.map(_.entrant)).groupBy(identity).values.forall(_.size <= concurrent)
            }
            within && planned.size == Schedule.matchCount(n, g, rotateRoles = false).toInt
        }
    }

    test("a pool of R plays one match, and one too small for the required roles plays none") {
        assertEquals(Schedule.roundRobin(members(2), twoPlayer, rotateRoles = false, 1).size, 1)
        assertEquals(Schedule.roundRobin(members(1), twoPlayer, rotateRoles = false, 1).size, 0)
        // Two required and one recommended: a pool of two still plays, with the recommended seat empty.
        val withRecommended = game(roles = 2, recommended = 1)
        val short = Schedule.roundRobin(members(2), withRecommended, rotateRoles = false, 1)
        assertEquals(short.map(_.seats.size), List(2))
        assertEquals(Schedule.matchCount(4, withRecommended, rotateRoles = false), 4L)
    }

    test("rotating roles has every member of each pairing play each role in it") {
        val planned = Schedule.roundRobin(members(4), twoPlayer, rotateRoles = true, 1)
        assertEquals(planned.size, 12)
        val byPair = planned.groupBy(_.seats.map(_.entrant).toSet)
        assert(byPair.values.forall { ms =>
            ms.flatMap(_.seats).groupBy(_.entrant).values.forall(_.flatMap(_.role).toSet == Set(1, 2))
        })
        // The second leg follows the first: no member plays twice in a slot.
        assert(
          planned.groupBy(_.slot).values.forall(ms => ms.flatMap(_.seats.map(_.entrant)).distinct.size == ms.size * 2)
        )
    }

    test(
      "roles are chosen in seed order where they matter, handed out by preference where any has one, and in seat order where they do not"
    ) {
        val chosen = Schedule.roles(List(3L, 7L), twoPlayer.seatingRoles, twoPlayer)
        assertEquals(chosen, List(SeatPlan(3L, None, Some(0)), SeatPlan(7L, None, Some(1))))

        val preferring = twoPlayer.copy(roles =
            List(
              TournamentRole(1, com.vivi.matchmaker.model.RoleType.Required, Some(1)),
              TournamentRole(2, com.vivi.matchmaker.model.RoleType.Required, Some(5))
            )
        )
        // The most preferred role, 2, goes to the strongest seed.
        assertEquals(
          Schedule.roles(List(3L, 7L), preferring.seatingRoles, preferring).map(_.role),
          List(Some(2), Some(1))
        )

        val unimportant = game(significant = false)
        assertEquals(
          Schedule.roles(List(3L, 7L), unimportant.seatingRoles, unimportant).map(_.role),
          List(Some(1), Some(2))
        )
    }
}
