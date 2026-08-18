package com.vivi.matchmaker.model

/** A player's acceptance of an open challenge. Mirrors the `acceptance` table split, the same
  * way [[OpenChallenge]] mirrors `open_challenge`: a `'P'`-type game's acceptance is a
  * [[PlainAcceptance]], a `'C'`-type game's is a [[CharacterAcceptance]] naming the character
  * accepting on the player's behalf.
  *
  * `gameRoleId` is the role the player will play in the match, chosen when accepting and carried
  * onto the [[Participant]] when the challenge is started. It is optional because a game need not
  * define roles at all, and because a game's roles may themselves be optional.
  */
sealed trait Acceptance {
  def challengeId: ChallengeId
  def playerId: PlayerId
  def gameId: GameId
  def gameRoleId: Option[GameRoleId]
}

case class PlainAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    gameRoleId: Option[GameRoleId] = None
) extends Acceptance

case class CharacterAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    characterId: CharacterId,
    gameRoleId: Option[GameRoleId] = None
) extends Acceptance
