package com.vivi.wavinghands

import java.time.Instant
import munit.FunSuite
import com.vivi.engine.Outcome
import Protocol.MoveRequest

/** The rules, played turn by turn: gestures in, the battle out. Each turn goes through [[Engine.orders]], as a player's
  * orders do, so a default target or casting here is the one a player who names none gets.
  */
class RulesSpec extends FunSuite {

    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val A = 1L
    private val B = 2L
    private val C3 = 3L

    private def duel(n: Int = 2): Duel =
        Duel(
          matchId = "m",
          wizards =
              (1 to n).toList.map(i => Wizard(s"sub-$i", i.toLong, Some(List("Ann", "Bob", "Cat", "Dan")(i - 1)))),
          turns = Nil,
          current = Nil,
          isPublic = false,
          isCompleted = false,
          createdAt = at,
          moveCallbackUrl = None,
          resultsCallbackUrl = None
        )

    private def req(gestures: String, extra: MoveRequest => MoveRequest = identity): MoveRequest = {
        val (l, r) = gestures.split(" ").toList match {
            case l :: r :: Nil => (l, r)
            case other         => fail(s"bad gestures '$gestures'")
        }
        extra(MoveRequest(left = l, right = r))
    }

    /** One turn: each wizard's orders, by participant id. Wizards left out do nothing with both hands. */
    private def turn(m: Duel, orders: (Long, MoveRequest)*): Duel = {
        val byWizard = orders.toMap
        val all = m.battle.active.map { w =>
            Engine.orders(m, w, byWizard.getOrElse(w.participantId, MoveRequest("-", "-")), at) match {
                case Right(o)  => o
                case Left(why) => fail(s"orders for ${w.name} refused: $why")
            }
        }
        m.copy(turns = m.turns :+ all)
    }

    /** Several turns of the same wizard's gestures, one string per turn, everyone else idle. */
    private def gestures(m: Duel, who: Long, turns: String*): Duel = turns.foldLeft(m)((d, g) => turn(d, who -> req(g)))

    private def damage(m: Duel, who: Long): Int = m.battle.wizard(who).get.damage
    private def last(m: Duel): List[String] = m.battle.reports.last.events

    // ---- gestures and castings ----------------------------------------------------------

    test("a series completes a spell on its last gesture, and overlapping series share gestures") {
        // F-F-F is paralysis; one more F is paralysis again.
        val m = gestures(duel(), A, "F -", "F -", "F -", "F -")
        assertEquals(m.battle.reports.map(_.events.count(_.contains("Paralysis"))), List(0, 0, 1, 1))
    }

    test("a clap with one hand is nothing, and breaks what that hand was building") {
        assertEquals(Rules.effective(Gesture.C, Gesture.P), (Gesture.Nothing, Gesture.P))
        assertEquals(Rules.effective(Gesture.C, Gesture.C), (Gesture.C, Gesture.C))
        // S, then a one-handed clap, then D: no missile.
        val m = gestures(duel(), A, "S -", "C -", "D -")
        assertEquals(damage(m, B), 0)
    }

    test("gestures completing two spells on one hand offer a choice, the biggest cast by default") {
        // Right hand W-P-P: counter-spell, and the last P is also a shield.
        val m = gestures(duel(), A, "- W", "- P")
        val w = m.battle.wizard(A).get
        val options = Rules.castingsFor(w, Gesture.Nothing, Gesture.P)
        assertEquals(options.map(_.key).toSet, Set("right=shield", "right=counter-spell"))
        assertEquals(Rules.defaultCasting(options).key, "right=counter-spell")
    }

    test("a spell ending in a two-handed gesture takes both hands, and competes with each hand's own spell") {
        // Magic mirror is C then a wave with both hands.
        val m = gestures(duel(), A, "C C")
        val options = Rules.castingsFor(m.battle.wizard(A).get, Gesture.W, Gesture.W)
        assert(options.exists(_.key == "both=magic-mirror"), options.map(_.key))
    }

    test("the short lightning bolt works once per battle") {
        val once = gestures(duel(), A, "W -", "D -", "D -", "C C")
        assertEquals(damage(once, B), 5)
        val twice = gestures(once, A, "W -", "D -", "D -", "C C")
        assertEquals(damage(twice, B), 5)
    }

