package com.vivi.matchmaker.model.tournament

import java.time.Duration
import com.vivi.matchmaker.model.{MoveType, TimeLimitKind}

/** The time limit a tournament match is played under: its kind, and its length. */
case class MatchClock(kind: TimeLimitKind, limit: Duration)

/** How a tournament spends a round's time (`tournament-design.md`, "Rounds"). */
object Clock {

    /** The clock of a match lasting a round of `d`, with `seats` seated.
      *
      * In a game of turns, each seat has a chess clock of `d / seats`. In a game where everyone moves at once, each
      * turn has `d / t`, where `t` is its turns — one more when ties are not allowed, since the game may need another
      * to find a winner (Q11), and one more again when roles are chosen first, for the choosing
      * (`tournament-design.md`, "Choice of Roles"). `maxTurns` is the tournament's, used where the game has none.
      */
    def forMatch(
        game: TournamentGame,
        d: Duration,
        seats: Int,
        tiesAllowed: Boolean,
        choosing: Boolean,
        maxTurns: Option[Int]
    ): MatchClock =
        game.moveType match {
            case MoveType.Sequential => MatchClock(TimeLimitKind.Total, d.dividedBy(math.max(seats, 1).toLong))
            case MoveType.Simultaneous =>
                val t =
                    game.maxTurns.orElse(maxTurns).getOrElse(1) + (if (tiesAllowed) 0 else 1) + (if (choosing) 1 else 0)
                MatchClock(TimeLimitKind.PerTurn, d.dividedBy(math.max(t, 1).toLong))
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
