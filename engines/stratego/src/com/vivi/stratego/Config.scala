package com.vivi.stratego

import com.vivi.engine.{EngineConfig, Live}

/** How this engine is assembled from its environment, shared by the two ways it runs.
  *
  * Everything read from the environment is read the same way by every engine — the base url, the matchmaker key, the
  * sign-in, where matches are kept, how matchmaker is called — and that is [[EngineConfig]]. What is left here is which
  * game they are assembled into.
  */
object Config {

    def routes(
        env: String => Option[String],
        defaultBaseUrl: Option[String] = None,
        announce: StrategoMatch => Unit = _ => (),
        live: Option[Live] = None
    ): Routes = {
        val baseUrl = EngineConfig.requiredBaseUrl(env, defaultBaseUrl)
        Routes(
          engine(env, baseUrl, announce),
          EngineConfig.playAuth(env, baseUrl),
          EngineConfig.matchmakerKey(env),
          live.orElse(EngineConfig.live(env, baseUrl))
        )
    }

    def engine(env: String => Option[String], baseUrl: String, announce: StrategoMatch => Unit = _ => ()): Engine =
        Engine(EngineConfig.matchStore[StrategoMatch](env), EngineConfig.matchmaker(env), baseUrl, announce = announce)
}
