package com.vivi.matchmaker.model

/** A player's acceptance of an open challenge. Mirrors the `acceptance` table split, the same
  * way [[OpenChallenge]] mirrors `open_challenge`: a `'P'`-type game's acceptance is a
  * [[PlainAcceptance]], a `'C'`-type game's is a [[CharacterAcceptance]] naming the character
  * accepting on the player's behalf.
  */
sealed trait Acceptance {
  def challengeId: ChallengeId
  def playerId: PlayerId
  def gameId: GameId
}

case class PlainAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId
) extends Acceptance

case class CharacterAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    gameId: GameId,
    characterId: CharacterId
) extends Acceptance
