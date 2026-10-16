package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** The main page's sections about the caller's own challenges and matches: invitations, ready to start, due, in
  * progress, waiting to start and recently completed. The game screen shows the same sections narrowed to its game.
  */
object MatchSections {

    import Views.{busyButton, currentPlayer, field, heldBusy, listing, refresh, refreshableSection}

    /** Shared by the three sections drawn from the caller's open challenges -- invitations, "Ready to Start" and
      * "Waiting to Start" are one response split by what the caller is to each challenge, so any of their buttons
      * reloads all three and all three have to say so. Held at this level rather than passed down because the sections
      * are siblings on the main page with nothing between them to own it.
      */
    private[ui] val refreshingChallenges: Var[Boolean] = Var(false)

    /* The same, for the two match lists. Shared by the home screen's copy of each section and the
     * game screen's, which are never both on screen — and, more to the point, by the sections
     * themselves and by the actions below that change what belongs in them. */
    private[ui] val refreshingDue: Var[Boolean] = Var(false)
    private[ui] val refreshingActive: Var[Boolean] = Var(false)

    /* And for the completed list, which a refresh can move a row *into*. Shared by the home screen's
     * "Recently Completed" and the game screen's "Your Completed Matches": they are two views of the
     * one list, only one of them is ever on screen, and `reloadCompleted` fills both. */
    private[ui] val refreshingCompleted: Var[Boolean] = Var(false)

    /** Reloads what a change to a challenge can have changed, the way each section's own button reloads it: the
      * caller's open challenges, and the game screen's list when that is the screen behind this.
      *
      * Dimmed and re-read rather than edited in place: a list that silently loses a row is a list that might have lost
      * the wrong one.
      */
    private[ui] def reloadChallengeSections(gameId: GameId): Unit = {
        refresh(refreshingChallenges, () => Store.reloadMyChallenges())
        if (Store.page.now() == Store.Page.OneGame(gameId)) Store.refreshChallenges(gameId)
    }

    /** Reloads what starting a challenge has changed: it leaves the challenge lists, and its players are asked to
      * choose their roles -- or, once they have, it is a match in "Current Matches", and in "Your Turn" as well if the
      * engine says the first move is this player's. The role choices and the tournaments come with it, since a
      * challenge is one.
      *
      * Through each section's own flag rather than through `Store.refreshMatches`, so that the lists visibly reload --
      * dimmed, and marked `aria-busy` while they do -- instead of quietly growing a row the player has to notice for
      * themselves. The completed list is not touched: a match that has just started has not finished.
      */
    private[ui] def reloadAfterStart(gameId: GameId): Unit = {
        refresh(refreshingDue, () => Store.reloadDue())
        refresh(refreshingActive, () => Store.reloadActive())
        Store.reloadTournamentChoices()
        Store.reloadMyTournaments()
        reloadChallengeSections(gameId)
    }

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

    /* What the caller is to a challenge, which decides the section it is listed in. */
    private def isInvitedTo(c: ChallengeSummary): Boolean =
        c.summary.isInvited && !c.summary.isEntered && !c.summary.isOwner

    /* The fewest entries a challenge can start with: enough to fill the game's required roles, and never fewer than
     * two. `None` when the game is not one this player has listed, which is a game whose roles nobody here knows. */
    private def leastToStart(game: Option[Game]): Option[Int] = game.map(g => math.max(2, g.requiredSeats))

    private def gameOf(games: Seq[Game], gameId: GameId): Option[Game] = games.find(_.gameId == gameId)

