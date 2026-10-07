package com.vivi.matchmaker.tournament

import com.vivi.matchmaker.model.{FixtureId, FixtureSlot, SlotId, SlotSource, TournamentParticipantId}

/** Who fills each slot of a round as it starts.
  *
  *   - A bye, nobody.
  *   - A `Seed` slot, the participant holding that seed now.
  *   - A `Winner` slot of rank up to the round's `advance`, whoever finished there in the named pool.
  *   - Every other slot — a fill slot, or one whose player has withdrawn or does not exist — by the fill rule: the best
  *     of the previous round's players who did not go through, by highest finish in their pool, then total score
  *     differential, then lowest seed.
  *
  * Nobody is placed twice, and nobody withdrawn is placed at all. A slot nobody can fill is left empty, and plays as a
  * bye.
  */
object Advancement {

    type Member = TournamentParticipantId

    def resolve(
        slots: List[FixtureSlot],
        previous: Map[FixtureId, List[Standing[Member]]],
        advance: Int,
        seedHolders: Map[Int, Member],
        withdrawn: Set[Member]
    ): Map[SlotId, Member] = {
        def available(m: Member) = !withdrawn.contains(m)

        val exact: List[(SlotId, Member)] = slots.flatMap { slot =>
            (slot.source match {
                case SlotSource.Bye        => None
                case SlotSource.Seed(seed) => seedHolders.get(seed)
                case SlotSource.Winner(pool, rank) if rank <= advance =>
                    previous.get(pool).flatMap(_.lift(rank - 1)).map(_.member)
                case SlotSource.Winner(_, _) => None
            }).filter(available).map(slot.slotId -> _)
        }
        // A player named by two slots goes to the first of them; the other is left to the fill rule.
        val placed = exact.foldLeft(List.empty[(SlotId, Member)]) { (acc, next) =>
            if (acc.exists(_._2 == next._2)) acc else acc :+ next
        }
        val placedMembers = placed.map(_._2).toSet
        val candidates = fillOrder(previous).filter(m => available(m) && !placedMembers.contains(m))
        val open = slots.filter(s => s.source != SlotSource.Bye && !placed.exists(_._1 == s.slotId)).map(_.slotId)
        (placed ++ open.zip(candidates)).toMap
    }

    /** Every player of the previous round's pools, in the order the fill rule takes them. */
    def fillOrder(previous: Map[FixtureId, List[Standing[Member]]]): List[Member] =
        previous.values.toList
            .flatMap(_.zipWithIndex.map((s, i) => (s, i + 1)))
            .sortBy((s, finish) => (finish, -s.differential, s.seed))
            .map(_._1.member)
}
