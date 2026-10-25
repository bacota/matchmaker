package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** The main page's sections about the caller's own matches: due, in progress and recently completed. The game screen
  * shows the same sections narrowed to its game.
  */
object MatchSections {

    import Views.{field, listing, refresh, refreshableSection}

    /* Whether each of the two match lists is reloading. Shared by the home screen's copy of each section and the
     * game screen's, which are never both on screen — and, more to the point, by the sections
     * themselves and by the actions below that change what belongs in them. */
    private[ui] val refreshingDue: Var[Boolean] = Var(false)
    private[ui] val refreshingActive: Var[Boolean] = Var(false)

    /* And for the completed list, which a refresh can move a row *into*. Shared by the home screen's
     * "Recently Completed" and the game screen's "Your Completed Matches": they are two views of the
     * one list, only one of them is ever on screen, and `reloadCompleted` fills both. */
    private[ui] val refreshingCompleted: Var[Boolean] = Var(false)

    /** Reloads the sections a re-check with the engine may have changed.
      *
      * Asking the engine is how a finish that never reached matchmaker — a callback that was lost, a game that simply
      * ends without saying so — is discovered, so the answer can be that the match is over. When it is, the row the
      * player clicked belongs in "Recently Completed" and nowhere else, and leaving it in "Current Matches" with a Play
      * button on it is worse than stale: it offers a turn in a match that has none.
      *
      * The completed list is reloaded only when the match actually finished. Every refresh could reload it, but the
      * usual answer is "still being played" — that is the whole point of the button — and two extra requests to be told
      * nothing changed is what the per-section reloads exist to avoid. `reloadCompleted` brings the result rows with
      * it, which the completed rows are drawn from.
      *
      * Due and active are reloaded either way: the news may be that the match is over, but it may equally be that the
      * turn has moved, which is what moves a row between those two.
      */
    private[ui] def reloadAfterMatchRefresh(refreshed: Match): Unit = {
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        if (refreshed.isCompleted || refreshed.isCancelled) refresh(refreshingCompleted, () => Store.reloadCompleted())
    }

    /** "List of all matches a player has a turn due" — the first thing in `ui.txt`, and the only list shown expanded
      * from the start, because it is the one that needs acting on.
      */
    /** The matches it is this player's turn in.
      *
      * `game` narrows it to one game, which is what the game screen shows. Filtered from the list the home page already
      * has rather than fetched per game, for the reason [[gameHistory]] is: it is the same list in the same order, and
      * a second request would only be a second chance for the two screens to disagree about it.
      */
    private[ui] def dueSection(game: Option[Game] = None): HtmlElement =
        refreshableSection(
          heading = Headings.matches.yourTurn,
          refreshing = refreshingDue,
          reload = () => Store.reloadDue(),
          subsection = false
        )(
          listing(Store.due.signal.map(matchesIn(game)), Store.loading(Store.Fetch.Due))(
            p(
              cls := "empty",
              game.fold("Nothing is waiting on you.")(g => s"Nothing is waiting on you in ${g.displayName}.")
            )
          )(matches => ul(matches.map(MatchTables.matchRow)))
        )

    /* One game's matches, or all of them. The game screens show a slice of each list rather than a
     * list of their own, so this is the slice. */
    private def matchesIn(game: Option[Game])(matches: Seq[MatchSummary]): Seq[MatchSummary] =
        game.fold(matches)(g => matches.filter(_.gameId == g.gameId))