    /** What this player has been invited to and has not answered.
      *
      * First on the page, and absent when there is nothing in it, for the reason "Ready to Start" is both: an
      * invitation is a thing somebody else did that is waiting on this player, not a list they came to check. Every
      * other section here is about a challenge or match they are already in — this is the only one about one they are
      * not, which is also why it names who offered it.
      */
    private[ui] def invitationsSection: HtmlElement =
        div(
          /* What the last answer to an invitation was, said once, to whoever is not watching the list.
           *
           * Answering one removes its row, and may remove the whole section with it — which is a
           * change nobody is told about by sight alone being gone. The error banner is `role="alert"`,
           * so a refusal already announces itself; this is the other half, and the half that was
           * silent. A one-line status rather than `aria-live` on the section, so that the first fetch
           * of the session does not read every row out unprompted. Present from the start and empty,
           * because a live region has to exist before the text arrives in it to be announced reliably. */
          p(
            cls := "sr-only",
            role := "status",
            aria.live := "polite",
            // From the store, and stamped there with the session that said it. See `Store.invitationsSaid`.
            child.text <-- Store.invitationsSaid
          ),
          child <-- Store.myChallenges.signal
              .combineWith(
                Store.games.signal,
                Store.known(Store.Fetch.MyChallenges),
                Store.failed(Store.Fetch.MyChallenges)
              )
              .map { (challenges, games, known, failed) =>
                  val unanswered = challenges.filter(isInvitedTo)
                  /* Three states, not two, and emptiness tells none of them apart. A list that failed
                   * stands with a line saying to ask again, and its refresh button is what asks -- the
                   * other sections drawn from this list are absent when it is empty, their buttons with
                   * them. Nothing at all until it has answered, which is a page still loading; and
                   * nothing once it has if there is nothing to answer. */
                  if (failed)
                      refreshableSection(
                        heading = "You Have Been Invited",
                        refreshing = refreshingChallenges,
                        reload = () => Store.reloadMyChallenges(),
                        subsection = false
                      )(
                        p(cls := "empty", "Couldn't load your invitations just now. Refresh to try again.")
                      )
                  else if (!known || unanswered.isEmpty) emptyNode
                  else
                      refreshableSection(
                        heading = "You Have Been Invited",
                        refreshing = refreshingChallenges,
                        reload = () => Store.reloadMyChallenges(),
                        subsection = false
                      )(ul(unanswered.map(c => invitationRow(c, gameOf(games, c.summary.tournament.gameId)))))
              }
        )

    /** One invitation: who asked, to what, on what terms — then the two answers.
      *
      * In a character game the invitation is a character's, so the row names it, Accept enters with it, and Decline
      * turns down its invitation; the character is read from the invitation, whoever owns it now being who may answer.
      * In a plain game Accept enters the player. Every fact it decides on comes from the response rather than from
      * `Store.games`, which holds only the active games -- an invitation outlives its game being deactivated.
      */
    private def invitationRow(invited: ChallengeSummary, game: Option[Game]): HtmlElement = {
        val t = invited.summary.tournament
        // Held outside the row, which every answer to the invitations redraws: accepting one invitation reloads them
        // all, and another's Accept still out would otherwise be pressable again.
        val acceptBusy = heldBusy(s"accept invitation ${t.tournamentId}")
        val declineBusy = heldBusy(s"decline invitation ${t.tournamentId}")
        def accepted(character: Option[CharacterId])(busy: Var[Boolean]): Unit = {
            val signIn = Store.currentSignIn
            Store.run(
              action =
                  ApiClient.enterTournament(gameId = t.gameId, tournamentId = t.tournamentId, characterId = character),
              busy = busy,
              onFailure = invitationGone(t)
            ) { _ =>
                Store.sayAboutInvitations(signIn, s"Invitation to ${invited.summary.gameName} accepted.")
                // Accepting may have filled one that starts itself: the start's reloads cover both.
                reloadAfterStart(t.gameId)
            }
        }
        def declined(busy: Var[Boolean], decline: Future[Unit]): Unit = {
            val signIn = Store.currentSignIn
            Store.run(action = decline, busy = busy, onFailure = invitationGone(t)) { _ =>
                Store.sayAboutInvitations(signIn, s"Invitation to ${invited.summary.gameName} declined.")
                reloadChallengeSections(t.gameId)
            }
        }
        li(
          cls := "row",
          child <-- currentPlayer.map {
              case None     => emptyNode
              case Some(me) =>
                  // The character of the caller's this invitation is for, in a character game.
                  invited.invitedCharacters.find(_.owner.exists(_.playerId == me.playerId)) match {
                      case Some(mine) =>
                          div(
                            div(
                              cls := "title",
                              s"${invited.summary.ownerNickname} invited ${mine.character.name} to ${invited.summary.gameName}"
                            ),
                            div(cls := "detail", challengeTerms(t, invited.entries.size)),
                            busyButton(label = "Accept", busy = acceptBusy)(accepted(Some(mine.character.characterId))),
                            busyButton(label = "Decline", classes = Some("link"), busy = declineBusy)(busy =>
                                declined(
                                  busy,
                                  ApiClient.revokeTournamentCharacterInvitation(
                                    gameId = t.gameId,
                                    tournamentId = t.tournamentId,
                                    characterId = mine.character.characterId
                                  )
                                )
                            )
                          )
                      case None =>
                          div(
                            div(
                              cls := "title",
                              s"${invited.summary.ownerNickname} invited you to ${invited.summary.gameName}"
                            ),
                            div(cls := "detail", challengeTerms(t, invited.entries.size)),
                            game match {
                                case Some(g) if g.gameType == GameType.Plain =>
                                    busyButton(label = "Accept", busy = acceptBusy)(accepted(None))
                                case _ =>
                                    button(
                                      tpe := "button",
                                      cls := "link",
                                      "Open the game",
                                      onClick --> (_ => Store.show(Store.Page.OneGame(t.gameId)))
                                    )
                            },
                            busyButton(label = "Decline", classes = Some("link"), busy = declineBusy)(busy =>
                                declined(
                                  busy,
                                  ApiClient.revokeTournamentInvitation(
                                    gameId = t.gameId,
                                    tournamentId = t.tournamentId,
                                    playerId = me.playerId
                                  )
                                )
                            )
                          )
                  }
          }
        )
    }

