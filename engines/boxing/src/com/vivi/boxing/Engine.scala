package com.vivi.boxing

import java.time.Instant
import scala.util.control.NonFatal
import com.vivi.engine.{GameEngine, Log, MatchStore, Matchmaker, MoveApplied, Refusal}
import Protocol._

/** Boxing: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and what is this
  * game's own — building a fighter, what a round plan is, and what a player is shown. How it tells matchmaker who is
  * pending, round by round, is [[Bout$]]'s.
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

    /** A player building their fighter, which a fighter's first bout is where they do.
      *
      * Written to matchmaker first, as the character's state, and only then to the bout: a fighter that is in this bout
      * but not in matchmaker would have to be built again — possibly differently — at its next one. If matchmaker
      * cannot be reached the build is refused and nothing changes, so the player can simply try again.
      *
      * A fighter is built once. A corner whose fighter arrived with its characteristics, or was built already, is
      * refused: changing them mid-bout, or between bouts, is not something this game offers.
      *
      * The one gap: two builds of the same corner racing each other both reach matchmaker, and only the first reaches
      * the bout. Matchmaker then keeps whichever wrote last. It takes one player submitting two different fighters from
      * two tabs in the same instant, and the next bout shows them the fighter matchmaker kept.
      */
    def build(matchId: String, cognitoId: String, fighter: Fighter): Either[Refusal, Bout] = {
        def unbuilt(m: Bout): Either[Refusal, Corner] =
            for {
                corner <- seatOf(m, cognitoId)
                _ <- Either.cond(corner.fighter.isEmpty, (), Refusal.Invalid("your fighter is already built"))
                _ <- Either.cond(!m.isOver, (), Refusal.Invalid("this bout is already over"))
            } yield corner

        for {
            valid <- Fighter.validate(fighter).left.map(Refusal.Invalid(_))
            current <- read(matchId)
            corner <- unbuilt(current)
            _ <- keep(current, corner, valid)
            built <- store
                .modify(matchId) { latest =>
                    // Decided again against the stored bout: a second tab building at the same moment
                    // must not overwrite the first.
                    unbuilt(latest) match {
                        case Right(c) =>
                            val updated =
                                latest.copy(corners =
                                    latest.corners.map(x => if (x == c) x.copy(fighter = Some(valid)) else x)
                                )
                            (Some(updated), Right(updated))
                        case Left(refusal) => (None, Left(refusal))
                    }
                }
                .toRight(Refusal.NotFound(s"no match '$matchId'"))
                .flatten
        } yield built
    }

    /** Saves a fighter to matchmaker, when there is a matchmaker to save it to. */
    private def keep(m: Bout, corner: Corner, fighter: Fighter): Either[Refusal, Unit] =
        m.matchmakerUrl match {
            // No callback urls were sent, so there is nowhere to keep it: the fighter lasts for this
            // bout only. That is the local, matchmaker-less case, and the only one.
            case None => Right(())
            case Some(url) =>
                try Right(matchmaker.saveCharacterState(url, corner.characterId, Fighter.toState(fighter)))
                catch {
                    case NonFatal(e) =>
                        Log.failure(e, s"saving fighter ${corner.characterId}")
                        Left(Refusal.Unavailable("your fighter could not be saved to matchmaker; please try again"))
                }
        }

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
                fighter <- corner.fighter.toRight(Refusal.Invalid("build your fighter before planning a round"))
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
                val record = Plan(corner.participantId, round, valid, at, current.roundStartedAt)
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
          buildRules = BuildRules(Fighter.Budget, Fighter.Min, Fighter.Max),
          corners = m.corners.map(c =>
              CornerView(
                side = c.side.toString,
                cognitoId = c.cognitoId,
                participantId = c.participantId,
                characterId = c.characterId,
                fighter = c.fighter.map(f => BuildRequest(f.strength, f.speed, f.agility, f.workrate, f.chin)),
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
          )
        )
    }
}
