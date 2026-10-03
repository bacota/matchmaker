package com.vivi.engine

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** A match's two message boards: one the players write on to each other, and one for whoever is watching a public
  * match.
  *
  * Who may read and write which is [[MessageRules]]'s, and is the same for every game. In short: the players' board is
  * the players', and readable by anyone a public match lets watch; the observers' board is written by signed-in people
  * who are not playing, and kept from the players until the match is over, so that nobody can be coached in the middle
  * of it. Once the match is over both boards are read-only, and stay readable.
  *
  * A message is stored with who wrote it, by subject, and the name they are shown by, which is looked up when they
  * write: a player's or an observer's matchmaker nickname. The subject is what lets a page mark a viewer's own
  * messages; it is never sent to the page.
  */
case class Message(
    matchId: String,
    board: MessageBoard,
    id: String,
    at: Instant,
    author: String,
    name: String,
    text: String
)

/** The two boards. */
enum MessageBoard(val wire: String) {
    case Players extends MessageBoard("players")
    case Observers extends MessageBoard("observers")
}

object MessageBoard {
    def parse(s: String): Option[MessageBoard] = values.find(_.wire == s)
}

/** Who is reading or writing: one of the match's players, someone signed in who is not one, or nobody signed in — a
  * watcher of the public board.
  */
enum Viewer {
    case Player, Observer, Anonymous
}

/** Who may read and write each board, decided from the viewer, whether the match is public, and whether it is over. */
object MessageRules {

    def canRead(board: MessageBoard, viewer: Viewer, isPublic: Boolean, over: Boolean): Boolean =
        board match {
            // The players', and anyone a public match lets watch.
            case MessageBoard.Players => viewer == Viewer.Player || isPublic
            // Only a public match has observers; and the players see what they said only once it can no longer help.
            case MessageBoard.Observers => isPublic && (viewer != Viewer.Player || over)
        }

    /** Why `viewer` may not write on `board`, or nothing if they may. */
    def refusalToPost(board: MessageBoard, viewer: Viewer, isPublic: Boolean, over: Boolean): Option[Refusal] =
        if (over) Some(Refusal.Invalid("the match is over; its message boards are read-only"))
        else
            board match {
                case MessageBoard.Players =>
                    Option.when(viewer != Viewer.Player)(
                      Refusal.NotYours("only the players write on the players' board")
                    )
                case MessageBoard.Observers =>
                    if (!isPublic) Some(Refusal.NotYours("this match is not public, so it has no observers"))
                    else
                        viewer match {
                            case Viewer.Player => Some(Refusal.NotYours("players do not write on the observers' board"))
                            case Viewer.Anonymous => Some(Refusal.Unauthenticated("sign in to write here"))
                            case Viewer.Observer  => None
                        }
            }
}

/** Where a match's messages are kept. */
trait MessageStore {
    def add(message: Message): Unit

    /** Every message of a match, on both boards, oldest first. */
    def list(matchId: String): List[Message]
}

class InMemoryMessageStore extends MessageStore {

    private val byMatch = ConcurrentHashMap[String, Vector[Message]]()

    def add(message: Message): Unit = byMatch.merge(message.matchId, Vector(message), _ ++ _)

    def list(matchId: String): List[Message] = byMatch.getOrDefault(matchId, Vector.empty).toList.sortBy(_.at)
}

/** Messages in a DynamoDB table keyed by match, and within a match by `key`: the time it was written, zero-padded so
  * that the keys sort as the times do, and its id, so that two written in the same millisecond are both kept. A query
  * by match is then every message of it, oldest first.
  *
  * `expiresAt` is the table's TTL, like the matches table's: written so that turning expiry on is a setting, not a
  * migration.
  */
class DynamoDbMessageStore(
    http: SignedHttp,
    table: String,
    region: String,
    lifetime: Duration = Duration.ofDays(365)
) extends MessageStore {

    private val dynamoDb = DynamoDb(http, region)

    def add(message: Message): Unit =
        dynamoDb.call(
          "PutItem",
          ujson.Obj(
            "TableName" -> table,
            "Item" -> ujson.Obj(
              "matchId" -> ujson.Obj("S" -> message.matchId),
              "key" -> ujson.Obj("S" -> f"${message.at.toEpochMilli}%015d#${message.id}"),
              "board" -> ujson.Obj("S" -> message.board.wire),
              "id" -> ujson.Obj("S" -> message.id),
              "at" -> ujson.Obj("S" -> message.at.toString),
              "author" -> ujson.Obj("S" -> message.author),
              "name" -> ujson.Obj("S" -> message.name),
              "text" -> ujson.Obj("S" -> message.text),
              "expiresAt" -> ujson.Obj("N" -> message.at.plus(lifetime).getEpochSecond.toString)
            )
          )
        )

    def list(matchId: String): List[Message] = {
        def page(from: Option[ujson.Value]): List[Message] = {
            val request = ujson.Obj(
              "TableName" -> table,
              "KeyConditionExpression" -> "matchId = :m",
              "ExpressionAttributeValues" -> ujson.Obj(":m" -> ujson.Obj("S" -> matchId))
            )
            from.foreach(key => request("ExclusiveStartKey") = key)
            val response = dynamoDb.call("Query", request)
            val found = response("Items").arr.toList.flatMap { item =>
                MessageBoard
                    .parse(item("board")("S").str)
                    .map(board =>
                        Message(
                          matchId,
                          board,
                          item("id")("S").str,
                          Instant.parse(item("at")("S").str),
                          item("author")("S").str,
                          item("name")("S").str,
                          item("text")("S").str
                        )
                    )
            }
            found ++ response.obj.get("LastEvaluatedKey").map(key => page(Some(key))).getOrElse(Nil)
        }
        page(None)
    }
}

