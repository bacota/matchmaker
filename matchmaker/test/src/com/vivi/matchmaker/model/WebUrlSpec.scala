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

    test("an https url, or http to this machine, is secure") {
        assert(WebUrl.isSecure("https://engine.example.com/play/1"))
        assert(WebUrl.isSecure("HTTPS://engine.example.com/"))
        assert(WebUrl.isSecure("http://localhost:8081/play/1"))
        assert(WebUrl.isSecure("http://localhost"))
        assert(WebUrl.isSecure("http://127.0.0.1:8081/play/1?as=alice"))
        assert(WebUrl.isSecure("http://[::1]:8081/play/1"))
        assert(WebUrl.isSecure("HTTP://LOCALHOST:8081/"))
    }

    test("http to anywhere else is not secure, however it names this machine") {
        assert(!WebUrl.isSecure("http://engine.example.com/play/1"))
        assert(!WebUrl.isSecure("http://localhost.example.com/play/1"))
        assert(!WebUrl.isSecure("http://localhost@engine.example.com/play/1"))
        assert(!WebUrl.isSecure("http://127.0.0.1.example.com/"))
        assert(!WebUrl.isSecure("http://localhost:8081@engine.example.com/"))
        assert(!WebUrl.isSecure("javascript:alert(1)//https://engine.example.com/"))
        assert(!WebUrl.isSecure(" https://engine.example.com/"))
    }
}
