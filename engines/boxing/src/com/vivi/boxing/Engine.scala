package com.vivi.boxing

import java.time.Instant
import scala.util.control.NonFatal
import Protocol._

/** Why a request was refused. Transport-independent so that the local server and the Lambda handler map it to a status
  * code the same way.
  */
enum Refusal(val status: Int, val message: String) {
    case NotFound(what: String) extends Refusal(404, what)
    case NotYours(what: String) extends Refusal(403, what)
    case Invalid(what: String) extends Refusal(400, what)

    /** Something behind the engine — matchmaker — did not answer. The request was fine, and may be repeated. */
    case Unavailable(what: String) extends Refusal(502, what)
}

/** What a successful plan produced, for the caller to answer with and for the callbacks below.
  *
  * `resolved` is the round this plan completed, if it was the second of the two; `finished` means that round ended the
  * bout, by a knockout or by being the last.
  */
case class PlanApplied(state: Bout, moved: Corner, plan: Plan, resolved: Option[Round], finished: Boolean)

/** The game itself: the four exchanges of `interaction-design.txt` from the engine's side, and the one this game adds —
  * building a fighter.
  *
  * Knows nothing about HTTP — [[Routes]] is what turns requests into these calls — and nothing about where bouts are
  * kept or how matchmaker is reached, which is what lets a whole bout be fought in a test with a map and a recorder.
  *
  * Each round is simultaneous, as a rock-paper-scissors throw is: both corners are pending from the moment the round
  * begins, either may plan first, neither sees the other's plan until both are in, and the round resolves on the
  * second. Then the next round begins, with both corners pending again.
  *
  * @param baseUrl
  *   the engine's own public base url, which is what the urls handed back to matchmaker in step 1 are built from.
  * @param announce
  *   called once with each new bout, which is how the local server prints the play url and who is in which corner.
  */
