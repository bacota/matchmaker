package com.vivi.stratego

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * {{{
  * # matchmaker on 8080, engine on 8093, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=stratego-dev mill -j 4 --ticker false engines.stratego.runMain com.vivi.stratego.LocalServer
  *
  * # or with nothing to call back to, to just play the board:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.stratego.runMain com.vivi.stratego.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        // 8093, after tic-tac-toe's 8090, rock-paper-scissors' 8091 and boxing's 8092, so that every
        // engine can be run against one matchmaker at once.
        LocalEngineServer.run("stratego", defaultPort = 8093) { (baseUrl, live) =>
            Config.routes(
              sys.env.get,
              defaultBaseUrl = Some(baseUrl),
              live = Some(live),
              announce = m => {
                  println(s"match ${m.matchId} created: $baseUrl/matches/${m.matchId}/play")
                  m.seats.foreach(seat =>
                      println(s"  ${seat.side} ${seat.cognitoId} (participant ${seat.participantId})")
                  )
                  if (m.isPublic) println(s"  public board $baseUrl/matches/${m.matchId}/board")
              }
            )
        }
}
