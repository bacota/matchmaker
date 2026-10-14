package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Reads and writes a plain game's invitations -- `player_invitation`, with its `invitation` row -- who may enter an
  * invitational tournament, a challenge that is not open among them. Permission and nothing more: an invitation is left
  * as it was when the player it invites enters. A character game's invitations are its characters', in
  * [[CharacterInvitationRepo]].
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class InvitationRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val playerId = SkunkIdCodecs.playerId

    // The invitation and its subtype in one statement, and nothing if the player is invited already.
    private val insertOne: Command[(GameId, TournamentId, GameId, TournamentId, PlayerId, PlayerId)] =
        sql"""WITH made AS (
                 INSERT INTO invitation (game_id, tournament_id, game_type)
                 SELECT $gameId, $tournamentId, 'P'
                  WHERE NOT EXISTS (SELECT 1 FROM player_invitation
                                     WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId)
                 RETURNING game_id, tournament_id, invitation_id)
          INSERT INTO player_invitation (game_id, tournament_id, invitation_id, player_id)
          SELECT game_id, tournament_id, invitation_id, $playerId FROM made""".command

    /** Invites a player; inviting one already invited changes nothing. */
    def invite(game: GameId, tournament: TournamentId, player: PlayerId): IO[Unit] =
        session.execute(insertOne)((game, tournament, game, tournament, player, player)).void

    // The subtype and the invitation it belongs to, in one statement: the key between them is checked at its end.
    private val deleteOne: Command[(GameId, TournamentId, PlayerId)] =
        sql"""WITH gone AS (
                 DELETE FROM player_invitation
                  WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId
                 RETURNING game_id, tournament_id, invitation_id)
          DELETE FROM invitation i USING gone
           WHERE i.game_id = gone.game_id AND i.tournament_id = gone.tournament_id
             AND i.invitation_id = gone.invitation_id""".command

    def revoke(game: GameId, tournament: TournamentId, player: PlayerId): IO[Unit] =
        session.execute(deleteOne)((game, tournament, player)).void

    private val selectOne: Query[(GameId, TournamentId, PlayerId), Long] =
        sql"""SELECT count(*) FROM player_invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId""".query(int8)

    def isInvited(game: GameId, tournament: TournamentId, player: PlayerId): IO[Boolean] =
        session.unique(selectOne)((game, tournament, player)).map(_ > 0)

    private val selectAll: Query[(GameId, TournamentId), (Long, String)] =
        sql"""SELECT p.player_id, p.nickname FROM player_invitation i
          JOIN player p ON p.player_id = i.player_id
          WHERE i.game_id = $gameId AND i.tournament_id = $tournamentId
          ORDER BY p.nickname, p.player_id""".query(int8 *: text)

    /** Everyone invited, with their nicknames, by nickname. */
    def list(game: GameId, tournament: TournamentId): IO[List[(PlayerId, String)]] =
        session.execute(selectAll)((game, tournament)).map(_.map((id, name) => (PlayerId(id), name)))
}
