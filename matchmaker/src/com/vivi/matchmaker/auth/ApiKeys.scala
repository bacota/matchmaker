package com.vivi.matchmaker.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The shared secrets matchmaker and a game engine authenticate each other with.
  *
  * One key per engine, used in both directions: matchmaker presents it when it creates a game or
  * asks for a match's status, and the engine presents the same key when it posts a move or a
  * result back. A key is therefore a fact about a *pair* of systems, and knowing it is the whole
  * of the claim "I am the other half of this pair".
  *
  * This replaces SigV4 and `AWS_IAM` on both sets of routes. What it gives up is that AWS was
  * verifying the signature before either function was invoked; what it buys is an engine that
  * needs no AWS identity at all, which is the point — an engine is a separate system and may not
  * be running in this account, or on AWS.
  *
  * The name a key is filed under differs by direction, because the two directions know different
  * things about each other:
  *
  *   - Inbound, matchmaker has only the key the caller presented, and needs to turn it into an
  *     identity: the entries are keyed by the engine's `external_id`, which is what the services
  *     compare a game-authorized caller against.
  *   - Outbound, matchmaker has the url it is about to call and no engine identity in hand: the
  *     entries are keyed by host.
  *
  * Both are parsed by this class; the two settings are `ENGINE_API_KEYS` and
  * `GAME_ENGINE_API_KEYS` respectively (see `Handler`).
  */
case class ApiKeys(entries: Map[String, String]) {

  def isEmpty: Boolean = entries.isEmpty

  /** The key to present when calling `name`. */
  def keyFor(name: String): Option[String] = entries.get(name)

  /** The name the presented key belongs to, or `None` if it belongs to no one.
    *
    * Every entry is compared, and compared with `MessageDigest.isEqual`, so that neither the time
    * this takes nor the point at which it stops is a function of how much of a guessed key was
    * right. A map lookup would be the obvious implementation and would leak both.
    */
  def nameOf(presented: String): Option[String] = {
    val offered = presented.getBytes(StandardCharsets.UTF_8)
    entries.foldLeft(Option.empty[String]) { case (found, (name, key)) =>
      if (MessageDigest.isEqual(offered, key.getBytes(StandardCharsets.UTF_8))) Some(name) else found
    }
  }
}

object ApiKeys {

  /** The header a key travels in, in both directions. Lowercase because API Gateway's payload
    * v2 lowercases header names and both sides look them up that way.
    */
  val Header = "x-api-key"

  val empty: ApiKeys = ApiKeys(Map.empty)

  /** Parses `name=key,name=key`.
    *
    * Split on the *first* `=` only: a key is opaque and may well contain one (base64 padding),
    * where a name is chosen by whoever writes the setting. Blank entries are skipped, so a
    * trailing comma is not an error, and an entry with no `=` is — it is far more likely to be a
    * key someone pasted without its name than a name they meant to leave keyless.
    */
  def parse(setting: Option[String]): ApiKeys =
    ApiKeys(
      setting.toList
        .flatMap(_.split(',').toList)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { entry =>
          entry.indexOf('=') match {
            case -1 => throw IllegalStateException(s"api key entry '${entry.take(8)}...' has no name; expected name=key")
            case at => entry.take(at).trim -> entry.drop(at + 1).trim
          }
        }
        .toMap
    )
}
