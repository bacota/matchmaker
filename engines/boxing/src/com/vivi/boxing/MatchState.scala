package com.vivi.boxing

import scala.util.control.NonFatal
import upickle.default.{ReadWriter, macroRW}
import java.time.Instant
import com.vivi.engine.{Game, MatchLike, Outcome, SeatLike, TurnLike}

/** One corner of a bout.
  *
  * `cognitoId` is who may fight from it — the subject the player signs in as. `participantId` is matchmaker's key for
  * the seat and is what every callback quotes back. `characterId` is the fighter, which is a matchmaker character, and
  * `fighter` its characteristics: `None` until the player has built it, which a fighter's first bout is where they do.
  */
case class Corner(side: Side, cognitoId: String, participantId: Long, characterId: Long, fighter: Option[Fighter])
    extends SeatLike

/** One corner's plan for one round: how the workrate was spent, when it was submitted, and when that player's clock
  * started for it — the moment the round began, for both corners, since neither waits for the other.
  */
case class Plan(participantId: Long, round: Int, allocation: Allocation, takenAt: Instant, startedAt: Instant)
    extends TurnLike

/** A round both corners have planned, and therefore resolved. */
case class Round(
    number: Int,
    red: Plan,
    blue: Plan,
    redEffective: Effective,
    blueEffective: Effective,
    outcome: RoundOutcome
) {

    /** When the round resolved, which is when the second plan came in — and when the next round began. */
    def resolvedAt: Instant = if (red.takenAt.isAfter(blue.takenAt)) red.takenAt else blue.takenAt
}

/** A bout in progress, and everything needed to answer for it or to call matchmaker back.
  *
  * What is stored is the corners and the plans; the rounds are derived from them, since a round's result is a function
  * of the two fighters and the two plans and storing it beside them would be a second copy that could disagree.
  *
  * Like rock-paper-scissors, and unlike tic-tac-toe, both corners move at once: each round, both players plan, neither
  * sees the other's plan, and the round resolves on whichever plan arrives second.
  */
case class Bout(
    matchId: String,
    corners: List[Corner],
    scheduledRounds: Int,
    plans: List[Plan],
    isPublic: Boolean,
    completed: Boolean,
    createdAt: Instant,
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    /** Where matchmaker's character routes are — `PUT {this}/characters/{id}/state` is how a fighter built here is
      * kept. `None` when matchmaker sent no callback urls to derive it from.
      */
    matchmakerUrl: Option[String]
) extends MatchLike {

    def cornerOf(side: Side): Option[Corner] = corners.find(_.side == side)

    def cornerFor(cognitoId: String): Option[Corner] = corners.find(_.cognitoId == cognitoId)

    def planOf(corner: Corner, round: Int): Option[Plan] =
        plans.find(p => p.participantId == corner.participantId && p.round == round)

    /** Every round both corners have planned, in order. */
    def rounds: List[Round] =
        (for {
            red <- cornerOf(Side.Red).toList
            blue <- cornerOf(Side.Blue).toList
            redFighter <- red.fighter.toList
            blueFighter <- blue.fighter.toList
            number <- 1 to scheduledRounds
            redPlan <- planOf(red, number).toList
            bluePlan <- planOf(blue, number).toList
        } yield {
            val redEffective = Effective.of(redFighter, redPlan.allocation)
            val blueEffective = Effective.of(blueFighter, bluePlan.allocation)
            Round(number, redPlan, bluePlan, redEffective, blueEffective, Rules.resolve(redEffective, blueEffective))
        }).sortBy(_.number)

    def knockout: Option[Round] = rounds.find(_.outcome.decision == Decision.Knockout)

    /** Over on a knockout, or once every scheduled round has been fought. */
    def isOver: Boolean = knockout.isDefined || rounds.size >= scheduledRounds

    /** The round being planned now. Past the last one once the bout is over. */
    def currentRound: Int = rounds.size + 1

    /** When the current round began: the bout's creation for round one, and the previous round's resolution after. */
    def roundStartedAt: Instant = rounds.lastOption.map(_.resolvedAt).getOrElse(createdAt)

    /** The corners still to plan the current round — both at the start of every round, and none once the bout is over.
      *
      * A corner whose fighter is not built yet is pending too: building it is the first thing that player has to do,
      * and it is done on their own clock.
      */
    def pending: List[Corner] = if (isOver) Nil else corners.filterNot(c => planOf(c, currentRound).isDefined)

    /** Points on the cards so far. A knockout round is not scored. */
    def points(side: Side): Int = rounds.flatMap(_.outcome.score).map(_.of(side)).sum

    def knockdownsScored(side: Side): Int =
        rounds.count(r => r.outcome.decision == Decision.Knockdown && r.outcome.winner.contains(side))

    /** The winning corner once the bout is over: whoever scored the knockout, or failing one whoever is ahead on
      * points. `None` while the bout goes on, and `None` for a draw on points, which [[isDraw]] tells apart.
      */
    def winner: Option[Corner] =
        if (!isOver) None
        else
            knockout.flatMap(_.outcome.winner) match {
                case Some(side) => cornerOf(side)
                case None =>
                    val (red, blue) = (points(Side.Red), points(Side.Blue))
                    if (red > blue) cornerOf(Side.Red) else if (blue > red) cornerOf(Side.Blue) else None
            }

    def isDraw: Boolean = isOver && winner.isEmpty

    /** How a finished bout was won — "knockout" or "points" — as matchmaker records it and as the page says it. */
    def method: Option[String] = Option.when(isOver)(if (knockout.isDefined) "knockout" else "points")

    def outcomeFor(corner: Corner): Outcome =
        winner match {
            case Some(w) if w.participantId == corner.participantId => Outcome.Win
            case Some(_)                                            => Outcome.Loss
            case None                                               => Outcome.Draw
        }
}

