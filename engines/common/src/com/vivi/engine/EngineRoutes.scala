package com.vivi.engine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal
import upickle.default.{read, write, Reader, Writer}
import Protocol.given

/** An engine's HTTP surface, as a function from request to response.
  *
  * Transport-independent for the same reason matchmaker's own `Router` is: the local server and the Lambda handler are
  * two ways of delivering the same request, and neither should be able to answer differently from the other.
  *
  * Three kinds of route, authorized three ways. The create and status calls are matchmaker's, and carry the API key the
  * engine and matchmaker share — checked here, since nothing in front of the function checks it. The play routes are a
  * player's, and carry a Cognito ID token from the same user pool matchmaker signs its players in with — [[PlayAuth]]
  * turns that into a subject and [[GameEngine.seatOf]] turns the subject into a seat. The board is nobody's and needs
  * no identity at all, which is why it is refused unless the match was created public.
  *
  * Every engine serves the same routes. What a game supplies is what is its own: the state a player is shown and the
  * page that shows it, what its moves look like on the wire, and any route only it has.
  *
  * With `live`, it also serves Play Live: a connection is admitted on the same terms as the state route — a seat in the
  * match, or a public match's board — and every successful player `POST` on a match is followed by a push to whoever is
  * watching it. A push follows the route rather than the move, so a route only one game has, like boxing's fighter, is
  * pushed without that game having to say so.
  *
  * @tparam V
  *   the game's play state, as the play page and a scripted client read it
  */
