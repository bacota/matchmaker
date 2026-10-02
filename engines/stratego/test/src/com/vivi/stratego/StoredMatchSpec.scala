package com.vivi.stratego

import java.time.Instant
import munit.FunSuite
import upickle.default.{read, write}

/** The JSON a match is stored as, pinned.
  *
  * DynamoDB holds every match as this JSON in one attribute, and a match in progress outlives a deploy. So the shape is
  * a contract with matches already stored: a renamed, nested or newly required field would make them unreadable, and
  * nothing else would notice until a player's next move failed. A deliberate change to the shape means changing this
  * literal — and either defaulting the new field or migrating the stored matches.
  *
  * The board is a few pieces rather than two armies, so that the literal stays readable; a board is stored as a list of
  * what stands on it, however many pieces that is.
  */
class StoredMatchSpec extends FunSuite {

    private def at(seconds: Long) = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds)

    private val stratego =
        StrategoMatch(
          matchId = "m-1",
          seats = List(Seat(Side.Red, "sub-alice", 11L), Seat(Side.Blue, "sub-bob", 22L)),
          board = Board(
            Board.empty.cells
                .updated(0, Some(Piece(0, Side.Red, Rank.Flag)))
                .updated(60, Some(Piece(40, Side.Blue, Rank.Sergeant, revealed = true)))
                .updated(99, Some(Piece(79, Side.Blue, Rank.Flag)))
          ),
          turns = List(
            MoveRecord(11L, Side.Red, at(1), at(0)),
            MoveRecord(22L, Side.Blue, at(2), at(0)),
            MoveRecord(
              11L,
              Side.Red,
              at(3),
              at(2),
              Some(Step(30, 30, 60)),
              Some(Battle(Rank.Scout, Rank.Sergeant, Result.DefenderWins))
            )
          ),
          isPublic = true,
          completed = false,
          createdAt = at(0),
          moveCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/moves"),
          resultsCallbackUrl = Some("http://matchmaker.test/games/1/matches/m-1/results")
        )

    // One line in storage; broken here only to be readable.
    private val stored =
        """|{"matchId":"m-1","seats":[{"side":"Red","cognitoId":"sub-alice","participantId":11},{"side":"Blue",
          |"cognitoId":"sub-bob","participantId":22}],"board":[{"square":0,"id":0,"side":"Red","rank":"Flag",
          |"revealed":false,"moved":false},{"square":60,"id":40,"side":"Blue","rank":"Sergeant","revealed":true,
          |"moved":false},{"square":99,"id":79,"side":"Blue","rank":"Flag","revealed":false,"moved":false}],
          |"turns":[{"participantId":11,"side":"Red","takenAt":"2026-01-01T00:00:01Z","startedAt":"2026-01-01T00:00:00Z"},
          |{"participantId":22,"side":"Blue","takenAt":"2026-01-01T00:00:02Z","startedAt":"2026-01-01T00:00:00Z"},
          |{"participantId":11,"side":"Red","takenAt":"2026-01-01T00:00:03Z","startedAt":"2026-01-01T00:00:02Z",
          |"step":{"pieceId":30,"from":30,"to":60},"battle":{"attacker":"Scout","defender":"Sergeant",
          |"result":"DefenderWins"}}],"isPublic":true,"completed":false,"createdAt":"2026-01-01T00:00:00Z",
          |"moveCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/moves",
          |"resultsCallbackUrl":"http://matchmaker.test/games/1/matches/m-1/results"}""".stripMargin.replace("\n", "")

    test("a match is stored as the JSON matches already in DynamoDB are written in") {
        assertEquals(write(stratego), stored)
    }

    test("a match already stored reads back as the same match") {
        assertEquals(read[StrategoMatch](stored), stratego)
    }
}
