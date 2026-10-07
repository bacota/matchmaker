package com.vivi.matchmaker.tournament

import scala.util.Random

/** Seeds: given when a tournament starts, swapped within each pool as each round ends, and recomputed for every player
  * when a round is marked `reseed`.
  */
object Seeding {

    /** The field in seed order, best first: by overall rating, highest first, with ties drawn at random from `random` —
      * seeded by the caller, so that a draw can be reproduced.
      */
    def initial[K](ratings: List[(K, Int)], random: Random): List[K] =
        random.shuffle(ratings).sortBy(-_._2).map(_._1)

    /** A pool's own seeds, handed out again in its finishing order: the winner takes the best seed in the pool, and so
      * on down. Seeds outside the pool are untouched.
      */
    def withinPool[K](standings: List[Standing[K]]): Map[K, Int] =
        standings.map(_.member).zip(standings.map(_.seed).sorted).toMap

    /** One player's record across the tournament so far, for a reseed. */
    case class Record[K](member: K, seed: Int, points: Int, differential: Double, rating: Int)

    /** Every player's new seed, from their record: points, then score differential, then overall rating, then their
      * seed before.
      */
    def reseed[K](records: List[Record[K]]): Map[K, Int] =
        records
            .sortBy(r => (-r.points, -r.differential, -r.rating, r.seed))
            .zipWithIndex
            .map((r, i) => r.member -> (i + 1))
            .toMap
}
