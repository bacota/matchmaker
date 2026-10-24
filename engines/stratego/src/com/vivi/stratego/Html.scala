package com.vivi.stratego

import upickle.default.write
import com.vivi.engine.{Finale, LoginConfig, PageResource, PlayLive, SignIn, TurnTimer}
import com.vivi.engine.HtmlText.{escape, scriptSafe}
import Protocol.given

/** The play page: the setup, the board, and the shell every engine's page has around them.
  *
  * The board is drawn from the state alone, and the state already leaves out every rank the viewer may not see — the
  * page hides nothing itself, since what the page receives anyone can read. It is drawn with the viewer's own army at
  * the bottom, which for Blue means turned round; the public board is drawn from Red's side.
  *
  * Ten squares across a phone is about 34 pixels a square, under the 44 a touch target should be. So the board is also
  * a set of labelled selects — the piece, and where it goes — that do everything a tap or a drag does at full size, and
  * that a screen reader or a keyboard can use as well as a finger. The board's squares are buttons too, each labelled
  * with its square and what is on it, and arrow keys move between them.
  *
  * A self-contained document with no assets, because the engine has no static hosting and a page that needs a second
  * request needs somewhere to serve it from. Its own styles and script are written as `resources/stratego/play.css` and
  * `play.js` and inlined here; the values the script needs from the server are in the `page-data` block. When the
  * viewer already has a seat, the state is inlined into the first render so the board is right before any script runs;
  * otherwise the page is a shell that signs the player in and then fetches it.
  *
  * The sign-in on it is [[com.vivi.engine.SignIn]], the same on every engine, which also serves the page the hosted
  * login redirects back to.
  */
object Html {

    /** The sign-in this page offers, keeping its tokens under the game's name as it always has. */
    val signIn: SignIn = SignIn("stratego")

    /** Play Live, its choice remembered under the same name. */
    val playLive: PlayLive = PlayLive("stratego")

    private val css: String = PageResource.text("stratego/play.css")

    private val script: String = PageResource.text("stratego/play.js")

    /** The winner: the enemy's flag taken and planted on the hill, under a shower of confetti. */
    val victory: Finale.Art = Finale.Art(
      Finale.picture(
        "A captured flag planted on a hilltop",
        Finale.withConfetti(
          """<path d="M20 206 Q100 150 180 206 Z" fill="#6d8f4e" stroke="#3f5a2a" stroke-width="3"/>
<rect x="97" y="56" width="6" height="128" rx="2" fill="currentColor"/>
<circle cx="100" cy="54" r="6" fill="#e0a800"/>
<path d="M103 62 Q130 52 156 64 Q140 80 156 98 Q130 86 103 96 Z" fill="#c62828" stroke="#7a1712"
      stroke-width="2" stroke-linejoin="round"/>
<path d="M126 70 l3 6 7 1 -5 5 1 7 -6 -3 -6 3 1 -7 -5 -5 7 -1 z" fill="#fff6c8"/>"""
        )
      ),
      "Victory! The flag is yours."
    )

    /** The loser: a white flag of surrender. */
    val defeat: Finale.Art = Finale.Art(
      Finale.picture(
        "A white flag of surrender",
        """<rect x="70" y="40" width="6" height="160" rx="2" fill="currentColor"/>
<circle cx="73" cy="38" r="6" fill="currentColor"/>
<path d="M76 48 Q104 38 130 50 Q156 62 170 52 L168 104 Q150 114 126 102 Q100 90 76 100 Z"
      fill="#f4f4f4" stroke="#777" stroke-width="2.5" stroke-linejoin="round"/>
<path d="M96 60 Q112 56 128 64 M96 78 Q114 72 140 84" fill="none" stroke="#bbb" stroke-width="2"/>"""
      ),
      "The army yields — this time. Regroup and go again."
    )

