package com.vivi.matchmaker.ui

/** A competition format's own wording of the competition form (`StandardModel.formOverrides`, carried on the stored
  * model as `TournamentModel.formOverrides`): `labels.<key>` rewords the caption `labels.properties` has as `<key>`,
  * and `tips.<key>` the tip `tips.properties` has. `None` leaves it out: a caption's `None` the whole field, a tip's
  * just the tip. Anything the format leaves alone keeps the UI's own words.
  */
private[ui] object FormOverrides {

    /** The fields every competition needs, or that the others need to be reached: never left out. The time unit is part
      * of the time limit's field rather than a field of its own.
      */
    val alwaysShown: Set[String] = Set("competition.name", "competition.format", "competition.timeUnit")

    /** The caption `key` names, as `overrides` words it, or `default`, the UI's own; `None` for a field left out. */
    def label(overrides: Map[String, Option[String]], key: String, default: String): Option[String] =
        if (alwaysShown(key)) Some(overrides.get(s"labels.$key").flatten.getOrElse(default))
        else overrides.getOrElse(s"labels.$key", Some(default))

    /** The tip `key` names, as `overrides` words it with each `{name}` in `fills` given its value, or `default`, the
      * UI's own already filled in; `None` for a tip left out, by the format or, as an empty `default`, by
      * `tips.properties`.
      */
    def tip(
        overrides: Map[String, Option[String]],
        key: String,
        default: String,
        fills: (String, String)*
    ): Option[String] =
        overrides
            .getOrElse(s"tips.$key", Some(default).filter(_.nonEmpty))
            .map(text => fills.foldLeft(text) { case (t, (name, value)) => t.replace(s"{$name}", value) })

    /** The keys of `overrides` that name neither a caption nor a tip there is, and so reword nothing. */
    def unknown(overrides: Map[String, Option[String]]): Set[String] =
        overrides.keySet.filterNot {
            case s"labels.$key" => Labels.keys(key)
            case s"tips.$key"   => Tips.keys(key)
            case _              => false
        }

    /** The keys of `overrides` that would leave out a field that is [[alwaysShown]], which the form shows anyway. */
    def unhideable(overrides: Map[String, Option[String]]): Set[String] =
        alwaysShown.map(k => s"labels.$k").filter(overrides.get(_).contains(None))
}