    // ---- damage and protection ----------------------------------------------------------

    test("a missile does one point, and a shield stops it") {
        val hit = turn(turn(duel(), A -> req("S -")), A -> req("D -"))
        assertEquals(damage(hit, B), 1)
        val blocked = turn(turn(duel(), A -> req("S -")), A -> req("D -"), B -> req("P -"))
        assertEquals(damage(blocked, B), 0)
    }

    test("a counter-spell stops a cause light wounds, but not a finger of death") {
        // A: W-F-P cause light wounds at B; B: W-P-P counter-spell on themselves.
        val m = turn(
          turn(turn(duel(), A -> req("W -"), B -> req("W -")), A -> req("F -"), B -> req("P -")),
          A -> req("P -"),
          B -> req("P -")
        )
        assertEquals(damage(m, B), 0)
        assert(last(m).exists(_.contains("stopped by a counter-spell")), last(m))

        val death = gestures(duel(), A, "P -", "W -", "P -", "F -", "S -", "S -", "S -")
        val dead = turn(death, A -> req("D -"), B -> req("- P", _.copy(targets = Map("right" -> "wizard:2"))))
        assert(dead.battle.wizard(B).get.isDead)
    }

    test("a magic mirror turns a missile back on its caster") {
        val m = turn(turn(duel(), A -> req("S -"), B -> req("C C")), A -> req("D -"), B -> req("W W"))
        assertEquals(damage(m, B), 0)
        assertEquals(damage(m, A), 1)
    }

    test("dispel magic stops every other spell that turn, and shields its subject from a stab") {
        // B: C-D-P-W, dispel magic on turn 4. A: a missile and a stab at B, both on turn 4.
        val prepared = turn(turn(turn(duel(), B -> req("C C")), B -> req("D -")), A -> req("S -"), B -> req("P -"))
        val m = turn(prepared, A -> req("D stab"), B -> req("W -"))
        assertEquals(damage(m, B), 0)
        assert(last(m).exists(_.contains("Missile is dispelled")), last(m))
        assert(last(m).exists(_.contains("stab is blocked")), last(m))
    }

    test("dispel magic works whatever its subject: cast at nobody, or at a wizard under a counter-spell") {
        // B: C-D-P-W, dispel magic on turn 4. A: a missile at B on turn 4, and a counter-spell (W-P-P) at B with it.
        val prepared = turn(
          turn(turn(duel(), B -> req("C C")), A -> req("- W"), B -> req("D -")),
          A -> req("S P"),
          B -> req("P -")
        )
        val atNobody = turn(prepared, A -> req("D -"), B -> req("W -", _.copy(targets = Map("left" -> "nobody"))))
        assert(last(atNobody).exists(_.contains("Missile is dispelled")), last(atNobody))
        val countered = turn(
          prepared,
          A -> req("D P", _.copy(targets = Map("right" -> "wizard:2"))),
          B -> req("W -")
        )
        assert(last(countered).exists(_.contains("Counter-spell is dispelled")), last(countered))
        assert(!last(countered).exists(_.contains("Dispel Magic is stopped")), last(countered))
    }

    test("a fireball does five unless its subject resists heat; removing the resistance as it lands lets it through") {
        val resistant = gestures(duel(), B, "W -", "W -", "F -", "P -")
        assert(resistant.battle.wizard(B).get.isResistantToHeat)
        // F-S-S-D-D: the S-D on the way is a missile too, which resisting heat does nothing about.
        val shrugged = gestures(resistant, A, "F -", "S -", "S -", "D -", "D -")
        assertEquals(damage(shrugged, B), 1)
        assert(last(shrugged).exists(_.contains("washes harmlessly")), last(shrugged))

        // The same fireball with a remove enchantment at B on its last gesture: B burns.
        val fried = turn(
          gestures(resistant, A, "F -", "S P", "S D", "D W"),
          A -> req("D P", _.copy(targets = Map("right" -> "wizard:2")))
        )
        assertEquals(damage(fried, B), 6)
        assert(!fried.battle.wizard(B).get.isResistantToHeat)
    }

