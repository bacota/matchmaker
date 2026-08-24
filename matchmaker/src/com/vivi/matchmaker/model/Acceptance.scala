package com.vivi.matchmaker.model

/** A player's acceptance of an open challenge. Mirrors the `acceptance` table split, the same
  * way [[OpenChallenge]] mirrors `open_challenge`: a `'P'`-type game's acceptance is a
  * [[PlainAcceptance]], a `'C'`-type game's is a [[CharacterAcceptance]] naming the character
  * accepting on the player's behalf.
  *
  * `gameRoleId` is the role the player will play in the match, chosen when accepting and carried
  * onto the [[Participant]] when the challenge is started. Every acceptance names one: a seat
  * with no role is a seat nothing can be said about, and a challenge cannot be started until each
  * of its game's required roles is actually taken. Two acceptances of one challenge may not name
  * the same role.
  */
sealed trait Acceptance {
  def challengeId: ChallengeId
  def playerId: PlayerId
  def gameId: GameId
  def gameRoleId: GameRoleId
}

case class PlainAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    gameRoleId: GameRoleId
) extends Acceptance

case class CharacterAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    characterId: CharacterId,
    gameRoleId: GameRoleId
) extends Acceptance
