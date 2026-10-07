package com.vivi.matchmaker.tournament

import scala.util.Random
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** The matches a pool plays, and how each seat gets its role. */
class PoolScheduleSpec extends ScalaCheckSuite {

    private val two = List(RoleSpec("white", preferred = true), RoleSpec("black"))
    private val plain = List(RoleSpec("first"), RoleSpec("second"))
    private val three = List(RoleSpec("a"), RoleSpec("b"), RoleSpec("c"))

    private def members(n: Int): List[Int] = (1 to n).toList

    private def roleOf(m: PlannedMatch[Int], member: Int): SeatRole = m.seats.find(_.member == member).get.role

    property("with no rotations, every way of seating the pool together plays once") {
        forAll(Gen.choose(1, 8), Gen.oneOf(plain, three)) { (n, roles) =>
            val planned = PoolSchedule.matches(members(n), roles, RoleMode.BySeed, Random(1))
            val groups = planned.map(_.seats.map(_.member).toSet)
            groups.distinct.size == groups.size &&
            groups.toSet == members(n).combinations(roles.size).map(_.toSet).toSet &&
            planned.map(_.matchNo) == (1 to planned.size).toList
        }
    }

    property("with rotations, every player plays every role at least that many times") {
        forAll(Gen.choose(2, 6), Gen.choose(1, 3), Gen.oneOf(plain, three)) { (n, rotations, roles) =>
            val planned = PoolSchedule.matches(members(n), roles, RoleMode.Rotate(rotations), Random(1))
            n < roles.size || members(n).forall(member =>
                roles.forall(role =>
                    planned.count(m => m.seats.contains(PlannedSeat(member, SeatRole.Assigned(role.name)))) >= rotations
                )
            )
        }
    }

    // RoundRobin with rotations: pairs do not meet in back-to-back matches where the schedule allows it.
    property("in a pool of three or more, the same players never meet in two matches in a row") {
        forAll(Gen.choose(3, 8), Gen.choose(1, 3)) { (n, rotations) =>
            val planned = PoolSchedule.matches(members(n), plain, RoleMode.Rotate(rotations), Random(1))
            planned.zip(planned.drop(1)).forall((a, b) => a.seats.map(_.member).toSet != b.seats.map(_.member).toSet)
        }
    }

    test("the higher seed takes a free preferred role, and the rest choose in seed order") {
        val planned = PoolSchedule.matches(List(1, 2), two, RoleMode.Choose, Random(1))
        assertEquals(planned.size, 1)
        // One seat left after the preferred role is taken: it is given the last role, and nobody chooses.
        assertEquals(roleOf(planned.head, 1), SeatRole.Assigned("white"))
        assertEquals(roleOf(planned.head, 2), SeatRole.Assigned("black"))
        assertEquals(planned.head.chooseOrder, Nil)
    }

    test("with no preferred role, the seats choose in seed order, best first") {
        val planned = PoolSchedule.matches(List(3, 1, 2).sorted, three, RoleMode.Choose, Random(1))
        assertEquals(planned.head.chooseOrder, List(1, 2, 3))
        assert(planned.head.seats.forall(_.role == SeatRole.ToChoose))
    }

    test("a game that cannot run the choosing gives the roles out by seed, after the preferred ones") {
        val roles = List(RoleSpec("a"), RoleSpec("b", preferred = true), RoleSpec("c"))
        val planned = PoolSchedule.matches(List(1, 2, 3), roles, RoleMode.BySeed, Random(1))
        assertEquals(
          planned.head.seats.map(s => s.member -> s.role).toMap,
          Map(
            1 -> SeatRole.Assigned("b"),
            2 -> SeatRole.Assigned("a"),
            3 -> SeatRole.Assigned("c")
          )
        )
    }

    property("roles dealt at random are a permutation, and the same draw gives the same deal") {
        forAll(Gen.choose(3, 6), Gen.long) { (n, seed) =>
            val a = PoolSchedule.matches(members(n), three, RoleMode.AtRandom, Random(seed))
            val b = PoolSchedule.matches(members(n), three, RoleMode.AtRandom, Random(seed))
            a == b && a.forall(m =>
                m.seats.map(_.role).collect { case SeatRole.Assigned(r) => r }.sorted == List("a", "b", "c")
            )
        }
    }

    test("a pool too small for a match plays nothing") {
        assertEquals(PoolSchedule.matches(List(1), plain, RoleMode.BySeed, Random(1)), Nil)
        assertEquals(PoolSchedule.matches(List(1, 2), three, RoleMode.BySeed, Random(1)), Nil)
    }

    test("a tie-break is one more match among the tied, numbered on, with no tie allowed") {
        val again = PoolSchedule.rematches(List(2, 3), two, RoleMode.Rotate(2), Random(1), after = 6)
        assertEquals(again.map(_.matchNo), List(7))
        assert(again.forall(_.noTie))
        assertEquals(roleOf(again.head, 2), SeatRole.Assigned("white"))
    }
}
