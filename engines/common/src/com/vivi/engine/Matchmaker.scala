package com.vivi.engine

import upickle.default.{read, write}
import Protocol.given

/** The calls an engine makes *back* to matchmaker: steps 2 and 3 of `interaction-design.txt`, and, for a game whose
  * seats are characters, telling matchmaker about a character a player has made here and keeping what the game knows
  * about one as the character's state.
  *
  * An interface because the tests must be able to see what the engine would have sent without a matchmaker to send it
  * to — [[RecordingMatchmaker]] is what every test drives.
  */
trait Matchmaker {
    def recordMove(url: String, notification: Protocol.MoveNotification): Unit
    def recordResults(url: String, results: Protocol.MatchResults): Unit

    /** `PUT {matchmakerUrl}/characters/{characterId}/state`. Unlike the two above this one is not best-effort, and an
      * engine that calls it should let it throw: state that matchmaker never heard about has to be made again at the
      * character's next match, and the player making it is there to be told.
      */
    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit

    /** `POST {matchmakerUrl}/characters`: a character a player has made in this engine, answered with the id matchmaker
      * gives it. Not best-effort either — a character matchmaker never heard of cannot be challenged with, so the
      * player making it has to be told it did not take.
      */
    def registerCharacter(matchmakerUrl: String, request: Protocol.RegisterCharacterRequest): Long

    /** `GET {matchmakerUrl}/characters?owner=`: the player's characters in this engine's game, which the engine shows
      * them since it keeps none itself.
      */
    def listCharacters(matchmakerUrl: String, ownerExternalId: String): List[Protocol.OwnedCharacter]

    /** `PUT {matchmakerUrl}/characters/{characterId}`: a player changing one of their characters' name and description
      * here. A character that is not theirs is refused by matchmaker, which owns that fact, as a 404
      * [[MatchmakerRefusal]].
      */
    def editCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.EditCharacterRequest): Unit

    /** `PUT {matchmakerUrl}/characters/{characterId}/owner`: a player giving one of their characters to another. A
      * nickname nobody has is a 400 [[MatchmakerRefusal]], whose reason says so.
      */
    def transferCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.TransferCharacterRequest): Unit

    /** `GET {matchmakerUrl}/nicknames?externalId=`: what a signed-in player is called, for showing them by on a message
      * board. `None` for a subject matchmaker has no player for.
      */
    def nicknameOf(matchmakerUrl: String, externalId: String): Option[String]
}

/** Posts the callbacks over HTTP, to the urls matchmaker itself supplied when it created the game.
  *
  * Authentication differs by deployment, and both are supported because both are real:
  *
  *   - Deployed, matchmaker's callback routes take the API key this engine and matchmaker share. Matchmaker holds one
  *     key per engine, so the key is also what says *which* engine is calling: it is filed under the game's
  *     `external_id`, and that is the identity the callback is attributed to.
  *   - Locally, matchmaker runs with `AUTH_MODE=header` and takes the caller from `X-External-Id`, so the engine sends
  *     the game's external id there instead.
  *
  * Both may be set: a keyed request that also carries the header is what a local engine pointed at a deployed
  * matchmaker would send, and matchmaker ignores whichever its mode does not use.
  */
class HttpMatchmaker(http: SignedHttp, apiKey: Option[String], externalId: Option[String]) extends Matchmaker {

    private def headers =
        Map("content-type" -> "application/json") ++
            apiKey.map("x-api-key" -> _) ++
            externalId.map("x-external-id" -> _)

    def recordMove(url: String, notification: Protocol.MoveNotification): Unit =
        http.post(url, write(notification), "execute-api", headers)

    def recordResults(url: String, results: Protocol.MatchResults): Unit =
        http.post(url, write(results), "execute-api", headers)

    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit =
        http.send(
          "PUT",
          s"${matchmakerUrl.stripSuffix("/")}/characters/$characterId/state",
          Some(write(Protocol.UpdateStateRequest(state))),
          "execute-api",
          headers
        )

