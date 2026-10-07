package com.vivi.matchmaker.tournament

import scala.util.Random
import scala.math.Ordering.Implicits.infixOrderingOps
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop._

/** Seeding at the start, within a pool after a round, and for a reseed round. */
class SeedingSpec extends ScalaCheckSuite {

    property("the field is seeded by rating, highest first, and the same draw breaks ties the same way") {
        forAll(Gen.listOf(Gen.choose(1400, 1420)), Gen.long) { (ratings, draw) =>
            val field = ratings.zipWithIndex.map((r, i) => i -> r)
            val seeded = Seeding.initial(field, Random(draw))
            val byRating = seeded.map(field.toMap)
            byRating == byRating.sortBy(-_) &&
            seeded.sorted == field.map(_._1) &&
            seeded == Seeding.initial(field, Random(draw))
        }
    }

    test("a pool's seeds go to its finishers in order: an upset swaps them") {
        val standings = List(Standing("b", 4, 3, 1.0), Standing("a", 1, 0, -1.0))
        assertEquals(Seeding.withinPool(standings), Map("b" -> 1, "a" -> 4))
    }

    property("a reseed is a ranking of everybody, by points, differential, rating, then seed") {
        val gen = Gen.listOf(for {
            points <- Gen.choose(0, 9)
            diff <- Gen.choose(-3, 3)
            rating <- Gen.choose(1400, 1600)
        } yield (points, diff.toDouble, rating))
        forAll(gen) { lines =>
            val records = lines.zipWithIndex.map { case ((p, d, r), i) => Seeding.Record(i, i + 1, p, d, r) }
            val seeds = Seeding.reseed(records)
            val ordered = records.sortBy(r => seeds(r.member))
            seeds.values.toList.sorted == (1 to records.size).toList &&
            ordered
                .zip(ordered.drop(1))
                .forall((x, y) =>
                    (x.points, x.differential, x.rating, -x.seed) >= (y.points, y.differential, y.rating, -y.seed)
                )
        }
    }
}
