package com.vivi.matchmaker.service

/** Errors raised by services for conditions that are the caller's fault (as opposed to infrastructure failures, which
  * just propagate as whatever the persistence layer throws).
  */
sealed abstract class ServiceError(message: String) extends RuntimeException(message)

/** A precondition on the request itself failed (e.g. a blank required field). */
case class ValidationError(message: String) extends ServiceError(message)

/** The request conflicts with existing state (e.g. a nickname that's already taken). */
case class ConflictError(message: String) extends ServiceError(message)

/** The caller is not allowed to perform this action. */
case class UnauthorizedError(message: String) extends ServiceError(message)

/** A referenced entity does not exist. */
case class NotFoundError(message: String) extends ServiceError(message)

/** The thing asked for existed and is gone for good — a friendly match's archive, which its bucket expires. */
case class GoneError(message: String) extends ServiceError(message)

/** The request is fine, but this deployment cannot serve it — archiving with no buckets configured. Not the caller's
  * fault, and nothing they can fix by asking differently, which is what a 503 says.
  */
case class UnavailableError(message: String) extends ServiceError(message)
