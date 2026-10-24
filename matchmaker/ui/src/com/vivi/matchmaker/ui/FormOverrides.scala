package com.vivi.matchmaker.ui

/** A competition format's own wording of the competition form (`StandardModel.formOverrides`, carried on the stored
  * model as `TournamentModel.formOverrides`): `labels.<key>` rewords the caption `labels.properties` has as `<key>`,
  * and `tips.<key>` the tip `tips.properties` has. Anything the format leaves alone keeps the UI's own words.
  */
private[ui] object FormOverrides {

    /** The caption `key` names, as `overrides` words it, or `default`, the UI's own. */
    def label(overrides: Map[String, String], key: String, default: String): String =
        overrides.getOrElse(s"labels.$key", default)

    /** The tip `key` names, as `overrides` words it with each `{name}` in `fills` given its value, or `default`, the
      * UI's own already filled in.
      */
    def tip(overrides: Map[String, String], key: String, default: String, fills: (String, String)*): String =
        overrides
            .get(s"tips.$key")
            .fold(default)(text => fills.foldLeft(text) { case (t, (name, value)) => t.replace(s"{$name}", value) })

    /** The keys of `overrides` that name neither a caption nor a tip there is, and so reword nothing. */
    def unknown(overrides: Map[String, String]): Set[String] =
        overrides.keySet.filterNot {
            case s"labels.$key" => Labels.keys(key)
            case s"tips.$key"   => Tips.keys(key)
            case _              => false
        }
}
