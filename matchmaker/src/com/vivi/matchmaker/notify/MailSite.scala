package com.vivi.matchmaker.notify

import com.vivi.matchmaker.util.Sites

/** What a mail calls the place it sends its reader, and where that is. `url` has no trailing `/`. See
  * design/game-sites-plan.md.
  */
case class MailSite(name: String, url: String)

/** Which site a mail sends its reader to: the game site its tournament was made on, when that site is still configured
  * and shows the mail's game, and otherwise the main site. Pure: the tournament's origin is read by the caller. See
  * design/game-sites-plan.md, step 6b.
  */
final class MailSites(val main: MailSite, sites: Sites) {

    /** Whether any site is configured: when not, callers skip reading the tournament's origin. */
    def isEmpty: Boolean = sites.isEmpty

    /** The site at `origin`, if it is still configured and lists the game; otherwise the main site. */
    def choose(origin: Option[String], gameHandle: String): MailSite =
        origin
            .flatMap(sites.at)
            .filter(_.games.contains(gameHandle))
            .fold(main)(site => MailSite(name = site.heading, url = site.origin))
}
