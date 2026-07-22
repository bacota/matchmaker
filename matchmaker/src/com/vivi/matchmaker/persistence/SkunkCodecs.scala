package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import java.time.{Instant, OffsetDateTime, ZoneOffset}

object SkunkCodecs {

  val instant: Codec[Instant] =
    timestamptz.imap(_.toInstant)(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))

  /** A value stored as JSONB text, encoded/decoded via a TextCodec. Bound as plain text;
    * callers must cast with `::jsonb` on the SQL side.
    */
  def jsonAsText[T](using codec: TextCodec[T]): Codec[T] =
    text.imap(codec.decode)(codec.encode)
}
