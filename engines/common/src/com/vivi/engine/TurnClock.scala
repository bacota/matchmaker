package com.vivi.engine

import java.time.{Duration, Instant}
import upickle.default.{ReadWriter, macroRW}

/** The clock of a live match, kept on the match itself.
  *
  * A live match is one whose turns the engine runs on its own, without telling matchmaker about each one — see
  * [[Protocol.LiveTerms]]. That makes the clock the engine's too, and it is one of two kinds:
  *
  *   - [[ClockKind.PerTurn]]: every turn gets the whole `limitSeconds`, afresh.
  *   - [[ClockKind.Total]]: a chess clock. `limitSeconds` is each player's budget for the whole match, and every turn
  *     they take spends part of it — so the deadline on the turn in front of them depends on every turn behind them.
  *
  * Either way, a seat still pending when its deadline passes has run out, and running out ends the match by forfeit:
  * every seat that ran out loses, and every other seat wins.
  *
  * A seat's clock starts when its turn does — the game's [[Game.clockStartedAt]] — or when its player first opens the
  * board, whichever is later. `opened` is when each player did, and a player who has not opened the board yet is not on
  * the clock at all: a live match is played by players who are there, and one who has not arrived has not yet been
  * asked to move. The cost is that a player who never turns up holds the match up indefinitely. Under a chess clock the
  * same rule decides what a turn cost: the time from when the player's clock started for it, not before.
  *
  * `timedOut` is who ran out, and is empty until somebody does. It is stored rather than worked out again from the
  * time, so that a match ended by its clock stays ended the same way however late it is next read — the same reason a
  * match's completion is stored.
  *
  * Each game keeps one of these on its match and reads [[outcomeOf]] before its own rules: a forfeit decides the match
  * whatever the board says. Everything else — when a turn has run out, recording it, reporting it — is
  * [[GameEngine]]'s, which is what lets every game have a clock without any of them implementing one.
  */
