package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Reads and writes `invitation` (V22): who may accept a challenge, and as what.
  *
  * Beside [[AcceptanceRepo]] rather than inside [[ChallengeRepo]], and shaped like it: an invitation is a row per
  * player per challenge, created and deleted one at a time, and read both by challenge (what does this challenge look
  * like) and by player (what have I been asked to play). A challenge's own repo would answer neither of those without
  * becoming a second acceptance repo.
  *
  * Like every write in this package it opens no transaction of its own — that is the calling service's job, and skunk
  * rejects nested transactions outright anyway.
  */
class InvitationRepo(session: Session[IO]) {
    private val challengeId = SkunkIdCodecs.challengeId
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val gameRoleId = SkunkIdCodecs.gameRoleId

    private val insertInvitation: Command[(GameId, ChallengeId, PlayerId, Option[GameRoleId])] =
        sql"""INSERT INTO invitation (game_id, challenge_id, player_id, game_role_id)
          VALUES ($gameId, $challengeId, $playerId, ${gameRoleId.opt})""".command

    /** Records that `playerId` may accept this challenge, as `gameRoleId` if that is set.
      *
      * A second invitation for the same player in the same challenge violates the primary key, which is what makes
      * inviting twice a `ConflictError` rather than two rows saying the same thing — see `ChallengeService.invite`.
      */
    def create(invitation: Invitation): IO[Invitation] =
        session
            .execute(insertInvitation)(
              (invitation.gameId, invitation.challengeId, invitation.playerId, invitation.gameRoleId)
            )
            .as(invitation)

    private val selectInvitation: Query[(GameId, ChallengeId, PlayerId), Option[GameRoleId]] =
        sql"""SELECT game_role_id FROM invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId"""
            .query(gameRoleId.opt)

    /** One player's invitation to one challenge, or `None` if they have not been invited.
      *
      * The question `accept` asks of a closed challenge, and the role it comes back with is the seat that acceptance
      * must name. Read inside the transaction that holds the challenge's `FOR UPDATE` lock, which is what keeps it from
      * racing a revoke.
      */
    def read(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Option[Invitation]] =
        session
            .option(selectInvitation)((gameId, challengeId, playerId))
            .map(_.map(role => Invitation(gameId, challengeId, playerId, role)))

    private val selectByChallenge: Query[(GameId, ChallengeId), (PlayerId, Option[GameRoleId])] =
        sql"""SELECT player_id, game_role_id FROM invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId
          ORDER BY player_id""".query(playerId *: gameRoleId.opt)

    /** Everyone invited to one challenge, in a stable order.
      *
      * What a challenge row says about itself: who has been asked, and which seats are being held. Also what decides
      * whether a role another player is asking for is free — see [[reservedRoles]], which is the same rows asked a
      * narrower question.
      */
    def listForChallenge(gameId: GameId, challengeId: ChallengeId): IO[List[Invitation]] =
        session
            .execute(selectByChallenge)((gameId, challengeId))
            .map(_.map((player, role) => Invitation(gameId, challengeId, player, role)))

    // player_id is decoded as a raw int8 and wrapped below: a trailing opaque-typed codec defeats
    // skunk's twiddle-list match-type resolution from outside Ids.scala, the same way
    // AcceptanceRepo's characterId does.
    private val selectReservedRoles: Query[(GameId, ChallengeId), (GameRoleId, Long)] =
        sql"""SELECT game_role_id, player_id FROM invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND game_role_id IS NOT NULL
          ORDER BY game_role_id""".query(gameRoleId *: int8)

    /** The seats held for somebody in particular, and who they are held for.
      *
      * Asked by `accept` of every challenge, open or closed: a role reserved for one player is not free for another,
      * which is what stops a passer-by taking the seat a challenger asked their friend to play. The player comes back
      * with the role because the answer is not "is this role reserved" but "is it reserved for somebody else".
      */
    def reservedRoles(gameId: GameId, challengeId: ChallengeId): IO[List[(GameRoleId, PlayerId)]] =
        session
            .execute(selectReservedRoles)((gameId, challengeId))
            .map(_.map((role, player) => (role, PlayerId(player))))

