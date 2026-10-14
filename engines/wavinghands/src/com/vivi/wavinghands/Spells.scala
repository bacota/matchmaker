package com.vivi.wavinghands

/** One hand's action on one turn: one of the five one-handed gestures, the clap, a stab, or nothing.
  *
  * A clap counts only when both hands make it, so a clap made with one hand is recorded as [[Gesture.Nothing]] — which,
  * like a stab, belongs to no spell and so breaks every series that hand was building.
  */
enum Gesture(val code: String, val label: String) {
    case F extends Gesture("F", "fingers")
    case P extends Gesture("P", "palm")
    case S extends Gesture("S", "snap")
    case W extends Gesture("W", "wave")
    case D extends Gesture("D", "digit")
    case C extends Gesture("C", "clap")
    case Stab extends Gesture("stab", "stab")
    case Nothing extends Gesture("-", "nothing")

    /** Whether this gesture can be part of a spell: everything but a stab and nothing. */
    def isSpellGesture: Boolean = this != Stab && this != Nothing

    /** What a hand paralysed in this gesture is held in on the following turn: a clap, snap or wave stiffens into
      * fingers, digit or palm, and anything else stays as it was — which is what lets a paralysed hand go on stabbing.
      */
    def paralysed: Gesture = this match {
        case C     => F
        case S     => D
        case W     => P
        case other => other
    }
}

object Gesture {
    def parse(s: String): Option[Gesture] =
        s.trim match {
            case "" | "-" => Some(Nothing)
            case other    => values.find(g => g.code.equalsIgnoreCase(other) || g.label.equalsIgnoreCase(other))
        }

    /** The gestures a hand may be told to make, in the order the play page offers them. */
    val offered: List[Gesture] = List(F, P, S, W, D, C, Stab, Nothing)
}

enum Hand {
    case Left, Right

    def other: Hand = if (this == Left) Right else Left
    def code: String = toString.toLowerCase
}

object Hand {
    def parse(s: String): Option[Hand] = values.find(_.code == s.trim.toLowerCase)
}

/** Which of a wizard's hands completed a spell: one of them, or both, for a spell whose last gesture is made with both
  * hands at once — the clap, or a doubled gesture such as magic mirror's two waves.
  */
enum Slot {
    case Left, Right, Both

    def code: String = toString.toLowerCase
}

object Slot {
    def parse(s: String): Option[Slot] = values.find(_.code == s.trim.toLowerCase)
    def of(hand: Hand): Slot = if (hand == Hand.Left) Left else Right
}

/** Whom a spell is aimed at when its caster does not say: themselves, for a protection, a cure or a summons; their
  * opponent, for everything else.
  */
enum Aim {
    case Self, Enemy
}

/** One step of a gesture series: a gesture, made by the casting hand alone or — `isBoth` — by both hands together. A
  * clap is always both.
  */
case class Step(gesture: Gesture, isBoth: Boolean)

/** One way to cast a spell. `isOncePerBattle` is the short lightning bolt, which each wizard may use once. */
case class Series(steps: List[Step], isOncePerBattle: Boolean = false) {
    def length: Int = steps.size

    /** Whether the last step needs both hands, which makes the spell take both hands' last gestures. */
    def isFinalBoth: Boolean = steps.last.isBoth

    /** The series as the rules write it: upper case for one hand, a parenthesis and lower case for both. */
    def notation: String =
        steps
            .map(s => if (s.isBoth && s.gesture != Gesture.C) "(" + s.gesture.code.toLowerCase else s.gesture.code)
            .mkString("-")
}

object Series {

