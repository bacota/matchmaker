package com.vivi.matchmaker.util

/** One game site: the matchmaker UI on a domain of its own, showing only some games. `origin` is its scheme and host,
  * with no path and no trailing slash; `games` are the names (`Game.name`) of the games it shows. See
  * design/game-sites-plan.md.
  */
case class Site(origin: String, heading: String, games: List[String])

/** The game sites this deployment serves, by origin, as the `UI_SITES` environment variable lists them. */
case class Sites(byOrigin: Map[String, Site]) {

    /** Whether no site is configured. */
    def isEmpty: Boolean = byOrigin.isEmpty

    /** The site a request came from, by its `Origin` header. None: the whole instance. */
    def forOrigin(origin: Option[String]): Option[Site] = origin.flatMap(at)

    /** The site at a stored origin, if it is still configured. */
    def at(origin: String): Option[Site] = byOrigin.get(Sites.normalized(origin))
}

object Sites {
    val none: Sites = Sites(Map.empty)

    private def normalized(origin: String): String = origin.trim.stripSuffix("/")

    /** `UI_SITES`: a JSON object keyed by origin, each value `{ "heading": …, "games": [ … ] }`. Throws on malformed
      * JSON: terraform writes it with `jsonencode`, so a failure to parse is a real bug, not something to work around.
      */
    def parse(json: String): Sites =
        Sites(
          ujson
              .read(json)
              .obj
              .map { case (key, value) =>
                  val origin = normalized(key)
                  origin -> Site(
                    origin = origin,
                    heading = value("heading").str,
                    games = value("games").arr.map(_.str).toList
                  )
              }
              .toMap
        )

    /** From the `UI_SITES` environment variable: none when it is unset or blank. */
    def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): Sites =
        env("UI_SITES").map(_.trim).filter(_.nonEmpty).fold(none)(parse)

    /** This process's sites, read once. */
    lazy val current: Sites = fromEnvironment()
}
