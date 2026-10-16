package com.vivi.matchmaker.ui

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** How a match is shown wherever it is listed: its row, its clocks and controls, its outcome and its results. */
object MatchTables {

    import Views.{
        busyButton,
        countdown,
        freshTipId,
        friendlyHeading,
        friendlyLabel,
        friendlyMeaning,
        listing,
        openSignedIn,
        scopeAttr,
        tipped
    }

    /** Who a match still being played is waiting on, under its row on somebody else's page. Named, and never "your
      * turn": a turn on this page is somebody else's by construction.
      */
    private[ui] def waitingFor(summary: MatchSummary): Modifier[HtmlElement] =
        if (summary.whoseTurn.nonEmpty) div(cls := "detail", s"waiting for ${summary.whoseTurn.mkString(", ")}")
        else div(cls := "detail", "waiting for the other players")

    /** A finished match's board, for its own player: "Review game". Fetched the way "Play" is — the urls live on the
      * match, not on the summary — with `publicUrl` the fallback for a match whose play url the engine has since
      * stopped honouring. Nothing for a cancelled match, which has no board worth looking at; a note instead of the
      * button once a friendly match's archive has expired.
      */
    private[ui] def reviewControl(summary: MatchSummary): HtmlElement =
        if (summary.isCompleted && summary.isArchiveExpired) archiveExpiredNote
        else if (summary.isCompleted)
            busyButton(label = "Review game", classes = Some("link")) { busy =>
                // Signed in: an answer after a sign-out would hand the next session's token to this match's url.
                Store.runSignedIn(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                    m.playUrl.orElse(m.publicUrl) match {
                        case Some(url) => openSignedIn(url)
                        // Found expired by this very request: the list was read before it was.
                        case None if m.isArchiveExpired =>
                            Store.reportProblem(
                              "This match's archive has expired: friendly matches are kept for 30 days."
                            )
                        case None => Store.reportProblem("This match has no url to view.")
                    }
                }
            }
        else span()

    /** Somebody else's public match, for anyone who cares to look: "Watch", straight off the summary's public url. None
      * where the engine serves no spectator page — a button that opens nothing is worse than none — and a note instead
      * once a friendly match's archive has expired.
      */
    private[ui] def watchControl(summary: MatchSummary): HtmlElement =
        if (summary.isArchiveExpired) archiveExpiredNote
        else
            summary.publicUrl
                .map(url => button(tpe := "button", cls := "link", "Watch", onClick --> (_ => openSignedIn(url))))
                .getOrElse(span())

    /** How the caller's own finished match came out: the engine's account where it gave one, and every seat's result
      * where it did not.
      */
    private[ui] def playerOutcome(summary: MatchSummary): Modifier[HtmlElement] =
        summary.resultSummary.fold(resultTable(summary))(resultSummary)

    /** How somebody else's public match came out, where the engine said: their page has no result table. */
    private[ui] def publicOutcome(summary: MatchSummary): Modifier[HtmlElement] =
        summary.resultSummary.map(resultSummary).getOrElse(emptyNode)

