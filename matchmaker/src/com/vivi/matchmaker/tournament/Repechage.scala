package com.vivi.matchmaker.tournament

/** A repechage's pools, planned once the final pair is known (tournament-plan Phase 10): the players who lost to each
  * finalist get a second chance, at third place.
  *
  * Each finalist's victims form a chain, in the order they were beaten: the first two play, the winner plays the next,
  * and so on up to the one beaten in the semi-final. The two chains' winners then play for third. A chain of one has
  * nothing to play, and its player is its winner; a chain of none — a finalist who had byes all the way — has no
  * winner, and whoever won the other chain is third without playing for it.
  *
  * The first links are played in the final's round, since everybody in them is known by then; each later link in the
  * round after the one before it; and the match for third in the round after both chains are done. In the final's round
  * the final comes first; after it, the chains' pools go in chain order, the match for third last.
  */
object Repechage {

    enum Source[+A] {

        /** A player beaten by a finalist, as the caller names them. */
        case Beaten(player: A)

        /** The winner of an earlier repechage pool, by its index in the plan. */
        case WinnerOf(pool: Int)
    }

    case class Pool[A](round: Int, position: Int, sources: List[Source[A]])

    /** The repechage's pools, for `chains` — each finalist's victims, earliest first — and a final in `finalRound`. */
    def plan[A](chains: List[List[A]], finalRound: Int): List[Pool[A]] = {
        // A chain's links as (round, sources), and its winner with the first round it can play in after.
        def links(chain: List[A]): (List[(Int, List[Source[A]])], Option[(Source[A], Int)]) = chain match {
            case Nil         => (Nil, None)
            case only :: Nil => (Nil, Some((Source.Beaten(only), finalRound)))
            case a :: b :: rest =>
                val first = (finalRound, List[Source[A]](Source.Beaten(a), Source.Beaten(b)))
                // Sources name links by their place in the chain for now; renumbered into the plan below.
                val later =
                    rest.zipWithIndex.map((c, i) => (finalRound + i + 1, List(Source.WinnerOf(i), Source.Beaten(c))))
                (first :: later, Some((Source.WinnerOf(rest.size), finalRound + rest.size + 1)))
        }

        val planned = chains.map(links)
        // Each chain's links, numbered in the plan: a chain's links follow the chains before it.
        val offsets = planned.scanLeft(0)(_ + _._1.size)
        def renumber(s: Source[A], offset: Int): Source[A] = s match {
            case Source.WinnerOf(i) => Source.WinnerOf(offset + i)
            case beaten             => beaten
        }
        val chainPools = planned.zip(offsets).flatMap { case ((ls, _), offset) =>
            ls.map((round, sources) => (round, sources.map(renumber(_, offset))))
        }
        val winners = planned.zip(offsets).flatMap { case ((_, winner), offset) =>
            winner.map((s, ready) => (renumber(s, offset), ready))
        }
        val third = Option.when(winners.sizeIs == 2)((winners.map(_._2).max, winners.map(_._1)))
        val all = chainPools ++ third.toList
        // Positions within each round, after the final's in its round.
        val counters = all.map(_._1).distinct.map(r => r -> (if (r == finalRound) 1 else 0)).toMap
        all
            .foldLeft((counters, List.empty[Pool[A]])) { case ((next, acc), (round, sources)) =>
                (next.updated(round, next(round) + 1), acc :+ Pool(round, next(round) + 1, sources))
            }
            ._2
    }
}