    // Every invitation one player holds, across every game -- the one query here that is not already
    // holding a challenge in hand, and the one `invitation_player` exists for.
    //
    // A challenge that has been started is left out for the reason `ChallengeRepo.listByGame` leaves
    // one out: it is no longer something to accept, and offering it would be offering a click the
    // service refuses. The invitation row survives the start (it is deleted with the challenge), so
    // this is a filter here rather than a delete there.
    private val selectByPlayer: Query[
      PlayerId,
      (GameId, ChallengeId, Option[GameRoleId], String, String, String, Option[String])
    ] =
        sql"""SELECT i.game_id, i.challenge_id, i.game_role_id,
                 g.name, challenger.nickname, ch.message, r.name
          FROM invitation i
          JOIN challenge ch ON ch.game_id = i.game_id AND ch.challenge_id = i.challenge_id
          JOIN game g ON g.game_id = i.game_id
          JOIN player challenger ON challenger.player_id = ch.challenger
          LEFT JOIN game_role r ON r.game_id = i.game_id AND r.game_role_id = i.game_role_id
          WHERE i.player_id = $playerId AND ch.started_match_id IS NULL
          ORDER BY i.create_date DESC, i.challenge_id"""
            .query(gameId *: challengeId *: gameRoleId.opt *: text *: text *: text *: text.opt)

    /** Everything this player has been invited to and could still accept, newest first.
      *
      * Carries the game's name, the challenger's nickname and the challenge's message, because the screen that asks
      * this — a player's home page — spans every game and has nothing to look them up from.
      */
    def listForPlayer(player: PlayerId): IO[List[ChallengeInvitation]] =
        session
            .execute(selectByPlayer)(player)
            .map(_.map { case (game, challenge, role, gameName, challenger, message, roleName) =>
                ChallengeInvitation(
                  Invitation(game, challenge, player, role),
                  gameName,
                  challenger,
                  message,
                  roleName
                )
            })

    private val selectByGame: Query[GameId, (ChallengeId, PlayerId, Option[GameRoleId])] =
        sql"""SELECT challenge_id, player_id, game_role_id FROM invitation
          WHERE game_id = $gameId
          ORDER BY challenge_id, player_id""".query(challengeId *: playerId *: gameRoleId.opt)

    /** Every invitation in one game, grouped by the challenge it belongs to.
      *
      * One query for a whole listing rather than one per challenge: `ChallengeService.listByGame` draws a dozen rows
      * and each of them says who was invited, which is a dozen round trips asked as one. A game with no invitations
      * anywhere answers with an empty map and costs a single index-less scan of a table that is empty in that case too.
      */
    def listForGame(gameId: GameId): IO[Map[ChallengeId, List[Invitation]]] =
        session
            .execute(selectByGame)(gameId)
            .map(
              _.map((challenge, player, role) => Invitation(gameId, challenge, player, role))
                  .groupBy(_.challengeId)
            )

    private val deleteOne: Command[(GameId, ChallengeId, PlayerId)] =
        sql"""DELETE FROM invitation
          WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId""".command

    /** Withdraws one invitation — a reject by the player, or a revoke by the challenger. The row is the same either
      * way; who is allowed to remove it is `ChallengeService`'s to decide.
      */
    def delete(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Unit] =
        session.execute(deleteOne)((gameId, challengeId, playerId)).void

    private val deleteByChallenge: Command[(GameId, ChallengeId)] =
        sql"DELETE FROM invitation WHERE game_id = $gameId AND challenge_id = $challengeId".command

    /** Every invitation to one challenge. Called when the challenge is deleted: `invitation` has a foreign key to it,
      * so the rows have to go first, and a cascade would delete them without anything saying they were there.
      */
    def deleteAllForChallenge(gameId: GameId, challengeId: ChallengeId): IO[Unit] =
        session.execute(deleteByChallenge)((gameId, challengeId)).void
}
