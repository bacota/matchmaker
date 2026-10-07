package com.vivi.engine

import java.time.Instant
import munit.FunSuite

/** Where each seat finished, as the results report it: [[Game.placing]].
  *
  * A game of two sides ranks by its outcome — 1 for a win or a draw, 2 for a loss — and every engine there is relies on
  * that default. A game of more seats gives each its place, and matchmaker records those ranks as they come.
  */
class PlacingSpec extends FunSuite {

    private case class Runner(cognitoId: String, participantId: Long) extends SeatLike

    private case class Lap(participantId: Long, takenAt: Instant, startedAt: Instant) extends TurnLike

    /** A finished race: each runner's place, ties sharing one. */
    private case class Race(matchId: String, runners: List[Runner], places: Map[Long, Int]) extends MatchLike {
        def isPublic: Boolean = false
        def moveCallbackUrl: Option[String] = None
        def resultsCallbackUrl: Option[String] = None
    }

    private class RaceGame(placed: Boolean) extends Game[Race, Runner, Lap] {
        def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, Race] = Left("not used")
        def seats(m: Race): List[Runner] = m.runners
        def isOver(m: Race): Boolean = true
        def markCompleted(m: Race): Race = m
        def pending(m: Race): List[Runner] = Nil
        def clockStartedAt(m: Race): Instant = Instant.EPOCH
        def turns(m: Race): List[Lap] = Nil
        def sequence(m: Race): Long = 0
        def outcome(m: Race, seat: Runner): Outcome =
            if (m.places(seat.participantId) == 1) Outcome.Win else Outcome.Loss
        def scores(m: Race, seat: Runner): Map[String, ujson.Value] = Map.empty
        def clock(m: Race): Option[TurnClock] = None
        def withClock(m: Race, clock: TurnClock): Race = m
        override def placing(m: Race, seat: Runner): Int =
            if (placed) m.places(seat.participantId) else super.placing(m, seat)
    }

    private val race = Race(
      "m-1",
      List(Runner("a", 1L), Runner("b", 2L), Runner("c", 3L), Runner("d", 4L)),
      Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 3)
    )

    private def ranks(game: RaceGame): Map[Long, Int] =
        GameEngine(game, InMemoryMatchStore[Race](), RecordingMatchmaker(), "http://engine.test")
            .resultsOf(race)
            .results
            .map(r => r.participantId -> r.rank)
            .toMap

    test("a game that places its seats reports each place as the rank, ties sharing one") {
        assertEquals(ranks(RaceGame(placed = true)), Map(1L -> 1, 2L -> 2, 3L -> 3, 4L -> 3))
    }

    test("a game that does not ranks by outcome, as every engine always has: 1 for a win, 2 otherwise") {
        assertEquals(ranks(RaceGame(placed = false)), Map(1L -> 1, 2L -> 2, 3L -> 2, 4L -> 2))
    }

    test("the winner is still the seat whose outcome is a win, however the game places") {
        val results =
            GameEngine(RaceGame(placed = true), InMemoryMatchStore[Race](), RecordingMatchmaker(), "http://e")
                .resultsOf(race)
                .results
        assertEquals(results.filter(_.isWinner).map(_.participantId), List(1L))
    }
}
