package com.vivi.matchmaker.model.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import Fixtures.*

class ScheduleSpec extends ScalaCheckSuite {

    private def members(n: Int) = (1 to n).toList.map(_.toLong)

    property("a two-player round robin plays every pair once, in n - 1 rounds for an even pool and n for an odd one") {
        forAll(Gen.choose(2, 16)) { n =>
            val planned =
                Schedule.roundRobin(members = members(n), game = twoPlayer, rotateRoles = false, concurrent = 1)
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
            val planned = Schedule.roundRobin(members = members(n), game = g, rotateRoles = false, concurrent)
            val within = planned.groupBy(_.slot).values.forall { ms =>
                ms.flatMap(_.seats.map(_.entrant)).groupBy(identity).values.forall(_.size <= concurrent)
            }
            within && planned.size == Schedule.matchCount(n = n, game = g, rotateRoles = false).toInt
        }
    }

    test("a pool of R plays one match, and one too small for the required roles plays none") {
        assertEquals(Schedule.roundRobin(members = members(2), game = twoPlayer, rotateRoles = false, 1).size, 1)
        assertEquals(Schedule.roundRobin(members = members(1), game = twoPlayer, rotateRoles = false, 1).size, 0)
        // Two required and one recommended: a pool of two still plays, with the recommended seat empty.
        val withRecommended = game(roles = 2, recommended = 1)
        val short = Schedule.roundRobin(members = members(2), game = withRecommended, rotateRoles = false, 1)
        assertEquals(short.map(_.seats.size), List(2))
        assertEquals(Schedule.matchCount(n = 4, game = withRecommended, rotateRoles = false), 4L)
    }

    test("rotating roles has every member of each pairing play each role in it") {
        val planned = Schedule.roundRobin(members = members(4), game = twoPlayer, rotateRoles = true, 1)
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
        val chosen = Schedule.roles(subset = List(3L, 7L), seating = twoPlayer.seatingRoles, game = twoPlayer)
        assertEquals(
          chosen,
          List(
            SeatPlan(entrant = 3L, role = None, chooseOrder = Some(0)),
            SeatPlan(entrant = 7L, role = None, chooseOrder = Some(1))
          )
        )

        val preferring = twoPlayer.copy(roles =
            List(
              TournamentRole(id = 1, roleType = com.vivi.matchmaker.model.RoleType.Required, preferenceLevel = Some(1)),
              TournamentRole(id = 2, roleType = com.vivi.matchmaker.model.RoleType.Required, preferenceLevel = Some(5))
            )
        )
        // The most preferred role, 2, goes to the strongest seed.
        assertEquals(
          Schedule.roles(subset = List(3L, 7L), seating = preferring.seatingRoles, game = preferring).map(_.role),
          List(Some(2), Some(1))
        )

        // Nothing to hand out, or to choose, in a game without roles -- even in a tournament whose seats always choose.
        val open = roleless().copy(hasRoleChoice = true)
        assertEquals(
          Schedule.roles(subset = List(3L, 7L), seating = open.seatingRoles, game = open),
          List(SeatPlan(entrant = 3L, role = None), SeatPlan(entrant = 7L, role = None))
        )
    }

    test("a game without roles plays each pool as one match when the tournament says no number") {
        for (n <- 2 to 12) {
            val members = (1 to n).toList.map(_.toLong)
            val g = roleless()
            val matches = Schedule.roundRobin(members = members, game = g, rotateRoles = true, concurrent = 1)
            assertEquals(Schedule.matchCount(n = n, game = g, rotateRoles = true), 1L)
            assertEquals(matches.map(_.seats.map(_.entrant)), List(members))
            assert(matches.flatMap(_.seats).forall(s => s.role.isEmpty && s.chooseOrder.isEmpty))
        }
    }

    test("a game without roles plays every subset of its players per match, and a smaller pool all together") {
        for (n <- 1 to 9; k <- 2 to 4) {
            val members = (1 to n).toList.map(_.toLong)
            val g = roleless(perMatch = Some(k))
            val matches = Schedule.roundRobin(members = members, game = g, rotateRoles = true, concurrent = 1)
            val expected = if (n < 2) 0 else if (n <= k) 1 else (1 to k).foldLeft(1L)((acc, i) => acc * (n - k + i) / i)
            assertEquals(Schedule.matchCount(n = n, game = g, rotateRoles = true), expected.toLong, s"n=$n k=$k")
            assertEquals(matches.size.toLong, expected.toLong, s"n=$n k=$k")
            assert(matches.forall(_.seats.sizeIs == math.min(n, k)), s"n=$n k=$k")
            assert(matches.flatMap(_.seats).forall(s => s.role.isEmpty && s.chooseOrder.isEmpty))
        }
    }
}
