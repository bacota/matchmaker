package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** An offer to play a match, waiting for other players to accept it.
  *
  * Open to anyone, or addressed to particular players — see [[isOpen]] and [[Invitation]].
  *
  * Mirrors the `challenge` table split: a `'P'`-type game's challenge is a plain [[PlainChallenge]], while a `'C'`-type
  * game's challenge is a [[CharacterChallenge]] naming the character it is offered on behalf of. The two can never be
  * mixed with the wrong kind of game — the schema's composite foreign keys enforce that, and the service layer checks
  * it too.
  */
sealed trait Challenge {
    def challengeId: ChallengeId
    def challenger: PlayerId
    def message: String
    def start: Option[Instant]
    def timeLimit: Option[Duration]

    /** Whether [[timeLimit]] is per turn or the player's budget for the whole match. */
    def timeLimitKind: TimeLimitKind

    /** The unit [[timeLimit]] was offered in, which is how it is said back. */
    def timeLimitUnit: TimeLimitUnit
    def settings: String
    def gameId: GameId

    /** Whether the match this challenge becomes may be watched by anyone, rather than only by the players in it.
      * Decided by the challenger here and passed to the game engine when the match is created, which is what makes the
      * engine issue a public url for it.
      */
    def isPublic: Boolean

    /** The role the challenger will play.
      *
      * Not a column on `challenge`: creating a challenge also creates the challenger's own acceptance, so this is
      * stored on that acceptance like every other player's role, and is read back from it. Setting it on a challenge is
      * how the challenger claims a role at creation; changing it afterwards means changing their acceptance. Mandatory,
      * because the acceptance it is stored on is.
      */
    def gameRoleId: GameRoleId

    /** Whether the match starts by itself as soon as every required role is taken, rather than waiting for the
      * challenger to press Start (V18).
      *
      * The challenger's decision, which is why it is here and not on the game: the same game is offered on different
      * terms by different people, and "whoever turns up, and we play" is one of them. "Required" is the rule a manual
      * start already enforces, so a challenge with optional roles left begins without them — a challenger who wants
      * those seats filled leaves this off, and pressing Start is then what says "this is everybody".
      */
    def autoStart: Boolean

    /** Whether anybody may accept this, rather than only the players invited to it (V22).
      *
      * The challenger's policy, and the whole of what "open" means — there is no second flag saying the same thing
      * another way, because two flags can disagree. False makes the [[Invitation]] rows the only way in:
      * `ChallengeService.accept` refuses a player who has not been invited, and a challenge that is closed with nobody
      * invited is refused at creation, since nobody could ever accept it.
      *
      * Independent of who has been invited, which is why it is a column here rather than something derived from the
      * invitations. An open challenge may carry invitations — a nudge to a friend, whose seat is held for them all the
      * same — and a closed one may be re-opened without anybody's invitation being touched.
      */
    def isOpen: Boolean
}

case class PlainChallenge(
    challengeId: ChallengeId,
    challenger: PlayerId,
    message: String,
    start: Option[Instant],
    timeLimit: Option[Duration],
    settings: String,
    gameId: GameId,
    isPublic: Boolean = false,
    gameRoleId: GameRoleId,
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
    timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
    autoStart: Boolean = false,
    isOpen: Boolean = true
) extends Challenge

case class CharacterChallenge(
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
    timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
    timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
    autoStart: Boolean = false,
    isOpen: Boolean = true
) extends Challenge

/** An open challenge together with how many players have accepted it so far.
  *
  * The count is not part of [[Challenge]] itself because a challenge is also what a client *sends* to create one, and
  * how many acceptances it has is not the client's to state. It is derived on read, which is the only place it means
  * anything.
  *
  * What it is for: a challenge cannot be started until every required role of its game has been taken, and the server
  * refuses one that is not ready. Sending the roles already claimed lets the UI not offer a Start that would be
  * refused, and not offer a role somebody else has already taken; the count is what it says beside the challenge, since
  * a game's roles are what the seats are and `takenRoles` is which of them are gone.
  */
case class ChallengeSummary(
    challenge: Challenge,
    acceptances: Int,
    takenRoles: Seq[GameRoleId] = Seq.empty,
    /** Who has been invited to this challenge, and as what.
      *
      * Beside `takenRoles` because it answers the other half of the same question: those are the seats that are gone,
      * these are the ones held for somebody in particular, and a player deciding what they may accept as needs both. It
      * also says who has been asked, which is the whole of what a closed challenge shows about itself.
      */
    invitations: Seq[Invitation] = Seq.empty,
    /** Which of those invited players have accepted, a subset of `invitations` by player.
      *
      * Derived on read like `acceptances` and `takenRoles`, and here rather than on [[Invitation]] for the reason
      * [[InvitedPlayer]] gives: accepting leaves the invitation exactly as it was, so the row cannot say. What reads it
      * is the challenger's Revoke, which the service refuses once the invitee has accepted.
      *
      * Not every acceptor — only the invited ones. An acceptance by somebody who was never invited is nobody's
      * invitation to revoke, and listing it would say more about who is playing than the rest of this summary does.
      */
    acceptedInvitees: Seq[PlayerId] = Seq.empty
)
