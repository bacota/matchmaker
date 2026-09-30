package com.vivi.boxing

import scala.util.control.NonFatal
import upickle.default.{ReadWriter, macroRW, read, write}

/** A fighter's five characteristics: what matchmaker keeps as the character's state, and what every round is built on.
  *
  * Workrate is how many points the fighter has to spend on each round; the other four are added to what is spent —
  * speed to offense, agility to defense, strength to power, each doubled — and chin is what stands between a heavy blow
  * and the canvas.
  */
case class Fighter(strength: Int, speed: Int, agility: Int, workrate: Int, chin: Int) {
    def total: Int = strength + speed + agility + workrate + chin

    def characteristics: List[(String, Int)] =
        List("strength" -> strength, "speed" -> speed, "agility" -> agility, "workrate" -> workrate, "chin" -> chin)
}

object Fighter {

    /** Points a new fighter is built from. Every characteristic is at least [[Min]], so the choice is how to spread the
      * other twenty: an average fighter is five across the board.
      */
    val Budget = 25
    val Min = 1
    val Max = 10

    /** A fighter as the build form submits it: every characteristic in range, and exactly the budget spent.
      *
      * Exactly rather than at most, because an unspent point is never what a player means — it is a slip, and a fighter
      * is built once.
      */
    def validate(f: Fighter): Either[String, Fighter] =
        f.characteristics.find((_, v) => v < Min || v > Max) match {
            case Some((name, v)) => Left(s"$name must be between $Min and $Max; it was $v")
            case None if f.total != Budget =>
                Left(s"a fighter is built from exactly $Budget points; these add up to ${f.total}")
            case None => Right(f)
        }

    /** The fighter a character's stored state describes, or `None` for a character not yet built.
      *
      * Matchmaker creates every character with empty state, so "nothing here" is the ordinary answer for a fighter's
      * first bout rather than an error. State that parses but breaks the rules is treated the same way: it was not
      * written by this engine, and a fighter that could not have been built here does not get into the ring.
      */
    def fromState(state: String): Option[Fighter] =
        try Some(read[Fighter](state)).flatMap(f => validate(f).toOption)
        catch { case NonFatal(_) => None }

    def toState(f: Fighter): String = write(f)

    given ReadWriter[Fighter] = macroRW
}

/** How a fighter spends their workrate in one round. */
case class Allocation(offense: Int, defense: Int, power: Int) {

    /** A `Long`, because the parts come off the wire: in `Int`, `Int.MaxValue + Int.MaxValue + 7` wraps to 5 and passes
      * as a workrate of 5. Summed without wrapping, three non-negative parts adding up to the workrate are each at most
      * the workrate, so nothing computed from them later can overflow either.
      */
    def total: Long = offense.toLong + defense.toLong + power.toLong
}

object Allocation {

    /** Nothing negative, and exactly the fighter's workrate spent. */
    def validate(a: Allocation, fighter: Fighter): Either[String, Allocation] =
        if (a.offense < 0 || a.defense < 0 || a.power < 0) Left("offense, defense and power cannot be negative")
        else if (a.total != fighter.workrate)
            Left(s"a round is planned with exactly your workrate of ${fighter.workrate}; these add up to ${a.total}")
        else Right(a)

    given ReadWriter[Allocation] = macroRW
}

/** One fighter's numbers for one round, once the allocation and the characteristics are combined. */
case class Effective(offense: Int, defense: Int, power: Int, chin: Int) {

    /** A blow harder than this is a knockout. */
    def effectiveChin: Int = defense + 3 * chin

    /** A blow harder than this, short of a knockout, is a knockdown. */
    def knockdownLine: Int = defense + chin
}

object Effective {
    def of(f: Fighter, a: Allocation): Effective =
        Effective(
          offense = a.offense + 2 * f.speed,
          defense = a.defense + 2 * f.agility,
          power = a.power + 2 * f.strength,
          chin = f.chin
        )

    given ReadWriter[Effective] = macroRW
}

