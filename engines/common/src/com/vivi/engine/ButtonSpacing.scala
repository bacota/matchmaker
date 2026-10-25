package com.vivi.engine

/** The room between a button and the text or button beside it, for a page to put among its styles.
  *
  * A page built by a script joins its markup with no whitespace between elements, so a button in a line of text would
  * otherwise touch the word beside it. Only in a line of text -- a paragraph, a list item, a cell, a caption: the
  * boards are grids of buttons and the control rows are flex rows with a gap, and a margin there would push the squares
  * off their cells or double the gap. The ⓘ is left out, being a 44px box with its own room round the glyph, and so is
  * the tip after it, which is placed absolutely. Zero specificity, so a page that spaces its buttons itself wins
  * wherever it says so. The same rule as matchmaker's `app.css`, which can afford to apply it more widely.
  */
object ButtonSpacing {

    val css: String =
        """  :where(p, li, td, th, dd, label, legend) > :where(span, a, strong, em, b, i, small, time, code, button:not(.tip-toggle)) + :where(button:not(.tip-toggle)),
  :where(p, li, td, th, dd, label, legend) > :where(button:not(.tip-toggle)) + :where(span, a, strong, em, b, i, small, time, code) {
    margin-inline-start: .75rem;
  }"""
}