    /** What to do when answering an invitation is refused because it is no longer there to answer: the list was fetched
      * before its owner withdrew it, or started or cancelled the challenge, or it filled up. 404 and 409 are both the
      * server saying so, so the lists are re-read and the server's own account is left in the banner — only it knows
      * which of those it was. Anything else says nothing about whether the invitation is still there, and is left as
      * `Store.run` reported it.
      */
    private def invitationGone(t: Tournament)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) => reloadAfterStart(t.gameId)
        case _                      => ()
    }

    /* Absent altogether when there is nothing ready to start, heading and refresh button with it.
     *
     * Every other list here says so when it is empty, because "no matches" is an answer to a
     * question somebody asked. This one is not a question anybody asks: a challenge becomes
     * startable when enough players have entered it, which is something that happens *to* its owner
     * rather than something they came to check. So it is a prompt, and a prompt with nothing to
     * prompt is noise on every screen that carries it. */
    private[ui] def readyToStartSection(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.myChallenges.signal
              .combineWith(Store.games.signal)
              .map { (challenges, games) =>
                  val mine = challengesIn(game)(challenges).filter { c =>
                      c.summary.isOwner && !c.summary.tournament.settings.isAutoStarted &&
                      leastToStart(gameOf(games, c.summary.tournament.gameId)).exists(c.entries.size >= _)
                  }
                  if (mine.isEmpty) emptyNode
                  else
                      refreshableSection(
                        heading = "Ready to Start",
                        refreshing = refreshingChallenges,
                        reload = () => Store.reloadMyChallenges(),
                        subsection = false
                      )(ul(mine.map(readyToStartRow)))
              }
        )

    private def readyToStartRow(c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        li(
          cls := "row",
          div(cls := "title", c.summary.gameName),
          div(cls := "detail", challengeTerms(t, c.entries.size)),
          busyButton(label = "Start") { busy =>
              Store.run(
                action = ApiClient.tournamentStep(gameId = t.gameId, tournamentId = t.tournamentId, step = "start"),
                busy = busy,
                onFailure = challengeStale(t)
              ) { _ =>
                  Views.announce("Challenge started.")
                  reloadAfterStart(t.gameId)
              }
          },
          // Enough players having entered is no promise the owner still wants the match, so the way out of it stays
          // here alongside the start.
          busyButton(label = "Cancel") { busy =>
              Store.run(
                action = ApiClient.stopTournament(gameId = t.gameId, tournamentId = t.tournamentId),
                busy = busy,
                onFailure = challengeStale(t)
              ) { _ =>
                  Views.announce("Challenge cancelled.")
                  reloadChallengeSections(t.gameId)
              }
          }
        )
    }

    /** What a refused start, cancel or withdrawal says about the lists on screen: a 409 or 404 is the challenge having
      * moved on since the list was fetched -- started by itself as it filled, started or cancelled by its owner -- so
      * the lists are re-read, the match lists with them in case it is now a match. Anything else -- a 5xx, a dropped
      * connection -- says nothing about the challenge, and is left as `Store.run` reported it.
      */
    private[ui] def challengeStale(t: Tournament)(failure: Throwable): Unit = failure match {
        case ApiError(404 | 409, _) => reloadAfterStart(t.gameId)
        case _                      => ()
    }

    /** A challenge's terms in a line: how many it seats and how many have entered, its time limit, and whether it
      * starts by itself, is live, or is rated.
      */
    private[ui] def challengeTerms(t: Tournament, entered: Int): String = {
        val seats = t.settings.maxEntrants.getOrElse(t.defaultPoolSize)
        List(
          Some(s"$entered of $seats entered"),
          t.settings.roundDuration.map(d => s"${Format.duration(d, TimeLimitUnit.bestFor(d))} to play"),
          Option.when(t.settings.isAutoStarted)("starts when full"),
          Option.when(t.settings.isLive)("live"),
          Option.when(!t.settings.isFriendly)("rated"),
          t.settings.start.map(at => s"for ${Format.instant(at)}")
        ).flatten.mkString(" · ")
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
          heading = "Your Turn",
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

    /* The same slice, over the caller's challenges. */
    private def challengesIn(game: Option[Game])(challenges: Seq[ChallengeSummary]): Seq[ChallengeSummary] =
        game.fold(challenges)(g => challenges.filter(_.summary.tournament.gameId == g.gameId))

    /** The matches still being played. Expanded rather than behind a toggle: the wire-frame lists it as one of the four
      * things the main page shows, and a section that has to be opened to find out whether it is empty is not shown.
      */
    private[ui] def myMatchesSection(game: Option[Game] = None): HtmlElement =
        refreshableSection(
          heading = "Current Matches",
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

    /** The challenges this player has entered that have not started: still filling up, or somebody else's to start. The
      * ones this player could start right now are left out: they are in "Ready to Start", and listing them twice would
      * offer the same button in two places.
      *
      * Absent when there is nothing waiting, like "Ready to Start" and for the same reason: what a player has entered
      * and is waiting on is news when there is some, and a heading over a sentence saying there is none the rest of the
      * time.
      */
    private[ui] def waitingSection(game: Option[Game] = None): HtmlElement =
        div(
          child <-- Store.myChallenges.signal.combineWith(Store.games.signal, currentPlayer).map {
              (challenges, games, player) =>
                  val waiting = challengesIn(game)(challenges).filter { c =>
                      val ready = c.summary.isOwner && !c.summary.tournament.settings.isAutoStarted &&
                          leastToStart(gameOf(games, c.summary.tournament.gameId)).exists(c.entries.size >= _)
                      (c.summary.isEntered || c.summary.isOwner) && !ready
                  }
                  if (waiting.isEmpty) emptyNode
                  else
                      refreshableSection(
                        heading = "Waiting to Start",
                        refreshing = refreshingChallenges,
                        reload = () => Store.reloadMyChallenges(),
                        subsection = true
                      )(ul(waiting.map(c => waitingRow(c, player))))
          }
        )

    private def waitingRow(c: ChallengeSummary, player: Option[Player]): HtmlElement = {
        val t = c.summary.tournament
        val mine = player.flatMap(me => c.entries.find(_.playerId.contains(me.playerId)))
        val full = t.settings.maxEntrants.exists(c.entries.size >= _)
        li(
          cls := "row",
          div(cls := "title", c.summary.gameName),
          div(cls := "detail", challengeTerms(t, c.entries.size)),
          div(
            cls := "detail",
            if (c.summary.isOwner) "your challenge, waiting for players"
            else if (full) s"full — waiting for ${c.summary.ownerNickname} to start it"
            else s"${c.summary.ownerNickname}'s challenge, waiting for players"
          ),
          // The owner calls their own off; anybody else withdraws their entry. Both are refused once it has started,
          // which `challengeStale` treats as the news it is.
          if (c.summary.isOwner)
              busyButton(label = "Cancel", classes = Some("link")) { busy =>
                  Store.run(
                    action = ApiClient.stopTournament(gameId = t.gameId, tournamentId = t.tournamentId),
                    busy = busy,
                    onFailure = challengeStale(t)
                  ) { _ =>
                      Views.announce("Challenge cancelled.")
                      reloadChallengeSections(t.gameId)
                  }
              }
          else
              mine.fold(emptyNode) { entry =>
                  busyButton(label = "Withdraw", classes = Some("link")) { busy =>
                      Store.run(
                        action = ApiClient.withdrawFromTournament(
                          gameId = t.gameId,
                          tournamentId = t.tournamentId,
                          entrantId = entry.entrantId
                        ),
                        busy = busy,
                        onFailure = challengeStale(t)
                      ) { _ =>
                          Views.announce("Withdrawn from the challenge.")
                          reloadChallengeSections(t.gameId)
                      }
                  }
              }
        )
    }

    /** The caller's finished matches in every game, a window of time at a time. */
    private[ui] def recentlyCompletedSection: HtmlElement =
        refreshableSection(
          heading = "Recently Completed",
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
            "Show",
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
                case (None, true)                      => "These matches could not be loaded."
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
