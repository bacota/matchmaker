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

    /* Signing is decided by where the engine runs, not by whether the environment holds keys: under
     * SnapStart a Lambda is given no AWS_ACCESS_KEY_ID at all, and an engine that looked for one would
     * write to DynamoDB unsigned and be refused. */
    test("AWS credentials come from the SDK's chain in Lambda, and are absent locally") {
        assert(AwsCredentials.provider(Map("AWS_LAMBDA_FUNCTION_NAME" -> "engine").get).isDefined)
        // A local run pointed at real AWS by keys in its environment still signs.
        assert(AwsCredentials.provider(Map("AWS_ACCESS_KEY_ID" -> "AKIA").get).isDefined)
        assertEquals(AwsCredentials.provider(_ => None), None)
    }
}
