package com.vivi.matchmaker.api

import upickle.default.{ReadWriter, readwriter, macroRW}
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** Wire format for the API.
  *
  * Three things here cannot be derived and so are written by hand: the opaque id types, which
  * have no structure for a macro to see; `java.time` values, which are given explicit textual
  * and numeric encodings rather than whatever a default might pick; and `Game`, whose
  * `parameters` field is an existential (`Seq[GameParameter[_]]`).
  */
object Json {

  // Ids are transparent on the wire — a PlayerId is just its number — mirroring how
  // SkunkIdCodecs maps them to their database columns.
  given ReadWriter[PlayerId] = readwriter[Long].bimap(_.value, PlayerId.apply)
  given ReadWriter[GameId] = readwriter[Int].bimap(_.value, GameId.apply)
  given ReadWriter[MatchId] = readwriter[String].bimap(_.value, MatchId.apply)
  given ReadWriter[CharacterId] = readwriter[Long].bimap(_.value, CharacterId.apply)
  given ReadWriter[GameRoleId] = readwriter[Int].bimap(_.value, GameRoleId.apply)
  given ReadWriter[GameParameterId] = readwriter[Int].bimap(_.value, GameParameterId.apply)
  given ReadWriter[ParticipantId] = readwriter[Long].bimap(_.value, ParticipantId.apply)
  given ReadWriter[ChallengeId] = readwriter[Long].bimap(_.value, ChallengeId.apply)

    given ReadWriter[GameType] = readwriter[String].bimap(_.code.toString,
        s => {
            if (s.length == 1) GameType.fromCode(s.head)
            else throw new IllegalArgumentException(s"expected 1-char gameType code, got '$s'")
        }
    )

  /** By its stored code, so the wire form is the column's form: 'FORFEIT'. */
  given ReadWriter[TimeoutAction] = readwriter[String].bimap(_.code, TimeoutAction.fromCode)

  /** Likewise: 'PER_TURN' or 'TOTAL'. */
  given ReadWriter[TimeLimitKind] = readwriter[String].bimap(_.code, TimeLimitKind.fromCode)

  /** And 'MINUTES' / 'HOURS' / 'DAYS'. */
  given ReadWriter[TimeLimitUnit] = readwriter[String].bimap(_.code, TimeLimitUnit.fromCode)

  given ReadWriter[Instant] = readwriter[String].bimap(_.toString, Instant.parse)

  // Seconds, matching how the persistence layer stores time_limit.
  given ReadWriter[Duration] = readwriter[Long].bimap(_.getSeconds, Duration.ofSeconds)

  given ReadWriter[Player] = macroRW
  given ReadWriter[GameRole] = macroRW
  given ReadWriter[GameParameterValue[String]] = macroRW
  given ReadWriter[GameParameter[String]] = macroRW
  given ReadWriter[Character[String]] = macroRW

  // Sealed-trait wire format: each concrete case gets its own macro-derived ReadWriter, merged
  // into one for the trait. upickle tags the JSON with a discriminator field so a reader can
  // tell a PlainOpenChallenge from a CharacterOpenChallenge (etc.) apart on the way back in.
  given ReadWriter[PlainOpenChallenge] = macroRW
  given ReadWriter[CharacterOpenChallenge] = macroRW
  given ReadWriter[OpenChallenge] =
    ReadWriter.merge(summon[ReadWriter[PlainOpenChallenge]], summon[ReadWriter[CharacterOpenChallenge]])
  given ReadWriter[OpenChallengeSummary] = macroRW

  given ReadWriter[PlainAcceptance] = macroRW
  given ReadWriter[CharacterAcceptance] = macroRW
  given ReadWriter[Acceptance] =
    ReadWriter.merge(summon[ReadWriter[PlainAcceptance]], summon[ReadWriter[CharacterAcceptance]])
  given ReadWriter[PendingAcceptance] = macroRW

  given ReadWriter[PlayerClock] = macroRW
  given ReadWriter[MatchSummary] = macroRW
  given ReadWriter[Match] = macroRW

  /** Structural twin of `Game` with the existential in `parameters` pinned to `String`.
    *
    * `TextCodec[String]` is the only instance in the codebase and every service is instantiated
    * as `[String]`, so this loses nothing in practice — it just gives the macro a concrete type
    * to work with.
    */
  private case class GameDto(
      gameId: GameId,
      gameType: GameType,
      name: String,
      description: String,
      url: String,
      active: Boolean,
      roles: Seq[GameRole],
      parameters: Seq[GameParameter[String]],
      externalId: String,
      // Defaulted so that a client written before turn timeouts existed still parses, and one
      // that omits it still creates a game — with the action every existing game already has.
      timeoutAction: TimeoutAction = TimeoutAction.Forfeit
  )

