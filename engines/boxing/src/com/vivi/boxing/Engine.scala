package com.vivi.boxing

import java.time.Instant
import com.vivi.engine.{GameEngine, MatchStore, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Boxing: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and what is this
  * game's own — what a round plan is, and what a player is shown. How it tells matchmaker who is pending, round by
  * round, is [[Bout$]]'s.
  *
  * @param announce
  *   called once with each new bout, which is how the local server prints the play url and who is in which corner.
  */
class Engine(
    store: MatchStore[Bout],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: Bout => Unit = _ => ()
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(Bout, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player's plan for the current round. Both corners planning at the same moment is the ordinary case here, and
      * both plans must land.
      */
    def plan(
        matchId: String,
        cognitoId: String,
        allocation: Allocation
    ): Either[Refusal, MoveApplied[Bout, Corner, Plan]] =
        core.applyMove(matchId, cognitoId) { (current, corner, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this bout is already over"))
                // Every corner is created with a built fighter; only a bout stored before that rule could lack one.
                fighter <- corner.fighter.toRight(Refusal.Invalid("this corner's fighter was never built"))
                valid <- Allocation.validate(allocation, fighter).left.map(Refusal.Invalid(_))
                round = current.currentRound
                // No "not your turn": every round is both corners' turn. What a player may not do is
                // change a plan once it is in — whoever planned last would then always win.
                _ <- Either.cond(
                  current.planOf(corner, round).isEmpty,
                  (),
                  Refusal.Invalid(s"you have already planned round $round; a plan cannot be changed")
                )
            } yield {
                // The round's start, or, in a live bout, this player's opening the board if that was later.
                val started = TurnClock.turnStart(current.clock, corner.participantId, current.roundStartedAt)
                val record = Plan(corner.participantId, round, valid, at, started)
                (current.copy(plans = current.plans :+ record), record)
            }
        }

    /** The state a play page renders. `corner` is the viewer's own, absent on the public board.
      *
      * The other corner's plan for the round being fought is in nobody's answer: a viewer learns only that it has been
      * made. Once the round resolves both plans are in `rounds`, for everyone.
      */
    def stateOf(m: Bout, corner: Option[Corner]): StateResponse = {
        val over = m.isOver
        def planView(a: Allocation) = PlanRequest(a.offense, a.defense, a.power)
        def numbers(e: Effective) = Numbers(e.offense, e.defense, e.power, e.effectiveChin)

        StateResponse(
          matchId = m.matchId,
          scheduledRounds = m.scheduledRounds,
          round = if (over) m.rounds.size else m.currentRound,
          waitingFor = m.pending.map(_.side.toString),
          you = corner.map(_.side.toString),
          yourPlan =
              corner.flatMap(c => m.planOf(c, m.currentRound)).filterNot(_ => over).map(p => planView(p.allocation)),
          completed = over,
          winner = m.winner.map(_.side.toString),
          draw = m.isDraw,
          method = m.method,
          corners = m.corners.map(c =>
              CornerView(
                side = c.side.toString,
                cognitoId = c.cognitoId,
                participantId = c.participantId,
                characterId = c.characterId,
                fighter = c.fighter.map(f => FighterView(f.strength, f.speed, f.agility, f.workrate, f.chin)),
                planned = !over && m.planOf(c, m.currentRound).isDefined,
                points = m.points(c.side)
              )
          ),
          rounds = m.rounds.map(r =>
              RoundView(
                number = r.number,
                red = planView(r.red.allocation),
                blue = planView(r.blue.allocation),
                redNumbers = numbers(r.redEffective),
                blueNumbers = numbers(r.blueEffective),
                decision = r.outcome.decision.label,
                winner = r.outcome.winner.map(_.toString),
                redPoints = r.outcome.score.map(_.red),
                bluePoints = r.outcome.score.map(_.blue)
              )
          ),
          clock = core.clockView(m)
        )
    }
}
