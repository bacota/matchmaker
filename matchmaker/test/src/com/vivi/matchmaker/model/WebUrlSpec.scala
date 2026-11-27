package com.vivi.matchmaker.model

import munit.FunSuite

class WebUrlSpec extends FunSuite {

    test("an http or https url is a web url, whatever the case of its scheme") {
        assert(WebUrl.isWeb("https://engine.example.com/play/1"))
        assert(WebUrl.isWeb("http://localhost:8081/play/1"))
        assert(WebUrl.isWeb("HTTPS://engine.example.com/"))
    }

    test("a url a browser would run, or one with anything before its scheme, is not") {
        assert(!WebUrl.isWeb("javascript:alert(1)//https://engine.example.com/"))
        assert(!WebUrl.isWeb("JavaScript:alert(1)"))
        assert(!WebUrl.isWeb("data:text/html,<script>alert(1)</script>"))
        assert(!WebUrl.isWeb(" javascript:alert(1)"))
        assert(!WebUrl.isWeb("java\tscript:alert(1)"))
        assert(!WebUrl.isWeb("//engine.example.com/play/1"))
        assert(!WebUrl.isWeb(""))
    }
}
