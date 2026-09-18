package com.vivi.matchmaker.api

import upickle.default.{ReadWriter, readwriter, macroRW}
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** Wire format for the API.
  *
  * Three things here cannot be derived and so are written by hand: the opaque id types, which have no structure for a
  * macro to see; `java.time` values, which are given explicit textual and numeric encodings rather than whatever a
  * default might pick; and `Game`, whose `parameters` field is an existential (`Seq[GameParameter[_]]`).
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

    given ReadWriter[GameType] = readwriter[String].bimap(
      _.code.toString,
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

    /** Preferences and defaults are plain objects of eight named fields, so a client reads them by name rather than by
      * position — the one thing about this wire format that must not depend on `NotificationType.values` order, since
      * the database binding already does.
      *
      * The tri-state is upickle's `Option`, which writes the value itself and omits the field entirely when there is
      * none — so an absent field is "Use Default", exactly the absence the nullable column holds, and a client that
      * omits a field is saying what it means rather than saying nothing.
      */
    given ReadWriter[NotificationPreferences] = macroRW
    given ReadWriter[NotificationDefaults] = macroRW
    given ReadWriter[GameNotificationPreferences] = macroRW

    /** The reason mail to a player is being held back, if any: the `code` of a `SuppressionReason`, as the database
      * stores it and the UI matches on.
      */
    given ReadWriter[SuppressionReason] = readwriter[String].bimap(_.code, SuppressionReason.fromCode)
    given ReadWriter[EmailSuppression.Notice] = macroRW
    given ReadWriter[NotificationSettings] = macroRW

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
      * `TextCodec[String]` is the only instance in the codebase and every service is instantiated as `[String]`, so
      * this loses nothing in practice — it just gives the macro a concrete type to work with.
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
        timeoutAction: TimeoutAction = TimeoutAction.Forfeit,
        // Which notifications this game's players get unless they say otherwise. Defaulted for the
        // same reason, and to the same thing V13 gave every game that already existed: send them all.
        // The form that registers a game requires an admin to choose all eight, which is a rule about
        // the form — a client that says nothing here still creates a game, it just creates a talkative
        // one.
        notifications: NotificationDefaults = NotificationDefaults.all(true)
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
            game.timeoutAction,
            game.notifications
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
            dto.timeoutAction,
            dto.notifications
          )
    )

    // Request bodies. Each carries only what the caller supplies; the caller's own identity always
    // comes from the X-External-Id header, never from the body.
    /** @param email
      *   the address the new player signs in with, as the client read it out of its own token. Optional: a client with
      *   no Cognito identity behind it has none to send, and one that omits it registers a player matchmaker cannot
      *   mail until the account form records one.
      */
    case class RegisterRequest(nickname: String, email: Option[String] = None)

    /** A change of nickname, from the account menu. Separate from `RegisterRequest` despite the identical shape: they
      * are two different requests, and one growing a field is not a reason for the other to gain it.
      */
    case class NicknameRequest(nickname: String)

    /** An address change that Cognito has already accepted, reported by the browser that watched it happen. Not a
      * request to change anything at Cognito — by the time this is sent, the change is done and the code has been
      * answered — only to record it. See `PlayerService.updateEmail`.
      */
    case class EmailRequest(email: String)
    case class CharacterRequest(name: String, description: String, externalId: String)
    case class UpdateStateRequest(state: String)

    // characterId is present iff the challenge being accepted belongs to a 'C'-type game; the
    // service layer checks that correspondence rather than trusting the caller to get it right.
    // gameRoleId is the role the accepting player will play. Required: every acceptance names a
    // role, and a challenge cannot be started until each of its game's required roles is taken.
    case class AcceptRequest(characterId: Option[CharacterId], gameRoleId: GameRoleId)

    /** The game engine's callbacks, from `interaction-design.txt`.
      *
      * Both are authorized as the game rather than as a player: X-External-Id carries the game's shared secret, the
      * same way the character-state route does.
      *
      * A participant is named by its id, which the engine was given when the game was created and quotes back — it
      * never learns matchmaker's player ids.
      */
    /** `takenAt` is when this move was made, which is when the clock starts for whoever is named in `next`. `startedAt`
      * is when the mover's *own* clock started for it, so the two together are what the move cost the player who made
      * it.
      *
      * Both are required, and `startedAt` in particular is the engine's to state rather than matchmaker's to infer. In
      * a game of alternating turns it is simply the move before, and matchmaker used to work it out that way — but in a
      * game where several players move at once nobody was waiting for the move before, and charging the second mover
      * from the first one's move bills them for someone else's thinking. Only the engine knows which kind of game this
      * is, so only the engine can say.
      *
      * FUTURE: an engine that does not track time at all cannot report a move under this shape. If one ever needs to,
      * make the pair optional *together* — a nested `timing` object — rather than two independent optional fields: a
      * move with a time but no start, or the reverse, is not a thing an engine should be able to say. Matchmaker's own
      * inference was removed with this change and is in the history if it is ever wanted back.
      */
    case class MoveNotification(
        participantId: ParticipantId,
        next: List[ParticipantId] = Nil,
        takenAt: Instant,
        startedAt: Instant
    )

    /** A player saving one level of their notification settings, and how far down they want it to reach.
      *
      * A wrapper rather than the bare preferences, because "and use these everywhere" is part of what the player said
      * when they pressed Save: sending it as a query parameter would put half of one answer in the url and half in the
      * body. Both flags default to false, so a client that knows nothing about the cascades saves the one level it
      * named and changes nothing else.
      *
      * @param applyToGames
      *   copy the questions this save changed into every game the player has said something about. Only meaningful for
      *   their defaults — there is no level between one game and another — so `PUT /me/notifications/games/{id}` takes
      *   the same body but refuses this flag rather than discarding it.
      * @param applyToMatches
      *   re-stamp the seats in the matches they are still playing, for the questions this save changed, from the chain
      *   as it then stands.
      */
    case class PreferencesRequest(
        preferences: NotificationPreferences,
        applyToGames: Boolean = false,
        applyToMatches: Boolean = false
    )

    /** One participant's outcome. `scores` is an open map because what a game scores on is the game's business: it is
      * stored as-is in `result.scores`.
      */
    case class ResultEntry(participantId: ParticipantId, rank: Int, scores: Map[String, ujson.Value], isWinner: Boolean)

    case class MatchResults(results: List[ResultEntry])

    /** One line of a finished match's result table, on the way back out to the UI.
      *
      * A view rather than the model type, because `ParticipantResult.scores` holds `Any` — the model is compiled for
      * Scala.js and may not name a JSON library, so the conversion happens at this boundary, as it does for the results
      * coming in.
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
    given ReadWriter[EmailRequest] = macroRW
    given ReadWriter[CharacterRequest] = macroRW
    given ReadWriter[UpdateStateRequest] = macroRW
    given ReadWriter[AcceptRequest] = macroRW
    given ReadWriter[PreferencesRequest] = macroRW
    given ReadWriter[MoveNotification] = macroRW
    given ReadWriter[ResultEntry] = macroRW
    given ReadWriter[MatchResults] = macroRW
    given ReadWriter[ParticipantResultView] = macroRW
}
