package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import skunk.data.Type
import java.time.{Instant, OffsetDateTime, ZoneOffset}

object SkunkCodecs {

  val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))

  /** skunk-core ships no jsonb codec, so this declares one directly: bound and read as the
    * raw JSON text, tagged with the "jsonb" wire type so skunk's strict column-alignment
    * check (added in 1.0) accepts it against an actual jsonb column.
    */
  val jsonb: Codec[String] = Codec.simple[String](identity, s => Right(s), Type("jsonb"))

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
