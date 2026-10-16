package com.vivi.matchmaker.persistence

import skunk.data.Arr
import com.vivi.matchmaker.model.{GameId, TournamentId}

/** A tournament's key: what a query of several tournaments at once is asked for, and answers by. */
case class TournamentKey(gameId: GameId, tournamentId: TournamentId)

object TournamentKey {

    /** The keys as the two arrays a query unnests into (game, tournament) pairs, each a lookup on a table's key. */
    def arrays(keys: List[TournamentKey]): (Arr[Int], Arr[Long]) =
        (Arr.fromFoldable(keys.map(_.gameId.value)), Arr.fromFoldable(keys.map(_.tournamentId.value)))
}
