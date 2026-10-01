package com.vivi.engine

/** Why a request was refused. Transport-independent so that the local server and the Lambda handler map it to a status
  * code the same way.
  */
enum Refusal(val status: Int, val message: String) {
    case NotFound(what: String) extends Refusal(404, what)

    /** The caller could not be identified: no token, or one that does not verify. Signing in is the remedy. */
    case Unauthenticated(what: String) extends Refusal(401, what)

    /** The caller is known, and this is not theirs — no seat in this match. Signing in again changes nothing. */
    case NotYours(what: String) extends Refusal(403, what)
    case Invalid(what: String) extends Refusal(400, what)

    /** Something behind the engine — matchmaker — did not answer. The request was fine, and may be repeated. */
    case Unavailable(what: String) extends Refusal(502, what)
}