    test("cures heal, but not below no damage, and work in the same turn as the wound") {
        val wounded = gestures(duel(), A, "W -", "P -", "F -", "D -") // cause heavy wounds: 3
        assertEquals(damage(wounded, B), 3)
        val cured = turn(gestures(wounded, B, "D -", "F -"), B -> req("W -"))
        assertEquals(damage(cured, B), 2)
    }

    test("a wizard dies at fifteen points, and two dying together draw") {
        val bolts = (1 to 3).foldLeft(duel()) { (d, _) =>
            gestures(d, A, "D -", "F -", "F -", "D -", "D -")
        }
        assert(bolts.battle.wizard(B).get.isDead)
        assert(bolts.isOver)
        assertEquals(bolts.winners.map(_.participantId), List(A))

        val both = (1 to 3).foldLeft(duel()) { (d, _) =>
            Seq("D -", "F -", "F -", "D -", "D -").foldLeft(d)((x, g) => turn(x, A -> req(g), B -> req(g)))
        }
        assert(both.isOver && both.isDrawn, both.battle.wizards.toString)
    }

    // ---- monsters -----------------------------------------------------------------------

    test("a goblin attacks the turn it is summoned, and a shield stops it") {
        val m = gestures(duel(), A, "S -", "F -", "W -")
        assertEquals(m.battle.monsters.map(_.kind), List("goblin"))
        assertEquals(damage(m, B), 1)
        val shielded = turn(gestures(duel(), A, "S -", "F -"), A -> req("W -"), B -> req("P -"))
        assertEquals(damage(shielded, B), 0)
    }

    test("a stab does one point, and kills a goblin") {
        val m = gestures(duel(), A, "S -", "F -", "W -")
        val stabbed = turn(m, B -> req("stab -", _.copy(stab = Some("monster:1"))))
        assert(stabbed.battle.monster(1).get.isDead)
    }

    // ---- enchantments -------------------------------------------------------------------

    test("amnesia makes its subject repeat last turn's gestures") {
        val m = turn(
          turn(turn(duel(), A -> req("D -"), B -> req("W S")), A -> req("P -"), B -> req("F D")),
          A -> req("P -"),
          B -> req("P W")
        )
        val b = m.battle.wizard(B).get
        assert(b.hasAmnesia)
        assertEquals(Rules.forced(b, Hand.Left), Some(Gesture.P))
        assertEquals(Rules.forced(b, Hand.Right), Some(Gesture.W))
        // Whatever B asks for, B repeats.
        val next = turn(m, B -> req("D D"))
        assertEquals(next.battle.wizard(B).get.history.last, (Gesture.P, Gesture.W))
    }

    test("paralysis freezes a hand, a wave stiffening into a palm") {
        val m = turn(
          gestures(duel(), A, "F -", "F -"),
          A -> req("F -", _.copy(hands = Map("left" -> "right"))),
          B -> req("- W")
        )
        val b = m.battle.wizard(B).get
        assertEquals(b.paralysed, Some(Hand.Right))
        assertEquals(Rules.allowed(b, Hand.Right), List(Gesture.P))
    }

    test("fear forbids clap, digit, fingers and snap for a turn") {
        val m = gestures(duel(), A, "S -", "W -", "D -")
        val b = m.battle.wizard(B).get
        assert(b.hasFear)
        assertEquals(Rules.allowed(b, Hand.Left).toSet, Set(Gesture.P, Gesture.W, Gesture.Stab, Gesture.Nothing))
        assert(Engine.orders(m, b, req("D -"), at).isLeft)
    }

    test("two different enchantments of the cancelling kind on one subject undo each other") {
        // A casts fear (S-W-D) and amnesia (D-P-P) at B in the same turn.
        val m = gestures(duel(), A, "S D", "W P", "D P")
        val b = m.battle.wizard(B).get
        assert(!b.hasFear && !b.hasAmnesia)
        assert(last(m).exists(_.contains("cancel each other")), last(m))
    }

