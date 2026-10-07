package com.vivi.engine

import java.time.Instant

/** What every match has, whatever its game: the four things matchmaker sent that the engine answers with.
  *
  * Accessors only, like [[HasMatchId]], which a match's case class fields implement — so a match is written to JSON
  * exactly as it was before it extended this.
  */
trait MatchLike extends HasMatchId {
    def isPublic: Boolean
    def moveCallbackUrl: Option[String]
    def resultsCallbackUrl: Option[String]

    /** What players call the game, and the match's own message, as matchmaker sent them: what its pages are titled by.
      * None for a match created before they were kept, or by a matchmaker that predates them.
      */
    def gameDisplayName: Option[String] = None
    def description: Option[String] = None
}

object MatchTitle {

    /** A match's pages' title: the game's name and the match's message — "Boxing — Title fight" — or the game's name
      * alone when it has none. `fallback` is the engine's own name for its game, for a match that kept no name. Never
      * the match id: an internal id means nothing to anybody.
      */
    def of(m: MatchLike, fallback: String): String = {
        val game = m.gameDisplayName.map(_.trim).filter(_.nonEmpty).getOrElse(fallback)
        m.description.map(_.trim).filter(_.nonEmpty).fold(game)(message => s"$game — $message")
    }
}

/** One player's place in a match: a seat, a corner, whatever the game calls it.
  *
  * `cognitoId` is who may move in it — the same subject the player signs in as, which is how matchmaker named them and
  * how the engine recognises them. `participantId` is matchmaker's key for the seat and is what every callback quotes
  * back.
  */
trait SeatLike {
    def cognitoId: String
    def participantId: Long
}

/** One move that was made: by whom, when, and when that player's clock started for it — which is what matchmaker
  * charges a time limit from, and what a status answer and the results report.
  */
trait TurnLike {
    def participantId: Long
    def takenAt: Instant
    def startedAt: Instant
}

/** How a finished match came out for one seat. */
enum Outcome {
    case Win, Loss, Draw

    def label: String = toString.toLowerCase
}

/** A game's rules, as far as matchmaker can see them.
  *
  * Everything an engine tells matchmaker — who is pending and since when, which turns have been played, whether the
  * match is over and how it came out — is an answer to one of these questions about a match, and [[GameEngine]] asks
  * them. How a move is decided, and what a play page is shown, stay with the game.
  *
  * @tparam M
  *   the game's match
  * @tparam S
  *   its seats
  * @tparam T
  *   the record of one move
  */
trait Game[M <: MatchLike, S <: SeatLike, T <: TurnLike] {

    /** A new match from matchmaker's create request, or why the request cannot be played. */
    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, M]

    /** What a player's place in the match is called, in what they are told when they have none: a seat, a corner. */
    def seatName: String = "seat"

    def seats(m: M): List[S]

    def isOver(m: M): Boolean

    /** The match with its completion recorded. Stored even though it is derivable, so that a finished match stays
      * finished — it is what the results callback keys off, and it is written once.
      */
    def markCompleted(m: M): M

    /** The seats that may move now — one in a game of turns, several in a game where everyone moves at once, and none
      * once the match is over.
      */
    def pending(m: M): List[S]

    /** When the clock started for every seat that is pending now: the move before, the match's creation, the start of
      * the round — whatever this game's waiting is measured from.
      */
    def clockStartedAt(m: M): Instant

    /** Every move made so far. */
    def turns(m: M): List[T]

    /** How many moves this match reflects, one more with each move. Matchmaker uses it to tell a late callback from a
      * current one.
      */
    def sequence(m: M): Long

    /** Only meaningful once the match is over. A match ended by its [[TurnClock]] must answer as the clock says — see
      * [[TurnClock.outcomeOf]].
      */
    def outcome(m: M, seat: S): Outcome

    /** Where a seat finished: 1 for first, with tied seats sharing a place. Only meaningful once the match is over.
      *
      * What matchmaker records as the seat's rank, and what a tournament's points are worked out from. The default
      * reads it off [[outcome]] — 1 for a win or a draw, 2 for a loss — which is all a game of two sides can say. A
      * game with more than two seats overrides it to give each its place, 1, 2, 3 and so on; [[outcome]] still says who
      * won for the page and the `outcome` score.
      */
    def placing(m: M, seat: S): Int = if (outcome(m, seat) == Outcome.Loss) 2 else 1

    /** What the game records about a seat beside its outcome. Matchmaker stores the map without reading it. */
    def scores(m: M, seat: S): Map[String, ujson.Value]

    /** How a finished match came out, in a line of HTML that matchmaker shows players in place of the result table: who
      * won and how, in the game's own terms. Only meaningful once the match is over, and `None` for a game that has
      * nothing to say beyond the table.
      *
      * Formatting tags only — `strong`, `em` and the like, with no attributes — since matchmaker keeps those and shows
      * anything else as text. A player's nickname is theirs to choose, so it goes through [[ResultText.name]], which
      * escapes it.
      */
    def summary(m: M): Option[String] = None

    /** The turn clock of a live match, and `None` for a match that is not live. */
    def clock(m: M): Option[TurnClock]

    /** The match with its clock replaced: set on a live match when it is created, and again when a turn runs out.
      *
      * Storing the clock is the whole of what a game does for it. What it must also do is honour it: a match whose
      * clock has [[TurnClock.ranOut run out]] is over, with nobody pending, and comes out as [[TurnClock.outcomeOf]]
      * says, whatever the game's own rules would make of the position.
      */
    def withClock(m: M, clock: TurnClock): M
}
