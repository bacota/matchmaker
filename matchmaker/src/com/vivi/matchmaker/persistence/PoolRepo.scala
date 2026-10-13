package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** A tournament's pools (V52): the root pool, the groups of its nesting stages, and the atomic pools that play matches.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class PoolRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId

    private val insertRoot: Query[(GameId, TournamentId), Long] =
        sql"""INSERT INTO pool (game_id, tournament_id, kind) VALUES ($gameId, $tournamentId, 'R')
          RETURNING pool_id""".query(int8)

    /** The tournament's root pool: the parent of its top-level pools and the scope of its own seeds. Written once, as
      * the tournament is created; a second is refused by `pool_root`.
      */
    def createRoot(game: GameId, tournament: TournamentId): IO[Long] = session.unique(insertRoot)((game, tournament))
}