/** How a round was decided, in order of precedence: each is only looked at when nothing above it happened. */
enum Decision(val label: String) {

    /** One fighter's power beat the other's effective chin. The bout ends. */
    case Knockout extends Decision("knockout")

    /** Power beat defense plus chin, short of a knockout: 10–8. */
    case Knockdown extends Decision("knockdown")

    /** Power beat defense: 10–9. */
    case TellingBlow extends Decision("telling blow")

    /** Nothing landed, and one fighter threw more — or, level on offense, defended better: 10–9. */
    case Activity extends Decision("outworked")

    /** Nothing landed and nothing separated them: 10–10. */
    case Even extends Decision("even")
}

/** A round's points, red's and blue's. */
case class Score(red: Int, blue: Int) {
    def of(side: Side): Int = side match {
        case Side.Red  => red
        case Side.Blue => blue
    }
}

object Score {
    def to(winner: Side, loserGets: Int): Score = winner match {
        case Side.Red  => Score(10, loserGets)
        case Side.Blue => Score(loserGets, 10)
    }
}

/** What happened in one round: how it was decided, who won it, and — unless it ended the bout — the points. */
case class RoundOutcome(decision: Decision, winner: Option[Side], score: Option[Score])

/** The rules of `boxing.txt`, as one function of the two fighters' numbers for a round. */
object Rules {

    /** Who comes out ahead when both fighters did the same thing: more offense, then more defense, else nobody. */
    def edge(red: Effective, blue: Effective): Option[Side] =
        if (red.offense != blue.offense) Some(if (red.offense > blue.offense) Side.Red else Side.Blue)
        else if (red.defense != blue.defense) Some(if (red.defense > blue.defense) Side.Red else Side.Blue)
        else None

    /** Which fighter landed, given whether each did.
      *
      * `None` when neither did, and also when both did and [[edge]] cannot separate them — in which case the round goes
      * on to the next test down, as the rules say: a knockout nobody wins is not a knockout.
      */
    private def landed(redLands: Boolean, blueLands: Boolean, edge: Option[Side]): Option[Side] =
        (redLands, blueLands) match {
            case (true, false) => Some(Side.Red)
            case (false, true) => Some(Side.Blue)
            case (true, true)  => edge
            case _             => None
        }

    def resolve(red: Effective, blue: Effective): RoundOutcome = {
        val tiebreak = edge(red, blue)

        val knockout = landed(red.power > blue.effectiveChin, blue.power > red.effectiveChin, tiebreak)
        val knockdown = landed(red.power > blue.knockdownLine, blue.power > red.knockdownLine, tiebreak)
        val tellingBlow = landed(red.power > blue.defense, blue.power > red.defense, tiebreak)

        knockout
            .map(w => RoundOutcome(Decision.Knockout, Some(w), None))
            .orElse(knockdown.map(w => RoundOutcome(Decision.Knockdown, Some(w), Some(Score.to(w, 8)))))
            .orElse(tellingBlow.map(w => RoundOutcome(Decision.TellingBlow, Some(w), Some(Score.to(w, 9)))))
            .getOrElse(
              tiebreak match {
                  case Some(w) => RoundOutcome(Decision.Activity, Some(w), Some(Score.to(w, 9)))
                  case None    => RoundOutcome(Decision.Even, None, Some(Score(10, 10)))
              }
            )
    }
}

/** Which corner a fighter is in. Matchmaker seats players by role, and these are the role names. */
enum Side {
    case Red, Blue

    def other: Side = this match {
        case Red  => Blue
        case Blue => Red
    }
}

object Side {
    def parse(s: String): Option[Side] =
        s.trim.toLowerCase match {
            case "red" | "red corner" | "1" | "one"   => Some(Red)
            case "blue" | "blue corner" | "2" | "two" => Some(Blue)
            case _                                    => None
        }
}

/** How a bout came out for one corner. */
enum Outcome {
    case Win, Loss, Draw

    def label: String = toString.toLowerCase
}
