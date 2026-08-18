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
    * the caller. The real exception is written to stderr here (rather than left to the caller to
    * log, which nothing was actually doing) so it lands in CloudWatch either way.
    *
    * `where` names the request that failed. Without it a stack trace in CloudWatch cannot be
    * matched to the call that produced it, which is most of what makes a 500 diagnosable.
    */
  def toResponse(error: Throwable, where: String = ""): Response = error match {
    case e: ServiceError => response(statusFor(e), e.getMessage)
    case e =>
      log(e, where)
      response(500, "internal error")
  }

  /** Writes a failure to stderr, with its cause chain, as one record.
    *
    * Every 5xx this service produces goes through here. `printStackTrace` alone was not enough:
    * it drops the request that failed, and the runtime interleaves its lines with other output,
    * so a trace could not reliably be read back as a unit.
    */
  def log(error: Throwable, where: String): Unit = {
    val subject = if (where.isEmpty) "request" else where
    val trace = java.io.StringWriter()
    error.printStackTrace(java.io.PrintWriter(trace))
    System.err.println(s"ERROR handling $subject: ${error.getClass.getName}: ${error.getMessage}\n$trace")
    System.err.flush()
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
