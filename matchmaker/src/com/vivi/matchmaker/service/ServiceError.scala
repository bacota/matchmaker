package com.vivi.matchmaker.service

/** Errors raised by services for conditions that are the caller's fault (as opposed to
  * infrastructure failures, which just propagate as whatever the persistence layer throws).
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
