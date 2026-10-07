package com.vivi.engine

import java.time.{Duration, Instant}
import upickle.default.{ReadWriter, macroRW}
import Protocol.{CreateGameRequest, EnginePlayer, RoleChoice, SeatRole}

/** One role chosen: by whom, which, when the choice was made, and when the chooser's clock started for it — the choice
  * before, or the match's creation for the first. A turn like any other, which is how its time is charged.
  */
case class RoleChosen(participantId: Long, role: String, takenAt: Instant, startedAt: Instant) extends TurnLike

/** A match whose players are still choosing their roles ([[Protocol.RoleChoice]]), kept apart from the game's own
  * matches until the game can begin.
  *
  * Nothing about the game exists yet: a game is created from a request whose every seat has a role, and until the
  * choosing is done there is no such request. So this keeps the request matchmaker sent, the choices made against it,
  * and — for a live match — the clock they are made on. Once the last role is settled, [[GameEngine]] creates the game
  * from [[roled]] and marks this `finished`; it is kept beside the game after that, since the choices are still the
  * match's first turns, and what matchmaker is told about sequences, turns and roles includes them.
  *
  * Stored in its own record rather than in the game's match: every game stores its match as JSON matches already stored
  * must keep reading as, and this way none of them changes. See [[GameEngine]].
  *
  * A match can also end here, before any game exists: a live chooser whose clock runs out, or one who concedes. Then
  * `finished` is set with no game behind it, and the outcome is [[outcomeOf]].
  *
  * @param createdAt
  *   when matchmaker created the match: the first chooser's clock starts then
  * @param autoAssigned
  *   the last seat's role, when it was given the one role left rather than choosing it — set when the choosing ends
  */
case class RoleChoosing(
    matchId: String,
    request: CreateGameRequest,
    createdAt: Instant,
    choices: List[RoleChosen] = Nil,
    clock: Option[TurnClock] = None,
    conceded: Option[Long] = None,
    finished: Boolean = false,
    autoAssigned: Option[SeatRole] = None
) extends HasMatchId {

    private def choice: RoleChoice = request.roleChoice.getOrElse(RoleChoice(Nil, Nil))

    /** The seats choosing, in the order they choose. */
    def order: List[Long] = choice.order

    /** What players call a role. */
    def displayName(role: String): String = choice.displayNames.getOrElse(role, role)

    /** The roles the request settled: seats that came with one. */
    def assigned: Map[Long, String] = request.players.flatMap(p => p.role.map(p.participantId -> _)).toMap

    def chosen: Map[Long, String] = choices.map(c => c.participantId -> c.role).toMap

    /** Every seat's role so far. */
    def roles: Map[Long, String] = assigned ++ chosen ++ autoAssigned.map(a => a.participantId -> a.role)

    /** The roles nobody has, in the game's order. */
    def free: List[String] = {
        val taken = roles.values.toSet
        choice.roles.filterNot(taken)
    }

    /** The choosers who have not chosen, in order. */
    def waiting: List[Long] = order.filterNot(roles.contains)

    /** The last chooser's role, when only one role is left for them: nothing to choose between. */
    def lastRole: Option[SeatRole] =
        (waiting, free) match {
            case (List(last), List(role)) => Some(SeatRole(last, role))
            case _                        => None
        }

    /** Whether every seat has a role, or the last can be given the one left. */
    def settled: Boolean = waiting.isEmpty || lastRole.isDefined

    /** Whether the match ended here, before any game: a clock run out, or a concession. */
    def ended: Boolean = ranOut || conceded.isDefined

    def ranOut: Boolean = clock.exists(_.ranOut)

    /** Whose choice it is, while there is one to make. */
    def chooser: Option[Long] = if (finished || ended || settled) None else waiting.headOption

    /** When the chooser's turn began: the last choice, or the match's creation. */
    def turnStartedAt: Instant = choices.lastOption.map(_.takenAt).getOrElse(createdAt)

    /** The request the game is created from: every seat roled. Only meaningful once [[settled]]. */
    def roled: CreateGameRequest =
        request.copy(
          players = request.players.map(p => p.copy(role = p.role.orElse(withLast.get(p.participantId)))),
          roleChoice = None
        )

    private def withLast: Map[Long, String] = roles ++ lastRole.map(r => r.participantId -> r.role)

    /** The roles to report, seat by seat: every seat whose role is known. */
    def seatRoles: List[SeatRole] =
        request.players.flatMap(p => roles.get(p.participantId).map(SeatRole(p.participantId, _)))

    /** How the match came out for a seat, when it ended here: whoever ran out of time or conceded lost, and everybody
      * else won.
      */
    def outcomeOf(participantId: Long): Option[Outcome] =
        clock
            .flatMap(_.outcomeOf(participantId))
            .orElse(conceded.map(c => if (c == participantId) Outcome.Loss else Outcome.Win))

    /** What a seat spent choosing, for its chess clock in the game to carry on from. */
    def spentBy(participantId: Long): Duration =
        choices
            .filter(_.participantId == participantId)
            .map(c => Duration.between(c.startedAt, c.takenAt))
            .filterNot(_.isNegative)
            .foldLeft(Duration.ZERO)(_.plus(_))

    def playerOf(participantId: Long): Option[EnginePlayer] = request.players.find(_.participantId == participantId)

    /** What a seat is shown as: its character, its player, or which seat it is. */
    def nameOf(participantId: Long): String =
        playerOf(participantId)
            .flatMap(p => p.characterName.orElse(p.nickname))
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse(s"seat ${request.players.indexWhere(_.participantId == participantId) + 1}")
}

