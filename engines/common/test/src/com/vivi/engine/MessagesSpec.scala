package com.vivi.engine

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite

/** Who may read and write a match's message boards, and what may be written. */
class MessagesSpec extends FunSuite {

    import MessageBoard.{Observers, Players}
    import Viewer.{Anonymous, Observer, Player}

    test("the players' board is the players', and readable by anyone a public match lets watch") {
        assert(MessageRules.canRead(Players, Player, isPublic = false, over = false))
        assert(!MessageRules.canRead(Players, Observer, isPublic = false, over = false))
        assert(MessageRules.canRead(Players, Observer, isPublic = true, over = false))
        assert(MessageRules.canRead(Players, Anonymous, isPublic = true, over = false))
    }

    test("the observers' board is kept from the players until the match is over") {
        assert(!MessageRules.canRead(Observers, Player, isPublic = true, over = false))
        assert(MessageRules.canRead(Observers, Player, isPublic = true, over = true))
        assert(MessageRules.canRead(Observers, Observer, isPublic = true, over = false))
        assert(MessageRules.canRead(Observers, Anonymous, isPublic = true, over = false))
        // A private match has no observers, and so no observers' board, even once it is over.
        assert(!MessageRules.canRead(Observers, Player, isPublic = false, over = true))
    }

    test("only players write on the players' board, and only signed-in watchers of a public match on the observers'") {
        def may(board: MessageBoard, viewer: Viewer, isPublic: Boolean = true) =
            MessageRules.refusalToPost(board, viewer, isPublic, over = false).isEmpty
        assert(may(Players, Player))
        assert(!may(Players, Observer))
        assert(!may(Players, Anonymous))
        assert(may(Observers, Observer))
        assert(!may(Observers, Player))
        assert(!may(Observers, Observer, isPublic = false))
        assertEquals(
          MessageRules.refusalToPost(Observers, Anonymous, isPublic = true, over = false),
          Some(Refusal.Unauthenticated("sign in to write here"))
        )
    }

    test("once the match is over, nobody writes on either board") {
        for (board <- MessageBoard.values; viewer <- Viewer.values)
            assert(MessageRules.refusalToPost(board, viewer, isPublic = true, over = true).isDefined)
    }

    private class Fixture {
        val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
        val store = InMemoryMessageStore()
        val messages =
            Messages(store, Map("sub-alice" -> "Alice").get, () => clock.get, maxLength = 20, maxPerBoard = 3)
        def later(seconds: Long): Unit = clock.updateAndGet(_.plusSeconds(seconds))
    }

    test("a message is kept trimmed, with its writer's nickname, or a stand-in when there is none") {
        val f = Fixture()
        val alice = f.messages.post("m-1", Players, "sub-alice", Player, "  good luck  ").toOption.get
        assertEquals((alice.name, alice.text), ("Alice", "good luck"))
        f.later(5)
        val unknown = f.messages.post("m-1", Observers, "sub-zed", Observer, "go red").toOption.get
        assertEquals(unknown.name, "an observer")
        assertEquals(f.messages.list("m-1").map(_.text), List("good luck", "go red"))
    }

    test("a name the caller already knows — a player's, from their seat — is used without asking matchmaker") {
        val f = Fixture()
        val bob = f.messages.post("m-1", Players, "sub-bob", Player, "gl", known = Some("Bob")).toOption.get
        assertEquals(bob.name, "Bob")
    }

    test("an empty message, or one too long, is refused") {
        val f = Fixture()
        assert(f.messages.post("m-1", Players, "sub-alice", Player, "   ").isLeft)
        assert(f.messages.post("m-1", Players, "sub-alice", Player, "x" * 21).isLeft)
        assert(f.messages.post("m-1", Players, "sub-alice", Player, "x" * 20).isRight)
    }

    test("a writer must pause between messages on a board, and a board has a limit") {
        val f = Fixture()
        assert(f.messages.post("m-1", Players, "sub-alice", Player, "one").isRight)
        assertEquals(
          f.messages.post("m-1", Players, "sub-alice", Player, "two"),
          Left(Refusal.Invalid("that was quick; wait a moment before writing again"))
        )
        // Somebody else is not held up by it.
        assert(f.messages.post("m-1", Players, "sub-bob", Player, "hi").isRight)
        f.later(1)
        assert(f.messages.post("m-1", Players, "sub-alice", Player, "two").isRight)
        f.later(1)
        assertEquals(
          f.messages.post("m-1", Players, "sub-bob", Player, "four"),
          Left(Refusal.Invalid("this board is full"))
        )
        // The other board, and another match, have room of their own.
        assert(f.messages.post("m-1", Observers, "sub-zed", Observer, "hello").isRight)
        assert(f.messages.post("m-2", Players, "sub-alice", Player, "hello").isRight)
    }

    test("a view shows each viewer what they may read, which messages are theirs, and where they may write") {
        val f = Fixture()
        f.messages.post("m-1", Players, "sub-alice", Player, "gl")
        f.messages.post("m-1", Observers, "sub-zed", Observer, "go red")

        val alice = MessageWire.view(f.messages, "m-1", Player, Some("sub-alice"), isPublic = true, over = false)
        assertEquals(alice.players.map(_.map(m => (m.name, m.mine))), Some(List(("Alice", true))))
        assertEquals(alice.observers, None)
        assertEquals(alice.canWrite, List("players"))

        val watcher = MessageWire.view(f.messages, "m-1", Anonymous, None, isPublic = true, over = false)
        assertEquals(watcher.players.map(_.size), Some(1))
        assertEquals(watcher.observers.map(_.map(_.mine)), Some(List(false)))
        assertEquals(watcher.canWrite, Nil)
        assert(watcher.signInToWrite)

        val over = MessageWire.view(f.messages, "m-1", Player, Some("sub-alice"), isPublic = true, over = true)
        assertEquals(over.observers.map(_.size), Some(1))
        assertEquals(over.canWrite, Nil)
    }
}
