package com.vivi.boxing

import upickle.default.write
import com.vivi.engine.{ButtonSpacing, Finale, LoginConfig, PageResource, PlayLive, SignIn, TurnTimer}
import com.vivi.engine.HtmlText.{escape, escapeScript, jsObject, scriptSafe}
import Protocol.given

/** The play page.
  *
  * A self-contained document with no assets, because the engine has no static hosting. Its own styles and scripts, and
  * the fighters page's, are written as files under `resources/boxing/` and inlined here; the values a script needs from
  * the server are in its page's `page-data` block. When the viewer already has a corner, the state is inlined into the
  * first render; otherwise the page is a shell that signs the player in and then fetches it.
  *
  * Nothing the page is given discloses a plan the server would not disclose: what is hidden is hidden in
  * `Engine.stateOf`, not here.
  *
  * The sign-in on it is [[com.vivi.engine.SignIn]], the same on every engine, which also serves the page the hosted
  * login redirects back to.
  */
object Html {

    private val boardCss: String = PageResource.text("boxing/board.css")
    private val boardScript: String = PageResource.text("boxing/board.js")
    private val fightersCss: String = PageResource.text("boxing/fighters.css")
    private val fightersScript: String = PageResource.text("boxing/fighters.js")

    /** The sign-in this page offers, keeping its tokens under the game's name as it always has. */
    val signIn: SignIn = SignIn("boxing")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("boxing")

    /** The winner: a boxer with a glove raised. Drawn in the page's ink, so it reads in either theme, with red gloves
      * and trunks.
      */
    val victory: Finale.Art = Finale.Art(
      Finale.picture(
        "A boxer with one glove raised in victory",
        Finale.withConfetti(
          """<g fill="currentColor">
  <circle cx="100" cy="62" r="18"/>
  <path d="M76 86 Q100 78 124 86 L116 142 L84 142 Z"/>
  <rect x="84" y="164" width="13" height="44" rx="5"/><rect x="103" y="164" width="13" height="44" rx="5"/>
</g>
<g stroke="currentColor" stroke-width="12" stroke-linecap="round" fill="none">
  <path d="M80 90 L66 60 L62 38"/>
  <path d="M120 92 L134 116 L138 128"/>
</g>
<g fill="#c62828">
  <circle cx="61" cy="28" r="14"/>
  <circle cx="139" cy="136" r="12"/>
  <path d="M82 140 H118 L120 168 H103 L100 154 L97 168 H80 Z"/>
</g>"""
        )
      ),
      "Winner! Your hand is raised."
    )

    /** The loser: slumped on the stool in the corner, a towel over the head. */
    val defeat: Finale.Art = Finale.Art(
      Finale.picture(
        "A boxer sitting on the stool in his corner, a towel over his head",
        """<g stroke="currentColor" stroke-width="5" stroke-linecap="round">
  <line x1="64" y1="158" x2="58" y2="208"/><line x1="136" y1="158" x2="142" y2="208"/>
</g>
<rect x="56" y="150" width="88" height="10" rx="3" fill="currentColor"/>
<g fill="currentColor">
  <path d="M82 106 Q100 100 118 106 L114 152 L86 152 Z"/>
  <rect x="72" y="160" width="14" height="46" rx="5"/><rect x="114" y="160" width="14" height="46" rx="5"/>
</g>
<g stroke="currentColor" stroke-width="11" stroke-linecap="round" fill="none">
  <path d="M86 110 L78 150"/><path d="M114 110 L122 150"/>
</g>
<circle cx="100" cy="103" r="16" fill="currentColor"/>
<path d="M74 122 Q72 74 100 70 Q128 74 126 122 L118 126 Q116 92 100 88 Q84 92 82 126 Z"
      fill="#f4f4f4" stroke="#777" stroke-width="2" stroke-linejoin="round"/>
<g fill="#c62828"><circle cx="77" cy="156" r="11"/><circle cx="123" cy="156" r="11"/></g>"""
      ),
      "You went the distance. Rest up — there's always a rematch."
    )