    /** Matches as a table: a row per match — its game (where the list mixes games), who it was played against, when it
      * ended (or, for matches still being played, started), what it did to their Elo rating -- or, for matches still
      * being played, whether it is friendly -- one column per game parameter (on a page about one game, where every
      * match has the same ones), and the way to look at its board — and under each, a row of its own across the table:
      * how a finished match came out, marked won, lost or drawn, or whom a running one is waiting for.
      *
      * The parameter columns are the parameters the matches shown were played under, in the order the first of them
      * lists them, so a game with none has no such column.
      *
      * Where any match shown is a character game's, the opponent is the character played against, and two columns join
      * it: first, the character the list's own seat played -- unless the list is a character's own page
      * (`aboutCharacter`), where every row would name the same one -- and after it the player who owned each opposing
      * character for that match.
      */
    private[ui] def matchTable(
        matches: Seq[MatchSummary],
        showGame: Boolean,
        showParameters: Boolean,
        outcome: MatchSummary => Modifier[HtmlElement],
        view: MatchSummary => HtmlElement,
        // The date column: when a finished match ended, or -- for a list of matches still being played --
        // when one started.
        dateHeading: String = "Completed",
        dated: MatchSummary => Option[java.time.Instant] = _.completedAt,
        // A column for won, lost or drawn: only where the matches are finished and there is one to say.
        showResult: Boolean = true,
        // Whether the list is one character's matches, which leaves out the column naming it.
        aboutCharacter: Boolean = false,
        // Whether a character game's matches get its columns at all; without them every row is laid out as a plain
        // game's, the opponent named as a player.
        characterColumns: Boolean = true
    ): HtmlElement = {
        val parameters =
            if (showParameters) matches.flatMap(_.parameters.map(_.displayName)).distinct else Seq.empty
        val showOwner = characterColumns && matches.exists(_.character.isDefined)
        val showCharacter = showOwner && !aboutCharacter
        val columns = Seq(showGame, showResult, showCharacter, showOwner).count(identity) + 4 + parameters.size

        def matchRow(summary: MatchSummary): Seq[HtmlElement] = {
            // Everyone else in it, each a way to their page -- the character's, where they played one; a match
            // played alone has nobody to name.
            def listed(links: Seq[HtmlElement]): Seq[HtmlElement] =
                links.zipWithIndex.map((link, i) => span(if (i > 0) ", " else emptyNode, link))
            val against: Modifier[HtmlElement] =
                if (summary.opponents.isEmpty) "nobody"
                else
                    listed(summary.opponents.map { o =>
                        o.character.filter(_ => showOwner).fold(Players.seatPlayer(o))(Players.characterLink)
                    })
            // Who owned each opposing character for the match; nothing for a plain game's row, whose opponent is
            // already the player.
            val owners = summary.opponents.filter(_.character.isDefined).map(Players.seatPlayer)

            val ended: Modifier[HtmlElement] =
                dated(summary) match {
                    case Some(when) =>
                        // The day, with the instant it was trimmed from kept machine-readable.
                        timeTag(
                          htmlAttr("datetime", com.raquo.laminar.codecs.StringAsIsCodec) := when.toString,
                          Format.date(when)
                        )
                    case None => if (summary.isCancelled) "cancelled" else ""
                }
            val told = outcome(summary)
            Seq(
              tr(
                cls := "match",
                role := "row",
                // Labelled in the cell, as the parameters are, for the narrow layout's card.
                if (showCharacter)
                    td(
                      role := "cell",
                      cls := "character",
                      summary.character.map(_ => dataAttr("label") := "Character"),
                      summary.character.map(Players.characterLink)
                    )
                else emptyNode,
                // Who it was against heads its row, for a screen reader.
                th(scopeAttr := "row", role := "rowheader", span(cls := "opponent", against)),
                if (showOwner)
                    td(
                      role := "cell",
                      cls := "owner",
                      Option.when(owners.nonEmpty)(dataAttr("label") := "Owner"),
                      listed(owners)
                    )
                else emptyNode,
                if (showGame) td(role := "cell", cls := "game", summary.gameName) else emptyNode,
                if (showResult)
                    td(role := "cell", cls := "result", summary.outcome.map(outcomeMark).getOrElse(emptyNode))
                else emptyNode,
                td(role := "cell", ended),
                // Where the matches are finished, what each did to the player's rating -- or, for a friendly match,
                // which moved nobody, that it was one. Otherwise a mark for a friendly match and nothing for a rated
                // one, with a word for a screen reader either way.
                if (showResult) eloCell(summary)
                else
                    td(
                      role := "cell",
                      cls := "friendly",
                      if (summary.isFriendly) span(cls := "mark", aria.hidden := true, "✓") else emptyNode,
                      span(cls := "sr-only", if (summary.isFriendly) "friendly" else "rated")
                    ),
                // Named in the cell as well as the heading, for the narrow layout that has no heading row to show.
                parameters.map(parameter =>
                    td(
                      role := "cell",
                      dataAttr("label") := parameter,
                      summary.parameters.find(_.displayName == parameter).map(_.value).getOrElse("")
                    )
                ),
                td(role := "cell", cls := "board", view(summary))
              ),
              // How it came out, set apart in colour: the line to read under the row. Indented by a column, an
              // empty cell under the first, so that it reads as the row's own detail.
              tr(
                cls := "outcome",
                role := "row",
                td(role := "cell", cls := "indent"),
                td(
                  role := "cell",
                  colSpan := columns - 1,
                  if (summary.isCancelled) div(cls := "detail", "Cancelled by its creator.") else emptyNode,
                  told
                )
              )
            )
        }

        def heading(content: Modifier[HtmlElement]*): HtmlElement =
            th(scopeAttr := "col", role := "columnheader", content)

        // The roles are the ones the elements have anyway, said outright: on a phone the table is laid out as a card
        // per match, and a browser may stop treating a table whose parts are no longer displayed as one as a table.
        div(
          cls := "table-scroll",
          table(
            cls := "match-table",
            role := "table",
            thead(
              role := "rowgroup",
              tr(
                role := "row",
                if (showCharacter) heading("Character") else emptyNode,
                heading("Opponent"),
                if (showOwner) heading("Owner") else emptyNode,
                if (showGame) heading("Game") else emptyNode,
                if (showResult) heading(cls := "result", "Result") else emptyNode,
                heading(dateHeading),
                if (showResult) heading(cls := "elo", "Elo") else heading(cls := "friendly", friendlyHeading()),
                parameters.map(parameter => heading(parameter)),
                heading(span(cls := "sr-only", "Board"))
              )
            ),
            // A body per match, so its two rows read as one.
            matches.map(summary => tbody(role := "rowgroup", matchRow(summary)))
          )
        )
    }

