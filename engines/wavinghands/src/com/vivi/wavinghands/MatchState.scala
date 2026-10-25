package com.vivi.wavinghands

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant
import com.vivi.engine.{Game, MatchLike, Outcome, ResultText, SeatLike, TurnClock}

/** A wizard's place in a duel. `cognitoId` is who may give its orders; `participantId` is matchmaker's key for it.
  * `nickname` is what the player is shown as, as matchmaker sent it.
  */
case class Wizard(cognitoId: String, participantId: Long, nickname: Option[String] = None) extends SeatLike

/** A duel: its wizards, and every order given in it.
  *
  * Nothing else is stored. The battle — every wizard's wounds and gestures, the monsters, what each turn did — is
  * [[Rules.replay]] of the orders, so it cannot disagree with them. `turns` are the turns played, each every active
  * wizard's orders for it; `current` are the orders given so far for the turn being played, kept hidden until the last
  * wizard has given theirs.
  */
case class Duel(
    matchId: String,
    wizards: List[Wizard],
    turns: List[List[Orders]],
    current: List[Orders],
    isPublic: Boolean,
    isCompleted: Boolean,
    createdAt: Instant,
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    clock: Option[TurnClock] = None,
    override val gameDisplayName: Option[String] = None,
    override val description: Option[String] = None,
    canTie: Boolean = true
) extends MatchLike {

    /** What a wizard is called: their nickname, or their place at the table. */
    def nameOf(w: Wizard): String =
        w.nickname.map(_.trim).filter(_.nonEmpty).getOrElse(s"Wizard ${wizards.indexOf(w) + 1}")

    lazy val battle: BattleState = Rules.replay(wizards.map(w => w.participantId -> nameOf(w)), turns)

    def wizardFor(cognitoId: String): Option[Wizard] = wizards.find(_.cognitoId == cognitoId)
    def wizard(participantId: Long): Option[Wizard] = wizards.find(_.participantId == participantId)

    def hasOrdered(w: Wizard): Boolean = current.exists(_.participantId == w.participantId)

    def hasRunOut: Boolean = clock.exists(_.hasRunOut)

    def isOver: Boolean = hasRunOut || battle.isOver

    /** Every wizard still fighting who has yet to give this turn's orders. */
    def pending: List[Wizard] =
        if (isOver) Nil
        else wizards.filter(w => battle.wizard(w.participantId).exists(_.isActive) && !hasOrdered(w))

    /** When the turn being played began: when the last one resolved, or the match was created. */
    def turnStartedAt: Instant = turns.lastOption.flatMap(_.map(_.takenAt).maxOption).getOrElse(createdAt)

    /** Where each wizard finished, once the duel is over. */
    lazy val ranks: Map[Long, Int] = Rules.ranks(battle)

    /** The wizards who won: everyone who finished first, unless that is more than one and the match may not be drawn —
      * then the least wounded of them, and failing that the first seated.
      */
    def winners: List[Wizard] =
        if (!isOver) Nil
        else if (hasRunOut) wizards.filter(w => clock.flatMap(_.outcomeOf(w.participantId)).contains(Outcome.Win))
        else {
            val first = wizards.filter(w => ranks.get(w.participantId).contains(1))
            if (first.sizeIs <= 1 || canTie) first
            else first.minByOption(w => battle.wizard(w.participantId).fold(Int.MaxValue)(_.damage)).toList
        }

    def isDrawn: Boolean = isOver && !hasRunOut && winners.sizeIs > 1
}

object Duel extends Game[Duel, Wizard, Orders] {

