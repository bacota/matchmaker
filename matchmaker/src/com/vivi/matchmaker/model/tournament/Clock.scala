package com.vivi.matchmaker.model.tournament

import java.time.Duration
import com.vivi.matchmaker.model.{MoveType, TimeLimitKind}

/** The time limit a tournament match is played under: its kind, and its length. */
case class MatchClock(kind: TimeLimitKind, limit: Duration)

/** How a tournament spends a round's time (`tournament-design.md`, "Rounds"). */
object Clock {

    /** The clock of a match with `seats` seated, `None` where the tournament gives no time for it.
      *
      * In a game of turns, each seat has a chess clock of `round / seats`, `round` being the round's duration. In a
      * game where everyone moves at once, each turn has `turn`, the tournament's turn duration.
      */
    def forMatch(
        game: TournamentGame,
        round: Option[Duration],
        turn: Option[Duration],
        seats: Int
    ): Option[MatchClock] =
        game.moveType match {
            case MoveType.Sequential =>
                round.map(d => MatchClock(kind = TimeLimitKind.Total, limit = d.dividedBy(math.max(seats, 1).toLong)))
            case MoveType.Simultaneous => turn.map(d => MatchClock(kind = TimeLimitKind.PerTurn, limit = d))
        }

    /** How long a seat has to choose its role: one turn's time in a game where everyone moves at once, and the seat's
      * whole clock in a game of turns, where the choosing is charged to it (`tournament-design.md`, "Choice of Roles").
      */
    def toChoose(clock: MatchClock): Duration = clock.limit
}

/** Who forfeits a match for having more than one seat in it (`tournament-design.md`, "Multiple entrants from same
  * player"): every entrant of a player seated more than once, but the one with the strongest seed.
  *
  * `seats` are each seat's entrant, the player it belongs to, and its seed.
  */
object MultipleEntries {
    def forfeits(seats: List[(Long, Long, Int)]): Set[Long] =
        seats
            .groupBy(_._2)
            .values
            .filter(_.sizeIs > 1)
            .flatMap(same => same.sortBy(_._3).tail.map(_._1))
            .toSet
}