case class TurnClock(
    limitSeconds: Long,
    kind: ClockKind = ClockKind.PerTurn,
    timedOut: List[Long] = Nil,
    opened: List[Opened] = Nil
) {

    def limit: Duration = Duration.ofSeconds(limitSeconds)

    def hasOpened(participantId: Long): Boolean = opened.exists(_.participantId == participantId)

    private def openedAt(participantId: Long): Option[Instant] = opened.find(_.participantId == participantId).map(_.at)

    /** The clock with `participantId` recorded as having opened the board at `at`; unchanged if they already had. */
    def opening(participantId: Long, at: Instant): TurnClock =
        if (hasOpened(participantId)) this else copy(opened = opened :+ Opened(participantId, at))

    /** When a seat's clock started on a turn that began at `turnStartedAt`: then, or when its player first opened the
      * board if that was later. `None` while they have not opened it, which is a clock that has not started.
      */
    def startedFor(participantId: Long, turnStartedAt: Instant): Option[Instant] =
        openedAt(participantId).map(at => later(at, turnStartedAt))

    /** What a seat has spent of its budget on the turns it has finished. Only a chess clock keeps a budget, so under a
      * per-turn clock this is nothing.
      *
      * Each turn costs the time from when the player's clock started for it — the turn's own start, or their opening
      * the board if that was later — to when they made it.
      */
    def spent(participantId: Long, turns: List[TurnLike]): Duration =
        if (kind == ClockKind.PerTurn) Duration.ZERO
        else
            turns
                .filter(_.participantId == participantId)
                .map { t =>
                    val from = openedAt(participantId).fold(t.startedAt)(later(_, t.startedAt))
                    val cost = Duration.between(from, t.takenAt)
                    if (cost.isNegative) Duration.ZERO else cost
                }
                .foldLeft(Duration.ZERO)(_.plus(_))

    /** What a seat has left before the turn in front of it: the whole limit under a per-turn clock, and whatever the
      * turns behind it have not spent under a chess clock.
      */
    def allowance(participantId: Long, turns: List[TurnLike]): Duration = limit.minus(spent(participantId, turns))

    /** When a seat's clock runs out on the turn that began at `turnStartedAt`, given the turns played so far; `None`
      * while its player has not opened the board. A chess clock with nothing left runs out the moment it starts.
      */
    def deadlineFor(participantId: Long, turnStartedAt: Instant, turns: List[TurnLike]): Option[Instant] =
        startedFor(participantId, turnStartedAt).map(_.plus(allowance(participantId, turns)))

    /** Whether the match has been ended by this clock. */
    def ranOut: Boolean = timedOut.nonEmpty

    /** How the match came out for a seat because of this clock, if it was the clock that ended it. Every seat that ran
      * out loses — two that ran out together both lose, with nobody winning — and every other seat wins.
      */
    def outcomeOf(participantId: Long): Option[Outcome] =
        Option.when(ranOut)(if (timedOut.contains(participantId)) Outcome.Loss else Outcome.Win)

    private def later(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b
}

/** What a live match's time limit is a limit on: each turn, or each player's whole match. The codes are matchmaker's
  * own `time_limit_kind`.
  */
enum ClockKind(val code: String) {
    case PerTurn extends ClockKind("PER_TURN")
    case Total extends ClockKind("TOTAL")
}

object ClockKind {
    def parse(code: String): Option[ClockKind] = values.find(_.code == code)

    given ReadWriter[ClockKind] =
        upickle.default.readwriter[String].bimap(_.code, c => parse(c).getOrElse(throw IllegalArgumentException(c)))
}

object TurnClock {

    /** When a seat's clock started for a move it is making now, on a turn that began at `turnStartedAt`: what a game
      * records as the move's `startedAt`.
      *
      * In a live match that is when the player's clock really started — the turn's start, or their first opening the
      * board if that was later — so that a move made seconds after opening an hour-old match is recorded as taking
      * seconds, which is what matchmaker shows as the time it cost. Anywhere else it is the turn's start, as it always
      * was.
      */
    def turnStart(clock: Option[TurnClock], participantId: Long, turnStartedAt: Instant): Instant =
        clock.flatMap(_.startedFor(participantId, turnStartedAt)).getOrElse(turnStartedAt)

    /** The clock a create request asks for: none for a match that is not live, and one per [[Protocol.LiveTerms]] for a
      * match that is — or why that cannot be played.
      */
    def of(request: Protocol.CreateGameRequest): Either[String, Option[TurnClock]] =
        request.live match {
            case None => Right(None)
            case Some(terms) =>
                for {
                    kind <- ClockKind
                        .parse(terms.kind)
                        .toRight(s"'${terms.kind}' is not a kind of clock; expected PER_TURN or TOTAL")
                    _ <- Either.cond(
                      terms.timeLimitSeconds > 0,
                      (),
                      s"a live match needs a time limit of at least a second; ${terms.timeLimitSeconds} was sent"
                    )
                } yield Some(TurnClock(terms.timeLimitSeconds, kind))
        }

    import Protocol.given
    given ReadWriter[Opened] = macroRW
    given ReadWriter[TurnClock] = macroRW
}

/** When a seat's player first opened the board of a live match. */
case class Opened(participantId: Long, at: Instant)

/** The clock as a play page is shown it, in a game's state answer.
  *
  * `kind` is `PER_TURN` or `TOTAL`, and `limitSeconds` the limit it applies. `seats` is empty once the match is over;
  * until then it is every seat being waited on, and under a chess clock every seat, since a budget is worth showing
  * whether or not it is running. `timedOut` is who ran out, by participant id, once somebody has.
  */
case class ClockView(limitSeconds: Long, kind: String, seats: List[SeatClock], timedOut: List[Long])

/** One seat's clock.
  *
  * `waiting` is whether the match is waiting on this seat, and `running` whether its clock is counting down — a seat
  * waited on whose player has not opened the board is not. `remainingMillis` is what it has left, measured by the
  * engine at the moment it answered: a duration rather than a deadline, so that a page whose own clock is wrong still
  * counts down the right amount. Under a per-turn clock it is absent until the clock runs. `startedAt` is when a
  * running clock started, which is how a page tells a new turn from the same one read again.
  */
case class SeatClock(
    participantId: Long,
    waiting: Boolean,
    running: Boolean,
    startedAt: Option[Instant],
    remainingMillis: Option[Long]
)

object ClockView {
    import Protocol.given
    given ReadWriter[SeatClock] = macroRW
    given ReadWriter[ClockView] = macroRW
}
