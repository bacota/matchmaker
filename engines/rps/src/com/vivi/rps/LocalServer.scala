package com.vivi.rps

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * The point of it is the loop it closes: matchmaker's own `LocalServer` on one port, this on another, a `game` row
  * whose `url` is this engine's `/games` and whose `external_id` is what `GAME_EXTERNAL_ID` is set to here, and all
  * four exchanges of `interaction-design.txt` happen for real over HTTP — with both sides' authentication in their
  * local mode.
  *
  * {{{
  * # matchmaker on 8080, engine on 8091, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=rps-dev mill -j 4 --ticker false engines.rps.runMain com.vivi.rps.LocalServer
  *
  * # or with nothing to call back to, to just play the board:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.rps.runMain com.vivi.rps.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        // 8091 rather than tic-tac-toe's 8090, so that both engines can be run against one
        // matchmaker at once — which is the only way to see two games in one player's lists.
        LocalEngineServer.run("rock-paper-scissors", defaultPort = 8091) { baseUrl =>
            Config.routes(
              sys.env.get,
              defaultBaseUrl = Some(baseUrl),
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
