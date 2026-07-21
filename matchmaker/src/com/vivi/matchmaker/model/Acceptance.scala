package com.vivi.matchmaker.model

sealed trait Acceptance {
  def challengeId: Long
  def playerId: Long
}

case class PlayerAcceptance(
    challengeId: Long,
    playerId: Long
) extends Acceptance

case class CharacterAcceptance(
    challengeId: Long,
    playerId: Long,
    characterId: Long
) extends Acceptance
