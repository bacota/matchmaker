package com.vivi.matchmaker.util

/** Translation between a JSON value and the plain Scala values the model holds.
  *
  * The model is compiled for Scala.js as well as the JVM and so may not depend on a JSON library
  * — that is why `Result.scores` is a `Map[String, Any]` rather than anything typed. Both places
  * that have to cross that line, the jsonb codec in the persistence layer and the game engine's
  * results callback, use these.
  *
  * Numbers come back as `Double`: JSON keeps no distinction between an integer and a decimal
  * that would survive the round trip anyway.
  */
object JsonValues {

  def toScala(v: ujson.Value): Any = v match {
    case ujson.Str(s)      => s
    case ujson.Num(n)      => n
    case ujson.Bool(b)     => b
    case ujson.Null        => null
    case ujson.Arr(values) => values.map(toScala).toList
    case ujson.Obj(fields) => fields.view.mapValues(toScala).toMap
  }

  def fromScala(v: Any): ujson.Value = v match {
    case null                => ujson.Null
    case s: String           => ujson.Str(s)
    case b: Boolean          => ujson.Bool(b)
    case n: Number           => ujson.Num(n.doubleValue)
    case m: Map[_, _]        => ujson.Obj.from(m.view.map((k, value) => k.toString -> fromScala(value)))
    case values: Iterable[_] => ujson.Arr.from(values.map(fromScala))
    case other               => ujson.Str(other.toString)
  }

  def objectToScala(json: String): Map[String, Any] = ujson.read(json) match {
    case ujson.Obj(fields) => fields.view.mapValues(toScala).toMap
    case other             => throw IllegalArgumentException(s"expected a JSON object, got: $other")
  }

  def objectFromScala(m: Map[String, Any]): String =
    ujson.write(ujson.Obj.from(m.view.map((k, v) => k -> fromScala(v))))
}
