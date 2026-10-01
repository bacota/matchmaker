package com.vivi.engine

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.concurrent.{
    Callable,
    ConcurrentHashMap,
    ExecutionException,
    Executors,
    Future,
    Semaphore,
    TimeUnit,
    TimeoutException
}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Play Live: a page that is told when its match changes, instead of asking every two seconds.
  *
  * What is pushed is the fact of a change and nothing else — `{"changed":"<matchId>"}` — and the page answers it by
  * fetching its state through the same authorized route it polls. So nothing a seat would hide can leak down a
  * connection, the state a viewer is shown is decided in one place as it always was, and pushes arriving out of order
  * cannot leave a page showing an older state than the one it last fetched.
  *
  * A push is best-effort, like the callbacks to matchmaker: it is sent after the change is committed and a lost one is
  * not retried. The page still checks once a minute while it is live, which is what bounds the cost of one that was
  * lost.
  *
  * A push is made inside the request that made the change, so it is held to `deadline` as a whole — finding the
  * watchers and every send to them — and the sends are made side by side rather than one after another, up to `fanOut`
  * at a time. Without that, a public board's crowd or two stalled connections could spend the function's whole timeout
  * on a move that was already made, and the player would be answered with a failure for it; without the limit, a crowd
  * would open a connection to the management API per watcher at once. Whatever has not finished by the deadline —
  * including a send still waiting for its turn — is abandoned and logged; the page's minute check covers it.
  *
  * @param url
  *   where the page opens its connection: the deployed WebSocket API's `wss://` url, or the local server's `ws://`
  * @param auth
  *   who is opening a connection. Not the play routes' own [[PlayAuth]] when deployed: the WebSocket API has no JWT
  *   authorizer, so the token is verified here — see [[EngineConfig.liveAuth]]
  * @param deadline
  *   how long a change may spend being pushed, all told
  * @param fanOut
  *   how many sends may be out at once, across every change this instance is pushing
  */
class Live(
    val url: String,
    val auth: PlayAuth,
    subscriptions: Subscriptions,
    channel: LiveChannel,
    deadline: Duration = Duration.ofSeconds(3),
    fanOut: Int = 64
) {

    // A thread per send: they spend their time waiting on the network, which is what virtual threads are for.
    private val threads = Executors.newVirtualThreadPerTaskExecutor()
    private val sending = Semaphore(fanOut)

    def subscribe(subscription: Subscription): Unit = subscriptions.add(subscription)

    def unsubscribe(connectionId: String): Unit = subscriptions.remove(connectionId)

    /** Tells every connection watching `matchId` that it has changed. A connection the channel reports gone is
      * forgotten, which is how one whose disconnect was never delivered stops being written to.
      *
      * Never fails the caller: by the time this runs the change it reports is committed, and a player must not be
      * answered with a 500 for a move that stands.
      */
    def changed(matchId: String): Unit = {
        val until = System.nanoTime() + deadline.toNanos
        val message = ujson.write(ujson.Obj("changed" -> matchId))

        val watchers = within(until, s"finding who watches match '$matchId'")(start(subscriptions.watching(matchId)))
        val sends = watchers.getOrElse(Nil).map(s => start(inTurn(until)(push(s, message))))
        settle(until, sends, s"pushing a change in match '$matchId'")
    }

    private def push(watcher: Subscription, message: String): Unit =
        if (!channel.send(watcher.connectionId, message)) subscriptions.remove(watcher.connectionId)

    /** `work` once fewer than `fanOut` others are out, provided that is before `until`; otherwise [[Live.NoTurn]].
      *
      * Checked again once the turn is had, and not left to the deadline's cancel: `settle` cancels one send after
      * another, and cancelling one that holds a turn hands the turn on, perhaps to a waiter it has not reached yet.
      */
    private def inTurn(until: Long)(work: => Unit): Unit = {
        if (!sending.tryAcquire(math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS)) throw Live.NoTurn()
        try
            if (System.nanoTime() < until) work
            else throw Live.NoTurn()
        finally sending.release()
    }

    /** Waits for `sends` until `until`, then cancels whatever is left in one pass — no send is waited on past the
      * deadline — and reports the failures and the late together, as one record carrying the first failure's trace.
      * Once per change rather than once per watcher: a crowded board during an outage would otherwise spend longer
      * writing stack traces than the deadline allows for sending.
      */
    private def settle(until: Long, sends: List[Future[?]], what: String): Unit = {
        sends.foreach { send =>
            val left = until - System.nanoTime()
            // Its outcome, whatever it is, is read from the future below.
            if (left > 0)
                try send.get(left, TimeUnit.NANOSECONDS)
                catch { case NonFatal(_) => () }
        }
        // `cancel` succeeds only on a send that is not yet done; with those that never had a turn, these are the late.
        val cancelled = sends.count(_.cancel(true))
        val (missed, failures) =
            sends.filter(_.state == Future.State.FAILED).map(_.exceptionNow).partition(_.isInstanceOf[Live.NoTurn])
        val late = cancelled + missed.size

        if (failures.nonEmpty || late > 0)
            Log.failure(
              failures.headOption.getOrElse(TimeoutException(s"not done within $deadline")),
              s"$what: ${failures.size} of ${sends.size} failed and $late were not done within $deadline"
            )
    }

    private def start[A](work: => A): Future[A] = threads.submit((() => work): Callable[A])

    /** `work`'s answer if it arrives by `until`. Otherwise — late or failed — logged, and a late one interrupted, so
      * that nothing outlives the request it was started in.
      */
    private def within[A](until: Long, what: String)(work: Future[A]): Option[A] =
        try Some(work.get(math.max(0L, until - System.nanoTime()), TimeUnit.NANOSECONDS))
        catch {
            case _: TimeoutException =>
                work.cancel(true)
                Log.failure(TimeoutException(s"not done within $deadline"), what)
                None
            case e: ExecutionException => Log.failure(e.getCause, what); None
            case NonFatal(e)           => Log.failure(e, what); None
        }
}