    /** The matches still being played. Expanded rather than behind a toggle: the wire-frame lists it as one of the four
      * things the main page shows, and a section that has to be opened to find out whether it is empty is not shown.
      */
    private[ui] def myMatchesSection(game: Option[Game] = None): HtmlElement =
        refreshableSection(
          heading = Headings.matches.current,
          refreshing = refreshingActive,
          reload = () => Store.reloadActive(),
          subsection = false
        )(
          listing(Store.active.signal.map(matchesIn(game)), Store.loading(Store.Fetch.Active))(
            p(
              cls := "empty",
              game.fold("You are not in any matches.")(g => s"You are not in any matches of ${g.displayName}.")
            )
          )(
            MatchTables.matchTable(
              _,
              showGame = game.isEmpty,
              showParameters = game.nonEmpty,
              outcome = MatchTables.playingStatus,
              view = MatchTables.playControls,
              dateHeading = "Started",
              dated = summary => Some(summary.start),
              showResult = false,
              // The main page lists every game's matches as a plain game's: who was played, not as what.
              characterColumns = game.nonEmpty
            )
          )
        )

    /** The caller's finished matches in every game, a window of time at a time. */
    private[ui] def recentlyCompletedSection: HtmlElement =
        refreshableSection(
          heading = Headings.matches.recentlyCompleted,
          refreshing = refreshingCompleted,
          reload = () => Store.reloadCompleted(),
          subsection = false
        )(
          completedWindow(Store.CompletedList.Mine(None), "Nothing finished yet.")(
            // Every game's, so no column for any one game's parameters: they differ from game to game.
            MatchTables.matchTable(
              _,
              showGame = true,
              showParameters = false,
              outcome = MatchTables.playerOutcome,
              view = MatchTables.reviewControl,
              characterColumns = false
            )
          )
        )

