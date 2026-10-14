package com.vivi.boxing

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/** The rules of `boxing.txt`, one clause at a time, on hand-picked numbers — and then the properties any pair of
  * numbers must satisfy.
  *
  * Effective numbers are given directly rather than built from fighters, so that each case says exactly which threshold
  * it is testing: `Effective(offense, defense, power, chin)`, with a knockout needing power above `defense + 3 * chin`
  * and a knockdown power above `defense + chin`.
  */
class RulesSpec extends ScalaCheckSuite {

    private def resolve(red: Effective, blue: Effective) = Rules.resolve(red, blue)

    test("a fighter's numbers for a round are the plan plus double speed, agility and strength") {
        val f = Fighter(strength = 4, speed = 3, agility = 6, workrate = 7, chin = 5)
        val e = Effective.of(f, Allocation(offense = 2, defense = 3, power = 2))
        assertEquals(e, Effective(offense = 8, defense = 15, power = 10, chin = 5))
        assertEquals(e.effectiveChin, 30)
        assertEquals(e.knockdownLine, 20)
    }

    test("power above the opponent's effective chin is a knockout, and the round is not scored") {
        // Blue's effective chin is 10 + 15 = 25.
        val outcome = resolve(
          Effective(offense = 2, defense = 2, power = 30, chin = 3),
          Effective(offense = 15, defense = 10, power = 10, chin = 5)
        )
        assertEquals(outcome, RoundOutcome(decision = Decision.Knockout, winner = Some(Side.Red), score = None))
    }

    test("power equal to the effective chin is not a knockout: it has to exceed it") {
        val outcome = resolve(
          Effective(offense = 2, defense = 20, power = 25, chin = 3),
          Effective(offense = 15, defense = 10, power = 10, chin = 5)
        )
        assertEquals(outcome.decision, Decision.Knockdown)
    }

    test("when both would knock the other out, the higher offense does") {
        val outcome = resolve(
          Effective(offense = 10, defense = 0, power = 30, chin = 1),
          Effective(offense = 5, defense = 0, power = 30, chin = 1)
        )
        assertEquals(outcome, RoundOutcome(decision = Decision.Knockout, winner = Some(Side.Red), score = None))
    }

    test("when both would knock the other out and offense is level, the higher defense does") {
        val outcome = resolve(
          Effective(offense = 5, defense = 0, power = 30, chin = 1),
          Effective(offense = 5, defense = 1, power = 30, chin = 1)
        )
        assertEquals(outcome, RoundOutcome(decision = Decision.Knockout, winner = Some(Side.Blue), score = None))
    }

    test("two knockouts nothing separates are no knockout, and nothing below separates them either") {
        // Level on offense and defense, so every tie below is level too: the round ends 10–10.
        val outcome = resolve(
          Effective(offense = 5, defense = 0, power = 30, chin = 1),
          Effective(offense = 5, defense = 0, power = 30, chin = 1)
        )
        assertEquals(outcome, RoundOutcome(decision = Decision.Even, winner = None, score = Some(Score(10, 10))))
    }

    test("power above defense plus chin, short of a knockout, is a knockdown: 10–8") {
        // Blue: knocked down above 15, out above 25.
        val outcome = resolve(
          Effective(offense = 0, defense = 10, power = 20, chin = 5),
          Effective(offense = 5, defense = 10, power = 10, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.Knockdown, winner = Some(Side.Red), score = Some(Score(10, 8)))
        )
    }

    test("a knockdown outranks the other fighter's telling blow") {
        // Red knocks Blue down (16 > 15); Blue lands a telling blow on Red (11 > 10). The knockdown is
        // looked at first, so it is the one that counts.
        val outcome = resolve(
          Effective(offense = 0, defense = 10, power = 16, chin = 5),
          Effective(offense = 5, defense = 10, power = 11, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.Knockdown, winner = Some(Side.Red), score = Some(Score(10, 8)))
        )
    }

    test("two knockdowns go to the higher offense") {
        val outcome = resolve(
          Effective(offense = 3, defense = 10, power = 20, chin = 5),
          Effective(offense = 4, defense = 10, power = 20, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.Knockdown, winner = Some(Side.Blue), score = Some(Score(8, 10)))
        )
    }

    test("power above defense, short of a knockdown, is a telling blow: 10–9") {
        val outcome = resolve(
          Effective(offense = 0, defense = 10, power = 12, chin = 5),
          Effective(offense = 5, defense = 10, power = 10, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.TellingBlow, winner = Some(Side.Red), score = Some(Score(10, 9)))
        )
    }

    test("two telling blows go to the higher offense") {
        val outcome = resolve(
          Effective(offense = 3, defense = 10, power = 12, chin = 5),
          Effective(offense = 5, defense = 10, power = 12, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.TellingBlow, winner = Some(Side.Blue), score = Some(Score(9, 10)))
        )
    }

    test("with nothing landing, the higher offense wins the round 10–9") {
        val outcome = resolve(
          Effective(offense = 8, defense = 10, power = 5, chin = 5),
          Effective(offense = 5, defense = 10, power = 5, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.Activity, winner = Some(Side.Red), score = Some(Score(10, 9)))
        )
    }

    test("with nothing landing and offense level, the higher defense wins the round") {
        val outcome = resolve(
          Effective(offense = 5, defense = 12, power = 5, chin = 5),
          Effective(offense = 5, defense = 10, power = 5, chin = 5)
        )
        assertEquals(
          outcome,
          RoundOutcome(decision = Decision.Activity, winner = Some(Side.Red), score = Some(Score(10, 9)))
        )
    }