  private given ReadWriter[GameDto] = macroRW

  given ReadWriter[Game] = readwriter[GameDto].bimap(
    game =>
      GameDto(
        game.gameId,
        game.gameType,
        game.name,
        game.description,
        game.url,
        game.active,
        game.roles,
        game.parameters.map(_.asInstanceOf[GameParameter[String]]),
        game.externalId,
        game.timeoutAction
      ),
    dto =>
      Game(
        dto.gameId,
        dto.gameType,
        dto.name,
        dto.description,
        dto.url,
        dto.active,
        dto.roles,
        dto.parameters,
        dto.externalId,
        dto.timeoutAction
      )
  )

  // Request bodies. Each carries only what the caller supplies; the caller's own identity always
  // comes from the X-External-Id header, never from the body.
  case class RegisterRequest(nickname: String)

  /** A change of nickname, from the account menu. Separate from `RegisterRequest` despite the
    * identical shape: they are two different requests, and one growing a field is not a reason
    * for the other to gain it.
    */
  case class NicknameRequest(nickname: String)
  case class CharacterRequest(name: String, description: String, externalId: String)
  case class UpdateStateRequest(state: String)

  // characterId is present iff the challenge being accepted belongs to a 'C'-type game; the
  // service layer checks that correspondence rather than trusting the caller to get it right.
  // gameRoleId is the role the accepting player will play. Required: every acceptance names a
  // role, and a challenge cannot be started until each of its game's required roles is taken.
  case class AcceptRequest(characterId: Option[CharacterId], gameRoleId: GameRoleId)

  /** The game engine's callbacks, from `interaction-design.txt`.
    *
    * Both are authorized as the game rather than as a player: X-External-Id carries the game's
    * shared secret, the same way the character-state route does.
    *
    * A participant is named by its id, which the engine was given when the game was created and
    * quotes back — it never learns matchmaker's player ids.
    */
  /** `takenAt` is when this move was made, which is when the clock starts for whoever is named in
    * `next`. `startedAt` is when the mover's *own* clock started for it, so the two together are
    * what the move cost the player who made it.
    *
    * Both are required, and `startedAt` in particular is the engine's to state rather than
    * matchmaker's to infer. In a game of alternating turns it is simply the move before, and
    * matchmaker used to work it out that way — but in a game where several players move at once
    * nobody was waiting for the move before, and charging the second mover from the first one's
    * move bills them for someone else's thinking. Only the engine knows which kind of game this
    * is, so only the engine can say.
    *
    * FUTURE: an engine that does not track time at all cannot report a move under this shape. If
    * one ever needs to, make the pair optional *together* — a nested `timing` object — rather
    * than two independent optional fields: a move with a time but no start, or the reverse, is
    * not a thing an engine should be able to say. Matchmaker's own inference was removed with
    * this change and is in the history if it is ever wanted back.
    */
  case class MoveNotification(
      participantId: ParticipantId,
      next: List[ParticipantId] = Nil,
      takenAt: Instant,
      startedAt: Instant
  )

  /** One participant's outcome. `scores` is an open map because what a game scores on is the
    * game's business: it is stored as-is in `result.scores`.
    */
  case class ResultEntry(participantId: ParticipantId, rank: Int, scores: Map[String, ujson.Value], isWinner: Boolean)

  case class MatchResults(results: List[ResultEntry])

  /** One line of a finished match's result table, on the way back out to the UI.
    *
    * A view rather than the model type, because `ParticipantResult.scores` holds `Any` — the
    * model is compiled for Scala.js and may not name a JSON library, so the conversion happens
    * at this boundary, as it does for the results coming in.
    */
  case class ParticipantResultView(
      gameId: GameId,
      matchId: MatchId,
      participantId: ParticipantId,
      nickname: String,
      roleName: String,
      rank: Option[Int],
      scores: Map[String, ujson.Value],
      isWinner: Boolean,
      // Whether the match ended on a clock rather than on the board. With `isWinner` this is the
      // difference between "won by forfeit" and "forfeited"; defaulted for the same reason the
      // game's action is.
      forfeit: Boolean = false,
      // How long this player spent over their turns across the whole match. Seconds on the wire,
      // like every other Duration here; zero for a match played before turns were recorded.
      timeTaken: Duration = Duration.ZERO
  )

  given ReadWriter[RegisterRequest] = macroRW
  given ReadWriter[NicknameRequest] = macroRW
  given ReadWriter[CharacterRequest] = macroRW
  given ReadWriter[UpdateStateRequest] = macroRW
  given ReadWriter[AcceptRequest] = macroRW
  given ReadWriter[MoveNotification] = macroRW
  given ReadWriter[ResultEntry] = macroRW
  given ReadWriter[MatchResults] = macroRW
  given ReadWriter[ParticipantResultView] = macroRW
}
