package com.vivi.matchmaker.tournament

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._
import com.vivi.matchmaker.model._

/** Who fills each slot as a round starts: the named finishers, then the fill rule. */
class AdvancementSpec extends ScalaCheckSuite {

    private val game = GameId(1)
    private val tournament = TournamentId(1)

    private def member(n: Int) = TournamentParticipantId(n.toLong)

    private def slot(id: Int, source: SlotSource) =
        FixtureSlot(game, tournament, FixtureId(100), SlotId(id.toLong), source)

    /** `pools` pools of `size`, each finished in seed order: pool i holds members i*size+1 ... */
    private def finished(pools: Int, size: Int): Map[FixtureId, List[Standing[TournamentParticipantId]]] =
        (0 until pools).map { p =>
            FixtureId(p.toLong) -> (1 to size).toList.map { place =>
                val n = p * size + place
                Standing(member(n), n, (size - place) * 3, (size - place).toDouble - p)
            }
        }.toMap

    test("a winner slot takes the named finisher, a seed slot the seed's holder, a bye nobody") {
        val resolved = Advancement.resolve(
          List(
            slot(1, SlotSource.Winner(FixtureId(0), 1)),
            slot(2, SlotSource.Winner(FixtureId(1), 1)),
            slot(3, SlotSource.Seed(7)),
            slot(4, SlotSource.Bye)
          ),
          finished(2, 2),
          advance = 1,
          seedHolders = Map(7 -> member(99)),
          withdrawn = Set.empty
        )
        assertEquals(resolved, Map(SlotId(1) -> member(1), SlotId(2) -> member(3), SlotId(3) -> member(99)))
    }

    test("a withdrawn finisher's slot goes to the best of those who did not go through") {
        val resolved = Advancement.resolve(
          List(slot(1, SlotSource.Winner(FixtureId(0), 1)), slot(2, SlotSource.Winner(FixtureId(1), 1))),
          finished(2, 2),
          advance = 1,
          seedHolders = Map.empty,
          withdrawn = Set(member(1))
        )
        // Second places are members 2 and 4; 2's differential is the better.
        assertEquals(resolved, Map(SlotId(1) -> member(2), SlotId(2) -> member(3)))
    }

    test("fill slots are taken by highest finish, then differential, then seed") {
        val resolved = Advancement.resolve(
          List(
            slot(1, SlotSource.Winner(FixtureId(0), 1)),
            slot(2, SlotSource.Winner(FixtureId(1), 1)),
            slot(3, SlotSource.Winner(FixtureId(2), 1)),
            slot(4, SlotSource.Winner(FixtureId(0), 2))
          ),
          finished(3, 3),
          advance = 1,
          seedHolders = Map.empty,
          withdrawn = Set.empty
        )
        // The second places are 2, 5 and 8, with differentials 1, 0 and -1.
        assertEquals(resolved(SlotId(4)), member(2))
    }

    test("going through to a reseed round: each pool's top, never a whole pool, then the fill rule, none withdrawn") {
        // Pools of three finish 1-2-3, 4-5-6; with two going through from each and member 4 withdrawn, 5 goes, and the
        // fill rule's best third place — 3, by its differential — takes the seat 4 would have had.
        val through = Advancement.through(finished(2, 3), advance = 2, seats = 4, withdrawn = Set(member(4)))
        assertEquals(through, List(member(1), member(2), member(5), member(3)))
        // A pool of two sends one through, however many may go.
        assertEquals(
          Advancement.through(finished(2, 2), advance = 3, seats = 2, withdrawn = Set.empty),
          List(member(1), member(3))
        )
    }

    property("every slot that can be filled is, nobody twice, nobody withdrawn, and no advancer displaced") {
        forAll(Gen.choose(1, 6), Gen.choose(2, 4), Gen.choose(0, 3)) { (pools, size, withdrawals) =>
            val previous = finished(pools, size)
            val slots = (0 until pools).toList.map(p => slot(p + 1, SlotSource.Winner(FixtureId(p.toLong), 1))) ++
                (1 to pools).toList.map(i => slot(100 + i, SlotSource.Winner(FixtureId(0), 2)))
            // Withdrawn: the winners of the second pool on, as many as there are.
            val out = (1 to withdrawals).filter(_ < pools).map(i => member(i * size + 1)).toSet
            val resolved = Advancement.resolve(slots, previous, advance = 1, Map.empty, out)
            val placed = resolved.values.toList
            val winners = previous.values.map(_.head.member).filterNot(out).toSet
            placed.distinct.size == placed.size &&
            placed.forall(m => !out.contains(m)) &&
            winners.subsetOf(placed.toSet) &&
            resolved.size == math.min(slots.size, previous.values.flatten.size - out.size)
        }
    }
}
