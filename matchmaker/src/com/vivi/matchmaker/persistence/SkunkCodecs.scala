package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import skunk.data.Type
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import com.vivi.matchmaker.model.GameType

object SkunkCodecs {

  val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))

  /** The `game_type` discriminator column (`CHAR(1)`, `'C'`/`'P'`), on `game` and on every
    * table split by it (`open_challenge`, `acceptance`, `participant`, and their `character_*`
    * siblings).
    */
  val gameType: Codec[GameType] = bpchar(1).imap(s => GameType.fromCode(s.head))(_.code.toString)

  /** skunk-core ships no jsonb codec, so this declares one directly: bound and read as the
    * raw JSON text, tagged with the "jsonb" wire type so skunk's strict column-alignment
    * check (added in 1.0) accepts it against an actual jsonb column.
    */
  val jsonb: Codec[String] = Codec.simple[String](identity, s => Right(s), Type("jsonb"))

  /** A jsonb object read as a plain Scala map (`result.scores`).
    *
    * The model may not depend on a JSON library — it is compiled for Scala.js as well — so the
    * map holds `Any` and the translation lives here. Numbers come back as `Double`: jsonb keeps
    * no distinction between an integer and a decimal that would survive the round trip anyway.
    */
  val jsonObject: Codec[Map[String, Any]] =
    jsonb.imap(decodeObject)(encodeObject)

  private def decodeObject(s: String): Map[String, Any] = ujson.read(s) match {
    case ujson.Obj(fields) => fields.view.mapValues(fromJson).toMap
    case other             => throw IllegalArgumentException(s"expected a JSON object, got: $other")
  }

  private def encodeObject(m: Map[String, Any]): String =
    ujson.write(ujson.Obj.from(m.view.map((k, v) => k -> toJson(v))))

  private def fromJson(v: ujson.Value): Any = v match {
    case ujson.Str(s)      => s
    case ujson.Num(n)      => n
    case ujson.Bool(b)     => b
    case ujson.Null        => null
    case ujson.Arr(values) => values.map(fromJson).toList
    case ujson.Obj(fields) => fields.view.mapValues(fromJson).toMap
  }

  private def toJson(v: Any): ujson.Value = v match {
    case null                 => ujson.Null
    case s: String            => ujson.Str(s)
    case b: Boolean           => ujson.Bool(b)
    case n: Number            => ujson.Num(n.doubleValue)
    case m: Map[_, _]         => ujson.Obj.from(m.view.map((k, value) => k.toString -> toJson(value)))
    case values: Iterable[_]  => ujson.Arr.from(values.map(toJson))
    case other                => ujson.Str(other.toString)
  }

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