/** Writing on a board: the limits on what may be written, and the name the writer is shown by.
  *
  * `nickname` is the writer's matchmaker nickname, looked up by subject when they write; `None` — matchmaker not
  * configured, as in the local server, or not answering — and they are shown as "a player" or "an observer" rather than
  * refused, since who they are is already proven by their token.
  *
  * The limits are checked against what is stored, outside any transaction: two messages racing past the cap or the
  * pause is a board one message longer than it should be, which is not worth a lock.
  *
  * @param maxLength
  *   characters in one message
  * @param maxPerBoard
  *   messages on one board of one match, so that a board cannot be filled without limit
  * @param pause
  *   the least time between one writer's messages on one board
  */
class Messages(
    store: MessageStore,
    nickname: String => Option[String],
    now: () => Instant = () => Instant.now(),
    val maxLength: Int = 500,
    maxPerBoard: Int = 500,
    pause: Duration = Duration.ofSeconds(1)
) {

    def list(matchId: String): List[Message] = store.list(matchId)

    /** `known` is the writer's name when the caller already has it — a player's, from their seat — which saves asking
      * matchmaker.
      */
    def post(
        matchId: String,
        board: MessageBoard,
        author: String,
        viewer: Viewer,
        text: String,
        known: Option[String] = None
    ): Either[Refusal, Message] = {
        val trimmed = text.trim
        val at = now()
        val onBoard = store.list(matchId).filter(_.board == board)
        for {
            _ <- Either.cond(trimmed.nonEmpty, (), Refusal.Invalid("a message needs some text"))
            _ <- Either.cond(
              trimmed.length <= maxLength,
              (),
              Refusal.Invalid(s"a message is at most $maxLength characters")
            )
            _ <- Either.cond(onBoard.size < maxPerBoard, (), Refusal.Invalid("this board is full"))
            _ <- Either.cond(
              onBoard.filter(_.author == author).lastOption.forall(last => !at.isBefore(last.at.plus(pause))),
              (),
              Refusal.Invalid("that was quick; wait a moment before writing again")
            )
        } yield {
            val name =
                known
                    .orElse(nickname(author))
                    .filter(_.nonEmpty)
                    .getOrElse(if (viewer == Viewer.Player) "a player" else "an observer")
            val message = Message(matchId, board, UUID.randomUUID().toString, at, author, name, trimmed)
            store.add(message)
            message
        }
    }
}

/** The message boards as the page reads and writes them. */
object MessageWire {

    import upickle.default.{ReadWriter, macroRW}

    /** One message as a viewer is shown it: never who wrote it by subject, only by name, and whether it was them. */
    case class MessageView(name: String, text: String, at: String, mine: Boolean)

    /** Both boards as `viewer` may see them. A board they may not read is absent, not empty, so that the page can say
      * why; `canWrite` is the boards they may write on now, and `signInToWrite` says that signing in would let them.
      */
    case class BoardsView(
        isPublic: Boolean,
        over: Boolean,
        players: Option[List[MessageView]],
        observers: Option[List[MessageView]],
        canWrite: List[String],
        signInToWrite: Boolean,
        maxLength: Int
    )

    /** `POST /matches/{matchId}/messages`. */
    case class PostMessage(board: String, text: String)

    given ReadWriter[MessageView] = macroRW
    given ReadWriter[BoardsView] = macroRW
    given ReadWriter[PostMessage] = macroRW

    /** Both boards of `matchId` as `viewer` — the subject `who`, if signed in — may see them. */
    def view(
        messages: Messages,
        matchId: String,
        viewer: Viewer,
        who: Option[String],
        isPublic: Boolean,
        over: Boolean
    ): BoardsView = {
        val all = messages.list(matchId)
        def board(b: MessageBoard): Option[List[MessageView]] =
            Option.when(MessageRules.canRead(b, viewer, isPublic, over))(
              all.filter(_.board == b).map(m => MessageView(m.name, m.text, m.at.toString, who.contains(m.author)))
            )
        BoardsView(
          isPublic,
          over,
          board(MessageBoard.Players),
          board(MessageBoard.Observers),
          MessageBoard.values.toList
              .filter(b => MessageRules.refusalToPost(b, viewer, isPublic, over).isEmpty)
              .map(_.wire),
          viewer == Viewer.Anonymous && isPublic && !over,
          messages.maxLength
        )
    }
}
