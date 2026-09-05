package com.vivi.rps

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import upickle.default.{read, write}
import RpsMatch.given

/** Raised when a match cannot be saved because someone else saved it first. The move that lost
  * the race is retried against the state that won it — see [[MatchStore.modify]].
  */
class ConcurrentModification(matchId: String) extends RuntimeException(s"match $matchId changed underneath this update")

/** Where matches live between requests.
  *
  * Two implementations for the two ways this engine runs: a map for the local server, whose
  * process outlives every match it serves, and DynamoDB for Lambda, where nothing survives an
  * invocation. The interface is the smaller of what those two can both do — get one match by id,
  * and save it only if nobody else has changed it.
  */
trait MatchStore {

  def get(matchId: String): Option[RpsMatch]

  /** Saves a new match. Fails if one with this id is already stored, since matchmaker generates
    * a fresh match id per game and a collision would mean two matches sharing a table.
    */
  def create(m: RpsMatch): Unit

  /** Applies `f` to the stored match, saving whatever match it returns and answering with
    * whatever else it returns. `None` for the match means don't save — a rejected move changes
    * nothing — and the answer is produced either way.
    *
    * `None` overall means no such match. `f` may run more than once: a move is read-decide-write,
    * and two players of the same match can be mid-move at once — in Lambda even in two different
    * containers. Retrying against the winner's state is what makes the loser's move land on the
    * state it will actually be played on rather than on a stale one. So `f` must decide and
    * nothing else; anything with an effect — a callback in particular — belongs after this
    * returns.
    */
  def modify[A](matchId: String)(f: RpsMatch => (Option[RpsMatch], A)): Option[A]
}

object MatchStore {
  /** How many times a losing writer re-reads and re-applies before giving up. A match here is two
    * players throwing once each, and they may well throw at the same instant — which is the one
    * contention this engine really expects. Needing more than this means something else.
    */
  val maxAttempts = 5
}

class InMemoryMatchStore extends MatchStore {

  private val matches = ConcurrentHashMap[String, RpsMatch]()

  def get(matchId: String): Option[RpsMatch] = Option(matches.get(matchId))

  def create(m: RpsMatch): Unit =
    if (matches.putIfAbsent(m.matchId, m) != null) throw ConcurrentModification(m.matchId)

  def modify[A](matchId: String)(f: RpsMatch => (Option[RpsMatch], A)): Option[A] = {
    // compute() holds the map's lock for the key, so read-decide-write is atomic here and the
    // retry the DynamoDB store needs has nothing to do.
    var answer: Option[A] = None
    matches.compute(
      matchId,
      (_, stored) =>
        if (stored == null) null
        else {
          val (updated, a) = f(stored)
          answer = Some(a)
          updated.getOrElse(stored)
        }
    )
    answer
  }

  def all: List[RpsMatch] = matches.values.asScala.toList
}

/** Matches in a DynamoDB table keyed by `matchId`, with the whole match as one JSON attribute.
  *
  * Nothing here queries by anything but the match id, so modelling the throws as attributes would
  * buy nothing and would tie the table's shape to the game's. The `version` attribute is what
  * makes [[modify]] safe: the conditional write fails rather than overwriting a move made
  * between this container's read and its write.
  */
class DynamoDbMatchStore(http: SignedHttp, table: String, region: String) extends MatchStore {

  private val endpoint = s"https://dynamodb.$region.amazonaws.com"

  private def call(target: String, payload: ujson.Obj): ujson.Value =
    ujson.read(
      http.post(
        endpoint,
        ujson.write(payload),
        "dynamodb",
        Map("content-type" -> "application/x-amz-json-1.0", "x-amz-target" -> s"DynamoDB_20120810.$target")
      )
    )

  private def load(matchId: String): Option[(RpsMatch, Long)] = {
    val response = call(
      "GetItem",
      ujson.Obj(
        "TableName" -> table,
        "Key" -> ujson.Obj("matchId" -> ujson.Obj("S" -> matchId)),
        // A move must not be decided against a stale replica, and a strongly consistent read of
        // one small item is what this costs.
        "ConsistentRead" -> true
      )
    )
    response.obj.get("Item").map { item =>
      (read[RpsMatch](item("state")("S").str), item("version")("N").str.toLong)
    }
  }

  private def save(m: RpsMatch, expected: Option[Long]): Unit = {
    val next = expected.getOrElse(0L) + 1
    val condition = expected match {
      case Some(v) => ujson.Obj("ConditionExpression" -> "version = :v", "ExpressionAttributeValues" -> ujson.Obj(":v" -> ujson.Obj("N" -> v.toString)))
      case None    => ujson.Obj("ConditionExpression" -> "attribute_not_exists(matchId)")
    }
    val payload = ujson.Obj(
      "TableName" -> table,
      "Item" -> ujson.Obj(
        "matchId" -> ujson.Obj("S" -> m.matchId),
        "version" -> ujson.Obj("N" -> next.toString),
        "state" -> ujson.Obj("S" -> write(m))
      )
    )
    condition.value.foreach((k, v) => payload(k) = v)

    try call("PutItem", payload)
    catch {
      case e: AwsError if e.getMessage.contains("ConditionalCheckFailedException") => throw ConcurrentModification(m.matchId)
    }
  }

  def get(matchId: String): Option[RpsMatch] = load(matchId).map(_._1)

  def create(m: RpsMatch): Unit = save(m, None)

  def modify[A](matchId: String)(f: RpsMatch => (Option[RpsMatch], A)): Option[A] = {
    def attempt(remaining: Int): Option[A] =
      load(matchId).map { (current, version) =>
        f(current) match {
          case (Some(updated), a) =>
            try {
              save(updated, Some(version))
              a
            } catch {
              case _: ConcurrentModification if remaining > 1 =>
                attempt(remaining - 1).getOrElse(throw ConcurrentModification(matchId))
            }
          case (None, a) => a
        }
      }

    attempt(MatchStore.maxAttempts)
  }
}
