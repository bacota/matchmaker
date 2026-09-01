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
  def start: Option[Instant]
  def timeLimit: Option[Duration]

  /** Whether [[timeLimit]] is per turn or the player's budget for the whole match. */
  def timeLimitKind: TimeLimitKind
  def settings: String
  def gameId: GameId

  /** Whether the match this challenge becomes may be watched by anyone, rather than only by the
    * players in it. Decided by the challenger here and passed to the game engine when the match
    * is created, which is what makes the engine issue a public url for it. */
  def isPublic: Boolean

  /** The role the challenger will play.
    *
    * Not a column on `open_challenge`: creating a challenge also creates the challenger's own
    * acceptance, so this is stored on that acceptance like every other player's role, and is read
    * back from it. Setting it on a challenge is how the challenger claims a role at creation;
    * changing it afterwards means changing their acceptance. Mandatory, because the acceptance it
    * is stored on is.
    */
  def gameRoleId: GameRoleId
}

case class PlainOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    isPublic: Boolean = false,
    gameRoleId: GameRoleId,
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn
) extends OpenChallenge

case class CharacterOpenChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    characterId: CharacterId,
    isPublic: Boolean = false,
    gameRoleId: GameRoleId,
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn
) extends OpenChallenge

/** An open challenge together with how many players have accepted it so far.
  *
  * The count is not part of [[OpenChallenge]] itself because a challenge is also what a client
  * *sends* to create one, and how many acceptances it has is not the client's to state. It is
  * derived on read, which is the only place it means anything.
  *
  * What it is for: a challenge cannot be started until every required role of its game has been
  * taken, and the server refuses one that is not ready. Sending the roles already claimed lets
  * the UI not offer a Start that would be refused, and not offer a role somebody else has
  * already taken; the count is what it says beside the challenge, since a game's roles are what
  * the seats are and `takenRoles` is which of them are gone.
  */
case class OpenChallengeSummary(challenge: OpenChallenge, acceptances: Int, takenRoles: Seq[GameRoleId] = Seq.empty)
