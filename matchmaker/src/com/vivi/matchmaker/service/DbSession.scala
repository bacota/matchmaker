package com.vivi.matchmaker.service

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import skunk.{Query, SSL, Session, Void}
import skunk.codec.all.bool
import skunk.implicits._
import org.typelevel.otel4s.trace.Tracer.Implicits.noop as noopTracer
import org.typelevel.otel4s.metrics.Meter.Implicits.noop as noopMeter

/** A borrowable session: acquiring it takes a connection from a pool built once at startup, releasing it returns the
  * connection to that pool rather than closing it.
  */
type SessionPool = Resource[IO, Session[IO]]

/** Builds the skunk sessions services use to talk to Postgres, from a `DbConfig`. */
private[service] object DbSession {

    /** Opens a connection pool that keeps `size` sessions and lends up to `max` at once. The outer `Resource` owns the
      * pool and should be acquired once for the lifetime of the process; the inner one is the `SessionPool` handed to
      * services, and is acquired and released per operation.
      *
      * Pooling matters beyond saving handshakes: skunk's default recycler validates a session as it is returned and
      * evicts it if it is no longer usable. That says nothing of a session closed while it sat in the pool -- as RDS or
      * the network closes one in a Lambda container frozen between requests -- so each is also checked as it is
      * borrowed ([[checked]]).
      *
      * A borrow made while all `size` are lent opens a session of its own, closed again as it is released, so that work
      * holding one session while it borrows another cannot wait forever on itself. Those are the overflow, up to `max -
      * size` at once; past that, a borrow waits for a pooled session. A `max` no larger than `size` is a plain pool
      * that only ever waits. The overflow pays a handshake per borrow, so it is a safety net, not capacity.
      */
    def pooled(config: DbConfig, size: Int, max: Int): Resource[IO, SessionPool] =
        for {
            pool <- builder(config).pooled(size)
            lent <- Resource.eval(Ref[IO].of(0))
            overflow <- Resource.eval(Semaphore[IO]((max - size).max(0).toLong))
        } yield {
            val core = checked(pool = pool, attempts = size + 1)
            val single = builder(config).single
            /* A pooled session is counted as lent from before it is waited for until it is back. */
            val claimed = Resource.make(lent.modify(n => if (n < size) (n + 1, true) else (n, false)))(isClaimed =>
                lent.update(_ - 1).whenA(isClaimed)
            )
            val waited = Resource.make(lent.update(_ + 1))(_ => lent.update(_ - 1))
            val permitted = Resource.make(overflow.tryAcquire)(isPermitted => overflow.release.whenA(isPermitted))
            claimed.flatMap {
                case true => core
                case false =>
                    permitted.flatMap {
                        case true  => single
                        case false => waited >> core
                    }
            }
        }

    private val alive: Query[Void, Boolean] = sql"VALUES (true)".query(bool)

    /* A query that failed on a session as it was borrowed: the session is closed, not the caller's work wrong. */
    private final case class Closed(cause: Throwable) extends Exception(cause)

    /* A session of `pool` that has answered a query as it is borrowed. One that cannot is given back, which its
     * recycler fails on and so discards, and another borrowed in its place: up to `attempts` in all, enough for every
     * pooled session to have been closed and a new one opened after them. The failure the last attempt met is the
     * caller's. A session that cannot be opened at all is not retried: the server is down, not the session stale. */
    private def checked(pool: SessionPool, attempts: Int): SessionPool =
        pool.evalTap(_.unique(alive).adaptError(Closed(_))).handleErrorWith {
            case Closed(_) if attempts > 1 => checked(pool = pool, attempts = attempts - 1)
            case Closed(cause)             => Resource.raiseError[IO, Session[IO], Throwable](cause)
            case e                         => Resource.raiseError[IO, Session[IO], Throwable](e)
        }

    /** A single, unpooled session. Retained for callers that want one connection and no pool. */
    def resource(config: DbConfig): SessionPool =
        builder(config).single

    /* The connection `config` describes, over SSL that trusts the server's certificate. */
    private def builder(config: DbConfig): Session.Builder[IO] =
        Session
            .Builder[IO]
            .withHost(config.host)
            .withPort(config.port)
            .withCredentials(Session.Credentials(user = config.user, password = config.password))
            .withDatabase(config.database)
            .withSSL(SSL.Trusted)
}
