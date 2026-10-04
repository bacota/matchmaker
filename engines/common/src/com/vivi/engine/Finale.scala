package com.vivi.engine

/** What a player is shown when their match ends: a picture that celebrates a win, or one that consoles a loss.
  *
  * A page puts [[Finale.markup]] under its status line and [[Finale.css]] among its styles, includes [[Finale.script]]
  * with its game's two pictures, and calls the function that defines once:
  *
  * {{{
  * const showFinale = finale();
  * ...
  * showFinale(state && state.completed && state.you ? (iWon ? "win" : "lose") : null);   // from render()
  * }}}
  *
  * Only for a player with a seat in the match. A draw shows nothing — there is nobody to cheer and nobody to console —
  * and so does the public board, where the viewer neither won nor lost.
  *
  * Drawn once per result rather than on every render: the page polls, and redrawing would restart the animation every
  * few seconds. Once closed it stays closed for the life of the page.
  *
  * Accessibility: the status line above already announces the result, so this adds no live region of its own; the
  * picture is an image with a name, and the close button is a real button of a size a finger can hit. The confetti
  * stands still for anybody who has asked for reduced motion.
  */
object Finale {

    /** One picture: the SVG, which names itself (`role="img"` and an `aria-label`), and the line shown under it. */
    case class Art(svg: String, caption: String)

    val markup: String =
        """<section id="finale" class="finale" hidden aria-labelledby="finale-caption">
    <div id="finale-art" class="finale-art"></div>
    <p id="finale-caption" class="finale-caption"></p>
    <button type="button" id="finale-close" class="finale-close">Close</button>
  </section>"""

    val css: String =
        """  .finale { text-align: center; margin: 0 0 1.5rem; padding: 1rem; border-radius: 12px;
             border: 1px solid var(--line, #8886); }
  .finale[hidden] { display: none; }
  .finale.win { background: color-mix(in srgb, #e0a800 12%, transparent); }
  .finale.lose { background: color-mix(in srgb, #4a78c2 10%, transparent); }
  .finale-art svg { display: block; width: 100%; max-width: 14rem; height: auto; margin: 0 auto; }
  .finale-caption { font-size: 1.125rem; font-weight: 700; margin: .5rem 0 .75rem; }
  .finale-close { font: inherit; font-size: 16px; min-height: 44px; min-width: 44px; padding: .5rem 1.25rem;
                  border-radius: 6px; border: 1px solid var(--line, #8886); background: transparent;
                  color: inherit; cursor: pointer; }
  .finale .confetti { animation: finale-fall 2.4s ease-in infinite; }
  .finale .confetti:nth-of-type(2n) { animation-duration: 3.1s; animation-delay: .4s; }
  .finale .confetti:nth-of-type(3n) { animation-duration: 2.7s; animation-delay: .9s; }
  @keyframes finale-fall { from { transform: translateY(-12px); opacity: 0; } 20% { opacity: 1; }
                           to { transform: translateY(40px); opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .finale .confetti { animation: none; } }"""

    /** Defines `finale()`, which answers the function a page's `render()` calls with "win", "lose" or nothing. */
    def script(win: Art, lose: Art): String = {
        def art(a: Art) = ujson.Obj("svg" -> a.svg, "caption" -> a.caption)
        val arts = HtmlText.scriptSafe(ujson.write(ujson.Obj("win" -> art(win), "lose" -> art(lose))))
        """  /* The picture at the end of a match; see Finale. */
  function finale() {
    const arts = """ + arts + """;
    const box = document.getElementById("finale");
    let shown = null, closed = false;
    document.getElementById("finale-close").addEventListener("click", () => { closed = true; box.hidden = true; });
    return function (result) {
      if (result === shown) return;
      shown = result;
      if (!result || closed || !arts[result]) { box.hidden = true; return; }
      document.getElementById("finale-art").innerHTML = arts[result].svg;
      document.getElementById("finale-caption").textContent = arts[result].caption;
      box.className = "finale " + result;
      box.hidden = false;
    };
  }"""
    }

    /* Confetti over the top of a winning picture: small shapes in a band above it, each falling a
     * little way and fading. Decorative, so hidden from a screen reader with the rest of the drawing. */
    private val confetti: String =
        Seq(
          ("#e0a800", 22, 14),
          ("#d32f2f", 48, 4),
          ("#2e7d32", 150, 8),
          ("#1f5fae", 176, 16),
          ("#e0a800", 132, 2),
          ("#d32f2f", 168, 30),
          ("#1f5fae", 34, 32),
          ("#2e7d32", 64, 22)
        ).map((colour, x, y) =>
            s"""<rect class="confetti" x="$x" y="$y" width="6" height="10" rx="1" fill="$colour" transform="rotate(20 $x $y)"/>"""
        ).mkString

    private def svg(label: String, body: String): String =
        s"""<svg viewBox="0 0 200 220" role="img" aria-label="$label" xmlns="http://www.w3.org/2000/svg">$body</svg>"""

    /** A trophy, for a game that has no picture of its own. */
    val trophy: Art = Art(
      svg(
        "A gold trophy",
        confetti +
            """<g stroke="#8a6500" stroke-width="3" stroke-linejoin="round">
  <path d="M62 50 H138 V88 Q138 132 100 140 Q62 132 62 88 Z" fill="#e0a800"/>
  <path d="M62 60 H40 Q38 96 66 104" fill="none" stroke-width="6"/>
  <path d="M138 60 H160 Q162 96 134 104" fill="none" stroke-width="6"/>
  <rect x="92" y="140" width="16" height="26" fill="#e0a800"/>
  <rect x="70" y="166" width="60" height="16" rx="3" fill="#e0a800"/>
  <rect x="60" y="182" width="80" height="14" rx="3" fill="#b88a00"/>
</g>
<path d="M100 70 l7 14 15 2 -11 10 3 15 -14 -7 -14 7 3 -15 -11 -10 15 -2 z" fill="#fff6c8"/>"""
      ),
      "You win!"
    )

    /** A sun coming out from behind a cloud, for a game that has no consolation of its own. */
    val brighterDays: Art = Art(
      svg(
        "A sun coming out from behind a rain cloud",
        """<g fill="#e0a800" stroke="#8a6500" stroke-width="2">
  <circle cx="132" cy="70" r="30"/>
</g>
<g stroke="#e0a800" stroke-width="5" stroke-linecap="round">
  <line x1="132" y1="22" x2="132" y2="32"/><line x1="172" y1="40" x2="165" y2="47"/>
  <line x1="184" y1="72" x2="174" y2="72"/><line x1="96" y1="40" x2="103" y2="47"/>
</g>
<path d="M44 140 Q40 110 70 108 Q78 84 106 92 Q130 90 134 114 Q160 116 158 140 Z"
      fill="#c9d3e0" stroke="#5b6b80" stroke-width="3" stroke-linejoin="round"/>
<g stroke="#4a78c2" stroke-width="4" stroke-linecap="round">
  <line x1="66" y1="152" x2="60" y2="168"/><line x1="94" y1="152" x2="88" y2="168"/>
  <line x1="122" y1="152" x2="116" y2="168"/><line x1="80" y1="176" x2="74" y2="192"/>
  <line x1="108" y1="176" x2="102" y2="192"/>
</g>"""
      ),
      "Not this time. Good game — there's always the next one."
    )

    /** Confetti for a game's own winning picture. */
    def withConfetti(body: String): String = confetti + body

    /** An SVG named `label`, drawn on the same 200 × 220 canvas as every other picture here. */
    def picture(label: String, body: String): String = svg(label, body)
}
