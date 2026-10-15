package com.vivi.hilo

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * {{{
  * # matchmaker on 8080, engine on 8095, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=hilo-dev mill -j 4 --ticker false engines.hilo.runMain com.vivi.hilo.LocalServer
  *
  * # or with nothing to call back to, to just play:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.hilo.runMain com.vivi.hilo.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        // 8095: after tic-tac-toe, rock-paper-scissors, boxing, stratego and waving hands, so all can run against one
        // matchmaker.
        LocalEngineServer.run("hilo", defaultPort = 8095) { (baseUrl, live) =>
            Config.routes(
              env = sys.env.get,
              defaultBaseUrl = Some(baseUrl),
              live = Some(live),
              announce = m => {
                  println(s"match ${m.matchId} created: $baseUrl/matches/${m.matchId}/play")
                  m.players.foreach(p => println(s"  ${m.nameOf(p)} ${p.cognitoId} (participant ${p.participantId})"))
                  if (m.isPublic) println(s"  public board $baseUrl/matches/${m.matchId}/board")
              }
            )
        }
}