    test("with nothing landing and nothing to separate them, both fighters get 10") {
        val outcome = resolve(
          Effective(offense = 5, defense = 10, power = 5, chin = 5),
          Effective(offense = 5, defense = 10, power = 5, chin = 5)
        )
        assertEquals(outcome, RoundOutcome(decision = Decision.Even, winner = None, score = Some(Score(10, 10))))
    }

    // ---------------------------------------------------------------------------
    // Building a fighter
    // ---------------------------------------------------------------------------

    test("a fighter is built from exactly the budget, each characteristic in range") {
        assert(Fighter.validate(Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)).isRight)
        assert(Fighter.validate(Fighter(strength = 10, speed = 10, agility = 1, workrate = 1, chin = 3)).isRight)
        assertEquals(
          Fighter.validate(Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 4)),
          Left("a fighter is built from exactly 25 points; these add up to 24")
        )
        assertEquals(
          Fighter.validate(Fighter(strength = 11, speed = 5, agility = 5, workrate = 3, chin = 1)),
          Left("strength must be between 1 and 10; it was 11")
        )
        assertEquals(
          Fighter.validate(Fighter(strength = 9, speed = 9, agility = 9, workrate = 0, chin = -2)),
          Left("workrate must be between 1 and 10; it was 0")
        )
    }

    test("a character's state is read as a fighter only if it is one this engine could have built") {
        assertEquals(
          Fighter.fromState(Fighter.toState(Fighter(strength = 6, speed = 4, agility = 5, workrate = 6, chin = 4))),
          Some(Fighter(strength = 6, speed = 4, agility = 5, workrate = 6, chin = 4))
        )
        // What matchmaker creates every character with.
        assertEquals(Fighter.fromState(""), None)
        assertEquals(Fighter.fromState("{}"), None)
        assertEquals(Fighter.fromState("not json"), None)
        // Parses, but over budget: not a fighter that gets into the ring.
        assertEquals(
          Fighter.fromState(
            Fighter.toState(Fighter(strength = 10, speed = 10, agility = 10, workrate = 10, chin = 10))
          ),
          None
        )
    }

    test("a round plan spends exactly the workrate, and nothing negative") {
        val f = Fighter(strength = 5, speed = 5, agility = 5, workrate = 6, chin = 4)
        assert(Allocation.validate(Allocation(offense = 2, defense = 2, power = 2), f).isRight)
        assertEquals(
          Allocation.validate(Allocation(offense = 2, defense = 2, power = 1), f),
          Left("a round is planned with exactly your workrate of 6; these add up to 5")
        )
        assertEquals(
          Allocation.validate(Allocation(offense = 8, defense = -1, power = -1), f),
          Left("offense, defense and power cannot be negative")
        )
    }

    test("a plan whose parts overflow an Int is refused, not wrapped into the workrate") {
        val f = Fighter(strength = 5, speed = 5, agility = 5, workrate = 5, chin = 5)
        // In Int arithmetic this sums to 5, exactly the workrate.
        assertEquals(
          Allocation.validate(Allocation(offense = Int.MaxValue, defense = Int.MaxValue, power = 7), f),
          Left(s"a round is planned with exactly your workrate of 5; these add up to ${2L * Int.MaxValue + 7}")
        )
    }

    // ---------------------------------------------------------------------------
    // Properties
    // ---------------------------------------------------------------------------

    private val effective: Gen[Effective] =
        for {
            offense <- Gen.choose(0, 30)
            defense <- Gen.choose(0, 30)
            power <- Gen.choose(0, 30)
            chin <- Gen.choose(1, 10)
        } yield Effective(offense = offense, defense = defense, power = power, chin = chin)

    private def mirrored(side: Side) = side.other

    property("the rules do not care which corner is which: swapping the fighters swaps the result") {
        Prop.forAll(effective, effective) { (red, blue) =>
            val straight = resolve(red, blue)
            val swapped = resolve(blue, red)
            assertEquals(swapped.decision, straight.decision)
            assertEquals(swapped.winner, straight.winner.map(mirrored))
            assertEquals(swapped.score, straight.score.map(s => Score(s.blue, s.red)))
        }
    }

    property("a scored round is 10–10, or 10 for its winner and 9 or 8 for the other") {
        Prop.forAll(effective, effective) { (red, blue) =>
            val outcome = resolve(red, blue)
            (outcome.winner, outcome.score) match {
                case (None, Some(score)) => assertEquals(score, Score(10, 10))
                case (Some(w), Some(score)) =>
                    assertEquals(score.of(w), 10)
                    val expected = if (outcome.decision == Decision.Knockdown) 8 else 9
                    assertEquals(score.of(w.other), expected)
                case (Some(_), None) => assertEquals(outcome.decision, Decision.Knockout)
                case (None, None)    => fail(s"a round with neither winner nor score: $outcome")
            }
        }
    }

    property("a knockout always has a winner, whose power beat the other's effective chin") {
        Prop.forAll(effective, effective) { (red, blue) =>
            val outcome = resolve(red, blue)
            if (outcome.decision == Decision.Knockout) {
                val (winner, loser) = if (outcome.winner.contains(Side.Red)) (red, blue) else (blue, red)
                assert(outcome.winner.isDefined)
                assert(winner.power > loser.effectiveChin)
            }
        }
    }
}
