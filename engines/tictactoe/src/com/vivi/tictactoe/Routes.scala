package com.vivi.tictactoe

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal
import upickle.default.{read, write}
import Protocol.given

/** A request, however it arrived. Header names are lowercased on the way in, since payload v2
  * lowercases them and a case-sensitive lookup for "Authorization" would silently never match.
  *
  * `claims` are only ever populated by the Lambda handler, from the block API Gateway's JWT
  * authorizer writes into the event; the local server verifies the bearer token itself instead.
  */
case class EngineRequest(
    method: String,
    path: String,
    query: Map[String, String] = Map.empty,
    body: String = "",
    headers: Map[String, String] = Map.empty,
    claims: Map[String, String] = Map.empty
) {
  def segments: List[String] = path.split('/').iterator.filter(_.nonEmpty).toList

  def bearerToken: Option[String] =
    headers.get("authorization").map(_.trim).collect {
      case value if value.toLowerCase.startsWith("bearer ") => value.drop(7).trim
    }.filter(_.nonEmpty)
}

case class EngineResponse(status: Int, body: String, contentType: String = "application/json")

/** The engine's HTTP surface, as a function from request to response.
  *
  * Transport-independent for the same reason matchmaker's own `Router` is: the local server and
  * the Lambda handler are two ways of delivering the same request, and neither should be able to
  * answer differently from the other.
  *
  * Three kinds of route, authorized three ways. The create and status calls are matchmaker's, and
  * carry the API key this engine and matchmaker share — checked here, since nothing in front of
  * the function checks it. The play routes are a player's, and carry a Cognito
  * ID token from the same user pool matchmaker signs its players in with — [[PlayAuth]] turns
  * that into a subject and [[Engine.seatOf]] turns the subject into a seat. The board is nobody's
  * and needs no identity at all, which is why it is refused unless the match was created public.
  */
class Routes(engine: Engine, playAuth: PlayAuth, matchmakerKey: Option[String]) {

  /** Matchmaker, or not.
    *
    * `None` for `matchmakerKey` means no key was configured, and the route is open — which is
    * the local case, where the engine is driven by curl and there is no secret to share. A
    * deployed engine is never in that state: `Config` refuses to start one without a key, so an
    * unset variable fails at the first cold start rather than quietly serving game creation to
    * anyone who finds the url.
    *
    * The comparison is constant-time, and a wrong key is refused the same way as a missing one:
    * distinguishing them tells a caller that guessing is worth continuing.
    */
  private def fromMatchmaker(request: EngineRequest): Boolean =
    matchmakerKey match {
      case None => true
      case Some(expected) =>
        request.headers.get("x-api-key").map(_.trim).exists { presented =>
          MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
        }
    }