    def registerCharacter(matchmakerUrl: String, request: Protocol.RegisterCharacterRequest): Long =
        read[Protocol.RegisteredCharacter](
          call("POST", s"${matchmakerUrl.stripSuffix("/")}/characters", Some(write(request)))
        ).characterId

    def listCharacters(matchmakerUrl: String, ownerExternalId: String): List[Protocol.OwnedCharacter] = {
        val owner = java.net.URLEncoder.encode(ownerExternalId, java.nio.charset.StandardCharsets.UTF_8)
        read[List[Protocol.OwnedCharacter]](
          call("GET", s"${matchmakerUrl.stripSuffix("/")}/characters?owner=$owner", None)
        )
    }

    def editCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.EditCharacterRequest): Unit =
        call("PUT", s"${matchmakerUrl.stripSuffix("/")}/characters/$characterId", Some(write(request)))

    def transferCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.TransferCharacterRequest): Unit =
        call("PUT", s"${matchmakerUrl.stripSuffix("/")}/characters/$characterId/owner", Some(write(request)))

    def nicknameOf(matchmakerUrl: String, externalId: String): Option[String] = {
        val who = java.net.URLEncoder.encode(externalId, java.nio.charset.StandardCharsets.UTF_8)
        try
            Some(
              read[Protocol.Nickname](
                call("GET", s"${matchmakerUrl.stripSuffix("/")}/nicknames?externalId=$who", None)
              ).nickname
            )
        catch { case MatchmakerRefusal(404, _) => None }
    }

    /* A call whose answer the engine reads. Matchmaker's own refusal is raised with its reason, so
     * that the engine can tell the player what it was; anything else is matchmaker failing, and the
     * AwsError says so. */
    private def call(method: String, url: String, body: Option[String]): String =
        http.exchange(method, url, body, "execute-api", headers) match {
            case (status, answer) if status >= 200 && status < 300 => answer
            case (status, answer) if status >= 400 && status < 500 =>
                throw MatchmakerRefusal(status, MatchmakerRefusal.reason(answer))
            case (status, answer) => throw AwsError(s"$method $url returned $status: $answer")
        }
}

/** Matchmaker answering a call with a 4xx: it heard the request and turned it down. `reason` is the `error` it gave. */
case class MatchmakerRefusal(status: Int, reason: String)
    extends RuntimeException(s"matchmaker refused the call ($status): $reason")

object MatchmakerRefusal {

    /** The `error` of matchmaker's `{"error": "..."}`, or the body as it came if it is not that. */
    def reason(body: String): String =
        try ujson.read(body).obj.get("error").map(_.str).getOrElse(body)
        catch { case scala.util.control.NonFatal(_) => body }
}

/** Keeps the callbacks instead of sending them.
  *
  * Used by the tests, and by the local server when it is started with no matchmaker to call, so that the engine can be
  * played through on its own — the board still works, and the callbacks it would have made are printed.
  */
class RecordingMatchmaker(log: String => Unit = _ => ()) extends Matchmaker {

