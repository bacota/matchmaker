package com.vivi.boxing

import com.vivi.engine.LocalEngineServer

/** Runs the engine on a local port, with matches in memory and no AWS involved.
  *
  * The point of it is the loop it closes: matchmaker's own `LocalServer` on one port, this on another, a `game` row
  * whose `url` is this engine's `/games` and whose `external_id` is what `GAME_EXTERNAL_ID` is set to here, and all
  * four exchanges of `interaction-design.txt` happen for real over HTTP — with both sides' authentication in their
  * local mode.
  *
  * {{{
  * # matchmaker on 8080, engine on 8092, callbacks authorized by the game's external id
  * GAME_EXTERNAL_ID=boxing-dev mill -j 4 --ticker false engines.boxing.runMain com.vivi.boxing.LocalServer
  *
  * # or with nothing to call back to, to just fight the bout:
  * MATCHMAKER_OFFLINE=true mill -j 4 --ticker false engines.boxing.runMain com.vivi.boxing.LocalServer
  * }}}
  */
object LocalServer {

    def main(args: Array[String]): Unit =
        // 8092, after tic-tac-toe's 8090 and rock-paper-scissors' 8091, so that all three engines can
        // be run against one matchmaker at once.
        LocalEngineServer.run("boxing", defaultPort = 8092) { baseUrl =>
            Config.routes(
              sys.env.get,
              defaultBaseUrl = Some(baseUrl),
              announce =
                  m => {
                      println(s"match ${m.matchId} created: $baseUrl/matches/${m.matchId}/play")
                      println(s"  ${m.scheduledRounds} rounds scheduled")
                      m.corners
                          .foreach(c =>
                              println(
                                s"  ${c.side} ${c.cognitoId} (participant ${c.participantId}, fighter ${c.characterId}${if (c.fighter.isEmpty) ", not built yet" else ""})"
                              )
                          )
                      if (m.isPublic) println(s"  public board $baseUrl/matches/${m.matchId}/board")
                  }
            )
        }
}
