package com.vivi.matchmaker.notify

import munit.FunSuite
import com.vivi.matchmaker.util.{Site, Sites}

/** Which site a mail sends its reader to. No database. See design/game-sites-plan.md, step 6b. */
class MailSitesSpec extends FunSuite {

    private val main = MailSite(name = "matchmaker", url = "https://matchmaker.example.com")

    private val ctf =
        Site(origin = "https://ctf.example", heading = "Capture the Flag", games = List("capture-the-flag"))
    private val fights = Site(origin = "https://fights.example", heading = "Fights", games = List("boxing"))

    private val sites = new MailSites(
      main = main,
      sites = Sites(Map(ctf.origin -> ctf, fights.origin -> fights))
    )

    test("a tournament made on a site that lists the game sends its mail there") {
        assertEquals(
          sites.choose(origin = Some("https://ctf.example"), gameHandle = "capture-the-flag"),
          MailSite(name = "Capture the Flag", url = "https://ctf.example")
        )
    }

    test("a site that does not list the game sends its mail to the main site") {
        assertEquals(sites.choose(origin = Some("https://fights.example"), gameHandle = "capture-the-flag"), main)
    }

    test("a site no longer configured sends its mail to the main site") {
        assertEquals(sites.choose(origin = Some("https://gone.example"), gameHandle = "capture-the-flag"), main)
    }

    test("a tournament made on the main domain sends its mail to the main site") {
        assertEquals(sites.choose(origin = None, gameHandle = "capture-the-flag"), main)
    }

    test("whether any site is configured") {
        assert(!sites.isEmpty)
        assert(new MailSites(main = main, sites = Sites.none).isEmpty)
    }

    test("the main site comes from UI_BASE_URL and is called matchmaker") {
        assertEquals(
          MailSettings(sender = Some("a@example.com"), uiBaseUrl = Some("https://matchmaker.example.com")).mainSite,
          Some(main)
        )
        assertEquals(MailSettings.none.mainSite, None)
    }
}