object Live {

    /** A send that did not get its turn before the deadline, and so was never made: late, not failed. */
    private class NoTurn extends Exception(null, null, false, false)
}

/** One open Play Live connection, and the match it watches. */
case class Subscription(connectionId: String, matchId: String)

/** Who is watching which match, between requests. The [[MatchStore]] of Play Live, in the same two forms. */
trait Subscriptions {
    def add(subscription: Subscription): Unit
    def remove(connectionId: String): Unit
    def watching(matchId: String): List[Subscription]
}

class InMemorySubscriptions extends Subscriptions {

    private val byConnection = ConcurrentHashMap[String, Subscription]()

    def add(subscription: Subscription): Unit = byConnection.put(subscription.connectionId, subscription)

    def remove(connectionId: String): Unit = byConnection.remove(connectionId)

    def watching(matchId: String): List[Subscription] =
        byConnection.values.asScala.filter(_.matchId == matchId).toList
}

/** Connections in a DynamoDB table keyed by connection id, with a `byMatch` index to find a match's watchers by.
  *
  * The index is eventually consistent, so a connection opened in the instant before a move may not be told of it. The
  * page fetches its state as soon as its connection opens, which covers all but that instant, and checks once a minute
  * while live, which covers the rest.
  *
  * `expiresAt` is the table's TTL. API Gateway closes a connection after two hours whatever happens, and its disconnect
  * is what removes the row — this is for the disconnect that never arrives.
  */
class DynamoDbSubscriptions(
    http: SignedHttp,
    table: String,
    region: String,
    now: () => Instant = () => Instant.now(),
    lifetime: Duration = Duration.ofHours(3)
) extends Subscriptions {

    private val dynamoDb = DynamoDb(http, region)

    def add(subscription: Subscription): Unit =
        dynamoDb.call(
          "PutItem",
          ujson.Obj(
            "TableName" -> table,
            "Item" -> ujson.Obj(
              "connectionId" -> ujson.Obj("S" -> subscription.connectionId),
              "matchId" -> ujson.Obj("S" -> subscription.matchId),
              "expiresAt" -> ujson.Obj("N" -> now().plus(lifetime).getEpochSecond.toString)
            )
          )
        )

    def remove(connectionId: String): Unit =
        dynamoDb.call(
          "DeleteItem",
          ujson.Obj("TableName" -> table, "Key" -> ujson.Obj("connectionId" -> ujson.Obj("S" -> connectionId)))
        )

    def watching(matchId: String): List[Subscription] = {
        def page(from: Option[ujson.Value]): List[Subscription] = {
            val request = ujson.Obj(
              "TableName" -> table,
              "IndexName" -> "byMatch",
              "KeyConditionExpression" -> "matchId = :m",
              "ExpressionAttributeValues" -> ujson.Obj(":m" -> ujson.Obj("S" -> matchId))
            )
            from.foreach(key => request("ExclusiveStartKey") = key)
            val response = dynamoDb.call("Query", request)
            val found = response("Items").arr.toList.map(item => Subscription(item("connectionId")("S").str, matchId))
            found ++ response.obj.get("LastEvaluatedKey").map(key => page(Some(key))).getOrElse(Nil)
        }
        page(None)
    }
}

/** Where a push is sent. */
trait LiveChannel {

    /** Sends `message` down connection `connectionId`, answering false when the connection is gone. */
    def send(connectionId: String, message: String): Boolean
}

/** The deployed channel: API Gateway's management API for the WebSocket API's stage.
  *
  * Signed as `execute-api`, which the execution role is allowed for exactly this stage's `@connections`. A 410 is the
  * connection having closed, which is an answer rather than a failure.
  *
  * @param endpoint
  *   the stage's `https://` url — the same host and stage as the `wss://` url the page connects to
  */
class ApiGatewayChannel(http: SignedHttp, endpoint: String) extends LiveChannel {

    private val base = endpoint.stripSuffix("/")

    def send(connectionId: String, message: String): Boolean = {
        val url = s"$base/@connections/${URLEncoder.encode(connectionId, UTF_8)}"
        http.exchange("POST", url, Some(message), "execute-api", Map("content-type" -> "application/json")) match {
            case (status, _) if status >= 200 && status < 300 => true
            case (410, _)                                     => false
            case (status, body)                               => throw AwsError(s"POST $url returned $status: $body")
        }
    }
}
