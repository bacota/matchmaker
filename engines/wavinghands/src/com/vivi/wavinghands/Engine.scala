package com.vivi.wavinghands

import com.vivi.engine.ErrorText
import java.time.Instant
import com.vivi.engine.{GameEngine, MatchStore, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Waving Hands: wizards duelling with spells made of gestures, every wizard's orders given in secret and carried out
  * at once. The exchanges with matchmaker are [[GameEngine]]'s; what is this game's own is what an order is, which is
  * [[orders]], and what a player is shown, which is [[stateOf]].
  *
  * A game without roles, for any number of wizards: matchmaker sends them in no particular order, and each turn every
  * wizard still fighting is pending until they have given their orders. The last order of a turn resolves it.
  */
class Engine(
    store: MatchStore[Duel],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: Duel => Unit = _ => ()
) {

    val core = GameEngine(Duel, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A wizard's orders for the turn being played. Given once: a wizard who could change their orders after seeing who
      * else had given theirs would have an edge nobody else has.
      */
    def move(
        matchId: String,
        cognitoId: String,
        request: MoveRequest
    ): Either[Refusal, MoveApplied[Duel, Wizard, Orders]] =
        core.applyMove(matchId, cognitoId) { (current, seat, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid(ErrorText.wavinghands.over))
                w <- current.battle
                    .wizard(seat.participantId)
                    .filter(_.isActive)
                    .toRight(Refusal.Invalid(ErrorText.wavinghands.out))
                _ <- Either.cond(
                  !current.hasOrdered(seat),
                  (),
                  Refusal.Invalid(ErrorText.wavinghands.alreadyOrdered)
                )
                order <- Engine
                    .orders(current, w, request, at)
                    .left
                    .map(Refusal.Invalid(_))
            } yield {
                val ordered = current.current :+ order
                val stillWaiting =
                    current.wizards.exists(x =>
                        current.battle.wizard(x.participantId).exists(_.isActive) &&
                            !ordered.exists(_.participantId == x.participantId)
                    )
                // The last orders of a turn play it.
                val next =
                    if (stillWaiting) current.copy(current = ordered)
                    else current.copy(turns = current.turns :+ ordered, current = Nil)
                (next, order)
            }
        }

    /** The state a play page renders. `seat` is the viewer's own, absent on the public board. The orders already given
      * for the turn being played are never in it, only that they have been given.
      */
    def stateOf(m: Duel, seat: Option[Wizard]): StateResponse = {
        val battle = m.battle
        val names = m.wizards.map(w => w.participantId -> m.nameOf(w)).toMap
        val you = seat.flatMap(s => battle.wizard(s.participantId))
        StateResponse(
          matchId = m.matchId,
          turn = battle.turnsPlayed + 1,
          you = seat.map(_.participantId.toString),
          isCompleted = m.isOver,
          isDrawn = m.isDrawn,
          winners = m.winners.map(_.participantId),
          waitingFor = m.pending.map(_.participantId),
          wizards = m.wizards.flatMap(x => battle.wizard(x.participantId).map(w => Engine.wizardView(m, w))),
          monsters = battle.monsters.map(x =>
              MonsterView(
                id = x.id,
                kind = x.kind,
                controller = x.controller,
                damage = x.damage,
                strength = x.strength,
                isDead = x.isDead
              )
          ),
          reports = battle.reports.map(r => ReportView(r.turn, r.events)).reverse,
          yourTurn = you.filter(_ => !m.isOver).filter(_.isActive).map(w => Engine.yourTurn(m, w, names)),
          spells = Spells.all.map(s => SpellView(s.name, s.series.map(_.notation), Spells.about.getOrElse(s.id, ""))),
          clock = core.clockView(m)
        )
    }
}

object Engine {

    /** The orders a request makes, checked against what the wizard may do this turn, or why it cannot be carried out.
      *
      * A hand that is forced — by amnesia or paralysis — makes the forced gesture whatever the request says, since the
      * play page offers nothing else. Everything else must be something the wizard can do: a frightened wizard cannot
      * gesture what fear forbids, a wizard has one knife, and every target must be there.
      */
    def orders(m: Duel, w: WizardState, request: MoveRequest, at: Instant): Either[String, Orders] = {
        val battle = m.battle
        def gesture(hand: Hand, raw: String): Either[String, Gesture] =
            Rules.forced(w, hand) match {
                case Some(g) => Right(g)
                case None =>
                    Gesture.parse(raw) match {
                        case None => Left(ErrorText.wavinghands.notAGesture(raw))
                        case Some(g) if !Rules.allowed(w, hand).contains(g) =>
                            Left(ErrorText.wavinghands.afraid(gesture = g.label, hand = hand.code))
                        case Some(g) => Right(g)
                    }
            }
        def target(raw: String): Either[String, Target] =
            Target.parse(raw) match {
                case Some(t @ Target.Wizard(id)) if battle.wizard(id).exists(_.isActive)  => Right(t)
                case Some(t @ Target.Monster(id)) if battle.monster(id).exists(!_.isDead) => Right(t)
                case Some(Target.Nobody)                                                  => Right(Target.Nobody)
                case _ => Left(ErrorText.wavinghands.noTarget)
            }
        def optional(raw: Option[String]): Either[String, Option[Target]] =
            raw.map(_.trim).filter(_.nonEmpty) match {
                case None    => Right(None)
                case Some(r) => target(r).map(Some(_))
            }

        for {
            left <- gesture(Hand.Left, request.left)
            right <- gesture(Hand.Right, request.right)
            _ <- Either.cond(
              !(left == Gesture.Stab && right == Gesture.Stab),
              (),
              ErrorText.wavinghands.oneKnife
            )
            options = Rules.castingsFor(w, left, right)
            casting = request.casting.flatMap(k => options.find(_.key == k)).getOrElse(Rules.defaultCasting(options))
            casts <- casting.spells.toList.sortBy(_._1.ordinal).foldLeft[Either[String, List[CastOrder]]](Right(Nil)) {
                case (acc, (slot, c)) =>
                    for {
                        done <- acc
                        aimed <- optional(request.targets.get(slot.code))
                        summonsTarget <- optional(request.summons.get(slot.code))
                    } yield done :+ CastOrder(
                      slot = slot,
                      spell = c.spell.id,
                      target = aimed.getOrElse(Rules.defaultTarget(battle, w.participantId, c.spell)),
                      series = c.series.notation,
                      hand = Option.when(c.spell == Spells.Paralysis)(
                        request.hands.get(slot.code).flatMap(Hand.parse).getOrElse(Hand.Left)
                      ),
                      monsterTarget = Option.when(c.spell.school == School.Summons)(summonsTarget).flatten
                    )
            }
            knife = Hand.values.find(h => (if (h == Hand.Left) left else right) == Gesture.Stab)
            stab <- knife match {
                case None => Right(None)
                case Some(hand) =>
                    optional(request.stab).flatMap { aimed =>
                        aimed.orElse(Rules.soleOpponent(battle, w.participantId)) match {
                            case Some(Target.Wizard(id)) if id == w.participantId =>
                                Left(ErrorText.wavinghands.selfStab)
                            case Some(Target.Nobody) | None => Left(ErrorText.wavinghands.stabWhom)
                            case Some(t)                    => Right(Some(StabOrder(hand, t)))
                        }
                    }
            }
            monsters <- battle.monsters
                .filter(x => !x.isDead && x.controller == w.participantId)
                .foldLeft[Either[String, Map[Int, Target]]](Right(Map.empty)) { (acc, x) =>
                    for {
                        done <- acc
                        aimed <- optional(request.monsters.get(x.id.toString))
                    } yield aimed.fold(done)(t => done + (x.id -> t))
                }
        } yield Orders(
          participantId = w.participantId,
          left = left,
          right = right,
          casts = casts,
          stab = stab,
          monsters = monsters,
          takenAt = at,
          startedAt =
              TurnClock.turnStart(clock = m.clock, participantId = w.participantId, turnStartedAt = m.turnStartedAt)
        )
    }

    def wizardView(m: Duel, w: WizardState): WizardView = {
        val turn = m.battle.turnsPlayed + 1
        val effects = List(
          Option.when(w.protectedThrough.exists(_ >= turn))(
            s"protected from evil through turn ${w.protectedThrough.get}"
          ),
          Option.when(w.isResistantToHeat)("resists heat"),
          Option.when(w.hasAmnesia)("amnesia: repeats last turn's gestures"),
          Option.when(w.hasFear)("afraid: no clap, digit, fingers or snap"),
          w.paralysed.map(h => s"${h.code} hand paralysed")
        ).flatten
        WizardView(
          participantId = w.participantId,
          name = w.name,
          damage = w.damage,
          isDead = w.isDead,
          isSurrendered = w.isSurrendered,
          hasOrdered = m.wizard(w.participantId).exists(m.hasOrdered),
          effects = effects,
          left = w.history.map(_._1.code).toList,
          right = w.history.map(_._2.code).toList
        )
    }

    def yourTurn(m: Duel, w: WizardState, names: Map[Long, String]): YourTurn = {
        val battle = m.battle
        val left = Rules.allowed(w, Hand.Left)
        val right = Rules.allowed(w, Hand.Right)
        val notes = List(
          Option.when(w.hasAmnesia)("Amnesia: both hands repeat last turn's gestures."),
          w.paralysed.map(h => s"Your ${h.code} hand is paralysed."),
          Option.when(w.hasFear)("Fear: you cannot clap, point, wiggle or snap.")
        ).flatten
        val castings = (for {
            l <- left
            r <- right
            if !(l == Gesture.Stab && r == Gesture.Stab)
        } yield s"${l.code},${r.code}" -> Rules
            .castingsFor(w, l, r)
            .map(c =>
                CastingView(
                  key = c.key,
                  spells = c.spells.toList.sortBy(_._1.ordinal).map { (slot, cand) =>
                      CastView(
                        slot = slot.code,
                        spell = cand.spell.id,
                        name = cand.spell.name,
                        defaultTarget = Rules.defaultTarget(battle, w.participantId, cand.spell).code,
                        isParalysis = cand.spell == Spells.Paralysis,
                        isSummons = cand.spell.school == School.Summons
                      )
                  }
                )
            )).toMap
        val targets =
            m.wizards.flatMap(x =>
                battle
                    .wizard(x.participantId)
                    .filter(_.isActive)
                    .map(b =>
                        TargetView(
                          Target.Wizard(x.participantId).code,
                          if (x.participantId == w.participantId) s"${names(x.participantId)} (you)"
                          else names(x.participantId)
                        )
                    )
            ) ++ battle.monsters
                .filterNot(_.isDead)
                .map(x =>
                    TargetView(
                      Target.Monster(x.id).code,
                      s"${names.getOrElse(x.controller, "someone")}'s ${x.kind} (#${x.id})"
                    )
                ) :+ TargetView(Target.Nobody.code, "nobody")
        YourTurn(
          isOrdered = m.wizard(w.participantId).exists(m.hasOrdered),
          left = left.map(_.code),
          right = right.map(_.code),
          notes = notes,
          castings = castings,
          targets = targets,
          monsters = battle.monsters.filter(x => !x.isDead && x.controller == w.participantId).map(_.id),
          defaultEnemy = Rules.soleOpponent(battle, w.participantId).fold("")(_.code)
        )
    }
}