    private val movesBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MoveNotification)]()
    private val resultsBuffer = scala.collection.mutable.ListBuffer[(String, Protocol.MatchResults)]()
    private val statesBuffer = scala.collection.mutable.ListBuffer[(Long, String)]()
    private val registrationsBuffer = scala.collection.mutable.ListBuffer[(Long, Protocol.RegisterCharacterRequest)]()

    /** The id the next registered character is given. Counted up, as matchmaker's identity column would. */
    private var nextCharacterId = 1L

    /** While set, [[registerCharacter]] fails as an unreachable matchmaker would, and records nothing. */
    @volatile var failRegistrations: Boolean = false

    /** While set, [[saveCharacterState]] fails as an unreachable matchmaker would, and records nothing. */
    @volatile var failStateSaves: Boolean = false

    /** While set, [[recordMove]] and [[recordResults]] fail as an unreachable matchmaker would, after recording the
      * attempt — so a test can see that a call was made and that its failure went no further.
      */
    @volatile var failCallbacks: Boolean = false

    def recordMove(url: String, notification: Protocol.MoveNotification): Unit = synchronized {
        movesBuffer += (url -> notification)
        log(s"POST $url ${write(notification)}")
        if (failCallbacks) throw AwsError(s"POST $url failed: unreachable")
    }

    def recordResults(url: String, results: Protocol.MatchResults): Unit = synchronized {
        resultsBuffer += (url -> results)
        log(s"POST $url ${write(results)}")
        if (failCallbacks) throw AwsError(s"POST $url failed: unreachable")
    }

    def saveCharacterState(matchmakerUrl: String, characterId: Long, state: String): Unit = synchronized {
        if (failStateSaves) throw AwsError(s"PUT $matchmakerUrl/characters/$characterId/state failed: unreachable")
        statesBuffer += (characterId -> state)
        log(s"PUT $matchmakerUrl/characters/$characterId/state $state")
    }

    /* What `registerCharacter` was given, as it stands now: edits and transfers apply to it, and the listing reads
     * it, as matchmaker would. */
    private val heldBuffer = scala.collection.mutable.LinkedHashMap[Long, Protocol.RegisterCharacterRequest]()

    def registerCharacter(matchmakerUrl: String, request: Protocol.RegisterCharacterRequest): Long =
        synchronized {
            if (failRegistrations) throw AwsError(s"POST $matchmakerUrl/characters failed: unreachable")
            val id = nextCharacterId
            nextCharacterId += 1
            registrationsBuffer += (id -> request)
            heldBuffer(id) = request
            log(s"POST $matchmakerUrl/characters ${write(request)} -> character $id")
            id
        }

    def listCharacters(matchmakerUrl: String, ownerExternalId: String): List[Protocol.OwnedCharacter] =
        synchronized {
            log(s"GET $matchmakerUrl/characters?owner=$ownerExternalId")
            heldBuffer.toList
                .collect {
                    case (id, c) if c.ownerExternalId == ownerExternalId =>
                        Protocol.OwnedCharacter(id, c.name, c.description, c.state)
                }
                .sortBy(_.name)
        }

    /** Who each nickname is, for [[transferCharacter]] and [[nicknameOf]] — matchmaker knows its players, and this has
      * to be told.
      */
    @volatile var players: Map[String, String] = Map.empty

    def nicknameOf(matchmakerUrl: String, externalId: String): Option[String] = {
        log(s"GET $matchmakerUrl/nicknames?externalId=$externalId")
        players.collectFirst { case (nickname, id) if id == externalId => nickname }
    }

    def editCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.EditCharacterRequest): Unit =
        synchronized {
            log(s"PUT $matchmakerUrl/characters/$characterId ${write(request)}")
            val c = owned(characterId, request.ownerExternalId)
            heldBuffer(characterId) = c.copy(name = request.name, description = request.description)
        }

    def transferCharacter(matchmakerUrl: String, characterId: Long, request: Protocol.TransferCharacterRequest): Unit =
        synchronized {
            log(s"PUT $matchmakerUrl/characters/$characterId/owner ${write(request)}")
            val c = owned(characterId, request.ownerExternalId)
            val to = players.getOrElse(
              request.toNickname,
              throw MatchmakerRefusal(400, s"no player is called '${request.toNickname}'")
            )
            heldBuffer(characterId) = c.copy(ownerExternalId = to)
        }

    /* As matchmaker answers: a character that is not the owner's is one that does not exist. */
    private def owned(characterId: Long, ownerExternalId: String): Protocol.RegisterCharacterRequest =
        heldBuffer
            .get(characterId)
            .filter(_.ownerExternalId == ownerExternalId)
            .getOrElse(throw MatchmakerRefusal(404, s"no character with id $characterId"))

    def moves: List[(String, Protocol.MoveNotification)] = synchronized(movesBuffer.toList)
    def results: List[(String, Protocol.MatchResults)] = synchronized(resultsBuffer.toList)

    /** Each character's state as saved, by character id, oldest first. */
    def characterStates: List[(Long, String)] = synchronized(statesBuffer.toList)

    /** Each character registered, with the id it was given, oldest first. */
    def registrations: List[(Long, Protocol.RegisterCharacterRequest)] = synchronized(registrationsBuffer.toList)
}