  def apply(request: EngineRequest): EngineResponse =
    try route(request)
    catch {
      case e: ConcurrentModification => error(409, e.getMessage)
      case NonFatal(e) =>
        // A bug or a failure of something behind the engine. The caller gets the shape of it;
        // the trace has to be on stderr or the 500 is not diagnosable.
        Log.failure(e, s"${request.method} ${request.path}")
        error(500, s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  private def route(request: EngineRequest): EngineResponse =
    (request.method.toUpperCase, request.segments) match {

      // Step 1. Matchmaker creating a game; the only route that makes a match exist.
      case ("POST", "games" :: Nil) if !fromMatchmaker(request) => unauthenticated

      case ("POST", "games" :: Nil) =>
        parse[Protocol.CreateGameRequest](request.body) match {
          case Left(why) => error(400, why)
          case Right(create) =>
            engine.createGame(create) match {
              case Left(refusal) => error(refusal)
              case Right(created) => EngineResponse(201, write(created))
            }
        }

      // Step 4. Matchmaker asking how the match is going.
      case ("GET", "matches" :: matchId :: "status" :: Nil) if !fromMatchmaker(request) => unauthenticated

      case ("GET", "matches" :: matchId :: "status" :: Nil) =>
        // Unparseable rather than absent is answered as a bad request: matchmaker sends this to
        // decide what a player is charged, and quietly treating a malformed time as "send
        // everything" would double-report turns it already has.
        parseSince(request) match {
          case Left(why) => error(400, why)
          case Right(since) =>
            engine.status(matchId, since) match {
              case Left(refusal) => error(refusal)
              case Right(status) => EngineResponse(200, write(status))
            }
        }

      /* The board page itself, which is served to anyone who asks — signed in or not.
       *
       * It carries no game state when the caller has no seat: the page is a shell that signs the
       * player in and then fetches `state` with their token, so an unauthenticated GET here
       * discloses nothing but the fact that a match id exists. The alternative, refusing it, would
       * mean a player following the url from matchmaker gets a bare 401 with nowhere to sign in.
       */
      case ("GET", "matches" :: matchId :: "play" :: Nil) =>
        engine.read(matchId) match {
          case Left(refusal) => error(refusal)
          case Right(m) =>
            val seat = playAuth.callerOf(request).toOption.flatMap(m.seatFor)
            val state = Option.when(seat.isDefined)(engine.stateOf(m, seat))
            EngineResponse(200, Html.board(matchId, state, playAuth.login), "text/html; charset=utf-8")
        }

      case ("GET", "matches" :: matchId :: "state" :: Nil) =>
        withSeat(request, matchId)((m, seat) => EngineResponse(200, write(engine.stateOf(m, Some(seat)))))

      case ("POST", "matches" :: matchId :: "moves" :: Nil) =>
        parse[Protocol.MoveRequest](request.body) match {
          case Left(why) => error(400, why)
          case Right(move) =>
            playAuth.callerOf(request) match {
              case Left(refusal) => error(refusal)
              case Right(caller) =>
                engine.move(matchId, caller, move.cell) match {
                  case Left(refusal) => error(refusal)
                  case Right(applied) =>
                    EngineResponse(200, write(engine.stateOf(applied.state, Some(applied.moved))))
                }
            }
        }

      /* Where the hosted login sends the player back to.
       *
       * One fixed path rather than the match's own url, because Cognito matches callback urls
       * exactly and cannot be given a pattern — a per-match redirect would mean registering one
       * per match. The page redeems the code and then returns to wherever the flow started, which
       * it carried through the `state` parameter.
       */
      case ("GET", "auth" :: "callback" :: Nil) =>
        playAuth.login match {
          case Some(login) => EngineResponse(200, Html.authCallback(login), "text/html; charset=utf-8")
          case None        => error(404, "this engine has no sign-in configured")
        }

      // The public board, for a match created public. Nobody's seat, so no token and no moves.
      case ("GET", "matches" :: matchId :: "board" :: Nil) =>
        withPublic(matchId)(m =>
          EngineResponse(200, Html.board(matchId, Some(engine.stateOf(m, None)), None, publicView = true), "text/html; charset=utf-8")
        )

      case ("GET", "matches" :: matchId :: "board" :: "state" :: Nil) =>
        withPublic(matchId)(m => EngineResponse(200, write(engine.stateOf(m, None))))

      case ("GET", "health" :: Nil) => EngineResponse(200, """{"status":"ok"}""")

      case _ => error(404, s"no route for ${request.method} ${request.path}")
    }

  private def withSeat(request: EngineRequest, matchId: String)(f: (TicTacToeMatch, Seat) => EngineResponse): EngineResponse = {
    val answer =
      for {
        m <- engine.read(matchId)
        caller <- playAuth.callerOf(request)
        seat <- engine.seatOf(m, caller)
      } yield f(m, seat)

    answer match {
      case Left(refusal)   => error(refusal)
      case Right(response) => response
    }
  }

  private def withPublic(matchId: String)(f: TicTacToeMatch => EngineResponse): EngineResponse =
    engine.read(matchId) match {
      case Left(refusal) => error(refusal)
      // Not 404: the match exists, and saying so tells a would-be watcher nothing they could not
      // learn by being in it. What they may not do is watch.
      case Right(m) if !m.isPublic => error(403, s"match '$matchId' is not public")
      case Right(m)                => f(m)
    }

  private def parse[A: upickle.default.Reader](body: String): Either[String, A] =
    try Right(read[A](body))
    catch { case NonFatal(e) => Left(s"unreadable request body: ${e.getMessage}") }

  private def unauthenticated: EngineResponse = error(401, "this route is matchmaker's; a valid API key is required")

  /* The `since` of a status call: an ISO-8601 instant, or nothing at all. */
  private def parseSince(request: EngineRequest): Either[String, Option[java.time.Instant]] =
    request.query.get("since").map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(raw) =>
        try Right(Some(java.time.Instant.parse(raw)))
        catch { case _: java.time.format.DateTimeParseException => Left(s"unreadable 'since': $raw") }
    }

  private def error(refusal: Refusal): EngineResponse = error(refusal.status, refusal.message)

  private def error(status: Int, message: String): EngineResponse =
    EngineResponse(status, ujson.write(ujson.Obj("error" -> message)))
}
