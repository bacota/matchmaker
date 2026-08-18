package com.vivi.tictactoe

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}

/** Lambda entry point, behind an API Gateway HTTP API using payload format 2.0.
  *
  * Handler string: `com.vivi.tictactoe.Handler::handleRequest`.
  *
  * The engine is built once per container, as matchmaker's handler builds its services: nothing
  * in it is per-request, and the DynamoDB store's signer and HTTP client are worth keeping warm.
  */
class Handler extends RequestStreamHandler {

  private lazy val routes = Handler.routes

  override def handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = {
    val event = String(input.readAllBytes(), UTF_8)
    val log = (msg: String) => Option(context).foreach(_.getLogger.log(msg))

    val response =
      try {
        val request = Handler.decode(event)
        val answer = routes(request)
        log(s"${request.method} ${request.path} -> ${answer.status}")
        answer
      } catch {
        case e: Throwable =>
          log(s"could not handle event: $e")
          EngineResponse(500, """{"error":"internal error"}""")
      }

    output.write(Handler.encode(response).getBytes(UTF_8))
    output.flush()
  }
}

object Handler {

  lazy val routes: Routes = Config.routes(k => Option(System.getenv(k)))

  /** Payload format 2.0: the method, the path, the query, the body, the headers, and the claims
    * the JWT authorizer wrote into the event.
    *
    * The `iam` block is not read. Which routes are `AWS_IAM` is the gateway's business, and by
    * the time the function runs an unsigned call to `/games` has already been rejected. The
    * claims are read because the play routes need to know *which* player signed in, not merely
    * that one did.
    */
  def decode(json: String): EngineRequest = {
    val event = ujson.read(json)

    def str(value: ujson.Value): Option[String] = value match {
      case ujson.Str(s) => Some(s)
      case _            => None
    }

    val method = event.obj
      .get("requestContext")
      .flatMap(_.obj.get("http"))
      .flatMap(_.obj.get("method"))
      .flatMap(str)
      .getOrElse("")

    // rawPath carries the stage prefix when the api has one; the routes match on the tail, so a
    // stage of "$default" (what the terraform uses) and an explicit stage both work.
    val path = event.obj.get("rawPath").flatMap(str).getOrElse("")

    val query = event.obj
      .get("queryStringParameters")
      .flatMap {
        case o: ujson.Obj => Some(o.value.flatMap((k, v) => str(v).map(k -> _)).toMap)
        case _            => None
      }
      .getOrElse(Map.empty[String, String])

    val headers = event.obj
      .get("headers")
      .flatMap {
        case o: ujson.Obj => Some(o.value.flatMap((k, v) => str(v).map(k.toLowerCase -> _)).toMap)
        case _            => None
      }
      .getOrElse(Map.empty[String, String])

    // requestContext.authorizer.jwt.claims, present only where a JWT authorizer ran — the play
    // routes. Array-valued claims (cognito:groups) are dropped; nothing here needs them.
    val claims = event.obj
      .get("requestContext")
      .flatMap(_.obj.get("authorizer"))
      .flatMap(_.obj.get("jwt"))
      .flatMap(_.obj.get("claims"))
      .flatMap {
        case o: ujson.Obj => Some(o.value.flatMap((k, v) => str(v).map(k -> _)).toMap)
        case _            => None
      }
      .getOrElse(Map.empty[String, String])

    val rawBody = event.obj.get("body").flatMap(str).getOrElse("")
    val isBase64 = event.obj.get("isBase64Encoded").exists {
      case ujson.Bool(b) => b
      case _             => false
    }

    EngineRequest(
      method,
      path,
      query,
      if (isBase64 && rawBody.nonEmpty) String(Base64.getDecoder.decode(rawBody), UTF_8) else rawBody,
      headers,
      claims
    )
  }

  def encode(response: EngineResponse): String =
    ujson.write(
      ujson.Obj(
        "statusCode" -> ujson.Num(response.status.toDouble),
        "headers" -> ujson.Obj("content-type" -> ujson.Str(response.contentType)),
        "isBase64Encoded" -> ujson.Bool(false),
        "body" -> ujson.Str(response.body)
      )
    )
}