    /** A finished match's Elo cell: the change it made to the player's rating, signed, or a "?" saying it was friendly
      * -- a tip on what that means -- for a match that changed nobody's. Empty for one with no change recorded: called
      * off, or finished before ratings were kept. Labelled for the narrow layout, which has no heading row to show.
      */
    private def eloCell(summary: MatchSummary): HtmlElement =
        if (summary.isFriendly)
            td(
              role := "cell",
              cls := "elo",
              // Only the "?", a word being too wide for the column; named for a screen reader by what it marks.
              tipped(id = freshTipId("friendly-tip"), text = friendlyMeaning, control = emptyNode)(
                cls := "tip-toggle",
                aria.label := "Friendly match",
                "?"
              ).amend(cls := "inline-tip")
            )
        else
            td(
              role := "cell",
              cls := "elo",
              summary.eloDelta.map(_ => dataAttr("label") := "Elo"),
              summary.eloDelta.map {
                  case delta if delta > 0 => s"+$delta"
                  case delta if delta < 0 => s"−${-delta}"
                  case _                  => "±0"
              }
            )

    /** A finished match's Result cell: won, lost or drawn, for whoever the list is about. The mark is drawn for the
      * eye, and the word is what a screen reader hears.
      */
    private def outcomeMark(outcome: MatchOutcome): HtmlElement = {
        val (glyph, word) = outcome match {
            case MatchOutcome.Won  => ("✓", "Won")
            case MatchOutcome.Lost => ("✗", "Lost")
            case MatchOutcome.Drew => ("=", "Drawn")
        }
        span(
          cls := s"outcome-mark ${outcome.code.toLowerCase}",
          title := word,
          span(aria.hidden := true, glyph),
          span(cls := "sr-only", word)
        )
    }

    /** The game's parameters as this match is played under them, one to a line, under what names the match.
      *
      * On running and finished rows alike: unlike the clock, these are what the match *is* — twelve rounds rather than
      * three — and they are as much a part of how it ended as of how it is going.
      */
    private def matchParameters(summary: MatchSummary): Modifier[HtmlElement] =
        if (summary.parameters.isEmpty) emptyNode
        else
            ul(
              cls := "parameters",
              aria.label := "Game parameters",
              summary.parameters.map(p => li(cls := "detail", s"${p.displayName}: ${p.value}"))
            )

    /** Where a Review or Watch link would be, for a friendly match whose archive has expired. */
    private def archiveExpiredNote: HtmlElement =
        div(cls := "detail", "archive expired — friendly matches are kept for 30 days")

    /** A match it is the caller's turn in, on the "Your Turn" list: what it is, and the clock on that turn. Saying
      * "your turn" here would repeat the heading on every row.
      */
    private[ui] def matchRow(summary: MatchSummary): HtmlElement =
        li(
          cls := "row",
          div(cls := "title", summary.gameName),
          div(cls := "detail", summary.description),
          if (summary.isFriendly) friendlyLabel() else emptyNode,
          matchParameters(summary),
          summary.due.map(countdown).getOrElse(emptyNode),
          timeLimitDetail(
            limit = summary.timeLimit,
            kind = summary.timeLimitKind,
            unit = summary.timeLimitUnit,
            live = summary.isLive
          ),
          playControls(summary),
          matchNotifications(summary)
        )

