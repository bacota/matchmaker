package com.vivi.engine

import munit.FunSuite

/** The settings read the same way by every engine. The sign-in settings are `PlayAuthSpec`'s. */
class EngineConfigSpec extends FunSuite {

    test("MATCHMAKER_API_KEY is required in Lambda and optional outside it") {
        assertEquals(EngineConfig.matchmakerKey(Map("MATCHMAKER_API_KEY" -> "k").get), Some("k"))
        assertEquals(EngineConfig.matchmakerKey(_ => None), None)
        // Blank is the same as unset: a variable set to "" is a forgotten one, not an opt-out.
        assertEquals(EngineConfig.matchmakerKey(Map("MATCHMAKER_API_KEY" -> "  ").get), None)
        intercept[IllegalStateException](
          EngineConfig.matchmakerKey(Map("AWS_LAMBDA_FUNCTION_NAME" -> "engine").get)
        )
    }
}
