package com.vivi.matchmaker.model.tournament

import munit.FunSuite
import Fixtures.*

class StandingsSpec extends FunSuite {

    test("a round robin being played shows its finished matches' standings, and a finished table is left as it is") {
        val design = TournamentDesign(List(StageDesign(brackets = false, poolSize = Some(4), demote = None)))
        val env = Env(design, twoPlayer, (1L to 4L).map(_ -> 1500).toMap)
        val (first, matches) = Tournament.nextRound(Tournament.start((1L to 4L).toList, env), env)
        // One match of the first slot played, the stronger seed winning; the other not yet.
        val played = matches.head
        val winner = played.planned.seats.map(_.entrant).min
        val recorded = Tournament.record(
          first,
          played.poolId,
          played.planned.no,
          played.planned.seats.map(s => Placing(s.entrant, Some(if (s.entrant == winner) 1 else 2)))
        )
        val shown = Standings.provisional(recorded, design, twoPlayer)
        val pool = Tournament.allPools(shown.root).head
        assertEquals(pool.members.find(_.entrant == winner).flatMap(_.rank), Some(1))
        assertEquals(pool.members.find(_.entrant == winner).flatMap(_.points), Some(2.0))
        // Nothing about the pool itself changes: it is still being played.
        assert(!pool.completed)
        assertEquals(Standings.provisional(first, design, twoPlayer), first)
    }
}
