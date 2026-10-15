package com.vivi.hilo

import java.time.Instant
import com.vivi.engine.{GameEngine, MatchStore, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Hilo: every player picks a number at once, and the highest number nobody else picked wins. The exchanges with
  * matchmaker are [[GameEngine]]'s; what is this game's own is what a pick is, which is [[move]], and what a player is
  * shown, which is [[stateOf]].
  */
class Engine(
    store: MatchStore[HiloMatch],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: HiloMatch => Unit = _ => ()
) {

    val core = GameEngine(HiloMatch, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player's pick for the round being played. Made once: a player who could change theirs after seeing who else
      * had picked would have an edge nobody else has. The last pick of a round plays it.
      */
    def move(matchId: String, cognitoId: String, number: Int): Either[Refusal, MoveApplied[HiloMatch, Player, Pick]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this match is already over"))
                _ <- Either.cond(
                  !current.hasPicked(seat),
                  (),
                  Refusal.Invalid("you have already picked this round; a pick cannot be taken back")
                )
                _ <- Either.cond(
                  number >= 1 && number <= current.highest,
                  (),
                  Refusal.Invalid(s"pick a number from 1 to ${current.highest}")
                )
            } yield {
                val pick = Pick(
                  participantId = seat.participantId,
                  number = number,
                  takenAt = at,
                  startedAt = TurnClock.turnStart(
                    clock = current.clock,
                    participantId = seat.participantId,
                    turnStartedAt = current.roundStartedAt
                  )
                )
                val picked = current.current :+ pick
                val next =
                    if (current.players.forall(p => picked.exists(_.participantId == p.participantId)))
                        current.copy(rounds = current.rounds :+ picked, current = Nil)
                    else current.copy(current = picked)
                (next, pick)
            }
        }

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board. The picks of the round
      * being played are never in it, except the viewer's own: only that the others have picked.
      */
    def stateOf(m: HiloMatch, seat: Option[Player]): StateResponse =
        StateResponse(
          matchId = m.matchId,
          round = m.rounds.size + 1,
          highest = m.highest,
          you = seat.map(_.participantId.toString),
          yourPick = seat.flatMap(m.pickOf).map(_.number),
          isCompleted = m.isOver,
          winners = if (m.isOver) m.winners.map(_.participantId) else Nil,
          waitingFor = m.pending.map(_.participantId),
          players = m.players.map(p =>
              PlayerView(participantId = p.participantId, name = m.nameOf(p), hasPicked = m.hasPicked(p))
          ),
          rounds = m.rounds.zipWithIndex.map { (round, i) =>
              RoundView(
                round = i + 1,
                // In seat order, whatever order they were picked in.
                picks = m.players.flatMap(p =>
                    round.find(_.participantId == p.participantId).map(x => PickView(p.participantId, x.number))
                ),
                winner = Rules.winnerOf(round).map(_.participantId)
              )
          }.reverse,
          clock = core.clockView(m)
        )
}
