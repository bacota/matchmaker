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

    /** `content` as a modal dialog: a scrim over the page, closed by Escape, by a click on the backdrop, or by its own
      * Close button, all of which call `close` -- which puts focus back where the dialog was opened from. Focus moves
      * into the dialog as it opens. `labelledBy` is the id of its heading.
      *
      * `saving` is whether the save it holds is out, while which `close` refuses: the dialog says so, in a status line
      * of its own -- everything outside it is inert, the page's live region included -- and its Close is marked
      * unavailable.
      */
    def dialog(
        content: HtmlElement,
        labelledBy: String,
        close: () => Unit,
        saving: Signal[Boolean] = Val(false)
    ): HtmlElement =
        div(
          cls := "modal-scrim",
          inertBehind,
          onClick --> (event => if (event.target == event.currentTarget) close()),
          content.amend(
            cls := "modal",
            role := "dialog",
            htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
            aria.labelledBy := labelledBy,
            tabIndex := -1,
            inContext(node => onMountCallback(_ => node.ref.focus())),
            onKeyDown.filter(_.key == "Escape") --> { event =>
                event.stopPropagation()
                close()
            },
            Views.errorBanner,
            p(
              cls := "detail",
              role := "status",
              child.text <-- saving.map(if (_) "Saving… This closes once it is saved." else "")
            ),
            div(
              cls := "alternatives",
              button(
                tpe := "button",
                cls := "link",
                aria.disabled <-- saving,
                Buttons.app.close,
                onClick --> (_ => close())
              )
            )
          )
        )
}
