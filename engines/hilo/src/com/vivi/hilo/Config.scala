package com.vivi.hilo

import com.vivi.engine.{EngineConfig, Live}

/** How this engine is assembled from its environment, shared by the two ways it runs. What every engine reads alike is
  * [[EngineConfig]]; what is left here is which game it is assembled into.
  */
object Config {

    def routes(
        env: String => Option[String],
        defaultBaseUrl: Option[String] = None,
        announce: HiloMatch => Unit = _ => (),
        live: Option[Live] = None
    ): Routes = {
        val baseUrl = EngineConfig.requiredBaseUrl(env, defaultBaseUrl)
        Routes(
          engine = engine(env = env, baseUrl = baseUrl, announce = announce),
          playAuth = EngineConfig.playAuth(env, baseUrl),
          matchmakerKey = EngineConfig.matchmakerKey(env),
          live = live.orElse(EngineConfig.live(env, baseUrl))
        )
    }

    def engine(env: String => Option[String], baseUrl: String, announce: HiloMatch => Unit = _ => ()): Engine =
        Engine(
          store = EngineConfig.matchStore[HiloMatch](env),
          matchmaker = EngineConfig.matchmaker(env),
          baseUrl = baseUrl,
          announce = announce
        )
}
