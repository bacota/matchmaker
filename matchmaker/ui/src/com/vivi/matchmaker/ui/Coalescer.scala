package com.vivi.matchmaker.ui

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

/** At most one request out per question, and at most one start per `spacingMs` — however often the question is asked.
  *
  * The store asks for a list whenever something says it may have changed, and a fault in what says so — a section that
  * fetches as it mounts, remounted by the very answer it fetched — asks for ever. Without this, every ask was a
  * request: a player's page once sent fifty a few seconds, and would have gone on until the tab was closed. With it, a
  * loop like that costs one request per `spacingMs` per list, and a list asked for while its request is out is answered
  * by the one request that follows, rather than by one each.
  *
  * Never by an answer that predates the ask. A list is often asked for *because* something just changed — a challenge
  * accepted, an invitation withdrawn — and the request already out may have left before the change, so its answer
  * cannot stand for the ask that came after. What an ask made meanwhile gets is the next request, started once the one
  * out has finished. Of the several asks it then stands for, the latest's `start` is the one run, being the latest idea
  * of what to fetch.
  *
  * An ask with nothing out and nothing recent starts at once, in the caller's own call: the ordinary case costs no
  * delay.
  *
  * @param now
  *   the time, in milliseconds
  * @param later
  *   runs a function after so many milliseconds; the browser's `setTimeout`, or a test's clock
  */
private[ui] final class Coalescer[K](spacingMs: Double, now: () => Double, later: (Double, () => Unit) => Unit) {

    private final class Slot {
        var running = false
        var startedAt = Double.NegativeInfinity
        var timerSet = false
        // The asks since the last start: one promise between them, and the latest of their starts.
        var waiting: Option[Promise[Unit]] = None
        var next: Option[() => Future[Unit]] = None
    }

    private val slots = scala.collection.mutable.Map.empty[K, Slot]

    /** Asks the question `key` names, to be answered by `start` — now, or by the next request if one is out or has only
      * just been made. Completes once an answer asked for no earlier than this has settled.
      */
    def apply(key: K)(start: () => Future[Unit]): Future[Unit] = {
        val slot = slots.getOrElseUpdate(key, new Slot)
        slot.next = Some(start)
        val promise = slot.waiting.getOrElse {
            val made = Promise[Unit]()
            slot.waiting = Some(made)
            made
        }
        pump(slot)
        promise.future
    }

    private def pump(slot: Slot): Unit =
        if (!slot.running && slot.waiting.isDefined) {
            val wait = slot.startedAt + spacingMs - now()
            if (wait > 0) {
                if (!slot.timerSet) {
                    slot.timerSet = true
                    later(wait, () => { slot.timerSet = false; pump(slot) })
                }
            } else {
                val promise = slot.waiting.get
                val start = slot.next.get
                slot.waiting = None
                slot.next = None
                slot.running = true
                slot.startedAt = now()
                val request =
                    try start()
                    catch { case NonFatal(e) => Future.failed(e) }
                request.onComplete { _ =>
                    slot.running = false
                    promise.success(())
                    pump(slot)
                }
            }
        }
}
