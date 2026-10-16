package com.vivi.matchmaker.ui

import scala.scalajs.js
import org.scalajs.dom

/** Keeps the keyboard's focus on the page when the element holding it is rebuilt or removed.
  *
  * Most of the page is drawn by `child <--` over a store list: a section over the list it reloads, a row over the match
  * it shows, the whole tournament page over the tournament. When the answer lands, the refresh button that was pressed,
  * the row's Cancel, the form's Save are new elements, and the focused one went out with the old. A browser drops the
  * focus to `<body>` then, and a keyboard or screen-reader user is put back at the top of the document and told
  * nothing. It happened from dozens of places, and fixing each one by hand left the next one written to fail the same
  * way, so it is caught here once instead.
  *
  * Whenever the focus is lost that way, it goes to the control that replaced the one that had it -- the same kind of
  * element with the same name and caption -- and where there is none, because the row or the button really is gone, to
  * the heading of the section or card it was in. A control is matched only when that is unambiguous: one control of
  * that name, or as many as before and so the one in the same place. A row's Cancel removing its own row must not leave
  * the focus on the next row's Cancel, where the next Enter would cancel something else.
  *
  * Not for a change of screen, which `Views.focusNewScreen` handles, and never when the focus is anywhere but `<body>`:
  * code that moved it on purpose -- a dialog handing it back to its trigger, a status line taking it -- has already
  * said where it belongs.
  */
object FocusKeeper {

    /** The element that last took the focus, and what it is called, so its replacement can be found. `ordinal` and
      * `count` place it among the elements of the same name at the time.
      */
    private case class Focused(element: dom.Element, key: String, ordinal: Int, count: Int)

    private var last: Option[Focused] = None

    /** Where focus was lost from while there was no heading there yet to take it -- a screen still saying "Loading…" --
      * and until when to go on looking.
      */
    private var pending: Option[(dom.Element, Double)] = None

    private val pendingMillis = 5000.0

    def install(): Unit = {
        dom.document.addEventListener(
          "focusin",
          (e: dom.FocusEvent) =>
              e.target match {
                  case el: dom.Element if el != dom.document.body => remember(el)
                  case _                                          => ()
              },
          useCapture = true
        )
        // A player who clicks away from a control, onto text or blank page, has let go of it on purpose: the focus is
        // on `<body>` then too, and a later redraw that removes the control must not hand it back and scroll there. A
        // blur that leaves the element in the page is that; one from its removal finds it gone by the time this looks.
        dom.document.addEventListener(
          "focusout",
          (e: dom.FocusEvent) =>
              e.target match {
                  case el: dom.Element =>
                      js.Promise
                          .resolve[Unit](())
                          .`then`[Unit](_ => if (el.isConnected && last.exists(_.element == el)) last = None)
                  case _ => ()
              },
          useCapture = true
        )
        // The same for focus already lost and waiting for a heading to appear: a click anywhere says the player has
        // moved on.
        dom.document.addEventListener("pointerdown", (_: dom.PointerEvent) => pending = None, useCapture = true)
        val observer = new dom.MutationObserver((records, _) => rescue(records))
        observer.observe(
          dom.document.body,
          new dom.MutationObserverInit {
              childList = true
              subtree = true
          }
        )
    }

    private def remember(el: dom.Element): Unit = {
        val key = keyOf(el)
        val same = alike(el.tagName, key)
        last = Some(Focused(element = el, key = key, ordinal = same.indexOf(el), count = same.length))
        pending = None
    }

    /** What a control is called: its kind, its name, its caption and its row. A busy button's spinner adds no text, so
      * a button is called the same before and after its request.
      */
    private def keyOf(el: dom.Element): String = {
        def attr(name: String) = Option(el.getAttribute(name)).getOrElse("")
        def text(selector: String) = Option(el.closest(selector)).map(_.textContent.trim).getOrElse("")
        // The row it is on as well, so that a row's Cancel is matched only to the same row's: a row that changed at
        // all is a different row, and the focus goes to the heading instead.
        List(
          el.tagName,
          attr("type"),
          attr("name"),
          attr("aria-label"),
          el.textContent.trim,
          text("label"),
          text("li, tr")
        )
            .mkString("|")
    }

    private def alike(tagName: String, key: String): Seq[dom.Element] =
        dom.document.querySelectorAll(tagName).toSeq.collect { case e: dom.Element if keyOf(e) == key => e }

    private def isLost: Boolean = {
        val active = dom.document.activeElement
        active == null || active == dom.document.body
    }

    private def rescue(records: js.Array[dom.MutationRecord]): Unit =
        if (!isLost) pending = None
        else
            last.filter(f => !f.element.isConnected) match {
                case Some(focused) =>
                    // The element that took the old one out of the page: the replacement, if any, is under it.
                    records.find(r => r.removedNodes.exists(_.contains(focused.element))).map(_.target) match {
                        case Some(parent: dom.Element) if !parent.classList.contains("screen") =>
                            last = None
                            // Last of all the screen itself -- a banner above every heading, dismissed -- unless there is
                            // no screen yet either, when whatever is drawn next under `parent` is waited for.
                            replacement(focused).orElse(headingNear(parent)).orElse(screen) match {
                                case Some(target) => take(target)
                                case None         => pending = Some(parent -> (js.Date.now() + pendingMillis))
                            }
                        case _ => ()
                    }
                case None =>
                    pending.foreach { (parent, until) =>
                        if (js.Date.now() > until || !parent.isConnected) pending = None
                        else headingNear(parent).foreach(take)
                    }
            }

    private def replacement(focused: Focused): Option[dom.html.Element] = {
        val same = alike(focused.element.tagName, focused.key).collect { case e: dom.html.Element => e }
        if (same.length == 1) same.headOption
        else if (same.length == focused.count && focused.ordinal >= 0) same.lift(focused.ordinal)
        else None
    }

    /** The heading of the section, card or dialog the lost element was in; or the first heading under where it was,
      * when what replaced it starts with one; or else the last heading before it -- the one it was under.
      */
    private def headingNear(parent: dom.Element): Option[dom.html.Element] = {
        val around = Option(parent.closest("section, .card, [role=dialog]")).getOrElse(parent)
        def before =
            dom.document
                .querySelectorAll("h2, h3")
                .filter(h => (h.compareDocumentPosition(parent) & dom.Node.DOCUMENT_POSITION_FOLLOWING) != 0)
                .lastOption
        Option(around.querySelector("h2, h3"))
            .orElse(Option(parent.querySelector("h2, h3")))
            .orElse(before)
            .collect { case h: dom.html.Element => h }
    }

    private def screen: Option[dom.html.Element] =
        Option(dom.document.querySelector("main.screen")).collect { case m: dom.html.Element => m }

    private def take(target: dom.html.Element): Unit = {
        pending = None
        if (target.tabIndex < 0 && !target.hasAttribute("tabindex")) target.setAttribute("tabindex", "-1")
        target.focus()
    }
}