    /** A series written as the rules write it: "WPP", "C(w", "P-P-(w-(s". Dashes are optional. */
    def of(notation: String, isOncePerBattle: Boolean = false): Series = {
        val cleaned = notation.replace("-", "")
        val steps = scala.collection.mutable.ListBuffer.empty[Step]
        var i = 0
        while (i < cleaned.length) {
            if (cleaned(i) == '(') {
                steps += Step(Gesture.parse(cleaned(i + 1).toString).get, isBoth = true)
                i += 2
            } else {
                val g = Gesture.parse(cleaned(i).toString).get
                steps += Step(g, isBoth = g == Gesture.C)
                i += 1
            }
        }
        Series(steps.toList, isOncePerBattle)
    }
}

/** What a spell is, for the rules that treat kinds of spell alike: an enchantment is what remove enchantment and dispel
  * magic end, and a summons makes a monster.
  */
enum School {
    case Protection, Summons, Damage, Enchantment
}

case class Spell(id: String, name: String, series: List[Series], aim: Aim, school: School) {

    /** Whether a magic mirror turns this spell back on its caster. Not the spells that protect a subject from other
      * spells — they would only argue with the mirror.
      */
    def isReflectable: Boolean = !Set("magic-mirror", "counter-spell", "dispel-magic").contains(id)
}

/** The spells this engine knows: phase one of the game, the core of a duel. Gestures that would complete a spell not
  * yet here complete nothing.
  */
object Spells {

    private def spell(id: String, name: String, aim: Aim, school: School)(series: Series*): Spell =
        Spell(id = id, name = name, series = series.toList, aim = aim, school = school)

    private def s(notation: String): Series = Series.of(notation)

    val Shield: Spell = spell("shield", "Shield", Aim.Self, School.Protection)(s("P"))
    val CounterSpell: Spell = spell("counter-spell", "Counter-spell", Aim.Self, School.Protection)(s("WPP"), s("WWS"))
    val MagicMirror: Spell = spell("magic-mirror", "Magic Mirror", Aim.Self, School.Protection)(s("C(w"))
    val RemoveEnchantment: Spell =
        spell("remove-enchantment", "Remove Enchantment", Aim.Self, School.Protection)(s("PDWP"))
    val DispelMagic: Spell = spell("dispel-magic", "Dispel Magic", Aim.Self, School.Protection)(s("CDPW"))
    val CureLightWounds: Spell = spell("cure-light-wounds", "Cure Light Wounds", Aim.Self, School.Protection)(s("DFW"))
    val CureHeavyWounds: Spell = spell("cure-heavy-wounds", "Cure Heavy Wounds", Aim.Self, School.Protection)(s("DFPW"))

    val SummonGoblin: Spell = spell("summon-goblin", "Summon Goblin", Aim.Self, School.Summons)(s("SFW"))
    val SummonOgre: Spell = spell("summon-ogre", "Summon Ogre", Aim.Self, School.Summons)(s("PSFW"))
    val SummonTroll: Spell = spell("summon-troll", "Summon Troll", Aim.Self, School.Summons)(s("FPSFW"))
    val SummonGiant: Spell = spell("summon-giant", "Summon Giant", Aim.Self, School.Summons)(s("WFPSFW"))

    val Missile: Spell = spell("missile", "Missile", Aim.Enemy, School.Damage)(s("SD"))
    val FingerOfDeath: Spell = spell("finger-of-death", "Finger of Death", Aim.Enemy, School.Damage)(s("PWPFSSSD"))
    val LightningBolt: Spell =
        spell("lightning-bolt", "Lightning Bolt", Aim.Enemy, School.Damage)(
          s("DFFDD"),
          Series.of("WDDC", isOncePerBattle = true)
        )
    val CauseLightWounds: Spell = spell("cause-light-wounds", "Cause Light Wounds", Aim.Enemy, School.Damage)(s("WFP"))
    val CauseHeavyWounds: Spell = spell("cause-heavy-wounds", "Cause Heavy Wounds", Aim.Enemy, School.Damage)(s("WPFD"))
    val Fireball: Spell = spell("fireball", "Fireball", Aim.Enemy, School.Damage)(s("FSSDD"))