object RoleChoosing {

    /** A new choosing match from matchmaker's request, or why it cannot be played. `clock` is the live match's clock,
      * when it is one.
      */
    def start(request: CreateGameRequest, now: Instant, clock: Option[TurnClock]): Either[String, RoleChoosing] =
        request.roleChoice match {
            case None => Left("the request has no roles to choose")
            case Some(choice) =>
                val unroled = request.players.filter(_.role.isEmpty).map(_.participantId)
                val assigned = request.players.flatMap(_.role)
                val made = RoleChoosing(request.matchId, request, now, clock = clock)
                if (choice.order.isEmpty) Left("roleChoice names nobody to choose")
                else if (choice.order.distinct.sizeIs != choice.order.size)
                    Left("roleChoice names a seat to choose more than once")
                else if (choice.order.toSet != unroled.toSet)
                    Left("roleChoice must name exactly the seats sent without a role")
                else if (choice.roles.distinct.sizeIs != choice.roles.size || choice.roles.exists(_.trim.isEmpty))
                    Left("roleChoice's roles must be distinct, and none of them blank")
                else if (assigned.distinct.sizeIs != assigned.size)
                    Left("two seats were sent with the same role")
                else if (!assigned.forall(choice.roles.contains))
                    Left("a seat was sent with a role roleChoice does not list")
                else if (made.free.sizeIs < choice.order.size)
                    Left(s"${choice.order.size} seats are to choose, but only ${made.free.size} roles are free")
                else Right(made)
        }

    /** The request with every chooser given a free role, in order: a stand-in for the game the choices will make, which
      * is how a request the game would refuse is refused at once rather than after everybody has chosen.
      */
    def provisional(request: CreateGameRequest): CreateGameRequest = {
        val choosing = RoleChoosing(request.matchId, request, Instant.EPOCH)
        val offered = choosing.waiting.zip(choosing.free).toMap
        request.copy(
          players = request.players.map(p => p.copy(role = p.role.orElse(offered.get(p.participantId)))),
          roleChoice = None
        )
    }

    import Protocol.given
    import TurnClock.given
    given ReadWriter[RoleChosen] = macroRW
    given ReadWriter[RoleChoosing] = macroRW
}

/** What the choosing page shows, and what its state route answers with: the free roles, who has chosen what, whose turn
  * it is, and the chooser's clock.
  *
  * `choosing` is always true: it is how the page tells this answer from the game's own state, which it reloads into
  * once the roles are settled. `you` is the viewer's seat, absent on the public board. `ended` is how the match ended,
  * when it ended before the game began; `done` that the game has begun.
  */
case class ChoosingView(
    choosing: Boolean,
    you: Option[Long],
    chooser: Option[Long],
    free: List[RoleOffer],
    seats: List[ChoosingSeat],
    done: Boolean,
    ended: Option[String],
    clock: Option[ClockView],
    canConcede: Boolean
)

/** A free role: its name, which is what a choice sends, and what players call it. */
case class RoleOffer(role: String, displayName: String)

/** A seat, by what it is shown as, with its role once it has one. */
case class ChoosingSeat(participantId: Long, name: String, role: Option[String], waiting: Boolean)

/** A choice, as the page posts it: a role, or a concession. */
case class ChooseRequest(role: Option[String] = None, concede: Boolean = false)

object ChoosingView {
    import ClockView.given
    given ReadWriter[RoleOffer] = macroRW
    given ReadWriter[ChoosingSeat] = macroRW
    given ReadWriter[ChoosingView] = macroRW
    given ReadWriter[ChooseRequest] = macroRW
}
