package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Reads and writes `character_invitation` (V25): which characters may accept a character game's challenge, and as
  * what. [[InvitationRepo]]'s counterpart, and shaped like it.
  *
  * Nothing here stores or trusts an owner. Where a question is about a player — what has this player been invited to —
  * the answer is joined through `character.player_id` as it stands now, which is what makes an invitation follow its
  * character to a new owner.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class CharacterInvitationRepo(session: Session[IO]) {
    private val challengeId = SkunkIdCodecs.challengeId
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val gameRoleId = SkunkIdCodecs.gameRoleId
    private val characterId = SkunkIdCodecs.characterId
    private val gameType = SkunkCodecs.gameType

    private val insertInvitation: Command[(GameId, ChallengeId, CharacterId, Option[GameRoleId])] =
        sql"""INSERT INTO character_invitation (game_id, challenge_id, character_id, game_role_id)
          VALUES ($gameId, $challengeId, $characterId, ${gameRoleId.opt})""".command

    def create(invitation: CharacterInvitation): IO[CharacterInvitation] =
        session
            .execute(insertInvitation)(
              (invitation.gameId, invitation.challengeId, invitation.characterId, invitation.gameRoleId)
            )
            .as(invitation)

    private val selectInvitation: Query[(GameId, ChallengeId, CharacterId), Option[GameRoleId]] =
        sql"""SELECT game_role_id FROM character_invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND character_id = $characterId"""
            .query(gameRoleId.opt)

    /** One character's invitation to one challenge, or `None` if it has not been invited. */
    def read(gameId: GameId, challengeId: ChallengeId, character: CharacterId): IO[Option[CharacterInvitation]] =
        session
            .option(selectInvitation)((gameId, challengeId, character))
            .map(_.map(role => CharacterInvitation(gameId, challengeId, character, role)))

    // character_id decoded as a raw int8 and wrapped below, for the reason InvitationRepo gives for
    // its player_id: a trailing opaque-typed codec defeats skunk's twiddle resolution from here.
    private val selectReservedRoles: Query[(GameId, ChallengeId), (GameRoleId, Long)] =
        sql"""SELECT game_role_id, character_id FROM character_invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND game_role_id IS NOT NULL
          ORDER BY game_role_id""".query(gameRoleId *: int8)

    /** The seats held for a particular character, and which. `InvitationRepo.reservedRoles` asked of characters. */
    def reservedRoles(gameId: GameId, challengeId: ChallengeId): IO[List[(GameRoleId, CharacterId)]] =
        session
            .execute(selectReservedRoles)((gameId, challengeId))
            .map(_.map((role, character) => (role, CharacterId(character))))

    // Addressed to this player through whichever characters they own right now. A started challenge
    // is left out for InvitationRepo.listForPlayer's reason.
    private val selectByOwner: Query[
      PlayerId,
      (GameId, ChallengeId, CharacterId, String, Option[GameRoleId], String, GameType, String, String, Option[String])
    ] =
        sql"""SELECT i.game_id, i.challenge_id, i.character_id, c.name, i.game_role_id,
                 g.name, g.game_type, challenger.nickname, ch.message, r.name
          FROM character_invitation i
          JOIN character c ON c.game_id = i.game_id AND c.character_id = i.character_id
          JOIN challenge ch ON ch.game_id = i.game_id AND ch.challenge_id = i.challenge_id
          JOIN game g ON g.game_id = i.game_id
          JOIN player challenger ON challenger.player_id = ch.challenger
          LEFT JOIN game_role r ON r.game_id = i.game_id AND r.game_role_id = i.game_role_id
          WHERE c.player_id = $playerId AND ch.started_match_id IS NULL
          ORDER BY i.create_date DESC, i.challenge_id, i.character_id"""
            .query(
              gameId *: challengeId *: characterId *: text *: gameRoleId.opt *: text *: gameType *: text *: text *:
                  text.opt
            )

    /** Everything this player's characters have been invited to and could still accept, newest first.
      *
      * Returned as [[ChallengeInvitation]]s addressed to `player` — the characters' current owner — carrying the
      * character, so the home page lists them beside a plain game's invitations and accepts with the right character.
      */
    def listForOwner(player: PlayerId): IO[List[ChallengeInvitation]] =
        session
            .execute(selectByOwner)(player)
            .map(_.map {
                case (game, challenge, character, characterName, role, gameName, kind, challenger, message, roleName) =>
                    ChallengeInvitation(
                      Invitation(game, challenge, player, role),
                      gameName,
                      kind,
                      challenger,
                      message,
                      roleName,
                      Some(CharacterName(character, game, characterName))
                    )
            })

    // The accepting player is read through character_acceptance to the acceptance it extends, which
    // is the row that names a player.
    private val selectByGame: Query[GameId, (ChallengeId, CharacterId, String, Option[GameRoleId], Option[PlayerId])] =
        sql"""SELECT i.challenge_id, i.character_id, c.name, i.game_role_id,
                 (SELECT a.player_id FROM character_acceptance ca
                    JOIN acceptance a ON a.game_id = ca.game_id AND a.challenge_id = ca.challenge_id
                                     AND a.game_role_id = ca.game_role_id
                   WHERE ca.game_id = i.game_id
                     AND ca.challenge_id = i.challenge_id
                     AND ca.character_id = i.character_id) AS accepted_by
          FROM character_invitation i
          JOIN character c ON c.game_id = i.game_id AND c.character_id = i.character_id
          WHERE i.game_id = $gameId
          ORDER BY i.challenge_id, c.name, i.character_id"""
            .query(challengeId *: characterId *: text *: gameRoleId.opt *: playerId.opt)

    /** Every character invitation in one game, grouped by challenge — `InvitationRepo.listForGame` for characters. */
    def listForGame(gameId: GameId): IO[Map[ChallengeId, List[InvitedCharacter]]] =
        session
            .execute(selectByGame)(gameId)
            .map(
              _.map((challenge, character, name, role, accepted) =>
                  InvitedCharacter(CharacterInvitation(gameId, challenge, character, role), name, accepted)
              ).groupBy(_.invitation.challengeId)
            )

    private val selectAccepted: Query[(GameId, ChallengeId, CharacterId), Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM character_acceptance
          WHERE game_id = $gameId AND challenge_id = $challengeId AND character_id = $characterId)""".query(bool)

    /** Whether this character has taken a seat in this challenge — the refusal reject and revoke share, asked of the
      * character the invitation names rather than of whoever owns it.
      */
    def hasAccepted(gameId: GameId, challengeId: ChallengeId, character: CharacterId): IO[Boolean] =
        session.unique(selectAccepted)((gameId, challengeId, character))

    private val deleteOne: Command[(GameId, ChallengeId, CharacterId)] =
        sql"""DELETE FROM character_invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND character_id = $characterId""".command

    /** Withdraws one invitation — a reject by the character's owner, or a revoke by the challenger. */
    def delete(gameId: GameId, challengeId: ChallengeId, character: CharacterId): IO[Unit] =
        session.execute(deleteOne)((gameId, challengeId, character)).void

    private val deleteByChallenge: Command[(GameId, ChallengeId)] =
        sql"DELETE FROM character_invitation WHERE game_id = $gameId AND challenge_id = $challengeId".command

    /** Every character invitation to one challenge, before the challenge itself is deleted. */
    def deleteAllForChallenge(gameId: GameId, challengeId: ChallengeId): IO[Unit] =
        session.execute(deleteByChallenge)((gameId, challengeId)).void
}
