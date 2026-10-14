package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Reads and writes a character game's invitations -- `character_invitation`, with its `invitation` row -- which
  * characters may enter an invitational tournament.
  *
  * The owner is never stored. It is read through the character whenever it matters, so whoever owns an invited
  * character when the invitation is answered is who may answer it, and an invitation follows a character handed on.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class CharacterInvitationRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val characterId = SkunkIdCodecs.characterId

    // The invitation and its subtype in one statement, and nothing if the character is invited already.
    private val insertOne: Command[(GameId, TournamentId, GameId, TournamentId, CharacterId, CharacterId)] =
        sql"""WITH made AS (
                 INSERT INTO invitation (game_id, tournament_id, game_type)
                 SELECT $gameId, $tournamentId, 'C'
                  WHERE NOT EXISTS (SELECT 1 FROM character_invitation
                                     WHERE game_id = $gameId AND tournament_id = $tournamentId
                                       AND character_id = $characterId)
                 RETURNING game_id, tournament_id, invitation_id)
          INSERT INTO character_invitation (game_id, tournament_id, invitation_id, character_id)
          SELECT game_id, tournament_id, invitation_id, $characterId FROM made""".command

    /** Invites a character; inviting one already invited changes nothing. */
    def invite(game: GameId, tournament: TournamentId, character: CharacterId): IO[Unit] =
        session.execute(insertOne)((game, tournament, game, tournament, character, character)).void

    // The subtype and the invitation it belongs to, in one statement: the key between them is checked at its end.
    private val deleteOne: Command[(GameId, TournamentId, CharacterId)] =
        sql"""WITH gone AS (
                 DELETE FROM character_invitation
                  WHERE game_id = $gameId AND tournament_id = $tournamentId AND character_id = $characterId
                 RETURNING game_id, tournament_id, invitation_id)
          DELETE FROM invitation i USING gone
           WHERE i.game_id = gone.game_id AND i.tournament_id = gone.tournament_id
             AND i.invitation_id = gone.invitation_id""".command

    def revoke(game: GameId, tournament: TournamentId, character: CharacterId): IO[Unit] =
        session.execute(deleteOne)((game, tournament, character)).void

    private val selectOne: Query[(GameId, TournamentId, CharacterId), Long] =
        sql"""SELECT count(*) FROM character_invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND character_id = $characterId""".query(int8)

    def isInvited(game: GameId, tournament: TournamentId, character: CharacterId): IO[Boolean] =
        session.unique(selectOne)((game, tournament, character)).map(_ > 0)

    // Each invited character with its owner now, if it has one.
    private val selectAll: Query[(GameId, TournamentId), (Long, String, Option[Long], Option[String])] =
        sql"""SELECT c.character_id, c.name, p.player_id, p.nickname FROM character_invitation i
          JOIN "character" c ON c.game_id = i.game_id AND c.character_id = i.character_id
          LEFT JOIN player p ON p.player_id = c.player_id
          WHERE i.game_id = $gameId AND i.tournament_id = $tournamentId
          ORDER BY c.name, c.character_id""".query(int8 *: text *: int8.opt *: text.opt)

    /** Every character invited, by name, with whoever owns it now. */
    def list(game: GameId, tournament: TournamentId): IO[List[InvitedCharacter]] =
        session
            .execute(selectAll)((game, tournament))
            .map(_.map { (id, name, owner, nickname) =>
                InvitedCharacter(
                  CharacterName(CharacterId(id), game, name),
                  owner.zip(nickname).map((o, n) => PlayerName(PlayerId(o), n))
                )
            })
}
