package com.vivi.matchmaker.util

import munit.FunSuite

/** Reading `UI_SITES`, and finding a request's site by its origin. No database. See design/game-sites-plan.md. */
class SitesSpec extends FunSuite {

    private val json =
        """{"https://capture-the-flag.vivi.com": {"heading": "Capture the Flag", "games": ["capture-the-flag"]},
          | "https://fights.vivi.com/": {"heading": "Fights", "games": ["boxing", "fencing"]}}""".stripMargin

    private val sites = Sites.parse(json)

    private val ctf = Site(
      origin = "https://capture-the-flag.vivi.com",
      heading = "Capture the Flag",
      games = List("capture-the-flag")
    )

    test("each site is read with its heading and games") {
        assertEquals(sites.byOrigin.size, 2)
        assertEquals(sites.byOrigin.get("https://capture-the-flag.vivi.com"), Some(ctf))
        assertEquals(sites.byOrigin.get("https://fights.vivi.com").map(_.games), Some(List("boxing", "fencing")))
    }

    test("a request's origin finds its site") {
        assertEquals(sites.forOrigin(Some("https://capture-the-flag.vivi.com")), Some(ctf))
    }

    test("a trailing slash on either side is ignored") {
        assertEquals(sites.forOrigin(Some("https://capture-the-flag.vivi.com/")), Some(ctf))
        assertEquals(sites.forOrigin(Some("https://fights.vivi.com")).map(_.heading), Some("Fights"))
    }

    test("an origin that is not a site is the whole instance") {
        assertEquals(sites.forOrigin(Some("https://matchmaker.vivi.com")), None)
    }

    test("no origin is the whole instance") {
        assertEquals(sites.forOrigin(None), None)
    }

    test("a stored origin finds its site while it is configured") {
        assertEquals(sites.at("https://capture-the-flag.vivi.com"), Some(ctf))
        assertEquals(sites.at("https://gone.vivi.com"), None)
    }

    test("an unset or blank UI_SITES is no sites") {
        assert(Sites.fromEnvironment(_ => None).isEmpty)
        assert(Sites.fromEnvironment(_ => Some("  ")).isEmpty)
        assert(Sites.fromEnvironment(_ => Some("{}")).isEmpty)
    }

    test("UI_SITES is read from the environment") {
        assertEquals(Sites.fromEnvironment(key => Option.when(key == "UI_SITES")(json)), sites)
    }

    test("malformed UI_SITES fails") {
        intercept[Exception](Sites.parse("""{"https://a.example": {"heading": "A"}}"""))
        intercept[Exception](Sites.parse("not json"))
    }
}