    /** One completed list, a window of time at a time: the frame to look back over, which window this is, the matches
      * in it, and Prev and Next to move to the newer and older windows of the same length.
      *
      * The same for every completed list — the main page's, a game's, somebody else's page's — with what is shown of a
      * row left to the caller. Fetches nothing when drawn: the list is asked for when its screen is arrived at or its
      * game opened, and again by the controls here, each of which is a click. See `Store.showCompleted`.
      *
      * Prev is there once the reader has gone back from the most recent window, and Next while the list holds anything
      * older than the window shown: once the oldest match this list could ever show is on screen, there is no Next.
      * `never` is what an empty most-recent window says when there is nothing further back either.
      */
    private[ui] def completedWindow(list: Store.CompletedList, never: String)(
        render: Seq[MatchSummary] => HtmlElement
    ): Modifier[HtmlElement] = {
        // This list's window, and nothing while another list's is held or this one's is on its way.
        val view: Signal[Option[CompletedPage]] =
            Store.completedView.signal.map(_.filter(_.list == list).map(_.page)).distinct
        // This list's own request and its own failure -- not `Fetch.Completed`'s, which is the last
        // request's for whichever list asked.
        val busy = Store.completedLoading.signal.map(_.contains(list)).distinct
        val failed = Store.completedFailed.signal.map(_.contains(list)).distinct

        /* Try again, for a list whose first window failed to load. It stays where it is while the
         * retry is out -- so the focus on it is not dropped -- and refuses a second press rather than
         * being disabled, which would drop the focus just the same. The status line says what is
         * happening; once there is a window, the button gives way to it.
         *
         * The focus follows to the status line, which is then saying which window it is -- but only if
         * the button still had it when it went. A reader who moved on while the retry was out is left
         * where they went, and hears the result from the live line. Whether it had it is noted as the
         * button is unmounted, which Laminar does before taking it out of the page: by the time the
         * retry's answer is handled it is gone, and the focus with it. */
        val retrying = Var(false)
        var statusLine: Option[dom.html.Element] = None
        var retryHadFocus = false

        def retry(): Unit =
            if (!Store.completedLoading.now().contains(list)) {
                retrying.set(true)
                retryHadFocus = false
                Store.showCompleted(list = list).onComplete { _ =>
                    retrying.set(false)
                    if (retryHadFocus && Store.completedView.now().exists(_.list == list))
                        statusLine.foreach(_.focus())
                    retryHadFocus = false
                }
            }

        // Built once and shown or not, rather than rebuilt with each change of state, so that it is
        // the same element -- and keeps the focus -- from the press to the answer.
        val retryButton =
            button(
              tpe := "button",
              cls := "link",
              aria.disabled <-- busy,
              child <-- busy.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
              "Try again",
              onClick --> (_ => retry()),
              onUnmountCallback(button => retryHadFocus = dom.document.activeElement == button.ref)
            )
        val showRetry = view
            .combineWith(failed, retrying.signal)
            .map {
                case (None, failedNow, asking) => failedNow || asking
                case _                         => false
            }
            .distinct

        def move(to: CompletedPage => Int): Unit =
            Store.completedView.now().filter(_.list == list).foreach { current =>
                Store.showCompleted(list = list, page = to(current.page), asOf = Some(current.page.asOf))
            }

        /* Named by the window it goes to -- "Previous week", "Next 24 hours" -- in the frame shown. */
        def step(
            word: String,
            direction: String,
            shown: CompletedPage => Boolean,
            to: CompletedPage => Int
        ) =
            child <-- view.map {
                case Some(page) if shown(page) =>
                    val caption = s"$word ${page.frame.period}"
                    button(
                      tpe := "button",
                      cls := s"link $direction",
                      // The visible words first, then which way they go, which "next" alone does not say.
                      aria.label := s"$caption: $direction completed matches",
                      // Not disabled while a window is on its way, which would drop the keyboard's focus.
                      aria.disabled <-- busy,
                      caption,
                      onClick --> (_ => if (!Store.completedLoading.now().contains(list)) move(to)),
                      // Gone as the window it asked for arrives, so a step that had the focus hands it to the
                      // line saying which window is now shown, rather than letting it fall to the top of the
                      // page.
                      onUnmountCallback(node =>
                          if (dom.document.activeElement == node.ref) statusLine.foreach(_.focus())
                      )
                    )
                case _ => emptyNode
            }

        Seq(
          field(
            Labels.matches.show,
            select(
              CompletedFrame.values.toSeq.map(frame => option(value := frame.code, frame.label)),
              value <-- Store.completedFrame.signal.map(_.code),
              onChange.mapToValue --> { code =>
                  CompletedFrame.fromCode(code).foreach { frame =>
                      Store.completedFrame.set(frame)
                      Store.showCompleted(list = list)
                  }
              }
            )
          ),
          // What the list is: which stretch of time its rows are from, or that they are on their way, or
          // that they could not be had. Said politely when it changes -- which is what Prev, Next, the
          // frame and Try again do -- from one line that is always there, since a line that appears
          // with its news is not announced. Focusable from script only: Try again sends the focus here
          // once it is answered.
          p(
            cls := "detail",
            aria.live := "polite",
            tabIndex := -1,
            onMountCallback(context => statusLine = Some(context.thisNode.ref)),
            onUnmountCallback(_ => statusLine = None),
            child.text <-- view.combineWith(failed).map {
                case (None, true)                      => ErrorText.loading.matches
                case (None, false)                     => "Loading…"
                case (Some(page), _) if page.page == 0 => s"Finished in the ${page.frame.label.toLowerCase}"
                case (Some(page), _) =>
                    s"Finished between ${Format.instant(page.from)} and ${Format.instant(page.until)}"
            }
          ),
          child <-- showRetry.map(if (_) retryButton else emptyNode),
          child <-- view.map {
              case None                                           => emptyNode
              case Some(page) if page.matches.nonEmpty            => render(page.matches)
              case Some(page) if page.page == 0 && !page.hasOlder => p(cls := "empty", never)
              case Some(page) if page.hasOlder =>
                  p(cls := "empty", s"None finished in this time. Previous ${page.frame.period} goes further back.")
              case Some(_) => p(cls := "empty", "None finished in this time.")
          },
          div(
            cls := "completed-steps",
            // Earlier on the left, later on the right, as time reads.
            step(word = "Previous", direction = "older", shown = _.hasOlder, to = _.page + 1),
            step(word = "Next", direction = "newer", shown = _.page > 0, to = _.page - 1)
          )
        )
    }
}
