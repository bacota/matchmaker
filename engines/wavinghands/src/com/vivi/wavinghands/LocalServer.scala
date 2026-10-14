package com.vivi.wavinghands

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * {{{
  * # matchmaker on 8080, engine on 8094, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=wavinghands-dev mill -j 4 --ticker false engines.wavinghands.runMain com.vivi.wavinghands.LocalServer
  *
  * # or with nothing to call back to, to just play:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.wavinghands.runMain com.vivi.wavinghands.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        // 8094: after tic-tac-toe, rock-paper-scissors, boxing and stratego, so all can run against one matchmaker.
        LocalEngineServer.run("waving hands", defaultPort = 8094) { (baseUrl, live) =>
            Config.routes(
              env = sys.env.get,
              defaultBaseUrl = Some(baseUrl),
              live = Some(live),
              announce = m => {
                  println(s"match ${m.matchId} created: $baseUrl/matches/${m.matchId}/play")
                  m.wizards.foreach(w => println(s"  ${m.nameOf(w)} ${w.cognitoId} (participant ${w.participantId})"))
                  if (m.isPublic) println(s"  public board $baseUrl/matches/${m.matchId}/board")
              }
            )
        }
}
