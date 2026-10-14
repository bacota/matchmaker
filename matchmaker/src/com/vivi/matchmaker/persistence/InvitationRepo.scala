package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Reads and writes `invitation`: who may enter an invitational tournament -- a challenge that is not open among them.
  * Permission and nothing more: an invitation is left as it was when the player it invites enters.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class InvitationRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val playerId = SkunkIdCodecs.playerId

    private val insertOne: Command[(GameId, TournamentId, PlayerId)] =
        sql"""INSERT INTO invitation (game_id, tournament_id, player_id)
          VALUES ($gameId, $tournamentId, $playerId) ON CONFLICT DO NOTHING""".command

    /** Invites a player; inviting one already invited changes nothing. */
    def invite(game: GameId, tournament: TournamentId, player: PlayerId): IO[Unit] =
        session.execute(insertOne)((game, tournament, player)).void

    private val deleteOne: Command[(GameId, TournamentId, PlayerId)] =
        sql"""DELETE FROM invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId""".command

    def revoke(game: GameId, tournament: TournamentId, player: PlayerId): IO[Unit] =
        session.execute(deleteOne)((game, tournament, player)).void

    private val selectOne: Query[(GameId, TournamentId, PlayerId), Long] =
        sql"""SELECT count(*) FROM invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId""".query(int8)

    def isInvited(game: GameId, tournament: TournamentId, player: PlayerId): IO[Boolean] =
        session.unique(selectOne)((game, tournament, player)).map(_ > 0)

    private val selectAll: Query[(GameId, TournamentId), (Long, String)] =
        sql"""SELECT p.player_id, p.nickname FROM invitation i
          JOIN player p ON p.player_id = i.player_id
          WHERE i.game_id = $gameId AND i.tournament_id = $tournamentId
          ORDER BY p.nickname, p.player_id""".query(int8 *: text)

    /** Everyone invited, with their nicknames, by nickname. */
    def list(game: GameId, tournament: TournamentId): IO[List[(PlayerId, String)]] =
        session.execute(selectAll)((game, tournament)).map(_.map((id, name) => (PlayerId(id), name)))
}
