package com.vivi.boxing

import java.time.Instant
import scala.util.control.NonFatal
import com.vivi.engine.{GameEngine, Log, MatchStore, MatchmakerRefusal, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Boxing: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and what is this
  * game's own — building a fighter, what a round plan is, and what a player is shown. How it tells matchmaker who is
  * pending, round by round, is [[Bout$]]'s.
  *
  * @param announce
  *   called once with each new bout, which is how the local server prints the play url and who is in which corner.
  * @param matchmakerUrl
  *   matchmaker's API, which a fighter is reported to once it is built. `None` refuses every build: a fighter
  *   matchmaker never heard of could never be put in a bout.
  */
class Engine(
    store: MatchStore[Bout],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: Bout => Unit = _ => (),
    matchmakerUrl: Option[String] = None
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(Bout, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player building a new fighter, which is made here and then reported to matchmaker as a character.
      *
      * A fighter is made in this engine, because this engine is what knows what a fighter is: the five characteristics,
      * the budget they are built from, and the range each must be in. Matchmaker only learns that one exists, with its
      * characteristics as the character's state, and from then on offers it in challenges and seats it in bouts — where
      * that state comes back to [[Bout.seat]].
      *
      * Nothing is kept here: matchmaker's answer is the fighter's only record, so a build matchmaker refuses or never
      * answers has made nothing, and the player can simply try again. The gap is the other way round — matchmaker
      * recording it and the answer being lost — which leaves the player a fighter they were told did not take, and
      * which they will find in matchmaker.
      */
    def buildFighter(cognitoId: String, request: BuildRequest): Either[Refusal, BuiltFighter] = {
        val fighter = Fighter(request.strength, request.speed, request.agility, request.workrate, request.chin)
        for {
            name <- Option(request.name.trim).filter(_.nonEmpty).toRight(Refusal.Invalid("give your fighter a name"))
            valid <- Fighter.validate(fighter).left.map(Refusal.Invalid(_))
            url <- matchmakerUrl.toRight(
              Refusal.Unavailable("this engine has no matchmaker to register fighters with")
            )
            characterId <- register(
              url,
              RegisterCharacterRequest(name, request.description.trim, cognitoId, Fighter.toState(valid))
            )
        } yield BuiltFighter(
          characterId,
          name,
          FighterView(valid.strength, valid.speed, valid.agility, valid.workrate, valid.chin)
        )
    }

    private def register(url: String, request: RegisterCharacterRequest): Either[Refusal, Long] =
        try Right(matchmaker.registerCharacter(url, request))
        catch {
            // Matchmaker has no player for this sign-in: the one refusal that is the player's to fix.
            case MatchmakerRefusal(404, _) =>
                Left(
                  Refusal.Invalid(
                    "matchmaker does not know you yet: sign in to matchmaker once to register, then build your fighter"
                  )
                )
            // Anything else it refuses is how this engine is set up, not anything the player did.
            case e: MatchmakerRefusal =>
                Log.failure(e, "registering a fighter")
                Left(Refusal.Unavailable(s"matchmaker would not take the fighter: ${e.reason}"))
            case NonFatal(e) =>
                Log.failure(e, "registering a fighter")
                Left(Refusal.Unavailable("your fighter could not be registered with matchmaker; please try again"))
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
