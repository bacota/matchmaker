package com.vivi.rps

import munit.FunSuite

class ShapeSpec extends FunSuite {

    test("each shape beats exactly one other and loses to exactly one") {
        Shape.values.foreach { shape =>
            assertEquals(Shape.values.count(shape.canBeat), 1, s"$shape should beat one shape")
            assertEquals(Shape.values.count(_.canBeat(shape)), 1, s"$shape should lose to one shape")
        }
    }

    test("the cycle is the one everybody knows") {
        assert(Shape.Rock.canBeat(Shape.Scissors))
        assert(Shape.Scissors.canBeat(Shape.Paper))
        assert(Shape.Paper.canBeat(Shape.Rock))
    }

    test("a shape does not beat itself, which is what makes a draw") {
        Shape.values.foreach(shape => assert(!shape.canBeat(shape)))
    }

    test("a throw is named by word or by initial, in any case") {
        assertEquals(Shape.parse("rock"), Some(Shape.Rock))
        assertEquals(Shape.parse("  Paper "), Some(Shape.Paper))
        assertEquals(Shape.parse("SCISSORS"), Some(Shape.Scissors))
        assertEquals(Shape.parse("r"), Some(Shape.Rock))
        assertEquals(Shape.parse("S"), Some(Shape.Scissors))
        // Near misses are refused rather than guessed at: the player is told what a throw is called.
        assertEquals(Shape.parse("scissor"), None)
        assertEquals(Shape.parse(""), None)
    }

    test("the two sides are named the several ways a role might spell them") {
        assertEquals(Side.parse("One"), Some(Side.One))
        assertEquals(Side.parse("1"), Some(Side.One))
        assertEquals(Side.parse("player two"), Some(Side.Two))
        assertEquals(Side.parse("P2"), Some(Side.Two))
        assertEquals(Side.parse("X"), None)
        assertEquals(Side.One.other, Side.Two)
    }
}