    def board(
        matchId: String,
        title: String,
        state: Option[Protocol.StateResponse],
        login: Option[LoginConfig],
        liveUrl: Option[String] = None,
        publicView: Boolean = false
    ): String = {
        val heading = state match {
            case Some(s) if s.isCompleted      => outcome(s)
            case Some(s) if s.phase == "setup" => "deploying"
            case Some(s)                       => s"${s.turn.getOrElse("")} to move"
            case None                          => "sign in to play"
        }
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
$css
${SignIn.css}
${PlayLive.css}
${TurnTimer.css}
${Finale.css}
</style>
</head>
<body>
$icons
<main>
  <h1>capture the flag</h1>
  <!-- Announced: the other player's move, and the result, arrive while this page is idle. -->
  <p id="status" role="status" aria-live="polite">${escape(heading)}</p>
  ${TurnTimer.markup}
  ${Finale.markup}
  <div class="board-bar">
    <button type="button" id="rules-button" aria-haspopup="dialog"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M4 5.5C4 4.7 4.7 4 5.5 4H11v16H5.5c-.8 0-1.5-.7-1.5-1.5zM20 5.5c0-.8-.7-1.5-1.5-1.5H13v16h5.5c.8 0 1.5-.7 1.5-1.5z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>Rules</button>
    <button type="button" id="key-button" popovertarget="key" aria-haspopup="dialog"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 11v6" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/><circle cx="12" cy="7.5" r="1.4" fill="currentColor"/></svg>Key</button>
  </div>
  <div id="board" role="group" aria-label="board"></div>
  <!-- Not a live region: what each step shows is announced by #news, below. -->
  <div id="replay" role="group" aria-label="replay" hidden>
    <button type="button" id="replay-first" aria-label="Opening position" title="Opening position (Home)"><span class="double" aria-hidden="true">◀◀</span></button>
    <button type="button" id="replay-back" aria-label="Previous move" title="Previous move (left arrow)">◀</button>
    <span id="replay-at"></span>
    <button type="button" id="replay-forward" aria-label="Next move" title="Next move (right arrow)">▶</button>
    <button type="button" id="replay-latest" aria-label="Latest position" title="Latest position (End)"><span class="double" aria-hidden="true">▶▶</span></button>
  </div>
  <!-- Announced: what the last move did, which the other player's arrives while this page is idle. -->
  <p id="news" role="status" aria-live="polite"></p>
  <div id="remaining" hidden></div>
  <div id="setup-controls" class="controls" hidden>
    <label>Swap<select id="swap-a"></select></label>
    <label>with<select id="swap-b"></select></label>
    <button id="swap" type="button">Swap</button>
    <button id="shuffle" type="button">Shuffle</button>
    <button id="deploy" type="button" class="primary">Deploy army</button>
  </div>
  <div id="move-controls" class="controls" hidden>
    <label>Piece<select id="move-from"></select></label>
    <label>To<select id="move-to"></select></label>
    <button id="move" type="button" class="primary">Move</button>
  </div>
  <div id="concede-bar" hidden>
    <button type="button" id="concede" aria-haspopup="dialog">Concede</button>
  </div>
  <dialog id="concede-dialog" aria-labelledby="concede-title" aria-describedby="concede-text">
    <h2 id="concede-title">Concede this match?</h2>
    <p id="concede-text">Your opponent wins. This cannot be undone.</p>
    <div class="actions">
      <!-- First, so that it has the focus when the dialog opens: the safe answer is the default. -->
      <button type="button" id="concede-cancel" autofocus>Keep playing</button>
      <button type="button" id="concede-confirm" class="give-up">Concede</button>
    </div>
  </dialog>
  <div id="lost"></div>
  <!-- What a square's label already says to a screen reader, shown to a mouse or keyboard after a
       second's rest; so it is hidden from the accessibility tree rather than said twice. -->
  <div id="tip" aria-hidden="true" hidden></div>
  <dialog id="rules" aria-labelledby="rules-title">
    <div class="head">
      <h2 id="rules-title">Rules</h2>
      <button type="button" class="close" aria-label="Close the rules" autofocus>✕</button>
    </div>
    $rules
  </dialog>
  <div id="key" popover role="dialog" aria-labelledby="key-title">
    <div class="head">
      <h2 id="key-title">Key</h2>
      <button type="button" class="close" popovertarget="key" popovertargetaction="hide" aria-label="Close the key">✕</button>
    </div>
    <dl>
      $key
    </dl>
    <p class="rule">The lower number wins a battle. Equal ranks both fall.</p>
    <dl>
      <dt><span class="chip Blue">?</span></dt><dd>An enemy piece you have not seen</dd>
      <dt><span class="chip Blue">?<span class="dot"></span></span></dt><dd>A piece that has moved but not been revealed — so its enemy knows it is not a mine
        or the flag</dd>
      <dt><span class="chip">${iconOf(
              "Sergeant"
            )}<span class="bar"></span></span></dt><dd>A piece the enemy has seen — when stepping back through the moves, seen by
        then</dd>
    </dl>
  </div>
  <!-- The sign-in form, rendered by renderSignIn() and shown whenever there is a login to
       offer and no seat to show for it. -->
  <div id="signin" hidden></div>
  <section id="players" aria-labelledby="players-title" hidden>
    <h2 id="players-title">Players</h2>
    <div id="seats"></div>
  </section>
  ${PlayLive.markup}
  <div id="error" role="alert"></div>
</main>
<script type="application/json" id="page-data">$pageData</script>
<script>
${signIn.authScript(login)}
${signIn.signInScript}
${playLive.script(liveUrl, matchId)}
${TurnTimer.script}
${Finale.script(victory, defeat)}
$replayScript

$script
</script>
</body>
</html>
"""
    }

    /** The position a replay shows, worked out on the page from [[Protocol.ReplayView]]: the opening, with the first
      * `k` of its moves made on it as the engine makes them. A battle names both ranks and reveals both pieces, a scout
      * that went more than one square is revealed, and a piece that moved is marked as having moved.
      *
      * Separate from the page's own script so that a test can run it as it is, and check that playing every move
      * arrives at the board the engine describes — see `ReplaySpec`.
      */
    private[stratego] val replayScript: String =
        """
  /* The position after the first `k` of `replay`'s moves: the pieces by square, and what each side
   * had lost by then, strongest first, as the state's own `lost` lists it. */
  function replayFrame(replay, k) {
    const by = {}, lost = { Red: [], Blue: [] };
    replay.opening.forEach(p => { by[p.square] = Object.assign({}, p); });
    replay.moves.slice(0, k).forEach(m => {
      const piece = by[m.from], defender = by[m.to], gap = Math.abs(m.to - m.from);
      delete by[m.from];
      piece.square = m.to;
      piece.isMoved = true;
      if (gap !== 1 && gap !== 10) piece.isRevealed = true;
      if (!m.battle) { by[m.to] = piece; return; }
      piece.rank = m.battle.attacker;
      piece.isRevealed = true;
      defender.rank = m.battle.defender;
      defender.isRevealed = true;
      if (m.battle.result !== "AttackerWins") lost[piece.side].push(m.battle.attacker);
      if (m.battle.result !== "DefenderWins") lost[defender.side].push(m.battle.defender);
      if (m.battle.result === "AttackerWins") by[m.to] = piece;
      else if (m.battle.result === "BothLost") delete by[m.to];
    });
    const order = ["Bomb", "Marshal", "General", "Colonel", "Major", "Captain", "Lieutenant", "Sergeant", "Miner",
                   "Scout", "Spy", "Flag"];
    return { by, lost: ["Red", "Blue"].map(side => ({ side, ranks: lost[side].sort((a, b) => order.indexOf(a) - order.indexOf(b)) })) };
  }
"""

    /** One icon per rank, drawn for this page. The officers and the sergeant wear simplified US Army insignia, which as
      * works of the US government are free to use, in their metals: four silver stars for the Marshal (a general), one
      * for the General (a brigadier), a silver eagle, a gold oak leaf, two silver bars joined, one silver bar, and
      * three chevrons, in green. The Engineer's (`Miner`'s) brown pick, the Scout's galloping horse — knees and hocks
      * folding its legs in under it, as a horse's do — the Assassin's (`Spy`'s) dagger, the black Mine (`Bomb`), and
      * the flag — in the other army's colour, through `--flag` — are plain symbols, not any published Stratego
      * edition's artwork, which is copyrighted.
      *
      * Each is a 24-unit square, except the four stars, which are a strip so that they can take a piece's whole width.
      * What is not metal is `currentColor`, the white of a coloured piece. Placed with `<use href="#rank-Marshal">`,
      * and decoration only — a square's label names its rank in words.
      */
    /** The rules as this engine plays them, which the page shows in a dialog. Pieces are named as the page names them —
      * see `NAME` — and numbered as the key numbers them. The move cap is the match's own, filled in when the dialog
      * opens.
      */
    private val rules: String =
        """<p><strong>Capture the enemy flag.</strong> Each side has 40 pieces whose ranks the other side cannot see.</p>
    <h3>Setting up</h3>
    <ul>
      <li>Arrange your army on your four home rows: swap any two pieces, then deploy. Neither side sees the other's.</li>
      <li>Either side may deploy first. Once deployed, a setup cannot be changed.</li>
    </ul>
    <h3>Moving</h3>
    <ul>
      <li>Red moves first, then the sides take turns, one piece a turn.</li>
      <li>A piece moves one square up, down, left or right, onto an empty square or onto an enemy piece to attack it.
        Never diagonally, never onto your own piece, and never into the two lakes.</li>
      <li>Mines and the Flag never move.</li>
      <li>A Scout may move any distance in a straight line over empty squares, and may attack at the end of it. It
        cannot jump. A Scout that moves more than one square shows the enemy what it is.</li>
      <li>No piece may move back and forth between the same two squares more than three times in a row.</li>
    </ul>
    <h3>Battles</h3>
    <ul>
      <li>Both pieces are revealed. The lower number wins, and the loser leaves the board. Equal ranks both fall.</li>
      <li>The Assassin takes the General (1), but only when the Assassin attacks. Otherwise it is the weakest piece.</li>
      <li>A Mine destroys any piece that attacks it, except an Engineer, which takes it.</li>
      <li>Any piece that attacks the Flag takes it.</li>
    </ul>
    <h3>Winning</h3>
    <ul>
      <li>Take the enemy's Flag and you win.</li>
      <li>A player with no move they may make when it is their turn loses — or the match is drawn, if neither side can
        move.</li>
      <li>After <span id="rules-cap">a set number of</span> moves, counting both sides', the match is drawn.</li>
      <li>A player may concede at any time, and the other side wins.</li>
      <li>In a timed match, a player whose time runs out loses.</li>
    </ul>
    <h3>What you can see</h3>
    <ul>
      <li>All of your own pieces. An enemy piece is a ? until a battle or a Scout's long move reveals it.</li>
      <li>A dot marks a piece, yours or the enemy's, that has moved but not been revealed: it cannot be a Mine or the
        Flag. An underline marks a piece that has been revealed.</li>
      <li>Hidden pieces stay hidden when the match ends.</li>
      <li>◀ and ▶ under the board, or the left and right arrow keys, step back and forward through the moves; ◀◀ and
        ▶▶, or Home and End on the board, go to the opening and the latest position. While stepping through, the dots
        and underlines show what each side knew at that move.</li>
    </ul>"""

    /** The key's lines, strongest first, named as the page's `NAME` names them. Numbered the classic European way — 1
      * is the Marshal, and the lower number wins — which is only how ranks are shown: `Rank.strength` is what decides a
      * fight. The page's `NUMBER` must agree.
      */
    private val key: String =
        List(
          (
            "Marshal",
            "1",
            "General",
            "The strongest piece — but it falls to the Assassin, if the Assassin attacks it."
          ),
          ("General", "2", "Brigadier", ""),
          ("Colonel", "3", "Colonel", ""),
          ("Major", "4", "Major", ""),
          ("Captain", "5", "Captain", ""),
          ("Lieutenant", "6", "Lieutenant", ""),
          ("Sergeant", "7", "Sergeant", ""),
          ("Miner", "8", "Engineer", "The only piece that survives attacking a mine, which it takes."),
          ("Scout", "9", "Scout", "Moves any distance in a straight line, and may attack at the end of it."),
          ("Spy", "", "Assassin", "Loses any other battle — but takes the General, if the Assassin attacks first."),
          ("Bomb", "", "Mine", "Never moves. Destroys any piece that attacks it except an Engineer."),
          ("Flag", "", "Flag", "Never moves. Take the enemy's to win.")
        ).map((rank, number, name, note) => keyEntry(rank = rank, number = number, name = name, note = note))
            .mkString("\n      ")

    /** A line of the key: the icon and number a rank is drawn with, its name, and what is special about it. */
    private def keyEntry(rank: String, number: String, name: String, note: String): String = {
        val noted = if (note.isEmpty) "" else s"<span>$note</span>"
        s"""<dt><span class="chip">${iconOf(rank)}</span>$number</dt><dd><strong>$name</strong>$noted</dd>"""
    }

    private def iconOf(rank: String): String =
        s"""<svg class="icon${
                if (rank == "Marshal") " wide" else ""
            }" aria-hidden="true" focusable="false"><use href="#rank-$rank"/></svg>"""

