package com.vivi.matchmaker.model

sealed trait Acceptance {
  def challengeId: ChallengeId
  def playerId: PlayerId
}

case class PlayerAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId
) extends Acceptance

case class CharacterAcceptance(
    challengeId: ChallengeId,
    playerId: PlayerId,
    characterId: CharacterId
) extends Acceptance