    /** How a match the caller is playing stands, under its row in "Current Matches": whose turn it is, the clock on
      * that turn and the rule behind it, and what they want to hear about the match.
      */
    private[ui] def playingStatus(summary: MatchSummary): Modifier[HtmlElement] =
        div(
          // `isPending` means it is this player's turn: it is the flag the "Your Turn" list selects on.
          if (summary.isPending) div(cls := "pending", "your turn")
          // Matchmaker is never told whose turn it is in a live match -- the game keeps that -- so
          // "waiting for the other players" would be a guess, and usually a wrong one.
          else if (summary.isLive) div(cls := "detail", "being played live: open the game to see whose turn it is")
          // Named, rather than "the other players": in a match of three it is the difference
          // between knowing who to chase and knowing only that it is not you.
          else waitingFor(summary),
          // The clock on the turn now being taken, whoever is taking it -- the more useful thing to
          // know about a match you cannot move in.
          summary.turnDue.map(countdown).getOrElse(emptyNode),
          // Under a chess clock the deadline above is only half the story: it says when this turn
          // runs out, not how much either player has to last the rest of the match on.
          if (summary.clocks.isEmpty) emptyNode else clockTable(summary.clocks),
          // The rule behind that deadline, which the deadline itself does not give away: the same
          // "due" line comes of a per-turn limit and of a budget nearly spent, and they call for
          // opposite decisions.
          timeLimitDetail(
            limit = summary.timeLimit,
            kind = summary.timeLimitKind,
            unit = summary.timeLimitUnit,
            live = summary.isLive
          ),
          matchNotifications(summary)
        )

    /** The caller's way into a match still being played, and the way out of it for its owner. */
    private[ui] def playControls(summary: MatchSummary): HtmlElement =
        div(
          cls := "controls",
          // The play url lives on the match rather than the summary, and is the game engine's,
          // not matchmaker's — so it is fetched when asked for and opened directly.
          busyButton(label = "Play", classes = Some("link")) { busy =>
              // Signed in, as "Review game" is.
              Store.runSignedIn(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
                  m.playUrl match {
                      case Some(url) => openSignedIn(url)
                      case None      => Store.reportProblem("This match has no play url yet.")
                  }
              }
          },
          // Step 4 of the engine flow: any participant may ask matchmaker to re-check with the
          // engine, which is what recovers from a callback that never arrived.
          busyButton(label = "Refresh", classes = Some("link")) { busy =>
              Store.run(ApiClient.refreshMatch(summary.gameId, summary.matchId), busy)(
                MatchSections.reloadAfterMatchRefresh
              )
          },
          // Only the owner's. Every player forfeits and the match turns friendly, so the
          // confirmation says what actually happens.
          if (summary.isOwner)
              busyButton(label = "Cancel", classes = Some("link")) { busy =>
                  if (
                    dom.window.confirm(
                      "Cancel this match? Every player forfeits, and it won't change anyone's rating."
                    )
                  )
                      Store.run(ApiClient.cancelMatch(summary.gameId, summary.matchId), busy) { _ =>
                          Views.announce("Match cancelled.")
                          Store.refreshMatches()
                      }
              }
          else emptyNode
        )

