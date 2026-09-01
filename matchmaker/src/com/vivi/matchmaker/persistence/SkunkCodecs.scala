package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import skunk.data.Type
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import com.vivi.matchmaker.model.{GameType, TimeLimitKind, TimeoutAction}
import com.vivi.matchmaker.util.JsonValues

object SkunkCodecs {

  val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))

  /** The `game_type` discriminator column (`CHAR(1)`, `'C'`/`'P'`), on `game` and on every
    * table split by it (`open_challenge`, `acceptance`, `participant`, and their `character_*`
    * siblings).
    */
  val gameType: Codec[GameType] = bpchar(1).imap(s => GameType.fromCode(s.head))(_.code.toString)

  /** `game.timeout_action`: what happens when a player's turn runs out. Text under a check
    * constraint rather than a one-character discriminator, because the set of actions is
    * expected to grow and 'FORFEIT' reads as itself in a query.
    */
  val timeoutAction: Codec[TimeoutAction] = text.imap(TimeoutAction.fromCode)(_.code)

  /** `time_limit_kind` on `match` and `open_challenge`: whether the limit is per turn or the
    * player's budget for the whole match. Text under a check constraint, as `timeoutAction` is.
    */
  val timeLimitKind: Codec[TimeLimitKind] = text.imap(TimeLimitKind.fromCode)(_.code)

  /** skunk-core ships no jsonb codec, so this declares one directly: bound and read as the
    * raw JSON text, tagged with the "jsonb" wire type so skunk's strict column-alignment
    * check (added in 1.0) accepts it against an actual jsonb column.
    */
  val jsonb: Codec[String] = Codec.simple[String](identity, s => Right(s), Type("jsonb"))

  /** A jsonb object read as a plain Scala map (`result.scores`).
    *
    * The translation itself is `JsonValues`, which the game engine's results callback shares:
    * the model may not depend on a JSON library — it is compiled for Scala.js as well — so the
    * map holds `Any` and every crossing of that line goes through the same conversion.
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
    s.stripPrefix("\"").stripSuffix("\"").replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")

  /** A value stored as a JSON string scalar (e.g. `"foo"`), encoded/decoded via a
    * TextCodec giving its plain-text representation.
    */
  def jsonAsText[T](using codec: TextCodec[T]): Codec[T] =
    jsonb.imap(s => codec.decode(jsonStringDecode(s)))(v => jsonStringEncode(codec.encode(v)))

  /** A value stored as plain TEXT (not JSON), encoded/decoded via a TextCodec. */
  def plainText[T](using codec: TextCodec[T]): Codec[T] =
    text.imap(codec.decode)(codec.encode)
}
