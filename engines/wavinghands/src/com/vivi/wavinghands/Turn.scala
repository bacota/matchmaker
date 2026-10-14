package com.vivi.wavinghands

/** A spell cast this turn: who cast it, what it is, and what it is aimed at — after any magic mirror has turned it back
  * on its caster, which `isReflected` records.
  */
case class Cast(caster: Long, spell: Spell, target: Target, order: CastOrder, isReflected: Boolean = false)

/** What the turn being played has settled so far.
  *
  * Kept apart from [[BattleState]] so that none of it can outlive its turn: [[Rules.play]] makes a new one for each
  * turn and throws it away at the end, once it has written what lasts — wounds, deaths, enchantments for the turn to
  * come — into the battle. A shield or a pending wound has nowhere to linger, so there is nothing to clear.
  *
  * `battle` is the battle as the turn began. Every spell reads it and none changes it; what a spell does goes in the
  * other fields. `cast` is every spell cast this turn, whether or not it got through.
  */
case class TurnState(
    battle: BattleState,
    turn: Int,
    cast: List[Cast],
    isDispelled: Boolean,
    // Whose enchantments end this turn, besides everyone's when dispel magic is cast.
    removed: Set[Target] = Set.empty,
    // Who is shielded this turn, by a spell landing now. A protection from evil cast earlier is in `battle`.
    shielded: Set[Target] = Set.empty,
    // Who a resist heat landing now protects. One cast earlier is in `battle`.
    resistsHeat: Set[Target] = Set.empty,
    // The enchantments that land this turn, by target, for the turn to come.
    enchantments: Map[Target, List[Cast]] = Map.empty,
    // Wounds and cures, added up and applied together at the end of the turn, so that the order they arrive in does not
    // decide who dies.
    harm: Map[Target, Int] = Map.empty,
    healing: Map[Target, Int] = Map.empty,
    killed: Set[Target] = Set.empty,
    // The monsters summoned this turn, each with what it attacks at once if its summoner chose.
    summoned: List[(MonsterState, Option[Target])] = Nil,
    // What each monster attacked, which a monster with amnesia attacks again.
    monsterTargets: Map[Int, Target] = Map.empty,
    events: Vector[String] = Vector.empty
) {

    def say(event: String): TurnState = copy(events = events :+ event)

    def name(t: Target): String = battle.nameOf(t)
    def who(id: Long): String = battle.wizard(id).fold("someone")(_.name)

    /** Whether something is there to be hit. A wizard who has died or surrendered is out of the duel, and nothing more
      * can reach them; a monster summoned this turn cannot be aimed at until the next.
      */
    def exists(t: Target): Boolean = t match {
        case Target.Wizard(id)  => battle.wizard(id).exists(_.isActive)
        case Target.Monster(id) => battle.monster(id).exists(!_.isDead)
        case Target.Nobody      => false
    }

    def isRemoved(t: Target): Boolean = isDispelled || removed(t)

    def isShielded(t: Target): Boolean =
        shielded(t) || (t match {
            case Target.Wizard(id) => !isRemoved(t) && battle.wizard(id).exists(_.protectedThrough.exists(_ >= turn))
            case _                 => false
        })

    def isResistantToHeat(t: Target): Boolean =
        !isRemoved(t) && (resistsHeat(t) || (t match {
            case Target.Wizard(id) => battle.wizard(id).exists(_.isResistantToHeat)
            case _                 => false
        }))

    def enchanted(t: Target, spell: Spell): Option[Cast] = enchantments.getOrElse(t, Nil).find(_.spell == spell)

    def shield(t: Target): TurnState = copy(shielded = shielded + t)

    def enchant(c: Cast): TurnState =
        copy(enchantments = enchantments.updated(c.target, enchantments.getOrElse(c.target, Nil) :+ c))

    def hurt(t: Target, points: Int, by: String, blockedByShield: Boolean): TurnState =
        if (blockedByShield && isShielded(t)) say(s"$by is blocked by a shield on ${name(t)}.")
        else copy(harm = harm.updated(t, harm.getOrElse(t, 0) + points)).say(s"$by hits ${name(t)} for $points.")

    def heal(t: Target, points: Int): TurnState = copy(healing = healing.updated(t, healing.getOrElse(t, 0) + points))

    def kill(t: Target): TurnState = copy(killed = killed + t)

    /** The number the next monster to appear takes. */
    def nextMonsterId: Int = (battle.monsters.map(_.id) ++ summoned.map(_._1.id)).maxOption.getOrElse(0) + 1
}
