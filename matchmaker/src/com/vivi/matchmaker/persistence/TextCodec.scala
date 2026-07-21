package com.vivi.matchmaker.persistence

/** Encodes/decodes the generic value types used by GameParameter, GameParameterValue,
  * and Character.state to/from the TEXT/JSONB columns that store them.
  */
trait TextCodec[T] {
  def encode(value: T): String
  def decode(text: String): T
}

object TextCodec {
  given TextCodec[String] with {
    def encode(value: String): String = value
    def decode(text: String): String = text
  }
}
