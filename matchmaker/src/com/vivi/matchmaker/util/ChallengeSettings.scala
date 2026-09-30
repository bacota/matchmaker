package com.vivi.matchmaker.util

import scala.util.control.NonFatal

/** The game-parameter choices a challenger makes, as they are kept in a challenge's `settings`.
  *
  * A game's parameters are the admin's: each has a name, the values it may take, and a default. The challenger picks
  * one of those values per parameter, and the pick is stored in the challenge's `settings` object under the parameter's
  * name — `{"rounds":"12"}` — which needs no column of its own and already travels from challenge to match to engine.
  * When the match starts, matchmaker sends the engine each parameter's chosen value in place of its default.
  *
  * Keys that name no parameter of the game are left alone: `settings` is also the engine's, and matchmaker has no
  * business refusing what it does not understand. Only a key that *does* name a parameter is held to that parameter's
  * values.
  *
  * The challenge form in the UI writes the same flat object of strings; `encode` is the shape it has to match.
  */
object ChallengeSettings {

    /** Each top-level key of `settings` whose value is a string or a whole number, as text.
      *
      * Unreadable or non-object settings have no choices in them — that is for [[problem]] to object to on the way in;
      * here, on the way out, a challenge stored before choices existed simply has none.
      */
    def choices(settings: String): Map[String, String] =
        objectOf(settings)
            .map(_.value.toMap.flatMap((k, v) => textOf(v).map(k -> _)))
            .getOrElse(Map.empty)

    /** Settings holding exactly these choices. */
    def encode(choices: Map[String, String]): String =
        ujson.write(ujson.Obj.from(choices.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

    /** Why `settings` cannot be accepted for a game whose parameters allow `allowed` (name to values), if it cannot.
      *
      * It must be a JSON object, and every key naming a parameter must hold one of that parameter's values.
      */
    def problem(settings: String, allowed: Map[String, Seq[String]]): Option[String] =
        objectOf(settings) match {
            case None => Some("settings must be a JSON object")
            case Some(obj) =>
                obj.value.iterator
                    .collect { case (name, raw) if allowed.contains(name) => (name, raw, allowed(name)) }
                    .collectFirst {
                        case (name, raw, values) if !textOf(raw).exists(values.contains) =>
                            s"'${textOf(raw).getOrElse(ujson.write(raw))}' is not a value of $name; " +
                                s"expected one of ${values.mkString(", ")}"
                    }
        }

    /** What the engine is told for each parameter: the challenger's choice where there is one the game still allows,
      * and the game's default otherwise.
      *
      * "Still allows" because the admin may have removed a value between the challenge being made and the match
      * starting, and a start that fails over it helps nobody; the default is the game's answer to "nothing chosen".
      */
    def resolve(
        defaults: Map[String, String],
        allowed: Map[String, Seq[String]],
        settings: String
    ): Map[String, String] = {
        val chosen = choices(settings)
        defaults.map { (name, default) =>
            name -> chosen.get(name).filter(v => allowed.getOrElse(name, Nil).contains(v)).getOrElse(default)
        }
    }

    private def objectOf(settings: String): Option[ujson.Obj] =
        try
            ujson.read(settings) match {
                case o: ujson.Obj => Some(o)
                case _            => None
            }
        catch { case NonFatal(_) => None }

    private def textOf(value: ujson.Value): Option[String] =
        value match {
            case ujson.Str(s)                          => Some(s)
            case ujson.Num(n) if n.isWhole && !n.isNaN => Some(n.toLong.toString)
            case _                                     => None
        }
}
