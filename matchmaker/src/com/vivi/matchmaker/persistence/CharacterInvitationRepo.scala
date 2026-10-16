package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.data.Arr
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

    // Each invited character with its owner now, if it has one: of each tournament named, unnested as (game,
    // tournament) pairs so that each is a lookup on its key.
    private val selectAll
        : Query[(Arr[Int], Arr[Long]), (GameId, TournamentId, Long, String, Option[Long], Option[String])] =
        sql"""SELECT i.game_id, i.tournament_id, c.character_id, c.name, p.player_id, p.nickname
          FROM unnest(${_int4}, ${_int8}) AS k (game_id, tournament_id)
          JOIN character_invitation i ON i.game_id = k.game_id AND i.tournament_id = k.tournament_id
          JOIN "character" c ON c.game_id = i.game_id AND c.character_id = i.character_id
          LEFT JOIN player p ON p.player_id = c.player_id
          ORDER BY c.name, c.character_id""".query(gameId *: tournamentId *: int8 *: text *: int8.opt *: text.opt)

    /** Every character invited, by name, with whoever owns it now. */
    def list(game: GameId, tournament: TournamentId): IO[List[InvitedCharacter]] =
        listFor(List(TournamentKey(game, tournament))).map(_.getOrElse(TournamentKey(game, tournament), Nil))

    /** As [[list]], for each of several tournaments in one query: those with no character invited are left out. */
    def listFor(tournaments: List[TournamentKey]): IO[Map[TournamentKey, List[InvitedCharacter]]] =
        if (tournaments.isEmpty) IO.pure(Map.empty)
        else
            session
                .execute(selectAll)(TournamentKey.arrays(tournaments))
                .map(_.groupMap((g, t, _, _, _, _) => TournamentKey(g, t)) { (game, _, id, name, owner, nickname) =>
                    InvitedCharacter(
                      CharacterName(characterId = CharacterId(id), gameId = game, name = name),
                      owner.zip(nickname).map((o, n) => PlayerName(PlayerId(o), n))
                    )
                })
}
