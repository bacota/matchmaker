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

/** An invitation together with whether the player it names has accepted it.
  *
  * The one thing about an invitation that cannot be read off the invitation. Accepting deliberately leaves the row in
  * place — the row is what permits the seat, and taking it away would make the acceptance unverifiable — so an
  * invitation waiting for an answer and one that has been taken up are the same row. A reader that has to tell them
  * apart needs the `acceptance` table, which is why this pairing exists rather than a field on [[Invitation]]: it is
  * derived on read, and an [[Invitation]] is also what is written.
  *
  * Who needs it: the challenger's own view of a challenge, where every invited player has a Revoke beside them.
  * `ChallengeService.revoke` refuses one whose invitee has accepted — the acceptance has to be removed first, which is
  * a different action — so without this the button is offered on a row it can never apply to.
  */
case class InvitedPlayer(invitation: Invitation, accepted: Boolean)

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
  * @param gameType
  *   whether the game is played through characters, which decides what an acceptance of this invitation has to name —
  *   and so whether it can be accepted from the list at all. Carried here rather than looked up for the reason the
  *   names are: the screen that draws this holds no game. It matters more than the names do, because the only game list
  *   a browser holds is of the *active* games, and a challenge in a game deactivated since the invitation was sent is
  *   still one its invitee may accept — `active` filters what is listed, not what may be played.
  * @param roleName
  *   the name of the seat they were asked to take, when they were asked for one. `Some` exactly when
  *   `invitation.gameRoleId` is, and read from `game_role` in the same query rather than looked up per row.
  * @param character
  *   for a character game, the character invited (V25). `invitation.playerId` is then that character's *current* owner
  *   — the caller — resolved on read rather than stored, and the acceptance has to name this character.
  */
case class ChallengeInvitation(
    invitation: Invitation,
    gameName: String,
    gameType: GameType,
    challengerNickname: String,
    message: String,
    roleName: Option[String],
    character: Option[CharacterName] = None
)

/** An invitation to one character to accept one challenge (V25) — a character game's counterpart of [[Invitation]].
  *
  * Names the character and not its owner, because in a character game it is the character that plays: whoever owns
  * [[characterId]] when the invitation is answered is who may answer it. The owner is never stored here, so a character
  * transferred to another player carries its invitations with it and leaves nothing behind for its previous owner.
  *
  * Otherwise it means what [[Invitation]] means: permission and nothing more, with [[gameRoleId]] the seat held for
  * this character or `None` for any free seat.
  */
case class CharacterInvitation(
    gameId: GameId,
    challengeId: ChallengeId,
    characterId: CharacterId,
    gameRoleId: Option[GameRoleId] = None
)

/** A character invitation as a challenge listing shows it: the character's name, which the challenger's screen has no
  * other way to learn, and whether that character has accepted — [[InvitedPlayer]]'s reason, asked of a character.
  *
  * @param acceptedBy
  *   the player whose acceptance seated this character, when it has been accepted. The player rather than a flag,
  *   because removing that acceptance is addressed by player, and the character's owner may have changed since.
  */
case class InvitedCharacter(invitation: CharacterInvitation, characterName: String, acceptedBy: Option[PlayerId])

/** A character invitation as it is asked for, before the challenge necessarily exists — [[Invite]] for a character
  * game.
  */
case class CharacterInvite(characterId: CharacterId, gameRoleId: Option[GameRoleId] = None)

/** A character the caller may name in a [[CharacterInvite]]: another player's character, without its state.
  *
  * What the invite form needs to offer a choice between somebody's characters, and nothing more. A character's state is
  * its owner's business, which is why this is not a [[Character]].
  */
case class CharacterName(characterId: CharacterId, gameId: GameId, name: String)