abstract class EngineRoutes[M <: MatchLike, S <: SeatLike, V: Writer](
    engine: GameEngine[M, S, ?],
    playAuth: PlayAuth,
    matchmakerKey: Option[String],
    signIn: SignIn,
    live: Option[Live] = None
) extends (EngineRequest => EngineResponse) {

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board — and what a viewer may
      * not see, such as a move the other player has yet to answer, must already be absent from it.
      */
    protected def stateOf(m: M, seat: Option[S]): V

    /** The play page, with `state` inlined when the viewer has a seat. `publicView` is the public board, and `liveUrl`
      * where its Play Live connection is opened — absent when this engine offers none, and the switch with it.
      */
    protected def page(
        matchId: String,
        state: Option[V],
        login: Option[LoginConfig],
        liveUrl: Option[String],
        publicView: Boolean
    ): String

    /** `POST /matches/{matchId}/moves`: the body read as one of this game's moves, and made by the caller. Answered,
      * when it is made, with [[moved]].
      */
    protected def move(request: EngineRequest, matchId: String): EngineResponse

    /** Routes only this game has, by method and path segments. */
    protected def extra: PartialFunction[(String, List[String]), EngineRequest => EngineResponse] =
        PartialFunction.empty

    /** Matchmaker, or not.
      *
      * `None` for `matchmakerKey` means no key was configured, and the route is open — which is the local case, where
      * the engine is driven by curl and there is no secret to share. A deployed engine is never in that state:
      * [[EngineConfig]] refuses to start one without a key, so an unset variable fails at the first cold start rather
      * than quietly serving game creation to anyone who finds the url.
      *
      * The comparison is constant-time, and a wrong key is refused the same way as a missing one: distinguishing them
      * tells a caller that guessing is worth continuing.
      */
    private def fromMatchmaker(request: EngineRequest): Boolean =
        matchmakerKey match {
            case None => true
            case Some(expected) =>
                request.headers.get("x-api-key").map(_.trim).exists { presented =>
                    MessageDigest
                        .isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
                }
        }

    def apply(request: EngineRequest): EngineResponse = {
        val response =
            try route(request)
            catch {
                case e: ConcurrentModification => error(409, e.getMessage)
                case NonFatal(e)               =>
                    // A bug or a failure of something behind the engine. The caller gets the shape of it;
                    // the trace has to be on stderr or the 500 is not diagnosable.
                    Log.failure(e, s"${request.method} ${request.path}")
                    error(500, s"${e.getClass.getSimpleName}: ${e.getMessage}")
            }
        pushIfChanged(request, response)
        response
    }

    /* Every player route that changes a match is a POST on it, and only a success changed anything.
     * Told after the change is committed, as matchmaker's callbacks are; `Live.changed` swallows its
     * own failures, since the move it reports already stands. */
    private def pushIfChanged(request: EngineRequest, response: EngineResponse): Unit =
        (request.method.toUpperCase, request.segments) match {
            case ("POST", "matches" :: matchId :: _ :: _) if response.status / 100 == 2 =>
                live.foreach(_.changed(matchId))
            case _ => ()
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
                            case Left(refusal)  => error(refusal)
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

            /* The play page itself, which is served to anyone who asks — signed in or not.
             *
             * It carries no game state when the caller has no seat: the page is a shell that signs the
             * player in and then fetches `state` with their token, so an unauthenticated GET here
             * discloses nothing but the fact that a match id exists — and nothing a seat would hide
             * least of all. The alternative, refusing it, would mean a player following the url from
             * matchmaker gets a bare 401 with nowhere to sign in.
             */
            case ("GET", "matches" :: matchId :: "play" :: Nil) =>
                engine.read(matchId) match {
                    case Left(refusal) => error(refusal)
                    case Right(m) =>
                        val seat = playAuth.callerOf(request).toOption.flatMap(engine.seatOf(m, _).toOption)
                        val state = Option.when(seat.isDefined)(stateOf(m, seat))
                        html(page(matchId, state, playAuth.login, live.map(_.url), publicView = false))
                }

            case ("GET", "matches" :: matchId :: "state" :: Nil) =>
                withSeat(request, matchId)((m, seat) => EngineResponse(200, write(stateOf(m, Some(seat)))))

            case ("POST", "matches" :: matchId :: "moves" :: Nil) => move(request, matchId)

            /* Where the hosted login sends the player back to.
             *
             * One fixed path rather than the match's own url, because Cognito matches callback urls
             * exactly and cannot be given a pattern — a per-match redirect would mean registering one
             * per match. The page redeems the code and then returns to wherever the flow started, which
             * it carried through the `state` parameter.
             */
            case ("GET", "auth" :: "callback" :: Nil) =>
                playAuth.login match {
                    case Some(login) => html(signIn.authCallback(login))
                    case None        => error(404, "this engine has no sign-in configured")
                }

            // The public board, for a match created public. Nobody's seat, so no token, no moves, and
            // no sight of anything a seat would hide — see `stateOf`.
            case ("GET", "matches" :: matchId :: "board" :: Nil) =>
                withPublic(matchId)(m =>
                    html(page(matchId, Some(stateOf(m, None)), None, live.map(_.url), publicView = true))
                )

            case ("GET", "matches" :: matchId :: "board" :: "state" :: Nil) =>
                withPublic(matchId)(m => EngineResponse(200, write(stateOf(m, None))))

            case ("GET", "health" :: Nil) => EngineResponse(200, """{"status":"ok"}""")

            // Play Live. Only a connection's own events carry its id; see `EngineRequest`.
            case ("CONNECT", "live" :: Nil) if request.connectionId.isDefined =>
                connect(request, request.connectionId.get)

            case ("DISCONNECT", "live" :: Nil) if request.connectionId.isDefined =>
                live.foreach(_.unsubscribe(request.connectionId.get))
                EngineResponse(200, "{}")

            // The page's keep-alive, should one reach the function: deployed, the gateway answers it
            // without invoking anything.
            case ("MESSAGE", "live" :: Nil) if request.connectionId.isDefined => EngineResponse(200, "{}")

            case key if extra.isDefinedAt(key) => extra(key)(request)

            case _ => error(404, s"no route for ${request.method} ${request.path}")
        }

    /** A move that was made, answered with the state it left the mover looking at. */
    protected def moved(applied: MoveApplied[M, S, ?]): EngineResponse =
        EngineResponse(200, write(stateOf(applied.state, Some(applied.moved))))

    /** `f` with the caller's subject, or the refusal that says who they are not. */
    protected def asPlayer(request: EngineRequest)(f: String => Either[Refusal, EngineResponse]): EngineResponse =
        playAuth.callerOf(request).flatMap(f) match {
            case Left(refusal)   => error(refusal)
            case Right(response) => response
        }

    /** Admits a Play Live connection to `?match=`, as a player — whose token comes as `?token=`, since a browser cannot
      * put a header on a WebSocket — or, with `?board=1`, as a watcher of a public match. Refused on exactly the terms
      * the state routes refuse, and a refusal here refuses the connection.
      */
    private def connect(request: EngineRequest, connectionId: String): EngineResponse =
        live match {
            case None => error(404, "this engine does not offer Play Live")
            case Some(l) =>
                val withToken = request.query.get("token").filter(_.nonEmpty) match {
                    case Some(token) => request.copy(headers = request.headers + ("authorization" -> s"Bearer $token"))
                    case None        => request
                }
                val admitted =
                    for {
                        matchId <- request.query
                            .get("match")
                            .filter(_.nonEmpty)
                            .toRight(Refusal.Invalid("say which match to watch with ?match="))
                        m <- engine.read(matchId)
                        _ <-
                            if (request.query.get("board").contains("1"))
                                Either.cond(m.isPublic, (), Refusal.NotYours(s"match '$matchId' is not public"))
                            else l.auth.callerOf(withToken).flatMap(engine.seatOf(m, _))
                    } yield l.subscribe(Subscription(connectionId, matchId))

                admitted match {
                    case Left(refusal) => error(refusal)
                    case Right(_)      => EngineResponse(200, "{}")
                }
        }

    private def withSeat(request: EngineRequest, matchId: String)(f: (M, S) => EngineResponse): EngineResponse = {
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

    private def withPublic(matchId: String)(f: M => EngineResponse): EngineResponse =
        engine.read(matchId) match {
            case Left(refusal) => error(refusal)
            // Not 404: the match exists, and saying so tells a would-be watcher nothing they could not
            // learn by being in it. What they may not do is watch.
            case Right(m) if !m.isPublic => error(403, s"match '$matchId' is not public")
            case Right(m)                => f(m)
        }

    protected def parse[A: Reader](body: String): Either[String, A] =
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

    private def html(body: String): EngineResponse = EngineResponse(200, body, "text/html; charset=utf-8")

    protected def error(refusal: Refusal): EngineResponse = error(refusal.status, refusal.message)

    protected def error(status: Int, message: String): EngineResponse =
        EngineResponse(status, ujson.write(ujson.Obj("error" -> message)))
}
