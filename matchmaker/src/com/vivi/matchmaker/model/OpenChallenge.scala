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

  /** Whether the match this challenge becomes may be watched by anyone, rather than only by the
    * players in it. Decided by the challenger here and passed to the game engine when the match
    * is created, which is what makes the engine issue a public url for it. */
  def isPublic: Boolean

  /** The role the challenger will play, which their own (implicit) acceptance is recorded with. */
  def gameRoleId: Option[GameRoleId]
}

case class PlainOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    numberOfPlayers: Short,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    isPublic: Boolean = false,
    gameRoleId: Option[GameRoleId] = None
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
    characterId: CharacterId,
    isPublic: Boolean = false,
    gameRoleId: Option[GameRoleId] = None
) extends OpenChallenge
