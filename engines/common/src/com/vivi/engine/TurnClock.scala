package com.vivi.engine

import java.time.{Duration, Instant}
import upickle.default.{ReadWriter, macroRW}

/** The turn clock of a live match, kept on the match itself.
  *
  * A live match is one whose turns the engine runs on its own, without telling matchmaker about each one — see
  * [[Protocol.LiveTerms]]. That makes the clock the engine's too: every pending seat gets `turnSeconds` from when its
  * clock started, and a seat still pending once that has passed has run out. Running out ends the match by forfeit:
  * every seat that ran out loses, and every other seat wins.
  *
  * A seat's clock starts when its turn does — the game's [[Game.clockStartedAt]] — or when its player first opens the
  * board, whichever is later. `opened` is when each player did, and a player who has not opened the board yet is not on
  * the clock at all: a live match is played by players who are there, and one who has not arrived has not yet been
  * asked to move. The cost is that a player who never turns up holds the match up indefinitely.
  *
  * `timedOut` is who ran out, and is empty until somebody does. It is stored rather than worked out again from the
  * time, so that a match ended by its clock stays ended the same way however late it is next read — the same reason a
  * match's completion is stored.
  *
  * Each game keeps one of these on its match and reads [[outcomeOf]] before its own rules: a forfeit decides the match
  * whatever the board says. Everything else — when a turn has run out, recording it, reporting it — is
  * [[GameEngine]]'s, which is what lets every game have a clock without any of them implementing one.
  */
case class TurnClock(turnSeconds: Long, timedOut: List[Long] = Nil, opened: List[Opened] = Nil) {

    def turn: Duration = Duration.ofSeconds(turnSeconds)

    /** When a turn whose clock started at `startedAt` runs out. */
    def deadline(startedAt: Instant): Instant = startedAt.plus(turn)

    def hasOpened(participantId: Long): Boolean = opened.exists(_.participantId == participantId)

    /** The clock with `participantId` recorded as having opened the board at `at`; unchanged if they already had. */
    def opening(participantId: Long, at: Instant): TurnClock =
        if (hasOpened(participantId)) this else copy(opened = opened :+ Opened(participantId, at))

    /** When a seat's clock started on a turn that began at `turnStartedAt`: then, or when its player first opened the
      * board if that was later. `None` while they have not opened it, which is a clock that has not started.
      */
    def startedFor(participantId: Long, turnStartedAt: Instant): Option[Instant] =
        opened.find(_.participantId == participantId).map(o => if (o.at.isAfter(turnStartedAt)) o.at else turnStartedAt)

    /** Whether the match has been ended by this clock. */
    def ranOut: Boolean = timedOut.nonEmpty

    /** How the match came out for a seat because of this clock, if it was the clock that ended it. Every seat that ran
      * out loses — two that ran out together both lose, with nobody winning — and every other seat wins.
      */
    def outcomeOf(participantId: Long): Option[Outcome] =
        Option.when(ranOut)(if (timedOut.contains(participantId)) Outcome.Loss else Outcome.Win)
}

object TurnClock {

    /** The clock a create request asks for: none for a match that is not live, and one per [[Protocol.LiveTerms]] for a
      * match that is — or why that cannot be played.
      */
    def of(request: Protocol.CreateGameRequest): Either[String, Option[TurnClock]] =
        request.live match {
            case None => Right(None)
            case Some(terms) if terms.turnTimeoutSeconds <= 0 =>
                Left(s"a live match needs a turn timeout of at least a second; ${terms.turnTimeoutSeconds} was sent")
            case Some(terms) => Right(Some(TurnClock(terms.turnTimeoutSeconds)))
        }

    import Protocol.given
    given ReadWriter[Opened] = macroRW
    given ReadWriter[TurnClock] = macroRW
}

/** When a seat's player first opened the board of a live match. */
case class Opened(participantId: Long, at: Instant)

/** The clock as a play page is shown it, in a game's state answer.
  *
  * `seats` is every seat being waited on, each with its own clock, and is empty once the match is over. `timedOut` is
  * who ran out, by participant id, once somebody has.
  */
case class ClockView(turnSeconds: Long, seats: List[SeatClock], timedOut: List[Long])

/** One pending seat's clock.
  *
  * `remainingMillis` is what is left of its turn, measured by the engine at the moment it answered — a duration rather
  * than a deadline, so that a page whose own clock is wrong still counts down the right amount. `startedAt` is when the
  * clock started, which is how a page tells a new turn from the same one read again. Both are absent while the seat's
  * player has not opened the board, since their clock has not started.
  */
case class SeatClock(participantId: Long, startedAt: Option[Instant], remainingMillis: Option[Long])

object ClockView {
    import Protocol.given
    given ReadWriter[SeatClock] = macroRW
    given ReadWriter[ClockView] = macroRW
}