/** Boxing as matchmaker sees it: rounds that are each simultaneous, one after another.
  *
  * Both corners are pending from the moment a round begins, either may plan first, and the round resolves on the second
  * plan. Their clocks start when the round does — the bout's creation for round one, and the moment the round before it
  * resolved after that — however late either of them plans. So the first plan of a round names nobody, and the plan
  * that resolves one names both corners, the mover included, because that is a new round starting.
  */
object Bout extends Game[Bout, Corner, Plan] {

    /** How many rounds a bout is scheduled for, when nothing says otherwise. */
    val DefaultRounds = 10
    val MinRounds = 3
    val MaxRounds = 25

    /** Seats the fighters, honouring the roles matchmaker sent when they name the two corners.
      *
      * Every corner must carry a character: this is a character game, and the fighter *is* the character. A seat
      * without one means the game was registered in matchmaker as a plain game, which is a mistake to report at the
      * start rather than a bout to fight with nobody in one corner.
      */
    def seat(players: List[Protocol.EnginePlayer]): Either[String, List[Corner]] =
        if (players.sizeIs != 2) Left(s"boxing is a two-fighter game; ${players.size} player(s) were sent")
        else if (players.map(_.cognitoId).distinct.sizeIs != 2)
            // A corner is found by the caller's subject, so one player holding both would be one person
            // seeing both plans.
            Left("the two corners must belong to two different players")
        else if (players.exists(_.characterId.isEmpty))
            Left("every corner needs a fighter; register boxing in matchmaker as a character game")
        else {
            val requested = players.map(p => p.role.flatMap(Side.parse))
            val sides =
                if (requested.flatten.distinct.sizeIs == 2) requested.map(_.get)
                else List(Side.Red, Side.Blue)
            Right(players.zip(sides).map { (p, side) =>
                Corner(
                  side,
                  p.cognitoId,
                  p.participantId,
                  p.characterId.get,
                  p.characterState.flatMap(Fighter.fromState)
                )
            })
        }

    /** How many rounds this bout is scheduled for: `rounds` in the challenge's settings if it names one, else the
      * game's `rounds` parameter, else [[DefaultRounds]].
      *
      * Settings first because they are the challenge's own. Matchmaker's challenge form stores the challenger's pick
      * there and also sends it as the parameter, so the two agree; the parameter alone is the game's default.
      */
    def scheduledRounds(request: Protocol.CreateGameRequest): Either[String, Int] = {
        val fromSettings =
            try
                ujson.read(request.settings).obj.get("rounds").map {
                    case ujson.Num(n) => n.toInt.toString
                    case other        => other.str
                }
            catch { case NonFatal(_) => None }
        val fromParameters = request.parameters.collectFirst { case (k, v) if k.trim.equalsIgnoreCase("rounds") => v }

        fromSettings.orElse(fromParameters).map(_.trim).filter(_.nonEmpty) match {
            case None => Right(DefaultRounds)
            case Some(raw) =>
                raw.toIntOption
                    .filter(n => n >= MinRounds && n <= MaxRounds)
                    .toRight(s"a bout is scheduled for $MinRounds to $MaxRounds rounds; '$raw' is not that")
        }
    }

    /** Matchmaker's base url, from the move callback it sent: the callbacks are `{base}/games/{g}/matches/{m}/moves`,
      * and the character routes hang off the same base.
      */
    def matchmakerUrlOf(moveCallbackUrl: Option[String]): Option[String] =
        moveCallbackUrl.flatMap { url =>
            val at = url.lastIndexOf("/games/")
            Option.when(at > 0 && url.endsWith("/moves"))(url.substring(0, at))
        }

    override def seatName: String = "corner"

    def seats(m: Bout): List[Corner] = m.corners

    def isOver(m: Bout): Boolean = m.isOver

    def markCompleted(m: Bout): Bout = m.copy(completed = true)

    def pending(m: Bout): List[Corner] = m.pending

    def clockStartedAt(m: Bout): Instant = m.roundStartedAt

    def turns(m: Bout): List[Plan] = m.plans

    def sequence(m: Bout): Long = m.plans.size.toLong

    def outcome(m: Bout, corner: Corner): Outcome = m.outcomeFor(corner)

    /** What a record of a fight would carry: how it ended and when, and each corner's points and knockdowns. */
    def scores(m: Bout, corner: Corner): Map[String, ujson.Value] =
        Map(
          "method" -> m.method.map(ujson.Str(_)).getOrElse(ujson.Null),
          "rounds" -> ujson.Num(m.rounds.size),
          "scheduledRounds" -> ujson.Num(m.scheduledRounds),
          "points" -> ujson.Num(m.points(corner.side)),
          "knockdowns" -> ujson.Num(m.knockdownsScored(corner.side)),
          "corner" -> ujson.Str(corner.side.toString)
        )

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, Bout] =
        for {
            corners <- seat(request.players)
            rounds <- scheduledRounds(request)
        } yield Bout(
          matchId = request.matchId,
          corners = corners,
          scheduledRounds = rounds,
          plans = Nil,
          isPublic = request.isPublic,
          completed = false,
          createdAt = now,
          moveCallbackUrl = request.moveCallbackUrl,
          resultsCallbackUrl = request.resultsCallbackUrl,
          matchmakerUrl = matchmakerUrlOf(request.moveCallbackUrl)
        )

    // Stored as JSON, as rock-paper-scissors stores its matches: the whole bout is one attribute.
    given ReadWriter[Side] = upickle.default.readwriter[String].bimap(_.toString, s => Side.valueOf(s))
    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Corner] = macroRW
    given ReadWriter[Plan] = macroRW
    given ReadWriter[Bout] = macroRW
}
