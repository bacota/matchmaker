package com.vivi.matchmaker.ui

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** What makes a dialog modal for more than the eye.
  *
  * A scrim stops the mouse reaching the page behind it, but not Tab, and not a screen reader's own navigation:
  * `aria-modal` is a claim, not an enforcement. So while a dialog is mounted, everything outside it is made `inert` —
  * unfocusable, unclickable and out of the accessibility tree — and given back when it unmounts.
  */
object Modal {

    /** Makes the rest of the page inert while the element this is given to is mounted: every sibling of it and of each
      * of its ancestors, up to the body. One already inert is left alone, and left inert afterwards, so that a dialog
      * opened over another gives back only what it took.
      */
    def inertBehind: Modifier[HtmlElement] = {
        var taken = List.empty[dom.Element]
        Seq(
          inContext[HtmlElement](node =>
              onMountCallback { _ =>
                  var at: dom.Element = node.ref
                  while (at != null && at != dom.document.body) {
                      val parent = at.parentNode
                      var sibling = if (parent == null) null else parent.firstChild
                      while (sibling != null) {
                          sibling match {
                              case element: dom.Element if element != at && !element.hasAttribute("inert") =>
                                  element.setAttribute("inert", "")
                                  taken = element :: taken
                              case _ => ()
                          }
                          sibling = sibling.nextSibling
                      }
                      at = at.parentNode match {
                          case element: dom.Element => element
                          case _                    => null
                      }
                  }
              }
          ),
          onUnmountCallback { _ =>
              taken.foreach(_.removeAttribute("inert"))
              taken = Nil
          }
        )
    }
}
