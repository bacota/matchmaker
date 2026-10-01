package com.vivi.rps

import com.vivi.engine.{AwsCredentials, EngineConfig, SignedHttp}

/** How this engine is assembled from its environment, shared by the two ways it runs. The settings that are the same
  * for every engine — the base url, the matchmaker key, the sign-in — are read by [[EngineConfig]].
  */
object Config {

    def routes(
        env: String => Option[String],
        defaultBaseUrl: Option[String] = None,
        announce: RpsMatch => Unit = _ => ()
    ): Routes = {
        val baseUrl = EngineConfig.requiredBaseUrl(env, defaultBaseUrl)
        Routes(engine(env, baseUrl, announce), EngineConfig.playAuth(env, baseUrl), EngineConfig.matchmakerKey(env))
    }

    def engine(env: String => Option[String], baseUrl: String, announce: RpsMatch => Unit = _ => ()): Engine = {
        val region = EngineConfig.region(env)
        val http = SignedHttp(AwsCredentials.fromEnvironment(env), region)

        val store = env("MATCH_TABLE") match {
            case Some(table) => DynamoDbMatchStore(http, table, region)
            // Fine for the local server, whose process outlives its matches, and wrong for Lambda,
            // where the next invocation may be a different container — hence the table.
            case None => InMemoryMatchStore()
        }

        val matchmaker: Matchmaker =
            if (env("MATCHMAKER_OFFLINE").contains("true")) RecordingMatchmaker(println)
            // Unsigned: matchmaker's callback routes take an API key now, not a SigV4 signature. The
            // signed client stays for DynamoDB above, which is still AWS and still needs one.
            else HttpMatchmaker(SignedHttp(None, region), EngineConfig.matchmakerKey(env), env("GAME_EXTERNAL_ID"))

        Engine(store, matchmaker, baseUrl, announce = announce)
    }
}
