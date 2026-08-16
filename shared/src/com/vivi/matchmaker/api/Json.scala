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

  // Transparent on the wire as its single-character code, mirroring the DB's game_type column.
  given ReadWriter[GameType] = readwriter[String].bimap(_.code.toString, s => GameType.fromCode(s.head))

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

  given ReadWriter[PlainAcceptance] = macroRW
  given ReadWriter[CharacterAcceptance] = macroRW
  given ReadWriter[Acceptance] =
    ReadWriter.merge(summon[ReadWriter[PlainAcceptance]], summon[ReadWriter[CharacterAcceptance]])

  given ReadWriter[MatchSummary] = macroRW

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
      minPlayers: Int,
      maxPlayers: Int
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
        game.minPlayers,
        game.maxPlayers
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
        dto.minPlayers,
        dto.maxPlayers
      )
  )

  // Request bodies. Each carries only what the caller supplies; the caller's own identity always
  // comes from the X-External-Id header, never from the body.
  case class RegisterRequest(nickname: String)
  case class CharacterRequest(name: String, description: String, externalId: String)
  case class UpdateStateRequest(state: String)

  // characterId is present iff the challenge being accepted belongs to a 'C'-type game; the
  // service layer checks that correspondence rather than trusting the caller to get it right.
  case class AcceptRequest(characterId: Option[CharacterId])

  given ReadWriter[RegisterRequest] = macroRW
  given ReadWriter[CharacterRequest] = macroRW
  given ReadWriter[UpdateStateRequest] = macroRW
  given ReadWriter[AcceptRequest] = macroRW
}