    /** What this player wants to hear about this one match, on the match's own row.
      *
      * The whole answer, not an override: the seat was stamped with the player's settings as they stood when the match
      * started, and what is saved here is what that match sends from now on regardless of what they change elsewhere —
      * unless they later ask for a change to be carried into the matches they are in, which the account panel offers.
      *
      * On the row rather than in the account panel because it is about this match: the player who wants quiet wants it
      * from the match that has got noisy, and finding it under Account would mean naming the match in a list of forty.
      *
      * Fetched when the form is first opened. Every row would otherwise cost a request on every reload of the list, for
      * a form almost nobody opens — and the preference cannot be shown from what the lists already hold, since a
      * `MatchSummary` does not carry it.
      *
      * The answers live in a `Var` outside the toggle, so closing and reopening the form shows what was being typed
      * rather than re-fetching over the top of it. Only the "Saved." line is lost, which is a message about something
      * that has already happened.
      *
      * Outside the row, too ([[notificationDrafts]]): the row is rebuilt by every reload of its list -- the player's
      * own refresh, or the cross-refresh a Start or Accept elsewhere fires -- and state made with the row silently
      * closed an open form and threw away the choices in it.
      */
    private def matchNotifications(summary: MatchSummary): HtmlElement = {
        val NotificationDraft(shown, fetched, preferences) = notificationDraft(summary.matchId)
        val busy = Var(false)

        div(
          button(
            tpe := "button",
            cls := "link",
            // The button is the form's control, so it says whether the form is open — both in the
            // label, which is what a sighted user reads, and in the state, which is what is announced.
            aria.expanded <-- shown.signal,
            // Not disabled while the form is fetched, which would drop the keyboard's focus.
            aria.disabled <-- busy.signal,
            child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
            child.text <-- shown.signal.map(if (_) "Hide notifications" else "Notifications"),
            onClick --> { _ =>
                if (busy.now()) ()
                else if (shown.now()) shown.set(false)
                else if (fetched.now()) shown.set(true)
                else
                    Store.run(ApiClient.matchNotifications(summary.gameId, summary.matchId), busy) { current =>
                        preferences.set(current.asPreferences)
                        fetched.set(true)
                        shown.set(true)
                    }
            }
          ),
          child <-- shown.signal.map {
              case false => emptyNode
              case true  =>
                  // `withDefault = false`: this match's seat answers every kind itself and there is nothing under it to defer to. The
                  // answers it opens with are what the seat was stamped with when the match started,
                  // so nothing is unanswered and the button is never disabled for want of a choice.
                  //
                  // Only `duringMatch` is asked about: the seat also holds an answer for
                  // `MatchStarted`, but that match has already started, so it is a choice that could
                  // never apply again. What is saved for it is what was fetched, unchanged.
                  Notifications.form(
                    heading = "Notification Preferences for this match",
                    explanation = "What we email you about this match, whatever you change elsewhere later.",
                    preferences = preferences,
                    withDefault = false,
                    saveLabel = "Save for this match",
                    kinds = NotificationType.duringMatch
                  ) { chosen =>
                      chosen.completeForSeat match {
                          case Some(answers) =>
                              ApiClient.updateMatchNotifications(
                                gameId = summary.gameId,
                                matchId = summary.matchId,
                                preferences = answers
                              )
                          // Unreachable: the form seeds every kind a seat holds and its button is
                          // disabled while any is unanswered. A failed Future rather than a silent
                          // success, so that a hole in that reasoning shows up beside the form instead
                          // of looking saved.
                          case None =>
                              scala.concurrent.Future.failed(
                                new RuntimeException("Answer every question before saving.")
                              )
                      }
                  }
          }
        )
    }

    private case class NotificationDraft(
        shown: Var[Boolean],
        fetched: Var[Boolean],
        preferences: Var[NotificationPreferences]
    )

    /** Each match's notifications form as the player left it, and the sign-in it belongs to: one player's answers are
      * never shown to the next (see `Store.currentSignIn`), so the drafts are dropped when the sign-in changes.
      */
    private val notificationDrafts = scala.collection.mutable.Map.empty[MatchId, NotificationDraft]
    private var draftsSignIn = Store.currentSignIn

    private def notificationDraft(matchId: MatchId): NotificationDraft = {
        if (!Store.isStillSignedInAs(draftsSignIn)) {
            notificationDrafts.clear()
            draftsSignIn = Store.currentSignIn
        }
        notificationDrafts.getOrElseUpdate(
          matchId,
          NotificationDraft(shown = Var(false), fetched = Var(false), preferences = Var(NotificationPreferences.unset))
        )
    }

    /** How a finished match ended, in its game engine's words: a line of HTML sent with the results.
      *
      * Cleaned again here although the server stored it cleaned, so that what reaches `innerHTML` is never only as safe
      * as the server's copy — see `SummaryHtml`. A `div` rather than a `p`, since a summary may hold a list.
      */
    private def resultSummary(html: String): HtmlElement = {
        val element = dom.document.createElement("div").asInstanceOf[dom.html.Div]
        element.className = "result-summary"
        element.innerHTML = SummaryHtml.clean(html)
        foreignHtmlElement(element)
    }

