package com.vivi.matchmaker.api

import java.util.Base64

/** Just enough of the API Gateway HTTP API payload format v2 to route a request and answer it.
  *
  * The event is picked apart with ujson rather than decoded into a case class: the real payload
  * carries a large `requestContext` (authorizer, identity, http, ...) that would have to be
  * modelled in full, and only a few fields of it matter here.
  */
object ApiGateway {

  /** A decoded request. Header names are lowercased on the way in, because payload v2 lowercases
    * them and a case-sensitive lookup for "X-External-Id" would silently never match.
    */
  case class Request(
      method: String,
      path: String,
      headers: Map[String, String],
      query: Map[String, String],
      body: String,
      claims: Map[String, String] = Map.empty,
      iam: Option[IamPrincipal] = None
  ) {
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    /** A claim from the token the gateway's JWT authorizer already verified.
      *
      * Empty when there is no authorizer in front of the function — locally, or if the route were
      * ever left unauthenticated — so a caller of this must decide what an absent claim means
      * rather than assuming the request was authenticated.
      */
    def claim(name: String): Option[String] = claims.get(name)

    /** Path split into non-empty segments: `/games/7/characters` becomes `List("games","7","characters")`. */
    def segments: List[String] = path.split('/').iterator.filter(_.nonEmpty).toList
  }

  /** The signing identity behind an `AWS_IAM`-authorized route, as API Gateway reports it once it
    * has verified the request's SigV4 signature.
    *
    * `userArn` for an assumed role names the *session* — `.../assumed-role/engine/i-0abc123` —
    * and the session part changes every time the role is assumed, so it cannot be what an
    * identity is recorded as. [[roleArn]] normalizes it to the role itself, which is stable and
    * is what an administrator would write down.
    */
  case class IamPrincipal(userArn: String, userId: Option[String], accountId: Option[String]) {

    private val assumedRole = """arn:([^:]+):sts::(\d+):assumed-role/([^/]+)/.*""".r

    /** The role this caller assumed, or the ARN as given when it is not an assumed role (an IAM
      * user's own ARN, say).
      */
    def roleArn: String = userArn match {
      case assumedRole(partition, account, role) => s"arn:$partition:iam::$account:role/$role"
      case other                                 => other
    }
  }

  case class Response(statusCode: Int, body: String)

  /** Header carrying the caller's identity in the local development mode, where there is no
    * gateway to verify a token. Deployed, the identity is the `sub` claim instead — see
    * `Authenticator`.
    */
  val ExternalIdHeader = "X-External-Id"

  def decodeRequest(json: String): Request = {
    val event = ujson.read(json)

    def strOpt(value: ujson.Value): Option[String] = value match {
      case ujson.Str(s) => Some(s)
      case _            => None
    }

    def obj(key: String): Option[ujson.Obj] = event.obj.get(key).flatMap {
      case o: ujson.Obj => Some(o)
      case _            => None
    }

    val method = event.obj
      .get("requestContext")
      .flatMap(_.obj.get("http"))
      .flatMap(_.obj.get("method"))
      .flatMap(strOpt)
      .getOrElse("")

    val path = event.obj.get("rawPath").flatMap(strOpt).getOrElse("")

    val headers = obj("headers")
      .map(_.value.flatMap { case (k, v) => strOpt(v).map(k.toLowerCase -> _) }.toMap)
      .getOrElse(Map.empty)

    val query = obj("queryStringParameters")
      .map(_.value.flatMap { case (k, v) => strOpt(v).map(k -> _) }.toMap)
      .getOrElse(Map.empty)

    val rawBody = event.obj.get("body").flatMap(strOpt).getOrElse("")
    val isBase64 = event.obj.get("isBase64Encoded").exists {
      case ujson.Bool(b) => b
      case _             => false
    }
    val body = if (isBase64 && rawBody.nonEmpty) String(Base64.getDecoder.decode(rawBody)) else rawBody

    // requestContext.authorizer.jwt.claims, present only when a JWT authorizer ran. Values are
    // strings except for the array-valued ones (`cognito:groups`, `aud` in some flows), which are
    // dropped rather than guessed at: nothing here needs them.
    val claims = event.obj
      .get("requestContext")
      .flatMap(_.obj.get("authorizer"))
      .flatMap(_.obj.get("jwt"))
      .flatMap(_.obj.get("claims"))
      .flatMap {
        case o: ujson.Obj => Some(o.value.flatMap { case (k, v) => strOpt(v).map(k -> _) }.toMap)
        case _            => None
      }
      .getOrElse(Map.empty[String, String])

    // requestContext.authorizer.iam, present only when a route is AWS_IAM-authorized — which is
    // how the game engine's callbacks are protected. Mutually exclusive with the jwt block above:
    // a route carries one authorizer, and which one it was is what says whether this request is a
    // player acting on their own behalf or another AWS principal acting on a game's.
    val iam = event.obj
      .get("requestContext")
      .flatMap(_.obj.get("authorizer"))
      .flatMap(_.obj.get("iam"))
      .flatMap {
        case o: ujson.Obj =>
          o.value.get("userArn").flatMap(strOpt).map { userArn =>
            IamPrincipal(userArn, o.value.get("userId").flatMap(strOpt), o.value.get("accountId").flatMap(strOpt))
          }
        case _ => None
      }

    Request(method, path, headers, query, body, claims, iam)
  }

  def encodeResponse(response: Response): String =
    ujson.write(
      ujson.Obj(
        "statusCode" -> ujson.Num(response.statusCode.toDouble),
        "headers" -> ujson.Obj("content-type" -> ujson.Str("application/json")),
        "isBase64Encoded" -> ujson.Bool(false),
        "body" -> ujson.Str(response.body)
      )
    )
}
