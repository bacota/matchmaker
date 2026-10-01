package com.vivi.tictactoe

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * The point of it is the loop it closes: matchmaker's own `LocalServer` on one port, this on another, a `game` row
  * whose `url` is this engine's `/games` and whose `external_id` is what `GAME_EXTERNAL_ID` is set to here, and all
  * four exchanges of `interaction-design.txt` happen for real over HTTP — with both sides' authentication in their
  * local mode.
  *
  * {{{
  * # matchmaker on 8080, engine on 8090, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=tictactoe-dev mill -j 4 --ticker false engines.tictactoe.runMain com.vivi.tictactoe.LocalServer
  *
  * # or with nothing to call back to, to just play the board:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.tictactoe.runMain com.vivi.tictactoe.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        LocalEngineServer.run("tic-tac-toe", defaultPort = 8090) { (baseUrl, live) =>
            Config.routes(
              sys.env.get,
              defaultBaseUrl = Some(baseUrl),
              live = Some(live),
              announce = m => {
                  println(s"match ${m.matchId} created: $baseUrl/matches/${m.matchId}/play")
                  m.seats.foreach(seat =>
                      println(s"  ${seat.mark} ${seat.cognitoId} (participant ${seat.participantId})")
                  )
                  if (m.isPublic) println(s"  public board $baseUrl/matches/${m.matchId}/board")
              }
            )
        }
}
