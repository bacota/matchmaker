package com.vivi.matchmaker.model

import munit.FunSuite

/** The arithmetic behind Elo ratings, without a database: what a result does to each seat's rating. */
class EloRatingSpec extends FunSuite {

    import EloRating.{Seat, deltas}

    private val a = PlayerId(1)
    private val b = PlayerId(2)
    private val c = PlayerId(3)

    private def seat(n: Long, player: PlayerId, rating: Int, rank: Int): Seat =
        Seat(participant = ParticipantId(n), player = player, rating = rating, rank = rank)

    private def by(n: Long): ParticipantId = ParticipantId(n)

    test("between two equally rated players, the winner gains half of k and the loser loses it") {
        assertEquals(
          deltas(
            Seq(seat(n = 1, player = a, rating = 1500, rank = 1), seat(n = 2, player = b, rating = 1500, rank = 2))
          ),
          Map(by(1) -> 16, by(2) -> -16)
        )
    }

    test("a draw between equals moves nothing") {
        assertEquals(
          deltas(
            Seq(seat(n = 1, player = a, rating = 1500, rank = 1), seat(n = 2, player = b, rating = 1500, rank = 1))
          ),
          Map(by(1) -> 0, by(2) -> 0)
        )
    }

    test("the favourite gains less for winning than the outsider would have") {
        // Expected 0.76 for the favourite: 32 * 0.24 is 7.7, rounded to 8.
        assertEquals(
          deltas(
            Seq(seat(n = 1, player = a, rating = 1600, rank = 1), seat(n = 2, player = b, rating = 1400, rank = 2))
          ),
          Map(by(1) -> 8, by(2) -> -8)
        )
        assertEquals(
          deltas(
            Seq(seat(n = 1, player = a, rating = 1600, rank = 2), seat(n = 2, player = b, rating = 1400, rank = 1))
          ),
          Map(by(1) -> -24, by(2) -> 24)
        )
    }

    test("with three seats, each pair is a game of its own, averaged over the two opponents") {
        assertEquals(
          deltas(
            Seq(
              seat(n = 1, player = a, rating = 1500, rank = 1),
              seat(n = 2, player = b, rating = 1500, rank = 2),
              seat(n = 3, player = c, rating = 1500, rank = 3)
            )
          ),
          Map(by(1) -> 16, by(2) -> 0, by(3) -> -16)
        )
    }

    test("a match with only one player in it has no deltas") {
        assertEquals(
          deltas(
            Seq(seat(n = 1, player = a, rating = 1500, rank = 1), seat(n = 2, player = a, rating = 1500, rank = 2))
          ),
          Map.empty[ParticipantId, Int]
        )
        assertEquals(deltas(Seq(seat(n = 1, player = a, rating = 1500, rank = 1))), Map.empty[ParticipantId, Int])
    }

    test("a match with a player in two of its seats has no deltas, since they would not add up to nothing") {
        assertEquals(
          deltas(
            Seq(
              seat(n = 1, player = a, rating = 1500, rank = 1),
              seat(n = 2, player = b, rating = 1500, rank = 2),
              seat(n = 3, player = a, rating = 1500, rank = 3)
            )
          ),
          Map.empty[ParticipantId, Int]
        )
        assert(!EloRating.hasDistinctPlayers(Seq(a, b, a)))
        assert(EloRating.hasDistinctPlayers(Seq(a, b, c)))
    }
}
