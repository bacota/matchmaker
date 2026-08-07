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
      body: String
  ) {
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    /** Path split into non-empty segments: `/games/7/characters` becomes `List("games","7","characters")`. */
    def segments: List[String] = path.split('/').iterator.filter(_.nonEmpty).toList
  }

  case class Response(statusCode: Int, body: String)

  /** Header carrying the caller's identity. A stand-in for a verified Cognito `sub` until
    * hosted-login token validation replaces it — which is a change to `Router.callerOf` alone.
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

    Request(method, path, headers, query, body)
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