    def board(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val pageData = scriptSafe(
          s"""{"publicView":$publicView,"state":${state.map(s => write(s)).getOrElse("null")}}"""
        )
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
  /* The rules, a tap away, in a dialog over the page. */
${rulesDialogCss}
$boardCss
${ButtonSpacing.css}
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
${Finale.css}
</style>
</head>
<body>
<main>
  <h1>boxing</h1>
  ${SignIn.markup}
  ${rulesDialog}
  <!-- A round resolves while the page is idle rather than in answer to anything this player just
       did, so what happened is announced rather than left to be found. -->
  <p id="status" role="status" aria-live="polite">${escape(heading(state))}</p>
  <p id="round"></p>
  ${TurnTimer.markup}
  ${Finale.markup}

  <section id="plan" hidden aria-labelledby="plan-heading">
    <form id="plan-form" novalidate>
      <h2 id="plan-heading">Plan the round</h2>
      <p id="plan-intro"></p>
      <div id="plan-fields"></div>
      <p id="plan-left" class="left" aria-live="polite"></p>
      <p id="plan-preview" class="preview"></p>
      <button type="submit" class="primary" id="plan-submit">Throw these punches</button>
    </form>
  </section>

  <div id="signin" hidden></div>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>

  <section aria-label="corners"><div id="corners"></div></section>
  <section id="card-section" hidden><table id="card"></table></section>
</main>
<script type="application/json" id="page-data">$pageData</script>
<script>
$escapeScript
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${TurnTimer.script}
${Finale.script(victory, defeat)}

  // What board.js says when something goes wrong, from the engines' errors file.
  const errors = ${jsObject("noCorner" -> ErrorText.boxing.noCorner, "unreachable" -> ErrorText.page.unreachable)};
$boardScript
</script>
</body>
</html>
"""
    }

    /** How boxing is played, as the sections of a page or a dialog: `section` is the heading level each section is
      * headed at under the title. Static apart from the numbers, which come from the code that enforces them, so what
      * it says cannot drift from what the engine does.
      */
    private def rulesContent(section: String): String = {
        val looks = List("strength", "speed", "agility", "workrate")
            .map { name =>
                val (high, low) = Fighter.Looks(name)
                s"<tr><th scope=\"row\">${name.capitalize}</th><td>$high</td><td>$low</td></tr>"
            }
            .mkString("\n        ")
        s"""  <p class="lead">Two fighters, a set number of rounds. Each round both players secretly decide how their fighter
  spends its energy, and the round is scored when both have decided.</p>

  <$section>Your fighter</$section>
  <p>A fighter has five characteristics. You build one by sharing <strong>${Fighter.Budget} points</strong> among
  them, each between <strong>${Fighter.Min} and ${Fighter.Max}</strong>. You build it once, on the fighters page,
  and it fights every bout as it is.</p>
  <div class="table">
    <table>
      <caption>What each characteristic does</caption>
      <thead><tr><th scope="col">Characteristic</th><th scope="col">What it does</th></tr></thead>
      <tbody>
        <tr><th scope="row">Workrate</th><td>How many points you have to spend in every round.</td></tr>
        <tr><th scope="row">Speed</th><td>Adds twice its value to your offense.</td></tr>
        <tr><th scope="row">Agility</th><td>Adds twice its value to your defense.</td></tr>
        <tr><th scope="row">Strength</th><td>Adds twice its value to your power.</td></tr>
        <tr><th scope="row">Chin</th><td>How much punishment you can take before going down.</td></tr>
      </tbody>
    </table>
  </div>

  <$section>Planning a round</$section>
  <p>Each round you split your fighter's <strong>workrate</strong> among <strong>offense</strong>,
  <strong>defense</strong> and <strong>power</strong>. You must spend all of it, and none of the three can be
  negative. Your opponent plans at the same time, and neither of you sees the other's plan until both are in.</p>
  <p>Your fighter's numbers for the round are then:</p>
  <ul>
    <li>offense = what you put on offense + 2 × speed</li>
    <li>defense = what you put on defense + 2 × agility</li>
    <li>power = what you put on power + 2 × strength</li>
  </ul>

  <$section>How a round is scored</$section>
  <p>The tests below are taken in order, and the first one that either fighter passes decides the round. Each one
  compares your power with what your opponent can stand:</p>
  <div class="table">
    <table>
      <caption>From the top: the first that applies</caption>
      <thead><tr><th scope="col">If your power is more than the opponent's</th><th scope="col">Result</th></tr></thead>
      <tbody>
        <tr><td>defense + 3 × chin</td><td><strong>Knockout</strong>: you win the bout there and then.</td></tr>
        <tr><td>defense + chin</td><td><strong>Knockdown</strong>: you take the round 10–8.</td></tr>
        <tr><td>defense</td><td><strong>Telling blow</strong>: you take the round 10–9.</td></tr>
        <tr><td colspan="2">Nothing landed: whoever had more offense takes the round 10–9 for outworking the other —
        or, level on offense, whoever had more defense. Level on both, the round is even, 10–10.</td></tr>
      </tbody>
    </table>
  </div>
  <p>If both fighters land the same kind of blow, the one with more offense gets it, then the one with more defense.
  If they are level on both, neither does, and the round goes to the next test down.</p>

  <$section>Winning the bout</$section>
  <ul>
    <li>A knockout ends the bout, and the fighter who landed it wins.</li>
    <li>Otherwise the bout goes the distance, and the fighter with more points after the last round wins. Equal
    points is a draw.</li>
    <li>A bout is ${Bout.MinRounds} to ${Bout.MaxRounds} rounds long; the challenger chooses, and it is
    ${Bout.DefaultRounds} if they do not.</li>
    <li>If the match has a time limit and you run out of time, you lose the bout.</li>
  </ul>

  <$section>Sizing up your opponent</$section>
  <p>You see your own fighter's numbers but never your opponent's. Instead you get a word for their highest
  characteristic and one for their lowest. Chin cannot be seen, so it is never described. When two
  characteristics share the highest (or lowest) value, both are described; when three or more do, nothing stands
  out at that end and nothing is said.</p>
  <div class="table">
    <table>
      <caption>What you are told about the other fighter</caption>
      <thead><tr><th scope="col">Characteristic</th><th scope="col">If it is their highest</th>
      <th scope="col">If it is their lowest</th></tr></thead>
      <tbody>
        $looks
      </tbody>
    </table>
  </div>
  <p>Once a round is scored, everyone is shown both fighters' total offense, defense and power for it. Your tactics
  for each round — how you spent your workrate — are shown to you alone, and you never see your opponent's.</p>
"""
    }

    /** The rules' own look, under `.rules`, so that it reads the same on its page and in the dialog and touches nothing
      * else on a page it is put in.
      */
    private val rulesCss: String =
        PageResource.text("boxing/rules.css")

    /** How boxing is played, as a page of its own: for a link from outside a bout, and for anybody, signed in or not.
      */
    def rulesPage: String =
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Boxing — how to play</title>
<style>
  :root { color-scheme: light dark; --line: #8886; --ink: #222; --paper: #fafafa; }
  @media (prefers-color-scheme: dark) { :root { --ink: #eee; --paper: #16181c; } }
  body { margin: 0; min-height: 100vh; background: var(--paper); color: var(--ink);
         font: 16px/1.6 ui-sans-serif, system-ui, sans-serif; }
  main { margin: 0 auto; padding: 1.5rem 1rem 3rem; max-width: 40rem; }
  h1 { font-size: 1.5rem; margin: 0 0 .5rem; }
$rulesCss
</style>
</head>
<body>
<main class="rules">
  <h1>How to play boxing</h1>
${rulesContent("h2")}</main>
</body>
</html>
"""

    /** "How to play", and the rules it opens over the page in a modal dialog: the browser's own, which keeps the focus
      * inside it, closes on Escape, and gives the focus back to the button. A tap outside the box closes it too. For
      * the play page and the fighters page, under the name of the game.
      */
    private val rulesDialog: String =
        s"""<p class="help"><button type="button" id="rules-open" aria-haspopup="dialog">How to play</button></p>
  <dialog id="rules" class="rules" aria-labelledby="rules-title">
    <div class="rules-head">
      <h2 id="rules-title">How to play boxing</h2>
      <button type="button" id="rules-close" aria-label="Close">×</button>
    </div>
${rulesContent("h3")}  </dialog>
  <script>
  (function () {
    const dialog = document.getElementById("rules");
    document.getElementById("rules-open").addEventListener("click", () => dialog.showModal());
    document.getElementById("rules-close").addEventListener("click", () => dialog.close());
    // The backdrop is the dialog element itself; a click on it, outside the box, closes it.
    dialog.addEventListener("click", e => { if (e.target === dialog) dialog.close(); });
  })();
  </script>"""

    /** The dialog's look: a box of a size to read on a phone, scrolled inside itself, over a dimmed page. */
    private val rulesDialogCss: String =
        PageResource.text("boxing/rules-dialog.css") + "\n" + rulesCss

    /** The fighters page: a player's fighters, each of which they may edit here, and the form a new one is built with.
      * Giving one away has its route but is not offered on the page yet. Every change to a fighter is made in this
      * engine; matchmaker is told of each, and is where the list comes from, since this engine keeps no fighters of its
      * own.
      *
      * Served to anyone, like the play page, and for the same reason: a browser navigation carries no token, so this is
      * the shell that signs the player in and then fetches and posts with one. Without a login configured — the local,
      * trusted mode — it goes straight on, and the player is whoever `?as=` says.
      */
    def fightersPage(login: Option[LoginConfig], rules: Protocol.BuildRules): String = {
        val fightersData = s"""{"rules":{"budget":${rules.budget},"min":${rules.min},"max":${rules.max}}}"""
        s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>boxing — your fighters</title>
<style>
  /* The rules, a tap away, in a dialog over the page. */
${rulesDialogCss}
$fightersCss
${ButtonSpacing.css}
${SignIn.css}
</style>
</head>
<body>
<main>
  <h1>boxing</h1>
  ${SignIn.markup}
  ${rulesDialog}

  <!-- What the last change or build did. Focus is moved here when it is said, since the list it is
       about is redrawn; it is a live region as well, for readers that do not follow focus. -->
  <p id="status" role="status" aria-live="polite" tabindex="-1"></p>
  <div id="error" role="alert"></div>

  <div id="content" hidden>
    <section aria-labelledby="yours-heading">
      <h2 id="yours-heading">Your fighters</h2>
      <p id="yours-note" class="detail">Loading your fighters…</p>
      <ul id="fighters"></ul>
    </section>

    <section aria-labelledby="build-heading">
      <form id="build-form" novalidate>
        <h2 id="build-heading">Build a new fighter</h2>
        <p>Spread ${rules.budget} points across five characteristics, each from ${rules.min} to ${rules.max}.
           A fighter is built once: these are the numbers it fights every bout with. Its name and description you can change later.</p>
        <div class="field wide">
          <label for="f-name">Name</label>
          <input id="f-name" type="text" autocomplete="off" required maxlength="80">
        </div>
        <div class="field wide">
          <label for="f-description">Description <span class="hint">(optional)</span></label>
          <input id="f-description" type="text" autocomplete="off" maxlength="280">
        </div>
        <div id="build-fields"></div>
        <p id="build-left" class="left" aria-live="polite"></p>
        <button type="submit" class="primary" id="build-submit">Build this fighter</button>
      </form>
    </section>
  </div>

  <div id="signin" hidden></div>
</main>
<script type="application/json" id="page-data">$fightersData</script>
<script>
$escapeScript
${signIn.authScript(login)}
${signIn.signInScript}

  // What fighters.js says when something goes wrong, from the engines' errors file.
  const errors = ${jsObject(
              "signIn" -> ErrorText.boxing.fightersSignIn,
              "sayWho" -> ErrorText.boxing.fightersSayWho,
              "unreachable" -> ErrorText.boxing.fightersUnreachable,
              "notBuilt" -> ErrorText.boxing.notBuilt
            )};
$fightersScript
</script>
</body>
</html>
"""
    }

    /* The heading as first served, before any script runs; `describe()` in the page says the same thing. */
    private def heading(state: Option[Protocol.StateResponse]): String =
        state match {
            case None                                                     => "sign in to fight"
            case Some(s) if s.isCompleted && s.method.contains("forfeit") => "time ran out"
            case Some(s) if s.isCompleted =>
                val how = if (s.method.contains("knockout")) " by knockout" else " on points"
                if (s.isDrawn) "a draw on points"
                else
                    s.winner.fold("over") { w =>
                        s.you.fold(s"$w wins$how")(you => (if (you == w) "you win" else "you lose") + how)
                    }
            case Some(s) =>
                val mine = s.you.flatMap(you => s.corners.find(_.side == you))
                if (mine.isDefined && s.yourPlan.isEmpty) s"plan round ${s.round}"
                else s"waiting for ${s.waitingFor.mkString(" and ")}"
        }
}
