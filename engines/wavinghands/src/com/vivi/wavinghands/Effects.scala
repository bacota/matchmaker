package com.vivi.wavinghands

/** When in a turn a spell takes effect, earliest first: what a spell depends on is applied before it. A remove
  * enchantment comes before the enchantments it undoes and the protections it takes away, and every protection before
  * the attacks it stops.
  *
  * Spells of one priority do not depend on one another, so within a priority they are applied in the order they were
  * cast, and that order decides nothing but the order of the report. Spells that do act on one another at once — dispel
  * magic, counter-spells and mirrors, and enchantments that contradict each other — are settled over the whole turn
  * before any spell is applied, by [[Rules.settle]].
  */
enum Priority {
    case Removal, Protection, Enchantment, Damage, Summons
}

/** What each spell does: the state transition from the turn as it stands to the turn with the spell applied. */
object Effects {

    def priority(spell: Spell): Priority = spell match {
        case Spells.RemoveEnchantment                                                      => Priority.Removal
        case Spells.Shield | Spells.CounterSpell | Spells.DispelMagic | Spells.MagicMirror => Priority.Protection
        case Spells.CureLightWounds | Spells.CureHeavyWounds                               => Priority.Damage
        case s if s.school == School.Enchantment                                           => Priority.Enchantment
        case s if s.school == School.Summons                                               => Priority.Summons
        case _                                                                             => Priority.Damage
    }

    /** The turn with the spell applied. Only spells that landed come here: whatever stopped, turned or wasted a spell
      * has already had its say.
      */
    def apply(c: Cast, turn: TurnState): TurnState = {
        val by = s"${turn.who(c.caster)}'s ${c.spell.name}"
        c.spell match {
            case Spells.RemoveEnchantment => turn.copy(removed = turn.removed + c.target)
            // A counter-spell and a dispel magic shield their subject too.
            case Spells.Shield | Spells.CounterSpell | Spells.DispelMagic => turn.shield(c.target)
            // Its work — turning spells back — was done when the turn was settled.
            case Spells.MagicMirror      => turn
            case Spells.CureLightWounds  => turn.heal(c.target, 1)
            case Spells.CureHeavyWounds  => turn.heal(c.target, 2)
            case Spells.Missile          => turn.hurt(c.target, 1, by, blockedByShield = true)
            case Spells.CauseLightWounds => turn.hurt(c.target, 2, by, blockedByShield = false)
            case Spells.CauseHeavyWounds => turn.hurt(c.target, 3, by, blockedByShield = false)
            case Spells.LightningBolt    => turn.hurt(c.target, 5, by, blockedByShield = false)
            case Spells.Fireball =>
                if (turn.isResistantToHeat(c.target)) turn.say(s"$by washes harmlessly over ${turn.name(c.target)}.")
                else turn.hurt(c.target, 5, by, blockedByShield = false)
            case Spells.FingerOfDeath => turn.kill(c.target).say(s"$by strikes ${turn.name(c.target)} dead.")
            case s if s.school == School.Enchantment => enchant(c, turn)
            case s if s.school == School.Summons     => summon(c, turn)
            case _                                   => turn
        }
    }

    /** An enchantment, for the turn to come — unless its subject's enchantments are removed as it lands. The two that
      * protect take effect at once.
      */
    private def enchant(c: Cast, turn: TurnState): TurnState =
        if (turn.isRemoved(c.target)) turn
        else {
            val enchanted = turn.enchant(c)
            c.spell match {
                case Spells.ProtectionFromEvil => enchanted.shield(c.target)
                case Spells.ResistHeat         => enchanted.copy(resistsHeat = enchanted.resistsHeat + c.target)
                case _                         => enchanted
            }
        }

    /** A monster for its target's side, which may attack at once. */
    private def summon(c: Cast, turn: TurnState): TurnState = {
        val controller = c.target match {
            case Target.Wizard(id)  => Some(id)
            case Target.Monster(id) => turn.battle.monster(id).map(_.controller)
            case Target.Nobody      => None
        }
        controller.fold(turn) { owner =>
            val (kind, strength) = Spells.Monsters(c.spell.id)
            val monster = MonsterState(id = turn.nextMonsterId, kind = kind, strength = strength, controller = owner)
            // Its first attack is its summoner's to choose only when the summoner controls it.
            val first = Option.when(owner == c.caster)(c.order.monsterTarget).flatten
            turn.copy(summoned = turn.summoned :+ (monster, first)).say(s"A $kind appears, serving ${turn.who(owner)}.")
        }
    }
}