    /** How a finished match ended: every seat, the winner first.
      *
      * The rows are already ordered by rank by the query, so the order of the list is the standing and the rank itself
      * does not need saying — but winning is marked from `isWinner` rather than from position, because a game may have
      * no winner at all (a draw, a cancelled match) and first place would otherwise invent one.
      *
      * A cancelled match has no engine-reported result: every seat forfeited when it was called off, and one cancelled
      * before that was how a cancel ended a match has rows with no rank or scores. Saying so beats rendering an
      * empty-looking table.
      */
    private def resultTable(summary: MatchSummary): HtmlElement =
        div(
          cls := "results",
          listing(
            Store.resultsByMatch.signal.map(_.getOrElse(summary.matchId, Seq.empty)),
            Store.loading(Store.Fetch.Results)
          )(
            p(
              cls := "empty",
              if (summary.isCancelled) "Called off before it finished." else "No result was reported."
            )
          )(rows =>
              div(
                // Said once above the table rather than on each line: a forfeit is how the match
                // ended, which is one fact about the match, not a separate fact about each seat.
                // The lines below still say which of the two things it meant for each player.
                if (summary.isCancelled)
                    p(cls := "detail", "Called off by its creator: every player forfeited.")
                else if (rows.exists(_.isForfeit))
                    p(cls := "detail", "Ended by forfeit: a player ran out of time on their turn.")
                else emptyNode,
                ul(
                  cls := "result-rows",
                  rows.map { row =>
                      li(
                        cls := "result-row",
                        // The emoji reads out as "trophy", which is a guess at what it means rather than
                        // a statement of it. The text says it; the emoji is decoration over the top.
                        if (row.isWinner) span(cls := "winner", aria.hidden := true, "🏆 ") else emptyNode,
                        if (row.isWinner) span(cls := "sr-only", "winner: ") else emptyNode,
                        span(cls := "who", row.nickname + row.roleName.fold("")(role => s" ($role)")),
                        // Their Elo rating as the match began, in a friendly match as in any other, and
                        // what the match did to it if it was rated.
                        span(cls := "detail", s" — ${Format.elo(row.eloStart, row.eloDelta)}"),
                        // Which side of the forfeit this player was on. `isWinner` is what separates
                        // them, and without this a win by forfeit would read as a win on the board.
                        if (!row.isForfeit) emptyNode
                        else if (row.isWinner) span(cls := "detail", " — won by forfeit")
                        else if (summary.isCancelled) span(cls := "detail", " — forfeited")
                        else span(cls := "detail", " — forfeited on time"),
                        // How long they spent over their turns, all told. Only when the match has
                        // turns recorded against somebody: a match played before turns were recorded
                        // would otherwise report a table of zeroes as if everybody had moved instantly.
                        if (rows.forall(_.timeTaken.isZero)) emptyNode
                        else span(cls := "detail", s" — ${Format.spent(row.timeTaken)} on the clock"),
                        // Whatever else the engine chose to report. Which keys exist is the game's
                        // business, so they are shown as they came rather than being named here.
                        if (row.scores.isEmpty) emptyNode
                        else
                            span(
                              cls := "scores",
                              " — ",
                              row.scores.toSeq
                                  .sortBy(_._1)
                                  .map((key, value) => s"$key: ${Format.jsonValue(value)}")
                                  .mkString(", ")
                            )
                      )
                  }
                )
              )
          )
        )

    /** What every player has left of a chess-clock budget.
      *
      * The player on the clock is shown their deadline, counting down, because their balance is being spent as it is
      * read; everybody else is shown a balance, which is not moving and would be a lie if it ticked. That is the same
      * distinction `PlayerClock.deadline` draws, and it is drawn there rather than here so the server and the page
      * cannot disagree about who is spending.
      */
    private def clockTable(clocks: Seq[PlayerClock]): HtmlElement =
        ul(
          cls := "clocks",
          clocks.map { clock =>
              li(
                cls := "clock",
                span(cls := "who", clock.nickname),
                clock.deadline match {
                    case Some(deadline) => countdown(deadline)
                    // Said as a balance rather than as a countdown: "6:00 left" of a clock that is not
                    // running is a fact, and it stays true until this player moves again.
                    case None => span(cls := "detail", Format.remaining(clock.remaining.toMillis))
                }
              )
          }
        )

    /** The clock something is played under, said in full wherever it is said at all.
      *
      * Both halves matter and neither is guessable from the other: ten minutes per turn and ten minutes for the whole
      * match are very different games. On a challenge it is the terms being offered; on a running match it is the rule
      * behind the deadline, which the deadline alone does not give away — the same "due" line comes of a fresh per-turn
      * limit and of a budget with a minute left in it.
      *
      * Said even when there is no limit, because "nothing here about a clock" and "no clock" are otherwise the same
      * sight.
      */
    private def timeLimitDetail(
        limit: Option[java.time.Duration],
        kind: TimeLimitKind,
        unit: TimeLimitUnit,
        live: Boolean
    ): HtmlElement =
        div(
          cls := "detail",
          limit match {
              case None => "no time limit"
              // A live match's clock is the game's rather than matchmaker's: a player who runs out
              // loses the match there and then.
              case Some(limit) =>
                  val terms = kind match {
                      case TimeLimitKind.PerTurn => s"${Format.duration(limit, unit)} per turn"
                      case TimeLimitKind.Total   => s"${Format.duration(limit, unit)} each for the whole match"
                  }
                  if (live) s"live: $terms" else terms
          }
        )
}
