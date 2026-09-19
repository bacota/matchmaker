package com.vivi.matchmaker.model

/** An invitation to accept one challenge (V22).
  *
  * Permission, and nothing more: it says this player may accept this challenge, and — when [[gameRoleId]] is set —
  * which seat they were asked to take. It carries no state about whether they have accepted, because it outlives the
  * acceptance it leads to. A player who accepts, backs out and changes their mind again was invited throughout.
  *
  * What it means depends on the challenge it belongs to. On a closed one ([[Challenge.isOpen]] false) the invitations
  * are the only way in. On an open one it is a nudge — anybody may still accept — but a named role is held for the
  * invitee either way, which is what keeps `gameRoleId` meaning one thing rather than two.
  *
  * @param gameRoleId
  *   the seat held for them, or `None` for "any seat that is still free". When it is set, their acceptance must name
  *   that role and nobody else may take it — both enforced by `ChallengeService.accept`, since the reservation and the
  *   acceptance are rows in different tables and no constraint can compare them.
  */
case class Invitation(
    gameId: GameId,
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameRoleId: Option[GameRoleId] = None
)

/** An invitation as it is asked for, before the challenge it belongs to necessarily exists.
  *
  * Separate from [[Invitation]] because a challenge created with invitations has no id yet, and an [[Invitation]]
  * carrying a made-up `challengeId` for the service to overwrite is a field that lies until it does not. This is the
  * half a caller actually decides: who, and as what.
  */
case class Invite(playerId: PlayerId, gameRoleId: Option[GameRoleId] = None)

/** An invitation as the invited player is shown it, on a screen that has no challenge in hand.
  *
  * The home page lists what a player has been invited to across every game at once, so it cannot look a game's name or
  * a challenger's nickname up from anything it already holds — where the game screen, which lists one game's
  * challenges, can. Hence the names travelling with the row.
  *
  * @param roleName
  *   the name of the seat they were asked to take, when they were asked for one. `Some` exactly when
  *   `invitation.gameRoleId` is, and read from `game_role` in the same query rather than looked up per row.
  */
case class ChallengeInvitation(
    invitation: Invitation,
    gameName: String,
    challengerNickname: String,
    message: String,
    roleName: Option[String]
)
