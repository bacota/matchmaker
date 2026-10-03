package com.vivi.matchmaker.ui

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

/** A list's outcome, read out of the map that holds every list's — `Outcomes.of`, behind `Store.loading`, `known` and
  * `failed`.
  *
  * What matters is what it does *not* do: say its answer again when another list's changes. Each saying redraws what is
  * drawn from it, and on a player's page a redraw fetched a game's challenges, whose answer was another list's change —
  * so the page asked for them for as long as it was open.
  */
class OutcomesSpec extends FunSuite {

    /** Everything `signal` emits while `change` runs, after its first value. */
    private def emitted[A](signal: Signal[A])(change: => Unit): List[A] = {
        val owner = new ManualOwner
        var seen = List.empty[A]
        signal.foreach(a => seen = seen :+ a)(using owner)
        val initial = seen
        change
        owner.killSubscriptions()
        seen.drop(initial.size)
    }

    test("another list's answer says nothing about this one") {
        val all = Var(Map.empty[String, Boolean])
        val loading = Outcomes.of(all.signal, "games")(_.isEmpty)
        val heard = emitted(loading) {
            all.update(_ + ("games" -> true))
            // From here on, only other lists: the challenges of a game, answered again and again.
            for (_ <- 1 to 5) all.update(_ + ("challenges:1" -> true))
            all.update(_ + ("challenges:1" -> false))
        }
        assertEquals(heard, List(false))
    }

    test("its own answer changing is said, each time it changes") {
        val all = Var(Map.empty[String, Boolean])
        val failed = Outcomes.of(all.signal, "games")(_.contains(false))
        val heard = emitted(failed) {
            all.update(_ + ("games" -> false))
            all.update(_ + ("games" -> false))
            all.update(_ + ("games" -> true))
            all.set(Map.empty)
        }
        assertEquals(heard, List(true, false))
    }
}
