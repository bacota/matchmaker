package com.vivi.matchmaker.api

import com.vivi.matchmaker.service._
import ApiGateway.Response

/** Maps failures to HTTP responses. */
object Errors {

  /** A failure the caller caused and can act on, distinguished from one they cannot. */
  def statusFor(error: Throwable): Int = error match {
    case _: ValidationError   => 400
    case _: UnauthorizedError => 403
    case _: NotFoundError     => 404
    case _: ConflictError     => 409
    case _                    => 500
  }

  def response(status: Int, message: String): Response =
    Response(status, ujson.write(ujson.Obj("error" -> ujson.Str(message))))

  /** Turns a failure into a response.
    *
    * Anything that is not a `ServiceError` is an infrastructure failure — a dropped connection,
    * a bug — and its message could disclose internals, so only a generic message is returned to
    * the caller. The real exception is printed to stderr here (rather than left to the caller to
    * log, which nothing was actually doing) so it lands in CloudWatch either way.
    */
  def toResponse(error: Throwable): Response = error match {
    case e: ServiceError => response(statusFor(e), e.getMessage)
    case e =>
      e.printStackTrace()
      response(500, "internal error")
  }

  val unauthenticated: Response =
    response(401, s"missing ${ApiGateway.ExternalIdHeader} header")

  /** No verified `sub` reached the function. Deployed, the gateway's authorizer answers 401 long
    * before this, so seeing it means a request arrived without passing one.
    */
  val unauthenticatedToken: Response =
    response(401, "no verified identity in request")

  val notFound: Response = response(404, "no such endpoint")

  def badRequest(message: String): Response = response(400, message)
}
