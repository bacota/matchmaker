package com.vivi.wavinghands

import java.time.Instant

/** What a spell, a stab or a monster is aimed at: a wizard, by their seat; a monster, by its number; or nobody, which
  * sends a spell off harmlessly.
  */
enum Target {
    case Wizard(participantId: Long)
    case Monster(id: Int)
    case Nobody

    def code: String = this match {
        case Wizard(id)  => s"wizard:$id"
        case Monster(id) => s"monster:$id"
        case Nobody      => "nobody"
    }
}

object Target {
    def parse(s: String): Option[Target] =
        s.trim.split(":", 2).toList match {
            case "wizard" :: id :: Nil  => id.toLongOption.map(Wizard(_))
            case "monster" :: id :: Nil => id.toIntOption.map(Monster(_))
            case "nobody" :: Nil        => Some(Nobody)
            case _                      => None
        }
}

/** A spell a wizard's gestures complete this turn, and what they aim it at. `series` is which of the spell's series
  * completed it, which matters for the short lightning bolt, usable once. `hand` is the hand a paralysis freezes;
  * `monsterTarget` is what a summoned monster attacks on the turn it appears.
  */
case class CastOrder(
    slot: Slot,
    spell: String,
    target: Target,
    series: String = "",
    hand: Option[Hand] = None,
    monsterTarget: Option[Target] = None
)

/** A stab, with the hand that holds the knife and whom it is aimed at. */
case class StabOrder(hand: Hand, target: Target)

/** A wizard's orders for one turn, as the rules will carry them out: the gestures each hand actually makes — after any
  * amnesia or paralysis has had its say — the spells they complete and their targets, the stab, and an attack for each
  * of the wizard's monsters.
  *
  * This is also the turn record matchmaker hears about: when it was given, and when that wizard's clock started.
  */
case class Orders(
    participantId: Long,
    left: Gesture,
    right: Gesture,
    casts: List[CastOrder],
    stab: Option[StabOrder],
    monsters: Map[Int, Target],
    takenAt: Instant,
    startedAt: Instant
) extends com.vivi.engine.TurnLike {
    def gesture(hand: Hand): Gesture = if (hand == Hand.Left) left else right
}

/** A spell some hand's gestures would complete, and which of the spell's series did it. */
case class Candidate(spell: Spell, series: Series, slot: Slot)

/** One way to cast what a turn's gestures complete: a spell for each hand, or one spell taking both hands. `key` names
  * it on the wire.
  */
case class Casting(spells: Map[Slot, Candidate]) {
    def key: String =
        Slot.values.toList.flatMap(slot => spells.get(slot).map(c => s"${slot.code}=${c.spell.id}")).mkString(";")

    /** How many gestures the casting's spells are, which is how the default casting is chosen: the biggest. */
    def weight: Int = spells.values.map(_.series.length).sum
}

case class WizardState(
    participantId: Long,
    name: String,
    damage: Int = 0,
    isDead: Boolean = false,
    isSurrendered: Boolean = false,
    // The turn the wizard died or surrendered, for the order the losers finish in.
    eliminatedAt: Option[Int] = None,
    // Every turn's effective gestures, left and right.
    history: Vector[(Gesture, Gesture)] = Vector.empty,
    // The first turn whose gestures may still be part of a spell: an anti-spell moves it past every gesture made so far.
    usableFrom: Int = 0,
    // Effects on the turn to come, from enchantments that landed on the turn just played.
    hasAmnesia: Boolean = false,
    hasFear: Boolean = false,
    paralysed: Option[Hand] = None,
    // The last turn a protection from evil shields the wizard on.
    protectedThrough: Option[Int] = None,
    isResistantToHeat: Boolean = false,
    usedOnce: Set[String] = Set.empty
) {
    def isActive: Boolean = !isDead && !isSurrendered
}

case class MonsterState(
    id: Int,
    kind: String,
    strength: Int,
    controller: Long,
    damage: Int = 0,
    isDead: Boolean = false,
    lastTarget: Option[Target] = None,
    // Effects on the turn to come.
    hasAmnesia: Boolean = false,
    isParalysed: Boolean = false
)

/** What happened on one turn, in plain words, for the players to read. */
case class TurnReport(turn: Int, events: List[String])

