package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.data.Arr
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

    // Of each tournament named, unnested as (game, tournament) pairs so that each is a lookup on its key.
    private val selectAll: Query[(Arr[Int], Arr[Long]), (GameId, TournamentId, Long, String)] =
        sql"""SELECT i.game_id, i.tournament_id, p.player_id, p.nickname
          FROM unnest(${_int4}, ${_int8}) AS k (game_id, tournament_id)
          JOIN player_invitation i ON i.game_id = k.game_id AND i.tournament_id = k.tournament_id
          JOIN player p ON p.player_id = i.player_id
          ORDER BY p.nickname, p.player_id""".query(gameId *: tournamentId *: int8 *: text)

    /** Everyone invited, with their nicknames, by nickname. */
    def list(game: GameId, tournament: TournamentId): IO[List[(PlayerId, String)]] =
        listFor(List(TournamentKey(game, tournament))).map(_.getOrElse(TournamentKey(game, tournament), Nil))

    /** As [[list]], for each of several tournaments in one query: those with nobody invited are left out. */
    def listFor(tournaments: List[TournamentKey]): IO[Map[TournamentKey, List[(PlayerId, String)]]] =
        if (tournaments.isEmpty) IO.pure(Map.empty)
        else
            session
                .execute(selectAll)(TournamentKey.arrays(tournaments))
                .map(_.groupMap((g, t, _, _) => TournamentKey(g, t))((_, _, id, name) => (PlayerId(id), name)))
}
