package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import skunk.data.Type
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import com.vivi.matchmaker.model.{
    GameType,
    NotificationDefaults,
    NotificationPreferences,
    TimeLimitKind,
    TimeLimitUnit,
    TimeoutAction
}
import com.vivi.matchmaker.util.JsonValues

object SkunkCodecs {

    val instant: Codec[Instant] =
        timestamptz.imap(_.toInstant)(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))

    /** The `game_type` discriminator column (`CHAR(1)`, `'C'`/`'P'`), on `game` and on every table split by it
      * (`challenge`, `acceptance`, `participant`, and their `character_*` siblings).
      */
    val gameType: Codec[GameType] = bpchar(1).imap(s => GameType.fromCode(s.head))(_.code.toString)

    /** `game.timeout_action`: what happens when a player's turn runs out. Text under a check constraint rather than a
      * one-character discriminator, because the set of actions is expected to grow and 'FORFEIT' reads as itself in a
      * query.
      */
    val timeoutAction: Codec[TimeoutAction] = text.imap(TimeoutAction.fromCode)(_.code)

    /** `time_limit_kind` on `match` and `challenge`: whether the limit is per turn or the player's budget for the whole
      * match. Text under a check constraint, as `timeoutAction` is.
      */
    val timeLimitKind: Codec[TimeLimitKind] = text.imap(TimeLimitKind.fromCode)(_.code)

    /** `time_limit_unit`: the unit a limit was offered in, and so the unit it is read back in. */
    val timeLimitUnit: Codec[TimeLimitUnit] = text.imap(TimeLimitUnit.fromCode)(_.code)

    /** The eleven `notify_*` columns of `player`, `participant` and `player_game`, as one value.
      *
      * Bound positionally, in `NotificationType.values` order: the eleven fields of
      * [[com.vivi.matchmaker.model.NotificationPreferences]] are in that order, and so is every column list that uses
      * this codec. A kind added to the enum in the wrong place would compile and silently store answers under the wrong
      * heading, which is why the enum's order is documented as part of it.
      *
      * One codec rather than eleven `bool.opt` at each site so that the statements read as being about preferences
      * rather than about eleven booleans, and so that adding a kind is one change here instead of one per query.
      */
    val notificationPreferences: Codec[NotificationPreferences] =
        (bool.opt *: bool.opt *: bool.opt *: bool.opt *: bool.opt *: bool.opt *: bool.opt *: bool.opt *: bool.opt *:
            bool.opt *: bool.opt).to[NotificationPreferences]

    /** The same eleven columns on `game`, where they are NOT NULL: the end of the chain has to answer. */
    val notificationDefaults: Codec[NotificationDefaults] =
        (bool *: bool *: bool *: bool *: bool *: bool *: bool *: bool *: bool *: bool *: bool)
            .to[NotificationDefaults]

    /** skunk-core ships no jsonb codec, so this declares one directly: bound and read as the raw JSON text, tagged with
      * the "jsonb" wire type so skunk's strict column-alignment check (added in 1.0) accepts it against an actual jsonb
      * column.
      */
    val jsonb: Codec[String] = Codec.simple[String](identity, s => Right(s), Type("jsonb"))

    /** A jsonb object read as a plain Scala map (`result.scores`).
      *
      * The translation itself is `JsonValues`, which the game engine's results callback shares: the model may not
      * depend on a JSON library — it is compiled for Scala.js as well — so the map holds `Any` and every crossing of
      * that line goes through the same conversion.
      */
    val jsonObject: Codec[Map[String, Any]] =
        jsonb.imap(JsonValues.objectToScala)(JsonValues.objectFromScala)

    private def jsonStringEncode(s: String): String = {
        val escaped = s.flatMap {
            case '"'  => "\\\""
            case '\\' => "\\\\"
            case '\n' => "\\n"
            case '\r' => "\\r"
            case '\t' => "\\t"
            case c    => c.toString
        }
        s""""$escaped""""
    }

    private def jsonStringDecode(s: String): String =
        s.stripPrefix("\"")
            .stripSuffix("\"")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

    /** A value stored as a JSON string scalar (e.g. `"foo"`), encoded/decoded via a TextCodec giving its plain-text
      * representation.
      */
    def jsonAsText[T](using codec: TextCodec[T]): Codec[T] =
        jsonb.imap(s => codec.decode(jsonStringDecode(s)))(v => jsonStringEncode(codec.encode(v)))

    /** A value stored as plain TEXT (not JSON), encoded/decoded via a TextCodec. */
    def plainText[T](using codec: TextCodec[T]): Codec[T] =
        text.imap(codec.decode)(codec.encode)
}