/** The battle as the turns played so far leave it. */
case class BattleState(
    wizards: List[WizardState],
    monsters: List[MonsterState] = Nil,
    reports: List[TurnReport] = Nil,
    turnsPlayed: Int = 0
) {
    def wizard(id: Long): Option[WizardState] = wizards.find(_.participantId == id)
    def monster(id: Int): Option[MonsterState] = monsters.find(_.id == id)
    def active: List[WizardState] = wizards.filter(_.isActive)

    /** Over once at most one wizard is still fighting, or the turn limit is reached. */
    def isOver: Boolean = active.sizeIs <= 1 || turnsPlayed >= Rules.MaxTurns

    def nameOf(target: Target): String = target match {
        case Target.Wizard(id) => wizard(id).fold("someone")(_.name)
        case Target.Monster(id) =>
            monster(id).fold("a monster")(m => s"${wizard(m.controller).fold("a")(w => w.name + "'s")} ${m.kind}")
        case Target.Nobody => "nobody"
    }
}

/** The rules of a duel: which spells gestures complete, what a wizard may gesture, and what a turn does. */
object Rules {

    /** Each wizard can take fourteen points of damage and dies at fifteen. */
    val Lethal = 15

    /** A battle that has gone this many turns is ended, and the wizards still standing ranked by their wounds. */
    val MaxTurns = 100

    // ---- gestures -------------------------------------------------------------------------

    /** What a pair of written gestures actually is: a clap with one hand is nothing. */
    def effective(left: Gesture, right: Gesture): (Gesture, Gesture) =
        if (left == Gesture.C && right != Gesture.C) (Gesture.Nothing, right)
        else if (right == Gesture.C && left != Gesture.C) (left, Gesture.Nothing)
        else (left, right)

    /** What a hand must gesture this turn, if anything forces it: amnesia repeats last turn's gesture, and paralysis
      * holds a hand in last turn's position.
      */
    def forced(w: WizardState, hand: Hand): Option[Gesture] =
        w.history.lastOption.flatMap { (l, r) =>
            val last = if (hand == Hand.Left) l else r
            if (w.hasAmnesia) Some(last)
            else if (w.paralysed.contains(hand)) Some(last.paralysed)
            else None
        }

    /** The gestures a hand may choose this turn: whatever it is forced into, or anything a frightened wizard can still
      * do — no clap, digit, fingers or snap — or anything at all.
      */
    def allowed(w: WizardState, hand: Hand): List[Gesture] =
        forced(w, hand) match {
            case Some(g)           => List(g)
            case None if w.hasFear => Gesture.offered.filterNot(Set(Gesture.C, Gesture.D, Gesture.F, Gesture.S))
            case None              => Gesture.offered
        }

    // ---- spells ---------------------------------------------------------------------------

    /** Whether `hand`'s last gestures in `history` make `series`. */
    private def completes(history: Vector[(Gesture, Gesture)], usableFrom: Int, hand: Hand, series: Series): Boolean = {
        val start = history.size - series.length
        start >= usableFrom && series.steps.zipWithIndex.forall { (step, i) =>
            val (l, r) = history(start + i)
            val (mine, other) = if (hand == Hand.Left) (l, r) else (r, l)
            mine == step.gesture && (!step.isBoth || other == step.gesture)
        }
    }

    /** Every spell the wizard's gestures complete on the turn just added to `history`. A spell whose last gesture takes
      * both hands is one spell however many hands its series fit.
      */
    def candidates(history: Vector[(Gesture, Gesture)], usableFrom: Int, usedOnce: Set[String]): List[Candidate] = {
        val found = for {
            spell <- Spells.all
            series <- spell.series
            if !(series.isOncePerBattle && usedOnce(onceKey(spell, series)))
            hand <- Hand.values.toList
            if completes(history, usableFrom, hand, series)
        } yield Candidate(spell, series, if (series.isFinalBoth) Slot.Both else Slot.of(hand))
        found.distinctBy(c => (c.spell.id, c.slot))
    }

    def onceKey(spell: Spell, series: Series): String = s"${spell.id}:${series.notation}"

    /** The ways to cast what a turn's gestures complete, the default first. Each hand's last gesture makes one spell at
      * most, and a spell completed must be cast — so a casting uses every hand that completed something, and the choice
      * is only between spells competing for the same gesture.
      */
    def castings(found: List[Candidate]): List[Casting] = {
        val left = found.filter(_.slot == Slot.Left)
        val right = found.filter(_.slot == Slot.Right)
        val both = found.filter(_.slot == Slot.Both).map(c => Casting(Map(Slot.Both -> c)))
        val single =
            if (left.isEmpty && right.isEmpty) (if (both.isEmpty) List(Casting(Map.empty)) else Nil)
            else
                for {
                    l <- if (left.isEmpty) List(None) else left.map(Some(_))
                    r <- if (right.isEmpty) List(None) else right.map(Some(_))
                } yield Casting(l.map(Slot.Left -> _).toMap ++ r.map(Slot.Right -> _).toMap)
        (single ++ both).sortBy(-_.weight)
    }

    /** The castings a wizard could make with these gestures this turn. */
    def castingsFor(w: WizardState, left: Gesture, right: Gesture): List[Casting] = {
        val (l, r) = effective(left, right)
        castings(candidates(w.history :+ (l, r), w.usableFrom, w.usedOnce))
    }

    /** The casting that is made when the wizard names none, or names one these gestures cannot make: the biggest, which
      * [[castings]] puts first.
      */
    def defaultCasting(options: List[Casting]): Casting = options.head

    /** Whom a spell goes to when its caster names nobody: themselves, or their opponent when there is exactly one. */
    def defaultTarget(state: BattleState, caster: Long, spell: Spell): Target =
        spell.aim match {
            case Aim.Self  => Target.Wizard(caster)
            case Aim.Enemy => soleOpponent(state, caster).getOrElse(Target.Nobody)
        }

    def soleOpponent(state: BattleState, wizard: Long): Option[Target] =
        state.active.filterNot(_.participantId == wizard) match {
            case only :: Nil => Some(Target.Wizard(only.participantId))
            case _           => None
        }

    // ---- a turn ---------------------------------------------------------------------------

    /** The battle from its first turn, replaying every turn's orders. Pure: the same orders always make the same
      * battle, which is what lets a match be stored as nothing but its orders.
      */
    def replay(wizards: List[(Long, String)], turns: List[List[Orders]]): BattleState =
        turns.foldLeft(BattleState(wizards.map((id, name) => WizardState(participantId = id, name = name))))(play)

    /** One turn: every active wizard's orders, carried out at once.
      *
      * First the spells that act on one another are [[settle]]d over the whole turn. Then every spell that got through
      * is applied in [[Priority]] order by [[Effects.apply]], then the stabs and the monsters' attacks, each step
      * taking the turn as it stands to the turn with that step done. Last, [[endOfTurn]] writes what lasts into the
      * battle, and the turn's state is thrown away.
      */
    def play(state: BattleState, orders: List[Orders]): BattleState = {
        val ordersOf = orders.map(o => o.participantId -> o).toMap
        val (settled, landing) = settle(state, ordersOf)
        val applied = landing.sortBy(c => Effects.priority(c.spell).ordinal).foldLeft(settled)((t, c) => Effects(c, t))
        endOfTurn(attack(stab(applied, ordersOf), ordersOf), ordersOf)
    }

    /** The wizards still fighting who gave orders this turn. */
    private def acting(state: BattleState, ordersOf: Map[Long, Orders]): List[WizardState] =
        state.active.filter(w => ordersOf.contains(w.participantId))

    /** What the turn's spells do to one another, which no spell can work out alone: dispel magic stops every other
      * spell; a magic mirror turns spells back on their casters and a counter-spell stops those aimed at its subject; a
      * spell at nobody, or at something gone, is wasted; and enchantments that contradict each other on one subject
      * cancel out. Gives the turn as it begins, and the spells that land, in the order they were cast.
      */
    def settle(state: BattleState, ordersOf: Map[Long, Orders]): (TurnState, List[Cast]) = {
        val cast: List[Cast] = acting(state, ordersOf).flatMap { w =>
            ordersOf(w.participantId).casts.flatMap(c =>
                Spells.byId(c.spell).map(s => Cast(caster = w.participantId, spell = s, target = c.target, order = c))
            )
        }
        val dispels = cast.filter(_.spell == Spells.DispelMagic)
        val begun = cast.foldLeft(
          TurnState(battle = state, turn = state.turnsPlayed + 1, cast = cast, isDispelled = dispels.nonEmpty)
        )((t, c) => t.say(s"${t.who(c.caster)} casts ${c.spell.name} at ${t.name(c.target)}."))

        // Dispel magic stops every other spell this turn, ends every enchantment and destroys every monster.
        val (afterStopping, working) =
            if (begun.isDispelled) {
                val stopped = cast
                    .filterNot(_.spell == Spells.DispelMagic)
                    .foldLeft(begun)((t, c) => t.say(s"${t.who(c.caster)}'s ${c.spell.name} is dispelled."))
                (stopped, dispels)
            } else {
                val countered = cast.filter(_.spell == Spells.CounterSpell).map(_.target).toSet
                val mirrored = cast.filter(_.spell == Spells.MagicMirror).map(_.target).toSet -- countered
                val (reflecting, aimed) = cast.foldLeft((begun, Vector.empty[Cast])) { case ((t, acc), c) =>
                    if (c.spell.isReflectable && mirrored(c.target) && c.target != Target.Wizard(c.caster))
                        (
                          t.say(
                            s"${c.spell.name} from ${t.who(c.caster)} is reflected by ${t.name(c.target)}'s magic mirror."
                          ),
                          acc :+ c.copy(target = Target.Wizard(c.caster), isReflected = true)
                        )
                    else (t, acc :+ c)
                }
                val unstoppable = Set(Spells.CounterSpell, Spells.FingerOfDeath)
                aimed.foldLeft((reflecting, List.empty[Cast])) { case ((t, acc), c) =>
                    if (countered(c.target) && !unstoppable(c.spell))
                        (
                          t.say(
                            s"${t.who(c.caster)}'s ${c.spell.name} is stopped by a counter-spell on ${t.name(c.target)}."
                          ),
                          acc
                        )
                    else (t, acc :+ c)
                }
            }

        // Only those whose target is there to be hit.
        val (afterAiming, there) = working.foldLeft((afterStopping, List.empty[Cast])) { case ((t, acc), c) =>
            if (t.exists(c.target)) (t, acc :+ c)
            else if (c.target == Target.Nobody) (t, acc)
            else (t.say(s"${t.who(c.caster)}'s ${c.spell.name} finds nothing to hit."), acc)
        }

        // Contradictory enchantments on one subject undo each other; others landing with them are unaffected.
        val enchantedTargets = there.filter(_.spell.school == School.Enchantment).map(_.target).distinct
        enchantedTargets.foldLeft((afterAiming, there)) { case ((t, landing), target) =>
            val contradictory = landing
                .filter(c => c.target == target && c.spell.school == School.Enchantment)
                .map(_.spell.id)
                .distinct
                .filter(Spells.Contradictory)
            if (contradictory.sizeIs > 1)
                (
                  t.say(s"The enchantments on ${t.name(target)} cancel each other out."),
                  landing.filterNot(c => c.target == target && Spells.Contradictory(c.spell.id))
                )
            else (t, landing)
        }
    }

    /** The stabs: blocked only by a shield. */
    private def stab(turn: TurnState, ordersOf: Map[Long, Orders]): TurnState =
        acting(turn.battle, ordersOf).foldLeft(turn) { (t, w) =>
            ordersOf(w.participantId).stab
                .filter(s => t.exists(s.target) && s.target != Target.Wizard(w.participantId))
                .fold(t)(s => t.hurt(s.target, 1, s"${w.name}'s stab", blockedByShield = true))
        }

    /** The monsters' attacks: every monster alive at the start of the turn, and every one summoned during it. */
    private def attack(turn: TurnState, ordersOf: Map[Long, Orders]): TurnState = {
        val state = turn.battle
        val attacking: List[(MonsterState, Option[Target])] =
            state.monsters.filterNot(_.isDead).map { m =>
                val ordered = state
                    .wizard(m.controller)
                    .filter(_.isActive)
                    .flatMap(_ => ordersOf.get(m.controller))
                    .flatMap(_.monsters.get(m.id))
                val target =
                    if (m.hasAmnesia) m.lastTarget
                    else ordered.orElse(soleOpponent(state, m.controller))
                (m, target)
            } ++ turn.summoned.map((m, first) => (m, first.orElse(soleOpponent(state, m.controller))))
        attacking.foldLeft(turn.copy(monsterTargets = attacking.flatMap((m, t) => t.map(m.id -> _)).toMap)) {
            case (t, (m, target)) =>
                val by = s"${t.who(m.controller)}'s ${m.kind}"
                if (m.isParalysed) t.say(s"$by is paralysed and does not attack.")
                else target.filter(t.exists).fold(t)(x => t.hurt(x, m.strength, by, blockedByShield = true))
        }
    }

    /** The battle after the turn: each wizard's gestures, wounds, cures and death, enchantments for the turn to come,
      * and surrender; each monster's wounds and death — destroyed by dispel magic, or by remove enchantment, after
      * attacking — and the turn's report.
      */
    private def endOfTurn(turn: TurnState, ordersOf: Map[Long, Orders]): BattleState = {
        val state = turn.battle
        val now = turn.turn
        val events = scala.collection.mutable.ListBuffer.from(turn.events)
        val wizards = state.wizards.map { w =>
            val t = Target.Wizard(w.participantId)
            if (w.isDead) w
            else {
                val o = ordersOf.get(w.participantId).filter(_ => w.isActive)
                val damage = math.max(0, w.damage + turn.harm.getOrElse(t, 0) - turn.healing.getOrElse(t, 0))
                val dies = turn.killed(t) || damage >= Lethal
                val history = o.fold(w.history)(o => w.history :+ effective(o.left, o.right))
                val surrenders = o.exists(o => effective(o.left, o.right) == (Gesture.P, Gesture.P))
                val gone = turn.isRemoved(t)
                if (gone && (w.isResistantToHeat || w.protectedThrough.isDefined))
                    events += s"${w.name}'s enchantments are removed."
                val paralysis =
                    turn.enchanted(t, Spells.Paralysis)
                        .map(c => w.paralysed.getOrElse(c.order.hand.getOrElse(Hand.Left)))
                val next = w.copy(
                  damage = damage,
                  isDead = dies,
                  isSurrendered = w.isSurrendered || (surrenders && !dies),
                  eliminatedAt = w.eliminatedAt.orElse(Option.when(dies || surrenders)(now)),
                  history = history,
                  usableFrom = if (turn.enchanted(t, Spells.AntiSpell).isDefined) history.size else w.usableFrom,
                  hasAmnesia = turn.enchanted(t, Spells.Amnesia).isDefined,
                  hasFear = turn.enchanted(t, Spells.Fear).isDefined,
                  paralysed = paralysis,
                  protectedThrough =
                      if (turn.enchanted(t, Spells.ProtectionFromEvil).isDefined) Some(now + 3)
                      else if (gone) None
                      else w.protectedThrough.filter(_ > now),
                  isResistantToHeat = !gone && (w.isResistantToHeat || turn.enchanted(t, Spells.ResistHeat).isDefined),
                  // Used once cast, whether or not it got through.
                  usedOnce = w.usedOnce ++ turn.cast
                      .filter(_.caster == w.participantId)
                      .flatMap(c =>
                          c.spell.series
                              .find(s => s.isOncePerBattle && s.notation == c.order.series)
                              .map(onceKey(c.spell, _))
                      )
                )
                if (dies) events += s"${w.name} dies."
                else if (next.isSurrendered && !w.isSurrendered) events += s"${w.name} surrenders."
                next
            }
        }
        val monsters = (state.monsters ++ turn.summoned.map(_._1)).map { m =>
            val t = Target.Monster(m.id)
            if (m.isDead) m
            else {
                val damage = math.max(0, m.damage + turn.harm.getOrElse(t, 0) - turn.healing.getOrElse(t, 0))
                val destroyed = turn.isDispelled || turn.removed(t)
                val dies = turn.killed(t) || damage >= m.strength || destroyed
                if (dies) events += s"${turn.who(m.controller)}'s ${m.kind} is destroyed."
                m.copy(
                  damage = damage,
                  isDead = dies,
                  lastTarget = turn.monsterTargets.get(m.id),
                  hasAmnesia = turn.enchanted(t, Spells.Amnesia).isDefined,
                  isParalysed = turn.enchanted(t, Spells.Paralysis).isDefined
                )
            }
        }
        BattleState(
          wizards = wizards,
          monsters = monsters,
          reports = state.reports :+ TurnReport(now, events.toList),
          turnsPlayed = now
        )
    }

    // ---- the outcome ----------------------------------------------------------------------

    /** Where each wizard finished, 1 the best, once the battle is over.
      *
      * Whoever is still fighting finishes first; then, of the rest, the later a wizard fell or surrendered the better,
      * and on the same turn a wizard who surrendered and lived finishes above one who died — which is the rules' "a
      * surrendering wizard loses, unless his spells killed his opponent", for any number of wizards. A battle that hit
      * the turn limit ranks those still fighting by their wounds.
      */
    def ranks(state: BattleState): Map[Long, Int] = {
        def key(w: WizardState): (Int, Int, Int, Int) =
            (
              if (w.isActive) 0 else 1,
              -w.eliminatedAt.getOrElse(Int.MaxValue),
              if (!w.isActive && !w.isDead) 0 else 1,
              if (w.isActive) w.damage else 0
            )
        val ordered = state.wizards.sortBy(key)
        val order = summon[Ordering[(Int, Int, Int, Int)]]
        ordered.map(w => w.participantId -> (1 + ordered.count(o => order.lt(key(o), key(w))))).toMap
    }
}
