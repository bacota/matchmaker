package com.vivi.matchmaker.ui

import com.raquo.laminar.api.L.{*, given}
import com.vivi.matchmaker.model._

/** The competition form's fields for a game's own settings, and the reading and writing of what is chosen in them. */
object GameSettingsFields {

    import Views.field

    /** One of a game's parameters as a competition's creator picks it: `name` is the key it is stored and sent to the
      * engine under, `label` what they are shown for it.
      */
    private[ui] case class ParameterChoice(name: String, label: String, values: Seq[String], default: Option[String])

    /** The values that may be picked for each of the game's parameters, in the order [[Format.parameterValues]] puts
      * them. Parameters with no values to choose between are left out: there is nothing to offer.
      */
    private[ui] def parameterChoices(game: Game): Seq[ParameterChoice] =
        game.parameters
            .map(p =>
                ParameterChoice(
                  name = p.name,
                  label = p.displayName,
                  values = Format.parameterValues(p.values.map(_.value.toString)),
                  default = p.defaultValue.map(_.toString)
                )
            )
            .filter(_.values.nonEmpty)

    /** A competition's parameter choices as its `settings` carries them: `{"rounds":"12"}`. The same flat object of
      * strings `ChallengeSettings` reads on the server.
      */
    private[ui] def settingsOf(choices: Map[String, String]): String =
        ujson.write(ujson.Obj.from(choices.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

    /** The choices `settings` holds, by parameter name: the flat object of strings [[settingsOf]] writes, with a whole
      * number read as one too. Empty for settings that are not such an object.
      */
    private[ui] def storedChoices(settings: String): Map[String, String] =
        try
            ujson.read(settings) match {
                case o: ujson.Obj =>
                    o.value.toMap.collect {
                        case (k, ujson.Str(v))              => k -> v
                        case (k, ujson.Num(n)) if n.isWhole => k -> n.toLong.toString
                    }
                case _ => Map.empty[String, String]
            }
        catch { case _: Throwable => Map.empty[String, String] }

    /** Each of the game's parameters' value for a form to start from: what `settings` chose, where it is still a value
      * the game allows, else the game's default, else its first value.
      */
    private[ui] def startingChoices(game: Game, settings: String = "{}"): Map[String, String] = {
        val stored = storedChoices(settings)
        parameterChoices(game).map { choice =>
            choice.name -> stored
                .get(choice.name)
                .filter(choice.values.contains)
                .orElse(choice.default.filter(choice.values.contains))
                .getOrElse(choice.values.head)
        }.toMap
    }

    /** One select per game parameter, captioned with what players are shown for it and keyed by the name the engine is
      * sent, writing into `parameters`.
      */
    private[ui] def parameterFields(game: Game, parameters: Var[Map[String, String]]): Seq[HtmlElement] =
        parameterChoices(game).map { choice =>
            field(
              if (choice.label == choice.name) choice.name.capitalize else choice.label,
              select(
                onChange.mapToValue --> (chosen => parameters.update(_.updated(choice.name, chosen))),
                value <-- parameters.signal.map(_.getOrElse(choice.name, "")),
                choice.values.map(v => option(value := v, v))
              )
            )
        }

    /** When to play as the browser's date and time input gives it -- the local time, with no zone -- as an instant, or
      * `None` for blank or anything the browser could not read.
      */
    private[ui] def startOf(raw: String): Option[java.time.Instant] =
        Option(raw.trim).filter(_.nonEmpty).flatMap { local =>
            val millis = new scala.scalajs.js.Date(local).getTime()
            Option.when(!millis.isNaN)(java.time.Instant.ofEpochMilli(millis.toLong))
        }

    /** An instant as the browser's date and time input shows it: the local time, to the minute. */
    private[ui] def localInputOf(at: java.time.Instant): String = {
        val d = new scala.scalajs.js.Date(at.toEpochMilli.toDouble)
        def two(n: Double) = f"${n.toInt}%02d"
        s"${d.getFullYear().toInt}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}"
    }
}