class Engine(
    store: MatchStore,
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: Bout => Unit = _ => ()
) {

    private val base = baseUrl.stripSuffix("/")

    /** Step 1: create a game. One play url serves both corners; the engine works out whose from who signed in. */
    def createGame(request: CreateGameRequest): Either[Refusal, CreateGameResponse] =
        Bout.create(request, now()) match {
            case Left(why) => Left(Refusal.Invalid(why))
            case Right(created) =>
                store.create(created)
                announce(created)
                Right(
                  CreateGameResponse(
                    statusUrl = s"$base/matches/${created.matchId}/status",
                    playUrl = s"$base/matches/${created.matchId}/play",
                    publicUrl = Option.when(created.isPublic)(s"$base/matches/${created.matchId}/board")
                  )
                )
        }

    def read(matchId: String): Either[Refusal, Bout] =
        store.get(matchId).toRight(Refusal.NotFound(s"no match '$matchId'"))

    /** The signed-in player's corner. A 403 rather than a 404: the caller is somebody, just not somebody in this bout.
      */
    def cornerOf(m: Bout, cognitoId: String): Either[Refusal, Corner] =
        m.cornerFor(cognitoId).toRight(Refusal.NotYours(s"'$cognitoId' has no corner in match '${m.matchId}'"))

    /** Step 4: what matchmaker asks for when a participant hits refresh.
      *
      * Every corner yet to plan the current round is pending, and its clock started when the round did — the bout's
      * creation for round one, and the moment the round before it resolved after that.
      */
    def status(matchId: String, since: Option[Instant] = None): Either[Refusal, GameStatusResponse] =
        read(matchId).map { m =>
            val over = m.isOver
            val pending = m.pending.map(_.participantId).toSet
            GameStatusResponse(
              completed = over,
              participants = m.corners.map { c =>
                  EngineParticipantStatus(
                    participantId = c.participantId,
                    pending = pending.contains(c.participantId),
                    completed = over,
                    prevMoveAt = Some(m.roundStartedAt)
                  )
              },
              // Strictly after `since`, so the turn matchmaker already has is not sent again.
              turns = m.plans
                  .filter(p => since.forall(at => p.takenAt.isAfter(at)))
                  .sortBy(_.takenAt)
                  .map(p => EngineTurn(p.participantId, p.takenAt, Some(p.startedAt)))
            )
        }

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
                corner <- cornerOf(m, cognitoId)
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
                try Right(matchmaker.saveFighter(url, corner.characterId, fighter))
                catch {
                    case NonFatal(e) =>
                        Log.failure(e, s"saving fighter ${corner.characterId}")
                        Left(Refusal.Unavailable("your fighter could not be saved to matchmaker; please try again"))
                }
        }

    /** A player's plan for the current round. Decided against the stored bout — atomically, since both corners planning
      * at the same moment is the ordinary case here — and then, having committed, reported to matchmaker.
      */
    def plan(matchId: String, cognitoId: String, allocation: Allocation): Either[Refusal, PlanApplied] = {
        val at = now()

        val outcome = store.modify(matchId) { current =>
            val decision =
                for {
                    corner <- cornerOf(current, cognitoId)
                    _ <- Either.cond(!current.isOver, (), Refusal.Invalid("this bout is already over"))
                    fighter <- corner.fighter.toRight(
                      Refusal.Invalid("build your fighter before planning a round")
                    )
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
                    val played = current.copy(plans = current.plans :+ record)
                    val resolved = played.rounds.find(_.number == round)
                    val finished = played.isOver
                    PlanApplied(played.copy(completed = finished), corner, record, resolved, finished)
                }

            decision match {
                case Right(applied) => (Some(applied.state), Right(applied))
                case Left(refusal)  => (None, Left(refusal))
            }
        }

        outcome.toRight(Refusal.NotFound(s"no match '$matchId'")).flatten.map { applied =>
            notify(applied)
            applied
        }
    }

    /** Steps 2 and 3. Every plan is reported; the plan that resolves a round starts the next one for both corners; and
      * the plan that ends the bout is followed by the results.
      */
    private def notify(applied: PlanApplied): Unit = {
        val m = applied.state
        val next =
            if (applied.resolved.isDefined && !applied.finished) m.corners.map(_.participantId)
            else Nil

        m.moveCallbackUrl.foreach { url =>
            matchmaker.recordMove(
              url,
              MoveNotification(
                participantId = applied.moved.participantId,
                next = next,
                takenAt = applied.plan.takenAt,
                startedAt = applied.plan.startedAt
              )
            )
        }

        if (applied.finished) m.resultsCallbackUrl.foreach(url => matchmaker.recordResults(url, resultsOf(m)))
    }

    /** How a finished bout was won, as matchmaker records it and as the page says it. */
    def methodOf(m: Bout): Option[String] =
        Option.when(m.isOver)(if (m.knockout.isDefined) "knockout" else "points")

    /** The finished bout as matchmaker records it: rank 1 for the winner and 2 for the loser, or 1 for both in a draw.
      *
      * The scores carry what a record of a fight would: how it ended and when, and each corner's points and knockdowns.
      */
    def resultsOf(m: Bout): MatchResults =
        MatchResults(
          m.corners.map { c =>
              val outcome = m.outcomeFor(c)
              ResultEntry(
                participantId = c.participantId,
                rank = if (outcome == Outcome.Loss) 2 else 1,
                scores = Map(
                  "outcome" -> ujson.Str(outcome.label),
                  "method" -> methodOf(m).map(ujson.Str(_)).getOrElse(ujson.Null),
                  "rounds" -> ujson.Num(m.rounds.size),
                  "scheduledRounds" -> ujson.Num(m.scheduledRounds),
                  "points" -> ujson.Num(m.points(c.side)),
                  "knockdowns" -> ujson.Num(m.knockdownsScored(c.side)),
                  "corner" -> ujson.Str(c.side.toString)
                ),
                isWinner = outcome == Outcome.Win
              )
          }
        )

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
          method = methodOf(m),
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