    override def seatName: String = "place in this duel"

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, Duel] =
        if (request.players.sizeIs < 2) Left(ErrorText.wavinghands.tooFewWizards(request.players.size.toString))
        else if (request.players.map(_.cognitoId).distinct.sizeIs != request.players.size)
            Left(ErrorText.wavinghands.samePlayer)
        else
            Right(
              Duel(
                matchId = request.matchId,
                // The order matchmaker sends them in, which is all a place at the table is: there are no roles.
                wizards = request.players.map(p => Wizard(p.cognitoId, p.participantId, p.nickname)),
                turns = Nil,
                current = Nil,
                isPublic = request.isPublic,
                isCompleted = false,
                createdAt = now,
                moveCallbackUrl = request.moveCallbackUrl,
                resultsCallbackUrl = request.resultsCallbackUrl,
                gameDisplayName = request.gameDisplayName,
                description = request.description,
                canTie = Protocol.canTie(request)
              )
            )

    def seats(m: Duel): List[Wizard] = m.wizards

    def isOver(m: Duel): Boolean = m.isOver

    def markCompleted(m: Duel): Duel = m.copy(isCompleted = true)

    def pending(m: Duel): List[Wizard] = m.pending

    def clockStartedAt(m: Duel): Instant = m.turnStartedAt

    def turns(m: Duel): List[Orders] = m.turns.flatten ++ m.current

    def sequence(m: Duel): Long = turns(m).size.toLong

    def outcome(m: Duel, seat: Wizard): Outcome =
        m.clock
            .flatMap(_.outcomeOf(seat.participantId))
            .getOrElse(
              if (m.winners.exists(_.participantId == seat.participantId))
                  (if (m.isDrawn) Outcome.Draw else Outcome.Win)
              else Outcome.Loss
            )

    /** Where the wizard finished: the winners first, then the others by how long they lasted. */
    override def rank(m: Duel, seat: Wizard): Option[Int] =
        Option.when(m.isOver && !m.hasRunOut) {
            if (m.winners.exists(_.participantId == seat.participantId)) 1
            else m.ranks.getOrElse(seat.participantId, m.wizards.size).max(2)
        }

    def scores(m: Duel, seat: Wizard): Map[String, ujson.Value] = {
        val w = m.battle.wizard(seat.participantId)
        Map(
          "damage" -> ujson.Num(w.fold(0)(_.damage)),
          "turns" -> ujson.Num(m.battle.turnsPlayed),
          // What a tournament's score tiebreak reads: the life a wizard had left, nothing for one who died.
          "score" -> ujson.Num(w.filterNot(_.isDead).fold(0)(x => Rules.Lethal - x.damage))
        )
    }

    override def summary(m: Duel): Option[String] =
        Option.when(m.isOver) {
            def who(w: Wizard) = ResultText.name(w.nickname, m.nameOf(w))
            val turns = m.battle.turnsPlayed
            val after = if (turns == 1) "after one turn" else s"after $turns turns"
            m.winners match {
                case _ if m.hasRunOut && m.winners.isEmpty => "Nobody gave their orders in time. Nobody wins."
                case w :: Nil if m.hasRunOut => s"${who(w)} wins: the others did not give their orders in time."
                case w :: Nil                => s"${who(w)} wins, $after."
                case Nil                     => s"Nobody wins, $after."
                case ws                      => s"${ws.map(who).mkString(" and ")} draw, $after."
            }
        }

    def clock(m: Duel): Option[TurnClock] = m.clock

    def withClock(m: Duel, clock: TurnClock): Duel = m.copy(clock = Some(clock))

    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Gesture] = upickle.default.readwriter[String].bimap(_.code, s => Gesture.parse(s).get)
    given ReadWriter[Hand] = upickle.default.readwriter[String].bimap(_.code, s => Hand.parse(s).get)
    given ReadWriter[Slot] = upickle.default.readwriter[String].bimap(_.code, s => Slot.parse(s).get)
    given ReadWriter[Target] = upickle.default.readwriter[String].bimap(_.code, s => Target.parse(s).get)
    given ReadWriter[CastOrder] = macroRW
    given ReadWriter[StabOrder] = macroRW
    given ReadWriter[Orders] = macroRW
    given ReadWriter[Wizard] = macroRW
    given ReadWriter[Duel] = macroRW
}
