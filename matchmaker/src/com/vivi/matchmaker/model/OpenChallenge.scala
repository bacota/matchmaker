package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** An open offer to play a match, waiting for other players to accept it.
  *
  * Mirrors the `open_challenge` table split: a `'P'`-type game's challenge is a plain
  * [[PlainOpenChallenge]], while a `'C'`-type game's challenge is a [[CharacterOpenChallenge]]
  * naming the character it is offered on behalf of. The two can never be mixed with the wrong
  * kind of game — the schema's composite foreign keys enforce that, and the service layer
  * checks it too.
  */
sealed trait OpenChallenge {
  def challengeId: ChallengeId
  def challenger: PlayerId
  def message: String
  def numberOfPlayers: Short
  def start: Option[Instant]
  def timeLimit: Option[Duration]
  def settings: String
  def gameId: GameId
}

case class PlainOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId
) extends OpenChallenge

case class CharacterOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    characterId: CharacterId
) extends OpenChallenge