    test("an anti-spell breaks off its subject's spells in progress") {
        // B builds D-F-F-D toward a lightning bolt; A's anti-spell lands as B makes the third gesture.
        val m = turn(
          turn(turn(duel(), A -> req("S -"), B -> req("D -")), A -> req("P -"), B -> req("F -")),
          A -> req("F -"),
          B -> req("F -")
        )
        val after = turn(turn(m, B -> req("D -")), B -> req("D -"))
        assertEquals(damage(after, A), 0)
    }

    test("protection from evil shields its subject for this turn and the next three") {
        val m = gestures(duel(), B, "W -", "W -", "P -")
        assertEquals(m.battle.wizard(B).get.protectedThrough, Some(6))
        // Missiles on turns 5 and 7: the first is blocked, the second is not.
        val five = turn(turn(m, A -> req("S -")), A -> req("D -"))
        assertEquals(damage(five, B), 0)
        val seven = turn(turn(five, A -> req("S -")), A -> req("D -"))
        assertEquals(damage(seven, B), 1)
    }

    // ---- surrender and multiplayer --------------------------------------------------------

    test("two palms at once surrender, and the other wizard wins") {
        val m = turn(duel(), A -> req("P P"))
        assert(m.battle.wizard(A).get.isSurrendered)
        assertEquals(m.winners.map(_.participantId), List(B))
    }

    test("with three wizards the duel goes on after one surrenders, and the losers rank by how long they lasted") {
        val m = turn(duel(3), A -> req("P P"))
        assert(!m.isOver)
        assertEquals(m.pending.map(_.participantId).toSet, Set(B, C3))
        val done = turn(m, B -> req("P P"))
        assert(done.isOver)
        assertEquals(done.winners.map(_.participantId), List(C3))
        assertEquals(Duel.rank(done, done.wizard(C3).get), Some(1))
        assertEquals(Duel.rank(done, done.wizard(B).get), Some(2))
        assertEquals(Duel.rank(done, done.wizard(A).get), Some(3))
    }

    test("a wizard who has surrendered is out of the duel: no stab reaches them, and none may be aimed at them") {
        val m = turn(duel(3), A -> req("P P"))
        // Orders the engine would refuse, written as the rules would carry them out: a stab at the surrendered wizard.
        val stab = Orders(
          participantId = B,
          left = Gesture.Stab,
          right = Gesture.Nothing,
          casts = Nil,
          stab = Some(StabOrder(hand = Hand.Left, target = Target.Wizard(A))),
          monsters = Map.empty,
          takenAt = at,
          startedAt = at
        )
        val idle = Engine.orders(m, m.battle.wizard(C3).get, MoveRequest("-", "-"), at).toOption.get
        val after = m.copy(turns = m.turns :+ List(stab, idle))
        assertEquals(damage(after, A), 0)
        assert(Engine.orders(m, m.battle.wizard(B).get, req("stab -", _.copy(stab = Some("wizard:1"))), at).isLeft)
        val targets = Engine.yourTurn(m, m.battle.wizard(B).get, Map(A -> "Ann", B -> "Bob", C3 -> "Cat")).targets
        assert(!targets.map(_.code).contains("wizard:1"), targets)
    }

    test("when the match may not be drawn, a tie for first goes to one wizard and the others rank below them") {
        // All three surrender together: tied for first, until the tiebreak picks the first seated.
        val m = turn(duel(3).copy(canTie = false), A -> req("P P"), B -> req("P P"), C3 -> req("P P"))
        assert(m.isOver && !m.isDrawn)
        assertEquals(m.winners.map(_.participantId), List(A))
        val ranks = m.wizards.map(w => w.participantId -> Duel.rank(m, w)).toMap
        assertEquals(ranks, Map(A -> Some(1), B -> Some(2), C3 -> Some(2)))
        assertEquals(m.wizards.map(w => Duel.outcome(m, w)), List(Outcome.Win, Outcome.Loss, Outcome.Loss))
    }

    test("with three wizards an attack spell needs a target; with none named it goes to nobody") {
        val m = gestures(duel(3), A, "S -", "D -")
        assertEquals(damage(m, B) + damage(m, C3), 0)
        val aimed = turn(turn(duel(3), A -> req("S -")), A -> req("D -", _.copy(targets = Map("left" -> "wizard:3"))))
        assertEquals(damage(aimed, C3), 1)
    }
}