    private val icons: String =
        """<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false"><defs>
  <symbol id="rank-Marshal" viewBox="0 8.4 24 7.2"><path style="fill:var(--silver)" stroke="none" d="M3.20 8.60 L4.04 10.84 L6.43 10.95 L4.56 12.44 L5.20 14.75 L3.20 13.43 L1.20 14.75 L1.84 12.44 L-0.03 10.95 L2.36 10.84zM9.07 8.60 L9.91 10.84 L12.30 10.95 L10.43 12.44 L11.07 14.75 L9.07 13.43 L7.07 14.75 L7.71 12.44 L5.84 10.95 L8.23 10.84zM14.93 8.60 L15.77 10.84 L18.16 10.95 L16.29 12.44 L16.93 14.75 L14.93 13.43 L12.93 14.75 L13.57 12.44 L11.70 10.95 L14.09 10.84zM20.80 8.60 L21.64 10.84 L24.03 10.95 L22.16 12.44 L22.80 14.75 L20.80 13.43 L18.80 14.75 L19.44 12.44 L17.57 10.95 L19.96 10.84z"/></symbol>
  <symbol id="rank-General" viewBox="0 0 24 24"><path style="fill:var(--silver)" stroke="none" d="M12.00 2.10 L14.59 9.03 L21.99 9.36 L16.19 13.96 L18.17 21.09 L12.00 17.01 L5.83 21.09 L7.81 13.96 L2.01 9.36 L9.41 9.03z"/></symbol>
  <symbol id="rank-Colonel" viewBox="0 0 24 24"><path style="fill:var(--silver)" stroke="none" d="M12 6.2c-1 0-1.8.8-1.8 1.8 0 .5.2.9.5 1.2L3.2 4.6c-.7-.4-1.5.2-1.2 1l2 5.6c.6 1.7 2.2 2.8 4 2.8h1.4l-1.6 2.6 1.9.3-.6 3.3 1.6-1.1 1.3 2.1 1.3-2.1 1.6 1.1-.6-3.3 1.9-.3-1.6-2.6H16c1.8 0 3.4-1.1 4-2.8l2-5.6c.3-.8-.5-1.4-1.2-1l-7.5 4.6c.3-.3.5-.7.5-1.2 0-1-.8-1.8-1.8-1.8z"/><path style="fill:var(--silver)" stroke="none" d="M10.3 7.6 8.6 8.3l1.9.6z"/></symbol>
  <symbol id="rank-Major" viewBox="0 0 24 24"><path style="fill:var(--gold)" stroke="none" d="M12 1.8c1.6 1.3 2 2.7 1.3 4 1.7-.7 3.2-.3 3.7 1-.6 1-1.6 1.5-2.8 1.6 1.9.2 3.3 1.1 3.5 2.7-1 .7-2.3.7-3.6.2 1.5 1 2.3 2.3 2 3.9-1.3.4-2.6 0-3.6-.9.4 1.5.1 2.9-.9 3.9l-.6 1.2v3.2h-1v-3.2l-.6-1.2c-1-1-1.3-2.4-.9-3.9-1 .9-2.3 1.3-3.6.9-.3-1.6.5-2.9 2-3.9-1.3.5-2.6.5-3.6-.2.2-1.6 1.6-2.5 3.5-2.7-1.2-.1-2.2-.6-2.8-1.6.5-1.3 2-1.7 3.7-1-.7-1.3-.3-2.7 1.3-4z"/><path d="M12 5.5v13" style="stroke:#0005" stroke-width="1"/></symbol>
  <symbol id="rank-Captain" viewBox="0 0 24 24"><g style="fill:var(--silver)" stroke="none"><rect x="5" y="3" width="4.5" height="18" rx=".6"/><rect x="14.5" y="3" width="4.5" height="18" rx=".6"/><rect x="9" y="5" width="6" height="1.6"/><rect x="9" y="17.4" width="6" height="1.6"/></g></symbol>
  <symbol id="rank-Lieutenant" viewBox="0 0 24 24"><rect style="fill:var(--silver)" stroke="none" x="9.5" y="3" width="5" height="18" rx=".6"/></symbol>
  <symbol id="rank-Sergeant" viewBox="0 0 24 24"><g fill="none" stroke-linecap="butt" stroke-linejoin="miter"><path style="stroke:var(--halo)" stroke-width="4.4" d="m4 9 8-5 8 5M4 14.5l8-5 8 5M4 20l8-5 8 5"/><path style="stroke:var(--green)" stroke-width="2.6" d="m4 9 8-5 8 5M4 14.5l8-5 8 5M4 20l8-5 8 5"/></g></symbol>
  <symbol id="rank-Miner" viewBox="0 0 24 24"><g transform="rotate(-35 12 12)" style="fill:none;stroke-linecap:round"><path d="M3 9.5c5-5 13-5 18 0M12 6v15.5" style="stroke:var(--halo);stroke-width:4"/><path d="M3 9.5c5-5 13-5 18 0M12 6v15.5" style="stroke:var(--brown);stroke-width:2.2"/></g></symbol>
  <symbol id="rank-Scout" viewBox="0 0 24 24"><g fill="currentColor" stroke="none"><ellipse cx="11" cy="9.8" rx="5.6" ry="2.9" transform="rotate(-4 11 9.8)"/><path d="M13.6 7.4 17.4 2.8 20.2 3.9 16.8 10.8Z"/><path d="M17.2 3 17.8.9 19 2.3 23.3 6.3C23.8 6.8 23.4 7.6 22.8 7.5L21 7.4 18 5.8Z"/><path d="M17.4 3.1 16 4.6 15.2 6.6 16.6 5.2Z"/><path d="M5.8 8.2C3.6 6.6 1.8 6.4.4 7.4 1.8 7.8 2.6 9.2 2.8 11.4 3.8 10 4.8 9.6 6.2 10Z"/></g><g fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15.8 10.8 20 12.8 19.2 16.2"/><path d="M15 11.6 18 13.6 16.2 16.4"/><path d="M7.4 10.8 3.4 12.6 3.8 16.2"/><path d="M8.6 11.8 7 15 9.4 17.4"/></g></symbol>
  <symbol id="rank-Spy" viewBox="0 0 24 24"><g transform="rotate(45 12 12) translate(12 12) scale(1.3) translate(-12 -12)"><g style="fill:var(--halo);stroke:var(--halo);stroke-width:2;stroke-linejoin:round"><path d="M12 1 14.2 5v8.5H9.8V5z"/><rect x="6" y="13.5" width="12" height="2.4" rx="1.1"/><rect x="10.7" y="15.9" width="2.6" height="4.6"/><circle cx="12" cy="21.4" r="1.7"/></g><path style="fill:var(--silver)" stroke="none" d="M12 1 14.2 5v8.5H9.8V5z"/><path d="M12 4v9" style="stroke:#0005" stroke-width=".8"/><g style="fill:var(--black)" stroke="none"><rect x="6" y="13.5" width="12" height="2.4" rx="1.1"/><rect x="10.7" y="15.9" width="2.6" height="4.6"/><circle cx="12" cy="21.4" r="1.7"/></g></g></symbol>
  <symbol id="rank-Bomb" viewBox="0 0 24 24"><circle cx="10" cy="14" r="7" style="fill:var(--black);stroke:var(--halo);stroke-width:2;paint-order:stroke;stroke-linejoin:round"/><path d="m14.5 9.5 3-3M20 2v2M23 5h-2M22 3l-1.5 1.5"/></symbol>
  <symbol id="rank-Flag" viewBox="0 0 24 24"><path d="M6 21.5V3"/><path style="fill:var(--flag);stroke:var(--halo);stroke-width:2;paint-order:stroke;stroke-linejoin:round" d="M6 4h12l-3 4 3 4H6z"/></symbol>
</defs></svg>"""

    private def outcome(state: Protocol.StateResponse): String =
        if (state.clock.exists(_.timedOut.nonEmpty)) "time ran out"
        else if (state.isDrawn) "drawn"
        else if (state.ending.contains("conceded"))
            state.winner.flatMap(Side.parse).map(w => s"${w.other} conceded — $w wins").getOrElse("over")
        else if (state.ending.contains("flag")) state.winner.map(w => s"$w captured the flag").getOrElse("over")
        else state.winner.map(w => s"$w wins").getOrElse("over")
}