    val Amnesia: Spell = spell("amnesia", "Amnesia", Aim.Enemy, School.Enchantment)(s("DPP"))
    val Fear: Spell = spell("fear", "Fear", Aim.Enemy, School.Enchantment)(s("SWD"))
    val Paralysis: Spell = spell("paralysis", "Paralysis", Aim.Enemy, School.Enchantment)(s("FFF"))
    val AntiSpell: Spell = spell("anti-spell", "Anti-spell", Aim.Enemy, School.Enchantment)(s("SPF"))
    val ProtectionFromEvil: Spell =
        spell("protection-from-evil", "Protection from Evil", Aim.Self, School.Enchantment)(s("WWP"))
    val ResistHeat: Spell = spell("resist-heat", "Resist Heat", Aim.Self, School.Enchantment)(s("WWFP"))

    val all: List[Spell] = List(
      Shield,
      CounterSpell,
      MagicMirror,
      RemoveEnchantment,
      DispelMagic,
      CureLightWounds,
      CureHeavyWounds,
      SummonGoblin,
      SummonOgre,
      SummonTroll,
      SummonGiant,
      Missile,
      FingerOfDeath,
      LightningBolt,
      CauseLightWounds,
      CauseHeavyWounds,
      Fireball,
      Amnesia,
      Fear,
      Paralysis,
      AntiSpell,
      ProtectionFromEvil,
      ResistHeat
    )

    def byId(id: String): Option[Spell] = all.find(_.id == id)

    /** What each spell does, in a line, for the play page's reference. */
    val about: Map[String, String] = Map(
      Shield.id -> "Blocks monsters, missiles and stabs for this turn.",
      CounterSpell.id -> "Stops every other spell at its subject this turn, except finger of death, and shields it.",
      MagicMirror.id -> "Turns spells cast at its subject this turn back on their casters.",
      RemoveEnchantment.id -> "Ends every enchantment on its subject, even those landing now. Destroys a monster.",
      DispelMagic.id -> "Stops every other spell this turn, ends all enchantments, destroys all monsters, and shields.",
      CureLightWounds.id -> "Heals 1 point of damage.",
      CureHeavyWounds.id -> "Heals 2 points of damage.",
      SummonGoblin.id -> "A goblin: hits for 1, dies to 1.",
      SummonOgre.id -> "An ogre: hits for 2, dies to 2.",
      SummonTroll.id -> "A troll: hits for 3, dies to 3.",
      SummonGiant.id -> "A giant: hits for 4, dies to 4.",
      Missile.id -> "1 point of damage. A shield stops it.",
      FingerOfDeath.id -> "Kills outright. Only dispel magic stops it.",
      LightningBolt.id -> "5 points of damage; a shield does not help. The short series works once per battle.",
      CauseLightWounds.id -> "2 points of damage; a shield does not help.",
      CauseHeavyWounds.id -> "3 points of damage; a shield does not help.",
      Fireball.id -> "5 points of damage, unless the subject resists heat.",
      Amnesia.id -> "Next turn its subject must repeat this turn's gestures.",
      Fear.id -> "Next turn its subject cannot clap, point, wiggle or snap.",
      Paralysis.id -> "Next turn one of its subject's hands is frozen where it is.",
      AntiSpell.id -> "Its subject's spells in progress are all broken off.",
      ProtectionFromEvil.id -> "A shield for this turn and the next three.",
      ResistHeat.id -> "Fireballs do nothing to its subject, until its enchantments are removed."
    )

    /** The enchantments that undo each other when two or more different ones land on one subject in one turn. */
    val Contradictory: Set[String] = Set(Amnesia.id, Fear.id, Paralysis.id)

    /** A summons's monster, by what it is: how hard it hits, which is also how much damage it takes to kill. */
    val Monsters: Map[String, (String, Int)] = Map(
      SummonGoblin.id -> ("goblin", 1),
      SummonOgre.id -> ("ogre", 2),
      SummonTroll.id -> ("troll", 3),
      SummonGiant.id -> ("giant", 4)
    )
}
