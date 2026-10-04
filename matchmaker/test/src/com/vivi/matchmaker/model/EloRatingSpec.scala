package com.vivi.matchmaker.model

import munit.FunSuite

/** The arithmetic behind Elo ratings, without a database: what a result does to the ratings it is between. */
class EloRatingSpec extends FunSuite {

    import EloRating.{Seat, adjusted}

    private val a = PlayerId(1)
    private val b = PlayerId(2)
    private val c = PlayerId(3)

    test("between two equally rated players, the winner gains half of k and the loser loses it") {
        assertEquals(adjusted(Seq(Seat(a, 1), Seat(b, 2)), Map(a -> 1500, b -> 1500)), Map(a -> 1516, b -> 1484))
    }

    test("a draw between equals moves nothing") {
        assertEquals(adjusted(Seq(Seat(a, 1), Seat(b, 1)), Map(a -> 1500, b -> 1500)), Map(a -> 1500, b -> 1500))
    }

    test("the favourite gains less for winning than the outsider would have") {
        // Expected 0.76 for the favourite: 32 * 0.24 is 7.7, rounded to 8.
        assertEquals(adjusted(Seq(Seat(a, 1), Seat(b, 2)), Map(a -> 1600, b -> 1400)), Map(a -> 1608, b -> 1392))
        assertEquals(adjusted(Seq(Seat(a, 2), Seat(b, 1)), Map(a -> 1600, b -> 1400)), Map(a -> 1576, b -> 1424))
    }

    test("with three seats, each pair is a game of its own, averaged over the two opponents") {
        assertEquals(
          adjusted(Seq(Seat(a, 1), Seat(b, 2), Seat(c, 3)), Map(a -> 1500, b -> 1500, c -> 1500)),
          Map(a -> 1516, b -> 1500, c -> 1484)
        )
    }

    test("a match with only one player in it moves nothing") {
        assertEquals(adjusted(Seq(Seat(a, 1), Seat(a, 2)), Map(a -> 1500)), Map.empty[PlayerId, Int])
        assertEquals(adjusted(Seq(Seat(a, 1)), Map(a -> 1500)), Map.empty[PlayerId, Int])
    }

    test("a player in two seats does not play themselves, and their seats' changes add up") {
        // a's first seat beats b and a's second loses to b: a win and a loss between equals.
        assertEquals(
          adjusted(Seq(Seat(a, 1), Seat(b, 2), Seat(a, 3)), Map(a -> 1500, b -> 1500)),
          Map(a -> 1500, b -> 1500)
        )
    }
}
