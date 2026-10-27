package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{
    Node as TNode,
    Pool,
    PoolMember,
    Problem,
    Repetition,
    StageRun,
    TournamentGame,
    Validation
}

/** The tournament screens: a game's tournaments, the caller's own on the main page, a tournament's page, and the form
  * that creates or edits one.
  *
  * Built from `Views`' own pieces — `field`, `withTip`, `busyButton`, `listing` — so that a tournament's page reads
  * like every other screen: labelled controls, explanations behind an ⓘ, buttons that say they are busy. Everything
  * that writes into the store goes through `Store`'s loads, so a late answer from an earlier session or an earlier
  * tournament is dropped.
  */
object Tournaments {

    import Views.{busyButton, field, freshTipId, heldBusy, listing, withTip}

    private val scopeAttr = htmlAttr("scope", com.raquo.laminar.codecs.StringAsIsCodec)

    // ---- lists -------------------------------------------------------------------------------

    /** A game's open competitions that the caller could enter, each with its Join button -- their own, then everybody
      * else's -- with the button that opens the New Competition dialog.
      */
    def gameSection(game: Game): HtmlElement =
        div(
          // Out here, not inside the list below: that is rebuilt whenever the list is reloaded, and a dialog inside it
          // would be rebuilt with it, losing what had been typed and the focus.
          child <-- Store.showTournamentForm.signal.distinct.map(isOpen =>
              if (!isOpen) emptyNode
              else
                  Modal.dialog(
                    content = form(
                      game = game,
                      existing = None,
                      onSaved = () => Store.showTournamentForm.set(false),
                      headingId = Some("new-competition-heading")
                    ),
                    labelledBy = "new-competition-heading",
                    close = () => closeNewCompetition(game.gameId),
                    saving = savingBusy(game.gameId, None).signal
                  )
          ),
          Views.refreshableSection(
            heading = Headings.tournaments.open,
            reload = () => Store.reloadTournaments(game.gameId),
            subsection = false
          )(
            button(
              tpe := "button",
              cls := "new-competition",
              htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
              aria.expanded <-- Store.showTournamentForm.signal,
              Headings.tournaments.create,
              onMountCallback(context => newCompetitionTrigger = Some(context.thisNode.ref)),
              onUnmountCallback(_ => newCompetitionTrigger = None),
              onClick --> (_ => Store.showTournamentForm.set(true))
            ),
            listing(
              Store.tournamentsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty)),
              Store.loading(Store.Fetch.Tournaments(game.gameId))
            )(p(cls := "empty", "No open competitions in this game."))(rows =>
                summaryList(rows, showGame = false, joinIn = Some(game))
            )
          ),
          // The formats the game offers, for whoever administers it: nothing at all for anybody else.
          child <-- Store.currentPlayer.flatMapSwitch {
              case Some(player) =>
                  Store.administers(game.gameId, player).map(if (_) formatsSection(game, player) else emptyNode)
              case None => Val(emptyNode)
          }
        )

    /* The button the New Competition dialog is opened from, so closing it can put focus back. */
    private var newCompetitionTrigger: Option[dom.html.Element] = None

    /* Closing the dialog abandons the invitation it was opened for, so the next competition made here is not quietly
     * addressed to whoever was looked at before. Refused while its save is out: the answer closes it and goes to what
     * was made, and a dialog closed and opened again meanwhile could make a second one. */
    private def closeNewCompetition(gameId: GameId): Unit =
        if (!savingBusy(gameId, None).now()) {
            Store.invitee.set(None)
            Store.showTournamentForm.set(false)
            newCompetitionTrigger.foreach(_.focus())
        }

    /* The form's save, busy for as long as it is out, whatever is drawn again or closed meanwhile. */
    private def savingBusy(gameId: GameId, existing: Option[Tournament]): Var[Boolean] =
        heldBusy(existing.fold(s"new competition in ${gameId.value}")(t => s"save tournament ${t.tournamentId.value}"))

    /** The competitions the caller runs, those of one game on that game's screen, every game's on the main page. */
    def runningSection(game: Option[Game]): HtmlElement =
        div(
          child <-- Store.myTournaments.signal
              .map(_.filter(s => s.isOwner && game.forall(_.gameId == s.tournament.gameId)))
              .distinct
              .map { running =>
                  if (running.isEmpty) emptyNode
                  else
                      Views.refreshableSection(
                        heading = Headings.tournaments.running,
                        reload = () => Store.reloadMyTournaments(),
                        subsection = false
                      )(ul(running.map(runningRow(_, showGame = game.isEmpty))))
              }
        )

    /** The main page's tournament sections: seats waiting on the caller to choose a role, and their tournaments. */
    def mainSections: HtmlElement =
        div(
          child <-- Store.tournamentChoices.signal.map { choices =>
              if (choices.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = Headings.tournaments.chooseRole,
                    reload = () => Store.reloadTournamentChoices(),
                    subsection = false
                  )(
                    ul(
                      choices.map { c =>
                          li(
                            cls := "row",
                            span(s"${c.tournamentName} (${c.gameName})"),
                            c.chooseBy.fold(emptyNode)(by => span(cls := "detail", s" · by ${when(by)}")),
                            button(
                              tpe := "button",
                              Buttons.tournaments.chooseRole,
                              aria.label := Buttons.tournaments.chooseRoleNamed(competition = c.tournamentName),
                              onClick --> (_ => Store.show(Store.Page.OneTournament(c.gameId, c.tournamentId)))
                            )
                          )
                      }
                    )
                  )
          },
          // Which rounds wait on this player, said when that changes. Out here, mounted before it has anything to say:
          // a live region drawn together with the rows it is about is never read out.
          div(
            cls := "sr-only",
            role := "status",
            aria.live := "polite",
            child.text <-- Store.myTournaments.signal
                .map(_.filter(isWaitingOnMe).map(_.tournament.name))
                .distinct
                .map(names => if (names.isEmpty) "" else names.mkString("A round is ready to start in ", ", ", "."))
          ),
          runningSection(None),
          // Those the caller runs are listed above, under their own heading.
          child <-- Store.myTournaments.signal.map(_.filterNot(_.isOwner)).map { mine =>
              if (mine.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = Headings.tournaments.mine,
                    reload = () => Store.reloadMyTournaments(),
                    subsection = false
                  )(
                    summaryList(mine, showGame = true)
                  )
          },
          // Drawn once, not from the list: the Show control keeps its focus as the list it asks for arrives.
          Views.refreshableSection(
            heading = Headings.tournaments.completed,
            reload = () => Store.reloadMyEndedTournaments(),
            subsection = false
          )(
            field(
              // Not "Show", as the completed matches' is: two controls of one name on a page cannot be told apart in
              // a screen reader's list of them.
              "Ended within",
              select(
                CompletedFrame.values.toSeq.map(frame => option(value := frame.code, frame.label)),
                value <-- Store.endedFrame.signal.map(_.code),
                onChange.mapToValue --> { code =>
                    CompletedFrame.fromCode(code).foreach { frame =>
                        // Said as well as shown: the list changes under a select whose focus stays where it is.
                        Store
                            .showEndedFrame(frame)
                            .foreach(_.foreach { found =>
                                val within = frame.label.toLowerCase
                                Views.announce(
                                  if (found.isEmpty) s"None of yours ended in the $within."
                                  else s"${found.size} of yours ended in the $within."
                                )
                            })
                    }
                }
              )
            ),
            listing(Store.myEndedTournaments.signal, Store.loading(Store.Fetch.MyEndedTournaments))(
              p(
                cls := "empty",
                // Nothing to say about the window when it could not be had: the banner says why.
                child.text <-- Store.endedFrame.signal
                    .combineWith(Store.failed(Store.Fetch.MyEndedTournaments))
                    .map {
                        case (_, true)  => ErrorText.loading.endedCompetitions
                        case (frame, _) => s"None of yours ended in the ${frame.label.toLowerCase}."
                    }
              )
            )(rows => summaryList(rows, showGame = true))
          ),
          child <-- Store.tournamentStandings.signal.map { rows =>
              if (rows.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = Headings.tournaments.whereYouStand,
                    reload = () => Store.reloadTournamentStandings(),
                    subsection = false
                  )(
                    standingsTable(rows)
                  )
          }
        )

    /** Where the caller stands in each tournament they entered: their place once it is over, and otherwise their seed
      * among those still in, and whether they are out. A table, as standings are, with a header per column.
      */
    private def standingsTable(rows: Seq[TournamentStanding]): HtmlElement =
        div(
          cls := "table-scroll",
          table(
            caption(cls := "sr-only", "Your place in each competition you entered"),
            thead(
              tr(
                th(scopeAttr := "col", "Competition"),
                th(scopeAttr := "col", "Game"),
                th(scopeAttr := "col", "Entry"),
                th(scopeAttr := "col", "Standing")
              )
            ),
            tbody(rows.map { r =>
                val standing = r.finalRank match {
                    case Some(rank)             => s"finished ${ordinal(rank)}"
                    case None if r.isEliminated => "out"
                    case None if r.seed.isDefined =>
                        s"seed ${r.seed.get} of ${r.entrants}" + r.stratum.filter(_ != 0).fold("")(s => s", stratum $s")
                    case None => r.status.label
                }
                tr(
                  td(
                    button(
                      cls := "link",
                      tpe := "button",
                      r.tournamentName,
                      onClick --> (_ => Store.show(Store.Page.OneTournament(r.gameId, r.tournamentId)))
                    )
                  ),
                  td(r.gameName),
                  td(r.characterName.getOrElse("you")),
                  td(standing)
                )
            })
          )
        )

    private def ordinal(n: Int): String = {
        val suffix =
            if ((n % 100) / 10 == 1) "th"
            else
                n % 10 match {
                    case 1 => "st"
                    case 2 => "nd"
                    case 3 => "rd"
                    case _ => "th"
                }
        s"$n$suffix"
    }

    /** A tournament of the viewer's own whose round is over and that waits on them to start the next. */
    private def isWaitingOnMe(s: TournamentSummary): Boolean =
        s.isOwner && s.isRoundOver && !s.tournament.settings.isAutoStarted

    /** One of the caller's own tournaments: what it is and how it was set up, the Manage button, and the step it waits
      * on them for -- starting it, or starting its next round -- when it waits on one.
      */
    private def runningRow(s: TournamentSummary, showGame: Boolean): HtmlElement = {
        val t = s.tournament
        val isStopped = t.isStopped && t.status == TournamentStatus.Running
        val progress =
            Option.when(showGame)(s.gameName).toList ++ List(t.status.label) ++ s.round.map(r => s"round $r") ++
                Option.when(s.isRoundOver)("round over") ++ Option.when(isStopped)("stopped") ++
                Option.when(s.formatName.nonEmpty)(s.formatName) ++ List(s"${s.entrants} entered")
        // Each game parameter by the name its game shows it under, which is known only once the games have come.
        def parameters(game: Option[Game]) = GameSettingsFields
            .storedChoices(t.settings.gameSettings)
            .toSeq
            .sortBy((name, _) => game.fold(0)(_.parameters.indexWhere(_.name == name)))
            .map { (name, chosen) =>
                val shown = game.flatMap(_.parameters.find(_.name == name)).map(_.displayName).filter(_ != name)
                s"${shown.getOrElse(name.capitalize)}: $chosen"
            }
        val ticked = List(
          t.settings.isInvitational -> Labels.competition.invitational,
          t.settings.isFriendly -> Labels.competition.friendly,
          t.settings.hasLateEntries -> Labels.competition.lateEntries,
          t.settings.hasConcurrentRounds -> Labels.competition.concurrentRounds,
          t.settings.isPublic -> Labels.competition.isPublic,
          t.settings.hasRoleChoice -> Labels.competition.chooseRoles,
          t.settings.isLive -> Labels.competition.live,
          t.settings.isAutoStarted -> Labels.competition.autoStart
        ).collect { case (true, caption) => caption }
        li(
          cls := "row",
          button(
            cls := "link",
            tpe := "button",
            t.name,
            onClick --> (_ => Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId)))
          ),
          span(cls := "detail", progress.mkString(" · ", " · ", "")),
          div(
            cls := "detail",
            child.text <-- Store.games.signal
                .map(_.find(_.gameId == t.gameId))
                .distinct
                .map(game => (parameters(game) ++ ticked).mkString(" · "))
          ),
          div(actions(s))
        )
    }

    /* The Manage button of one of the caller's own tournaments, and the step it waits on them for. */
    private def actions(s: TournamentSummary): Seq[Modifier[HtmlElement]] = {
        val t = s.tournament
        Seq(
          button(
            tpe := "button",
            Buttons.tournaments.manage,
            aria.label := Buttons.tournaments.manageNamed(competition = t.name),
            onClick --> { _ =>
                editOnArrival = Some(t.tournamentId)
                Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId))
            }
          ),
          // Offered once it has enough entries to start, which is when its owner may start it -- one that starts
          // itself as well, before it is full or with no most to fill.
          if (t.status == TournamentStatus.Open)
              child <-- hasEnoughToStart(gameId = t.gameId, entered = s.entrants).map(isOffered =>
                  if (!isOffered) emptyNode
                  else
                      busyButton(label = Buttons.tournaments.start, busy = stepBusy(t, "start"))(busy =>
                          Store.runSignedIn(
                            thenListed(
                              ApiClient.tournamentStep(gameId = t.gameId, tournamentId = t.tournamentId, step = "start")
                            ),
                            busy
                          )(_ => ())
                      ).amend(aria.label := Buttons.tournaments.startNamed(competition = t.name))
              )
          else if (isWaitingOnMe(s))
              busyButton(label = Buttons.tournaments.startRound, busy = startRoundBusy(t))(busy =>
                  Store.runSignedIn(
                    thenListed(ApiClient.startRound(gameId = t.gameId, tournamentId = t.tournamentId)),
                    busy
                  )(_ => ())
              ).amend(aria.label := Buttons.tournaments.startRoundNamed(competition = t.name))
          else emptyNode
        )
    }

    /* Whether `entered` entries are enough to start a tournament of the game: false until the games list has
     * arrived, since the game says how many a match seats. */
    private def hasEnoughToStart(gameId: GameId, entered: Int): Signal[Boolean] =
        Store.games.signal.map(_.find(_.gameId == gameId).exists(g => entered >= Tournament.leastToStart(g))).distinct

    /* The tournament whose page is to open with its Edit form open: set by a main-page Manage button, and taken by the
     * page as it is drawn. */
    private var editOnArrival: Option[TournamentId] = None

    /* With `joinIn`, a game's list of competitions the caller could enter, each row with its Join button. */
    private def summaryList(
        rows: Seq[TournamentSummary],
        showGame: Boolean,
        joinIn: Option[Game] = None
    ): HtmlElement =
        ul(
          rows.map { s =>
              val t = s.tournament
              li(
                cls := "row",
                button(
                  cls := "link",
                  tpe := "button",
                  t.name,
                  onClick --> (_ => Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId)))
                ),
                span(
                  cls := "detail",
                  (Option.when(showGame)(s.gameName).toList ++ List(t.status.label, s"${s.entrants} entered") ++
                      Option.when(isWaitingOnMe(s))("round over") ++
                      Option.when(s.isOwner)("yours") ++ Option.when(s.isEntered)("entered") ++
                      Option.when(s.isInvited && !s.isEntered)("invited")).mkString(" · ", " · ", "")
                ),
                joinIn.fold(emptyNode)(joinButton(_, t))
              )
          }
        )

    /* Joining from a game's list. A plain game's entry is made here, and the competition's page then shown, which moves
     * focus off a row the entry may take out of the list; a character game's is made on that page, which asks which
     * character enters. Refused, the list is read again: what it offered had stopped being true. */
    private def joinButton(game: Game, t: Tournament): HtmlElement = {
        val shown = () => Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId))
        busyButton(label = Buttons.tournaments.join, busy = answerBusy(t))(busy =>
            if (game.gameType == GameType.Character) shown()
            else
                Store.runSignedIn(
                  ApiClient.enterTournament(gameId = t.gameId, tournamentId = t.tournamentId, characterId = None),
                  busy,
                  onFailure = _ => Store.reloadTournaments(t.gameId)
                ) { _ =>
                    Views.announce(s"Joined ${t.name}.")
                    Store.reloadTournaments(t.gameId)
                    Store.reloadMyTournaments()
                    shown()
                }
        ).amend(aria.label := Buttons.tournaments.joinNamed(competition = t.name))
    }

    // ---- a tournament's page ----------------------------------------------------------------

    /* The page is drawn again whenever the tournament is reloaded, which every owner action does. What must outlive
     * that is held here, above it: whether the Edit form is open, and the form itself, which is drawn once as it opens
     * from the tournament as it then stood -- redrawn with the page, it closed, or lost what had been typed. The
     * buttons' busy flags are held too, by `heldBusy`. */
    def page(gameId: GameId, tournamentId: TournamentId): HtmlElement = {
        val editing = Var(editOnArrival.contains(tournamentId))
        editOnArrival = None
        // Drawn once per answer, and shared by the two parts.
        val parts = Store.tournament.signal.map(
          _.filter(_.summary.tournament.tournamentId == tournamentId).map(detail(_, editing))
        )
        div(
          child <-- parts.map(_.fold(p(cls := "empty", "Loading…"))(_.top)),
          // Drawn again only as it opens or closes, or as the tournament first arrives -- the form can be asked for
          // open before it has, from the main page's Edit -- and not with every reload.
          child <-- editing.signal
              .combineWith(parts.map(_.isDefined))
              .distinct
              .map((isOpen, _) =>
                  Store.tournament
                      .now()
                      .filter(_ => isOpen)
                      .filter(_.summary.tournament.tournamentId == tournamentId)
                      .flatMap(editForm(_, editing))
                      .getOrElse(emptyNode)
              ),
          child <-- parts.map(_.fold(emptyNode)(_.rest))
        )
    }

    private def editForm(d: TournamentDetail, editing: Var[Boolean]): Option[HtmlElement] = {
        val t = d.summary.tournament
        Store.games
            .now()
            .find(_.gameId == t.gameId)
            .map(g =>
                form(
                  game = g,
                  existing = Some(t),
                  onSaved = () => editing.set(false),
                  entered = d.entries.count(!_.isWithdrawn)
                )
            )
    }

    /** One flag for both Start Next Round buttons, the main page's and the tournament page's. */
    private def startRoundBusy(t: Tournament): Var[Boolean] = heldBusy(s"start round ${t.tournamentId}")

    /** One flag for each of the owner's steps through a tournament's life, for the main page's button and the page's.
      */
    private def stepBusy(t: Tournament, step: String): Var[Boolean] = heldBusy(s"$step tournament ${t.tournamentId}")

    private def reload(d: TournamentDetail): Unit = {
        val t = d.summary.tournament
        Store.reloadTournament(t.gameId, t.tournamentId)
        Store.reloadMyTournaments()
        Store.reloadTournamentChoices()
    }

    /** A tournament's page in two parts, above the Edit form and below it. */
    private case class Detail(top: HtmlElement, rest: HtmlElement)

    private def detail(d: TournamentDetail, editing: Var[Boolean]): Detail = {
        val t = d.summary.tournament
        val game = Store.games.now().find(_.gameId == t.gameId)
        val me = Store.player.now() match {
            case Store.PlayerState.Registered(p) => Some(p)
            case _                               => None
        }
        val names = nameOf(d)

        val top = div(
          h2(t.name),
          p(cls := "detail", s"${d.summary.gameName} · ${t.status.label} · run by ${d.summary.ownerNickname}"),
          if (t.isStopped && t.status == TournamentStatus.Running)
              p(cls := "detail", stoppedNote)
          else emptyNode,
          if (t.description.nonEmpty) p(t.description) else emptyNode,
          if (d.summary.isOwner && t.status != TournamentStatus.Finished && t.status != TournamentStatus.Cancelled)
              ownerControls(d, editing)
          else emptyNode
        )
        val rest = div(
          entriesSection(d, me),
          if (d.summary.isOwner && t.settings.isInvitational) invitationsSection(d) else emptyNode,
          roundSection(d = d, game = game, me = me, names = names),
          d.state.fold(emptyNode)(s =>
              standings(node = s.root, names = names, heading = Some(Headings.tournaments.standings))
          ),
          d.state.flatMap(_.root.ranking).fold(emptyNode)(r => finalRanking(r, names))
        )
        Detail(top = top, rest = rest)
    }

    private val stoppedNote = "Stopped: no more rounds will begin. It finishes when this round is over."

    /** Each entrant by what they are shown as: the character, in a character game, with its player. */
    private def nameOf(d: TournamentDetail): Long => String = {
        val byId = d.entries.map(e => e.entrantId.value -> e).toMap
        id => byId.get(id).fold("an unnamed entrant")(e => e.characterName.fold(e.nickname)(c => s"$c (${e.nickname})"))
    }

    private def ownerControls(d: TournamentDetail, editing: Var[Boolean]): HtmlElement = {
        val t = d.summary.tournament
        def step(label: String, name: String) =
            busyButton(label = label, busy = stepBusy(t, name))(busy =>
                Store.runSignedIn(
                  thenShown(d)(ApiClient.tournamentStep(gameId = t.gameId, tournamentId = t.tournamentId, step = name)),
                  busy
                )(_ => reload(d))
            )
        // `after` is told whether the owner is still on the page they pressed it on: the answer can come after they
        // have gone elsewhere, and must not pull them back or speak about a page they have left. An answer that comes
        // after a sign-out is dropped whole; it is not the next player's.
        def stopButton(label: String, question: String)(after: Boolean => Unit) =
            busyButton(label = label, classes = Some("warn"), busy = heldBusy(s"stop tournament ${t.tournamentId}"))(
              busy =>
                  if (dom.window.confirm(question)) {
                      val from = Store.page.now()
                      Store.runSignedIn(
                        ApiClient.stopTournament(gameId = t.gameId, tournamentId = t.tournamentId),
                        busy
                      )(_ => after(Store.page.now() == from))
                  }
            )
        val roundOpen = d.round.exists(_.status != TournamentMatchStatus.Done)
        val entered = d.entries.count(!_.isWithdrawn)
        // Entries can keep coming after the pool size was set to how many had entered, and a game without roles played
        // as one match a pool would then start as more than one match. A pool of everyone entered never is.
        val morePools = t.defaultPoolSize
            .filter(size =>
                t.status == TournamentStatus.Open &&
                    Store.games.now().exists(g => g.gameId == t.gameId && g.isSeatedWithoutRoles) && entered > size
            )
            .map(size =>
                p(
                  cls := "detail",
                  role := "status",
                  s"$entered have entered and pools hold $size, so this will start as more than one pool. " +
                      "Edit the competition to change the pool size."
                )
            )
        // Set once a Check round is queued: the check finishes after the request does, so the page says so.
        val checking = Var(false)
        // This round's own length, in hours; blank for the tournament's.
        val roundHours = Var("")
        def roundLength =
            roundHours.now().trim.toDoubleOption.filter(_ > 0).map(h => java.time.Duration.ofMinutes((h * 60).round))
        div(
          cls := "card",
          h3(Headings.tournaments.run),
          morePools.getOrElse(emptyNode),
          div(
            cls := "row",
            t.status match {
                case TournamentStatus.Open =>
                    span(
                      child <-- hasEnoughToStart(gameId = t.gameId, entered = d.entries.count(!_.isWithdrawn))
                          .map(if (_) step(Buttons.tournaments.start, "start") else emptyNode)
                    )
                case TournamentStatus.Running =>
                    span(
                      if (roundOpen) emptyNode
                      else
                          span(
                            Views.tipField(
                              id = freshTipId("round-length-tip"),
                              caption = Labels.competition.roundLength,
                              text = Tips.competition.roundLength
                            )(
                              input(
                                tpe := "number",
                                inputMode := "decimal",
                                minAttr := "0",
                                controlled(value <-- roundHours.signal, onInput.mapToValue --> roundHours)
                              )
                            ),
                            busyButton(label = Buttons.tournaments.startRound, busy = startRoundBusy(t))(busy =>
                                Store.runSignedIn(
                                  thenShown(d)(
                                    ApiClient.startRound(
                                      gameId = t.gameId,
                                      tournamentId = t.tournamentId,
                                      duration = roundLength
                                    )
                                  ),
                                  busy
                                )(_ => reload(d))
                            )
                          ),
                      if (roundOpen)
                          withTip(
                            id = freshTipId("check-round-tip"),
                            subject = "Check Round",
                            text = Tips.competition.checkRound
                          )(
                            busyButton(
                              label = Buttons.tournaments.checkRound,
                              busy = heldBusy(s"check round ${t.tournamentId}")
                            )(busy =>
                                Store.run(ApiClient.checkRound(t.gameId, t.tournamentId), busy)(_ => checking.set(true))
                            )
                          )
                      else emptyNode,
                      span(
                        aria.live := "polite",
                        child <-- checking.signal.map(
                          if (_) span(cls := "detail", " Checking the round. Refresh in a minute to see what changed.")
                          else emptyNode
                        )
                      ),
                      if (!t.isStopped && d.design.stages.exists(_.repeat == Repetition.UntilStopped))
                          busyButton(
                            label = Buttons.tournaments.stopRepeating,
                            busy = heldBusy(s"stop repeating ${t.tournamentId}")
                          )(busy =>
                              Store.runSignedIn(ApiClient.stopTournamentStage(t.gameId, t.tournamentId), busy) { _ =>
                                  Views.announce("This stage will not repeat again.")
                                  reload(d)
                              }
                          )
                      else emptyNode
                    )
                case _ => emptyNode
            },
            if (t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled) emptyNode
            else
                button(
                  tpe := "button",
                  // Whether the form is open is the announced state, not a second label: "Close Edit, expanded"
                  // says the same thing twice and reads as a contradiction. The form itself shows it to a sighted user.
                  aria.expanded <-- editing.signal,
                  Buttons.tournaments.edit,
                  onClick --> (_ => editing.update(!_))
                ),
            t.status match {
                // Not started, or no match of it begun, so nothing has been played: stopping it deletes it, which is
                // cancelling it.
                case TournamentStatus.Open | TournamentStatus.Running if !d.hasBegunMatch =>
                    stopButton(
                      label = Buttons.tournaments.cancel,
                      question = "Cancel this competition? It will be deleted, with its entries."
                    )(isStillHere => {
                        Store.reloadMyTournaments()
                        Store.reloadTournaments(t.gameId)
                        if (isStillHere) Store.show(Store.Page.OneGame(t.gameId))
                    })
                case TournamentStatus.Running if !t.isStopped =>
                    // The button goes with the reload, and the note that takes its place is drawn with the page, so
                    // it is said here. FocusKeeper takes the focus to this card's heading.
                    stopButton(
                      label = Buttons.tournaments.stop,
                      question = "Stop this competition? Its current round is played out, and then it finishes."
                    )(isStillHere => {
                        if (isStillHere) Views.announce(stoppedNote)
                        reload(d)
                    })
                case _ => emptyNode
            }
          )
        )
    }

    private def entriesSection(d: TournamentDetail, me: Option[Player]): HtmlElement = {
        val t = d.summary.tournament
        val open =
            t.status == TournamentStatus.Open || (t.status == TournamentStatus.Running && t.settings.hasLateEntries)
        val mine = d.entries.filter(e => me.exists(m => e.playerId.contains(m.playerId)))
        val characterGame = d.gameType == GameType.Character
        val chosen: Var[Option[CharacterId]] = Var(None)
        sectionTag(
          h3(Headings.tournaments.entries(d.entries.count(!_.isWithdrawn).toString)),
          if (d.entries.isEmpty) p(cls := "empty", "Nobody has entered yet.")
          else
              ul(
                cls := "entries",
                d.entries.map { e =>
                    li(
                      cls := "row",
                      span(nameOf(d)(e.entrantId.value)),
                      span(
                        cls := "detail",
                        (if (e.isWithdrawn) " · withdrawn" else "") + s" · rated ${e.ratingAtEntry}"
                      ),
                      if (
                        !e.isWithdrawn && (mine
                            .contains(e) || d.summary.isOwner) && t.status != TournamentStatus.Finished
                      )
                          busyButton(label = Buttons.tournaments.withdraw, busy = heldBusy(s"withdraw ${e.entrantId}"))(
                            busy =>
                                Store.runSignedIn(
                                  ApiClient.withdrawFromTournament(
                                    gameId = t.gameId,
                                    tournamentId = t.tournamentId,
                                    entrantId = e.entrantId
                                  ),
                                  busy
                                ) { _ =>
                                    Views.announce(s"${nameOf(d)(e.entrantId.value)} withdrawn.")
                                    reload(d)
                                }
                          ).amend(
                            aria.label := Buttons.tournaments.withdrawNamed(entrant = nameOf(d)(e.entrantId.value))
                          )
                      else emptyNode
                    )
                }
              ),
          // Offered while the caller has an entry left to make, or one withdrawn to take back up.
          if (
            !open || me.isEmpty || (mine.count(!_.isWithdrawn) >= t.settings.entriesPerPlayer && !mine
                .exists(_.isWithdrawn))
          )
              emptyNode
          else
              div(
                cls := "row",
                if (characterGame)
                    field(
                      "Enter with",
                      select(
                        onMountCallback(_ => Store.refreshCharacters(t.gameId)),
                        onChange.mapToValue --> (v => chosen.set(v.toLongOption.map(CharacterId(_)))),
                        option(value := "", "Choose a character"),
                        // By invitation, only the characters invited may enter, so only they are offered.
                        children <-- Store.charactersByGame.signal.map(
                          _.getOrElse(t.gameId, Seq.empty)
                              .filter(c =>
                                  !t.settings.isInvitational || d.summary.isOwner ||
                                      d.invitedCharacters.exists(_.character.characterId == c.characterId)
                              )
                              .map(c => option(value := c.characterId.value.toString, c.name))
                        )
                      )
                    )
                else emptyNode,
                busyButton(
                  label = if (mine.exists(_.isWithdrawn)) Buttons.tournaments.enterAgain else Buttons.tournaments.enter,
                  busy = answerBusy(t)
                )(busy =>
                    Store.runSignedIn(
                      thenShown(d)(
                        ApiClient.enterTournament(
                          gameId = t.gameId,
                          tournamentId = t.tournamentId,
                          characterId = chosen.now()
                        )
                      ),
                      busy
                    )(_ => reload(d))
                ),
                declineButton(d, me)
              )
        )
    }

    /** Turning down the caller's invitation, before they have entered: theirs in a plain game, and in a character game
      * every invitation of a character of theirs. Nothing when they have none.
      */
    private def declineButton(d: TournamentDetail, me: Option[Player]): Node = {
        val t = d.summary.tournament
        val declines: Seq[() => Future[Unit]] = me.toSeq.flatMap { m =>
            d.invited
                .filter(_.playerId == m.playerId)
                .map(_ =>
                    () =>
                        ApiClient.revokeTournamentInvitation(
                          gameId = t.gameId,
                          tournamentId = t.tournamentId,
                          playerId = m.playerId
                        )
                ) ++ d.invitedCharacters
                .filter(_.owner.exists(_.playerId == m.playerId))
                .map(inv =>
                    () =>
                        ApiClient.revokeTournamentCharacterInvitation(
                          gameId = t.gameId,
                          tournamentId = t.tournamentId,
                          characterId = inv.character.characterId
                        )
                )
        }
        val hasEntered = d.entries.exists(e => me.exists(m => e.playerId.contains(m.playerId)))
        if (declines.isEmpty || hasEntered) emptyNode
        else
            busyButton(label = Buttons.tournaments.decline, busy = answerBusy(t))(busy =>
                // Reloaded even when one of them fails: the others went through, and the page would otherwise go on
                // offering them.
                Store.runSignedIn(thenShown(d)(Future.sequence(declines.map(_()))), busy, onFailure = _ => reload(d)) {
                    _ =>
                        Views.announce("Invitation declined.")
                        Store.reloadMyTournaments()
                        reload(d)
                }
            )
    }

    /* Entering and declining, one busy flag between them: each changes what the other may do, and until its answer
     * reloads the page, both are still offered. So it is held through that reload too -- see `thenShown`. */
    private def answerBusy(t: Tournament): Var[Boolean] = heldBusy(s"answer tournament ${t.tournamentId.value}")

    /* `action`, settled only once the page has been read again after it: a button whose answer changes what the page
     * offers stays busy until the page says so, rather than for one round trip less. */
    private def thenShown[A](d: TournamentDetail)(action: Future[A]): Future[A] = {
        val t = d.summary.tournament
        action.flatMap(answer => Store.reloadTournament(t.gameId, t.tournamentId).map(_ => answer))
    }

    /* `action`, settled only once the caller's lists have been read again after it: the start buttons in their rows,
     * which the answer takes away, stay busy until the rows are drawn again, rather than offering a second press that
     * is refused because the first one worked. `thenShown` is the same for the tournament's own page. */
    private def thenListed[A](action: Future[A]): Future[A] =
        action.flatMap(answer => Store.reloadMyTournaments().zip(Store.reloadTournamentChoices()).map(_ => answer))

    /** Who may enter an invitational tournament, and the owner's means of changing that: players in a plain game, and
      * in a character game characters -- found through their owners, and answered by whoever owns them when they do.
      */
    private def invitationsSection(d: TournamentDetail): HtmlElement = {
        val t = d.summary.tournament
        val characterGame = d.gameType == GameType.Character
        // The player this competition was made for, from their page: found already, and forgotten once used.
        val asked = Store.invitee.now()
        Store.invitee.set(None)
        val prefix = Var(asked.fold("")(_.nickname))
        val found: Var[Seq[PublicPlayer]] = Var(asked.toSeq)
        val invitedCharacters = d.invitedCharacters.map(_.character.characterId).toSet
        sectionTag(
          h3(Headings.tournaments.invitations),
          if (d.invited.isEmpty && d.invitedCharacters.isEmpty) p(cls := "empty", "Nobody is invited yet.")
          else
              ul(
                d.invited.map(inv =>
                    li(
                      cls := "row",
                      span(inv.nickname),
                      busyButton(
                        label = Buttons.tournaments.uninvite,
                        busy = heldBusy(s"uninvite ${inv.playerId} from ${t.tournamentId}")
                      )(busy =>
                          Store.runSignedIn(
                            ApiClient.revokeTournamentInvitation(
                              gameId = t.gameId,
                              tournamentId = t.tournamentId,
                              playerId = inv.playerId
                            ),
                            busy
                          ) { _ =>
                              Views.announce(s"Invitation of ${inv.nickname} removed.")
                              reload(d)
                          }
                      ).amend(aria.label := Buttons.tournaments.uninviteNamed(invitee = inv.nickname))
                    )
                ) ++ d.invitedCharacters.map(inv =>
                    li(
                      cls := "row",
                      span(inv.character.name),
                      span(cls := "detail", inv.owner.fold(" · nobody's")(o => s" · ${o.nickname}'s")),
                      busyButton(
                        label = Buttons.tournaments.uninvite,
                        busy = heldBusy(s"uninvite ${inv.character.characterId} from ${t.tournamentId}")
                      )(busy =>
                          Store.runSignedIn(
                            ApiClient
                                .revokeTournamentCharacterInvitation(
                                  gameId = t.gameId,
                                  tournamentId = t.tournamentId,
                                  characterId = inv.character.characterId
                                ),
                            busy
                          ) { _ =>
                              Views.announce(s"Invitation of ${inv.character.name} removed.")
                              reload(d)
                          }
                      ).amend(aria.label := Buttons.tournaments.uninviteNamed(invitee = inv.character.name))
                    )
                )
              ),
          div(
            cls := "row",
            field(
              Labels.competition.findPlayer,
              input(controlled(value <-- prefix.signal, onInput.mapToValue --> prefix))
            ),
            busyButton(label = Buttons.tournaments.search, disabledWhen = prefix.signal.map(_.trim.isEmpty))(busy =>
                Store.run(ApiClient.searchPlayers(prefix.now().trim), busy)(r => found.set(r.players))
            )
          ),
          ul(
            aria.live := "polite",
            children <-- found.signal.map(_.map { p =>
                if (characterGame)
                    // A character game invites characters, so the player found is a way to theirs.
                    li(
                      cls := "row",
                      span(p.nickname),
                      Players.characterInviteButtons(gameId = t.gameId, owner = p, exclude = invitedCharacters) {
                          (c, busy) =>
                              Store.run(
                                ApiClient.inviteCharacterToTournament(
                                  gameId = t.gameId,
                                  tournamentId = t.tournamentId,
                                  characterId = c.characterId
                                ),
                                busy
                              )(_ => reload(d))
                      }
                    )
                else
                    li(
                      cls := "row",
                      span(p.nickname),
                      busyButton(
                        label = Buttons.tournaments.invite,
                        busy = heldBusy(s"invite ${p.playerId} to ${t.tournamentId}")
                      )(busy =>
                          Store.runSignedIn(
                            ApiClient.inviteToTournament(
                              gameId = t.gameId,
                              tournamentId = t.tournamentId,
                              playerId = p.playerId
                            ),
                            busy
                          ) { _ =>
                              Views.announce(s"${p.nickname} invited.")
                              reload(d)
                          }
                      ).amend(aria.label := Buttons.tournaments.inviteNamed(nickname = p.nickname))
                    )
            })
          )
        )
    }

    /** The round being played: each match, its seats and their roles, the caller's choice when it is their turn, and
      * the owner's means of deciding a match by hand. A live region, since its matches move on while the page is open.
      */
    private def roundSection(
        d: TournamentDetail,
        game: Option[Game],
        me: Option[Player],
        names: Long => String
    ): HtmlElement = {
        val t = d.summary.tournament
        val roleName = game.toList.flatMap(_.roles).map(r => r.gameRoleId -> r.displayName).toMap
        val entryOf = d.entries.map(e => e.entrantId -> e).toMap
        if (d.round.isEmpty) div()
        else
            sectionTag(
              aria.live := "polite",
              h3(Headings.tournaments.round(d.state.map(_.round).getOrElse(0).toString)),
              if (d.summary.isRoundOver)
                  p(
                    cls := "detail",
                    "Every match of this round is over." +
                        (if (t.settings.isAutoStarted) ""
                         else if (d.summary.isOwner) " Start the next round when you are ready."
                         else s" The next starts when ${d.summary.ownerNickname} starts it.")
                  )
              else
                  t.roundDeadline.fold(emptyNode)(at =>
                      p(cls := "detail", s"Every match of this round must be over by ${when(at)}.")
                  ),
              ul(
                d.round.map { m =>
                    val myTurn = m.seats.find(s =>
                        s.isChoosing && entryOf
                            .get(s.entrantId)
                            .exists(e => me.exists(m => e.playerId.contains(m.playerId)))
                    )
                    li(
                      div(
                        cls := "row",
                        span(
                          m.seats
                              .map(s =>
                                  names(s.entrantId.value) + s.role.flatMap(roleName.get).fold("")(r => s" as $r")
                              )
                              .mkString(" v ")
                        ),
                        span(
                          cls := "detail",
                          s" · ${statusText(m)}" +
                              m.seats.find(_.isChoosing).flatMap(_.chooseBy).fold("")(by => s" · choose by ${when(by)}")
                        )
                      ),
                      m.createFailure.fold(emptyNode)(f =>
                          p(
                            cls := "error",
                            if (f.isStopped) ErrorText.round.notCreatedStopped(error = f.error, at = when(f.at))
                            else ErrorText.round.notCreated(error = f.error, tries = f.tries.toString, at = when(f.at))
                          )
                      ),
                      myTurn.fold(emptyNode)(_ => chooser(t = t, m = m, game = game)),
                      if (d.summary.isOwner && m.status != TournamentMatchStatus.Done)
                          decider(t = t, m = m, names = names, d = d)
                      else emptyNode
                    )
                }
              )
            )
    }

    private def statusText(m: TournamentMatchView): String = m.status match {
        case TournamentMatchStatus.Choosing => "choosing roles"
        case TournamentMatchStatus.Due      => "about to begin"
        case TournamentMatchStatus.Started  => "being played"
        case TournamentMatchStatus.Done     => "finished"
    }

    /** The caller's choice of role, among those still free. */
    private def chooser(t: Tournament, m: TournamentMatchView, game: Option[Game]): HtmlElement = {
        val taken = m.seats.flatMap(_.role).toSet
        val free = game.toList
            .flatMap(g => TournamentGame.of(g).seatingRoles.take(m.seats.size))
            .map(r => GameRoleId(r.id))
            .filterNot(taken)
        val roleName = game.toList.flatMap(_.roles).map(r => r.gameRoleId -> r.displayName).toMap
        val picked: Var[Option[GameRoleId]] = Var(free.headOption)
        div(
          cls := "row",
          field(
            "Your role",
            select(
              onChange.mapToValue --> (v => picked.set(v.toIntOption.map(GameRoleId(_)))),
              free.map(r => option(value := r.value.toString, roleName.getOrElse(r, r.value.toString)))
            )
          ),
          busyButton(
            label = Buttons.tournaments.pickRole,
            disabledWhen = picked.signal.map(_.isEmpty),
            busy = heldBusy(s"choose role in ${m.matchId}")
          )(busy =>
              picked
                  .now()
                  .foreach(r =>
                      Store.runSignedIn(
                        ApiClient.chooseRole(
                          gameId = t.gameId,
                          tournamentId = t.tournamentId,
                          matchId = m.matchId,
                          role = r
                        ),
                        busy
                      )(_ => Store.tournament.now().foreach(reload))
                  )
          )
        )
    }

    /** The owner deciding a match by hand: a rank for each seat. */
    private def decider(
        t: Tournament,
        m: TournamentMatchView,
        names: Long => String,
        d: TournamentDetail
    ): HtmlElement = {
        val open = Var(false)
        val ranks = m.seats.map(s => s.entrantId -> Var((m.seats.indexOf(s) + 1).toString))
        div(
          withTip(
            id = freshTipId("decide-tip"),
            subject = "Decide by hand",
            text = Tips.competition.decide
          )(
            button(
              tpe := "button",
              cls := "link",
              child.text <-- open.signal.map(if (_) Buttons.app.close else Buttons.tournaments.decideByHand),
              onClick --> (_ => open.update(!_))
            )
          ),
          child <-- open.signal.map {
              case false => emptyNode
              case true =>
                  div(
                    cls := "row",
                    ranks.map((e, v) =>
                        field(
                          s"Rank of ${names(e.value)}",
                          input(
                            tpe := "number",
                            inputMode := "numeric",
                            minAttr := "1",
                            controlled(value <-- v.signal, onInput.mapToValue --> v)
                          )
                        )
                    ),
                    busyButton(label = Buttons.tournaments.saveRanks, busy = heldBusy(s"decide ${m.matchId}"))(busy =>
                        val typed = ranks.map((e, v) => v.now().trim.toIntOption.filter(_ >= 1).map(EntrantRank(e, _)))
                        if (typed.exists(_.isEmpty)) Store.reportProblem(ErrorText.competitionForm.unranked)
                        else
                            Store.runSignedIn(
                              ApiClient
                                  .decideMatch(
                                    gameId = t.gameId,
                                    tournamentId = t.tournamentId,
                                    matchId = m.matchId,
                                    ranks = typed.flatten.toList
                                  ),
                              busy
                            )(_ => reload(d))
                    )
                  )
          }
        )
    }

    /** A (sub)tournament's stage runs, latest first, each pool's standings in a table. A pool that plays a design shows
      * that design's own standings beneath it.
      */
    private def standings(node: TNode, names: Long => String, heading: Option[String]): HtmlElement =
        if (node.runs.isEmpty) div()
        else
            sectionTag(
              heading.fold(emptyNode)(h => h3(h)),
              node.runs.reverse.map(run => runView(run, names))
            )

    private def runView(run: StageRun, names: Long => String): HtmlElement =
        val stage = (run.stage + 1).toString
        val named =
            if (run.repetition > 0)
                Headings.tournaments.stageRepeated(stage = stage, play = (run.repetition + 1).toString)
            else Headings.tournaments.stage(stage)
        div(
          h4(if (run.isCompleted) named else Headings.tournaments.stageBeingPlayed(named)),
          run.pools.zipWithIndex.map((pool, i) => poolView(pool = pool, label = poolName(i), names = names)),
          if (run.sittingOut.isEmpty) emptyNode
          else p(cls := "detail", s"Sitting this one out: ${run.sittingOut.map(names).mkString(", ")}")
        )

    /** A pool by its place in its run: A, B, C… — never the id the state keys it by. */
    private def poolName(i: Int): String =
        if (i < 26) ('A' + i).toChar.toString else s"${i + 1}"

    private def poolView(pool: Pool, label: String, names: Long => String): HtmlElement = {
        val scored = pool.members.exists(_.points.isDefined)
        // A pool that plays a design of one run of one pool -- an elimination match -- would only repeat its own table.
        val nested = pool.sub.filter(sub => sub.runs.sizeIs > 1 || sub.runs.exists(_.pools.sizeIs > 1))
        div(
          div(
            cls := "table-scroll",
            table(
              cls := "leaderboard",
              caption(s"Pool $label" + (if (pool.stratum != 0) s", stratum ${pool.stratum}" else "")),
              thead(
                tr(
                  th(scopeAttr := "col", "Seed"),
                  th(scopeAttr := "col", "Participant"),
                  if (scored) th(scopeAttr := "col", "Points") else emptyNode,
                  th(scopeAttr := "col", "Rank"),
                  th(scopeAttr := "col", "Outcome")
                )
              ),
              tbody(
                pool.members
                    .sortBy(m => (m.rank.getOrElse(Int.MaxValue), m.seedIn))
                    .map(m =>
                        tr(
                          td(m.seedIn.toString),
                          td(names(m.entrant)),
                          if (scored) td(m.points.fold("")(p => if (p == p.floor) p.toLong.toString else p.toString))
                          else emptyNode,
                          td(m.rank.fold("")(_.toString)),
                          td(outcomeOf(pool, m))
                        )
                    )
              )
            )
          ),
          nested.fold(emptyNode)(sub =>
              div(cls := "detail-panel", standings(node = sub, names = names, heading = None))
          )
        )
    }

    private def outcomeOf(pool: Pool, m: PoolMember): String =
        if (!pool.isCompleted) ""
        else if (m.isEliminated) "out"
        else
            m.stratumOut match {
                case Some(s) if s > m.stratumIn => "up"
                case Some(s) if s < m.stratumIn => "down"
                case _                          => "stays"
            }

    private def finalRanking(ranking: Map[Long, Int], names: Long => String): HtmlElement =
        sectionTag(
          h3(Headings.tournaments.finalRanking),
          div(
            cls := "table-scroll",
            table(
              cls := "leaderboard",
              caption(cls := "sr-only", "Final ranking"),
              thead(tr(th(scopeAttr := "col", "Rank"), th(scopeAttr := "col", "Participant"))),
              tbody(ranking.toList.sortBy((e, r) => (r, names(e))).map((e, r) => tr(td(r.toString), td(names(e)))))
            )
          )
        )

    private def when(at: java.time.Instant): String =
        new scala.scalajs.js.Date(at.toEpochMilli.toDouble).toLocaleString()

    // ---- creating and editing ---------------------------------------------------------------

    /** The models offered for a game, grouped by classification, as a select's options: those with none first, under no
      * heading. `current` is drawn chosen: the options can arrive after the select's value was set, and a value set
      * before its option exists is lost, leaving the first shown as chosen.
      */
    private def modelOptions(models: Seq[TournamentModel], current: Option[TournamentModelId]): Seq[HtmlElement] = {
        def options(ms: Seq[TournamentModel]): Seq[HtmlElement] =
            ms.sortBy(_.name.toLowerCase)
                .map(m =>
                    option(
                      value := m.tournamentModelId.value.toString,
                      selected := current.contains(m.tournamentModelId),
                      m.name
                    )
                )
        val (classified, unclassified) = models.partition(_.classification.isDefined)
        options(unclassified) ++
            classified
                .groupBy(_.classification.get)
                .toSeq
                .sortBy(_._1)
                .map((classification, inGroup) => optGroup(labelAttr := classification, options(inGroup)))
    }

    /* The units a match time limit is given in, and how many minutes each is. */
    private val timeUnits: Seq[(String, Long)] =
        Seq("minutes" -> 1L, "hours" -> 60L, "days" -> 1440L, "weeks" -> 10080L)

    /* The match time limit a new competition starts with, and saves when its format leaves the field out: none, as
     * on the server. */
    private val defaultTimeLimit: Option[java.time.Duration] = None

    private val labelAttr = htmlAttr("label", com.raquo.laminar.codecs.StringAsIsCodec)

    /** The form that creates a tournament from one of the models its game offers, or edits `existing`. A running
      * tournament's model and default pool size are not offered for editing, since its stages cannot change.
      *
      * For a game without roles it also asks how many players a match has, and — for a tournament not yet started, with
      * `entered` entries so far — offers the pool size that puts everyone entered in one pool. `headingId` heads it
      * Headings.tournaments.create, under that id, for the dialog it is shown in.
      */
    def form(
        game: Game,
        existing: Option[Tournament],
        onSaved: () => Unit,
        entered: Int = 0,
        headingId: Option[String] = None
    ): HtmlElement = {
        val key = existing.fold("new-tournament")(t => s"tournament-${t.tournamentId.value}")
        val running = existing.exists(_.status == TournamentStatus.Running)
        val s0 = existing.map(_.settings).getOrElse(TournamentSettings())
        // A game without roles: whether each pool is one match, and otherwise how many players a match has, as typed.
        val wholePool = Var(game.isSeatedWithoutRoles && existing.isDefined && s0.playersPerMatch.isEmpty)
        val perMatch = Var(s0.playersPerMatch.orElse(game.fewestPlayers).fold("")(_.toString))
        val name = Var(existing.map(_.name).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val models: Signal[Seq[TournamentModel]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty))
        // The model chosen, by id; none until the models have come, for a new tournament.
        val modelId: Var[Option[TournamentModelId]] = Var(existing.map(_.tournamentModelId))
        val poolSize = Var(existing.flatMap(_.defaultPoolSize).fold("")(_.toString))
        // Who a new competition is for, when it was asked for from their page: invitational, and once it is made its
        // page opens with them found, ready to invite.
        val asked = if (existing.isEmpty) Store.invitee.now() else None
        val invitational = Var(s0.isInvitational || asked.isDefined)
        // Unticked for a new competition: an admin's is rated unless they tick it.
        val friendly = Var(existing.fold(false)(_.settings.isFriendly))
        // Whether the creator of a new competition enters it as it is made: ticked unless they administer the game, who
        // more often makes one for others to play. The game's admins can arrive after the form is drawn, and the box
        // follows them until the creator ticks or unticks it. With a character of theirs, in a character game.
        val join = Var(!Store.isAdministering(game.gameId))
        var hasChosenJoin = false
        val joinWith: Var[Option[CharacterId]] = Var(None)
        val isCharacterGame = game.gameType == GameType.Character
        val lateEntries = Var(s0.hasLateEntries)
        val concurrentRounds = Var(s0.hasConcurrentRounds)
        val isPublic = Var(s0.isPublic)
        val minRating = Var(s0.minRating.fold("")(_.toString))
        val maxRating = Var(s0.maxRating.fold("")(_.toString))
        val entries = Var(s0.entriesPerPlayer.toString)
        // The match time limit as typed, in the unit chosen: the largest unit it is a whole number of, and blank, in
        // hours, for none.
        val (limit0, unit0) = s0.roundDuration.orElse(defaultTimeLimit).fold(("", "hours")) { d =>
            val minutes = d.toMinutes
            timeUnits.reverse
                .collectFirst { case (unit, size) if minutes % size == 0 => ((minutes / size).toString, unit) }
                .getOrElse((minutes.toString, "minutes"))
        }
        val timeLimit = Var(limit0)
        val timeUnit = Var(unit0)
        val maxTurns = Var(s0.maxTurns.fold("")(_.toString))
        val chooseRoles = Var(s0.hasRoleChoice)
        val live = Var(s0.isLive)
        val maxEntrants = Var(s0.maxEntrants.fold("")(_.toString))
        val autoStart = Var(s0.isAutoStarted)
        val plannedStart = Var(s0.start.fold("")(GameSettingsFields.localInputOf))
        val parameters = Var(GameSettingsFields.startingChoices(game, s0.gameSettings))
        val problems: Var[List[Problem]] = Var(Nil)
        // Whether a save has been tried: until then a format not yet chosen is not called a problem.
        val hasTriedSaving = Var(false)
        val chosen: Var[Option[TournamentModel]] = Var(None)
        // The captions and tips as the chosen format words them, where it does, and the fields and tips it leaves out,
        // as `None` (`FormOverrides`).
        val overrides: Signal[Map[String, Option[String]]] =
            chosen.signal.map(_.fold(Map.empty[String, Option[String]])(_.formOverrides)).distinct
        def labelOf(key: String, default: String): Signal[Option[String]] =
            overrides.map(FormOverrides.label(_, key, default))
        def tipOf(key: String, default: String, fills: (String, String)*): Signal[Option[String]] =
            overrides.map(FormOverrides.tip(_, key, default, fills*))
        def isLeftOut(key: String): Boolean =
            chosen.now().exists(m => FormOverrides.label(m.formOverrides, key, "").isEmpty)
        /* What a field saves: what it holds, or, when the chosen format leaves it out, `default` -- what a new
         * competition starts with, which for a field that choosing a format fills in is what the format fills in. */
        def unlessLeftOut[A](key: String, value: => A, default: => A): A = if (isLeftOut(key)) default else value
        def isJoining: Boolean =
            existing.isEmpty && unlessLeftOut(key = "competition.join", value = join.now(), default = false)
        val fresh = TournamentSettings()
        // The model an existing tournament was made from, when the game no longer offers it -- its admins took it off,
        // or the code no longer defines it: not among those offered, and kept unless another is chosen.
        val kept = existing.map(_.tournamentModelId)
        val keptRetired: Signal[Option[TournamentModelId]] =
            models.map(ms => kept.filter(id => ms.nonEmpty && !ms.exists(_.tournamentModelId == id)))
        def isKeeping: Boolean = chosen.now().isEmpty && modelId.now().exists(kept.contains)

        def playersPerMatch: Option[Int] =
            if (!game.isSeatedWithoutRoles) None
            else
                unlessLeftOut(
                  key = "competition.playersPerMatch",
                  value = Option.when(!wholePool.now())(perMatch.now().trim.toIntOption).flatten,
                  default = game.fewestPlayers
                )
        // The game's R as this tournament plays it: its roles, or for a game without roles the players per match.
        def r: Int = TournamentGame.of(game, playersPerMatch).poolSeats

        // The number of participants choosing a model last filled in, while the field still holds it.
        var filledEntrants: Option[String] = None
        /* Choosing a model fills in the pool size it suggests -- the game's R where it suggests none -- whether it
         * takes late entries, and whether its players choose their roles; all stay the creator's to change, but for a
         * model whose players always choose, which every tournament made from it does. A model whose pool is everyone
         * taking part fills in the number of participants as well, the game's R and at least 2, unless one has been
         * typed; and a model chosen after it takes that filled-in number back out. */
        def choose(m: TournamentModel): Unit = {
            modelId.set(Some(m.tournamentModelId))
            chosen.set(Some(m))
            poolSize.set(m.poolSize.getOrElse(r).toString)
            lateEntries.set(m.hasLateEntries)
            chooseRoles.set(m.hasRoleChoice)
            val typed = maxEntrants.now().trim
            val isUntyped = typed.isEmpty || filledEntrants.contains(typed)
            if (isUntyped) {
                filledEntrants = Option.when(m.hasPoolOfEveryone)(math.max(r, 2).toString)
                maxEntrants.set(filledEntrants.getOrElse(""))
            }
        }

        def int(v: Var[String]) = v.now().trim.toIntOption
        def duration: Option[java.time.Duration] =
            unlessLeftOut(
              key = "competition.timeLimit",
              value = timeLimit.now().trim.toDoubleOption.filter(_ > 0).map { n =>
                  val size = timeUnits.toMap.getOrElse(timeUnit.now(), 60L)
                  java.time.Duration.ofMinutes((n * size).round)
              },
              default = defaultTimeLimit
            )
        // Left out, the server's default: the format's suggestion, else the game's R.
        def poolSizeAsked: Option[Int] =
            unlessLeftOut(key = "competition.poolSize", value = int(poolSize), default = None)
        def maxEntrantsAsked: Option[Int] =
            unlessLeftOut(key = "competition.maxEntrants", value = int(maxEntrants), default = fresh.maxEntrants)
        def settings = TournamentSettings(
          // Anybody else, who is not shown the box, keeps what it was -- friendly, for a new competition.
          isFriendly = unlessLeftOut(
            key = "competition.friendly",
            value = if (Store.isAdministering(game.gameId)) friendly.now() else s0.isFriendly,
            default = fresh.isFriendly
          ),
          isInvitational = unlessLeftOut(
            key = "competition.invitational",
            value = invitational.now(),
            default = fresh.isInvitational || asked.isDefined
          ),
          hasLateEntries = unlessLeftOut(
            key = "competition.lateEntries",
            value = lateEntries.now(),
            default = chosen.now().fold(fresh.hasLateEntries)(_.hasLateEntries)
          ),
          minRating = unlessLeftOut(key = "competition.minRating", value = int(minRating), default = fresh.minRating),
          maxRating = unlessLeftOut(key = "competition.maxRating", value = int(maxRating), default = fresh.maxRating),
          entriesPerPlayer = unlessLeftOut(
            key = "competition.entries",
            value = int(entries).getOrElse(1),
            default = fresh.entriesPerPlayer
          ),
          roundDuration = duration,
          maxTurns = unlessLeftOut(key = "competition.maxTurns", value = int(maxTurns), default = fresh.maxTurns),
          hasConcurrentRounds = unlessLeftOut(
            key = "competition.concurrentRounds",
            value = concurrentRounds.now(),
            default = fresh.hasConcurrentRounds
          ),
          isPublic = unlessLeftOut(key = "competition.isPublic", value = isPublic.now(), default = fresh.isPublic),
          gameSettings = GameSettingsFields.settingsOf(parameters.now()),
          isLive = unlessLeftOut(key = "competition.live", value = live.now(), default = fresh.isLive),
          start = unlessLeftOut(
            key = "competition.plannedStart",
            value = GameSettingsFields.startOf(plannedStart.now()),
            default = fresh.start
          ),
          hasRoleChoice = unlessLeftOut(
            key = "competition.chooseRoles",
            value = chooseRoles.now(),
            default = chosen.now().fold(fresh.hasRoleChoice)(_.hasRoleChoice)
          ),
          maxEntrants = maxEntrantsAsked,
          isAutoStarted =
              unlessLeftOut(key = "competition.autoStart", value = autoStart.now(), default = fresh.isAutoStarted),
          playersPerMatch = playersPerMatch
        )
        /* "A number of players" with no number is not "everyone in the pool", though both leave `playersPerMatch`
         * empty: refused here, so that a blank or mistyped count is never saved as the other mode. */
        def countProblem: List[Problem] =
            Option
                .when(
                  game.isSeatedWithoutRoles && !isLeftOut("competition.playersPerMatch") && !wholePool.now() &&
                      perMatch.now().trim.toIntOption.forall(_ < 1)
                )(
                  Problem(
                    where = "",
                    message = "players per match has to be a whole number above 0, or choose Entire Pool"
                  )
                )
                .toList
        def found: List[Problem] =
            countProblem ++ (chosen.now() match {
                // The format it was made from, checked again as it is saved.
                case None if isKeeping => Nil
                case None => if (hasTriedSaving.now()) List(Problem(where = "", message = "choose a format")) else Nil
                case Some(m) =>
                    Validation.validate(
                      design = m.design,
                      game = TournamentGame.of(game, playersPerMatch),
                      maxTurns = settings.maxTurns,
                      // As the server checks it: a pool of everyone entered at the most it takes.
                      defaultPoolSize = poolSizeAsked
                          .orElse(m.poolSize)
                          .orElse(if (m.hasPoolOfEveryone) maxEntrantsAsked else Some(r))
                    )
            })
        def check(): Unit = problems.set(found)

        /* The fields a check reads, with those choosing a format also fills in, as last accepted: a change whose check
         * raises a problem -- an error or a warning -- they did not have is put back, and the problem said in the
         * banner instead. The problems are worked out rather than read back from `problems`, whose set, made inside
         * an observer, lands only after it returns. */
        type Fields =
            (Boolean, String, Option[TournamentModel], Option[TournamentModelId], String, String, Boolean, Boolean)
        def fields: Fields = (
          wholePool.now(),
          perMatch.now(),
          chosen.now(),
          modelId.now(),
          maxTurns.now(),
          poolSize.now(),
          lateEntries.now(),
          chooseRoles.now()
        )
        var accepted: Fields = fields
        var acceptedProblems: List[Problem] = Nil
        // Whether the next check is of the format the form fills in as the formats arrive: not a change to undo.
        var isFillingIn = false
        /* The banner the form last raised -- a change undone, or a save refused for what the form lacks -- while it may
         * still be up. It is about what the form held then, so it comes down at the next change made on the form, or
         * when the form closes. */
        var raisedNotice: Option[String] = None
        def raiseNotice(notice: String): Unit = {
            Store.reportProblem(notice)
            raisedNotice = Some(notice)
        }
        def withdrawNotice(): Unit = {
            raisedNotice.foreach(Store.withdrawProblem)
            raisedNotice = None
        }
        def checkChange(): Unit = {
            val now = found
            val raised = now.filterNot(acceptedProblems.contains)
            if (raised.isEmpty || isFillingIn) {
                isFillingIn = false
                accepted = fields
                acceptedProblems = now
                problems.set(now)
            } else {
                val (pool, count, model, id, turns, size, late, roles) = accepted
                Var.set(
                  wholePool -> pool,
                  perMatch -> count,
                  chosen -> model,
                  modelId -> id,
                  maxTurns -> turns,
                  poolSize -> size,
                  lateEntries -> late,
                  chooseRoles -> roles
                )
                problems.set(acceptedProblems)
                val notice = ErrorText.competitionForm.undone(raised.map(_.describe).mkString("; "))
                raiseNotice(notice)
            }
        }

        def checkbox(
            caption: Signal[Option[String]],
            v: Var[Boolean],
            tip: Signal[Option[String]],
            disabledWhen: Signal[Boolean] = Val(false),
            onChoose: Boolean => Unit = _ => ()
        ) = {
            val id = freshTipId(s"$key-tip")
            val words = caption.map(_.getOrElse(""))
            withTip(id = id, subject = words, text = tip)(
              label(
                input(
                  tpe := "checkbox",
                  aria.describedBy := id,
                  disabled <-- disabledWhen,
                  controlled(
                    checked <-- v.signal,
                    onClick.mapToChecked --> { on =>
                        v.set(on); onChoose(on)
                    }
                  )
                ),
                child.text <-- words
              )
            ).amend(Views.shownWhen(caption))
        }
        def number(caption: Signal[Option[String]], v: Var[String], tip: Signal[Option[String]]) =
            Views.tipField(id = freshTipId(s"$key-tip"), caption = caption, text = tip)(
              input(tpe := "number", inputMode := "numeric", controlled(value <-- v.signal, onInput.mapToValue --> v))
            )

        /* How many players a match of a game without roles has: a number in the game's range, or the entire pool. */
        val uncappedCounts = 18
        def playersControl: HtmlElement = {
            val fewest = game.requiredSeats.toString
            val id = freshTipId(s"$key-tip")
            val caption = labelOf("competition.playersPerMatch", Labels.competition.playersPerMatch)
            val words = caption.map(_.getOrElse(""))
            // The counts offered: the game's range, up to `uncappedCounts` more for a game with no most, and the count
            // already saved should it lie outside them.
            val counts = {
                val range = game.requiredSeats to game.mostSeats.getOrElse(game.requiredSeats + uncappedCounts)
                (range ++ s0.playersPerMatch.filterNot(range.contains)).sorted
            }
            div(
              Views.shownWhen(caption),
              withTip(
                id = id,
                subject = words,
                text = game.mostSeats.fold(
                  tipOf(
                    "competition.playersPerMatchAtLeast",
                    Tips.competition.playersPerMatchAtLeast(fewest = fewest),
                    "fewest" -> fewest
                  )
                )(m =>
                    tipOf(
                      "competition.playersPerMatchBetween",
                      Tips.competition.playersPerMatchBetween(fewest = fewest, most = m.toString),
                      "fewest" -> fewest,
                      "most" -> m.toString
                    )
                )
              )(
                label(
                  cls := "field",
                  child.text <-- words,
                  select(
                    aria.describedBy := id,
                    onChange.mapToValue --> { v =>
                        wholePool.set(v == "pool")
                        if (v != "pool") perMatch.set(v)
                    },
                    value <-- wholePool.signal
                        .combineWith(perMatch.signal)
                        .map((isPool, n) => if (isPool) "pool" else n.trim),
                    counts.map(n => option(value := n.toString, n.toString)),
                    option(value := "pool", "Entire Pool")
                  )
                )
              )
            )
        }

        div(
          cls := "card competition-form",
          // Any field's input or change bubbles up to here; setting the fields from code, as an undo does, does not.
          onInput --> (_ => withdrawNotice()),
          onChange --> (_ => withdrawNotice()),
          onUnmountCallback(_ => withdrawNotice()),
          // Headed when it is a dialog of its own, which is named by its heading.
          headingId.fold(emptyNode)(id => h3(idAttr := id, Headings.tournaments.create)),
          // The models as they come: an existing tournament's is found among them, and a new one starts on the first.
          models --> { ms =>
              chosen.now() match {
                  case Some(_) => ()
                  case None =>
                      val filled = modelId.now() match {
                          case Some(id) => ms.find(_.tournamentModelId == id)
                          case None     => ms.headOption
                      }
                      filled.foreach { m =>
                          isFillingIn = true
                          if (modelId.now().isDefined) chosen.set(Some(m)) else choose(m)
                      }
              }
          },
          Store.currentPlayer.flatMapSwitch {
              case Some(player) => Store.administers(game.gameId, player)
              case None         => Val(false)
          } --> (isAdmin => if (!hasChosenJoin) join.set(!isAdmin)),
          asked.fold(emptyNode)(who =>
              p(cls := "detail", s"Once it is made, you can invite ${who.nickname} from its page.")
          ),
          field(
            labelOf("competition.name", Labels.competition.name),
            input(controlled(value <-- name.signal, onInput.mapToValue --> name))
          ),
          field(
            labelOf("competition.description", Labels.competition.description),
            input(controlled(value <-- description.signal, onInput.mapToValue --> description))
          ),
          if (running) p(cls := "detail", "The format of a competition under way cannot be changed.")
          else
              div(
                Views.tipField(
                  id = freshTipId(s"$key-tip"),
                  caption = labelOf("competition.format", Labels.competition.format),
                  text = tipOf("competition.format", Tips.competition.format)
                )(
                  select(
                    onChange.mapToValue --> { v =>
                        Option(v).filter(_.nonEmpty).foreach { id =>
                            Store.tournamentModelsByGame
                                .now()
                                .getOrElse(game.gameId, Seq.empty)
                                .find(_.tournamentModelId.value == id) match {
                                case Some(m) => choose(m)
                                // Back to the format it was made from, which the game no longer offers.
                                case None =>
                                    modelId.set(kept)
                                    chosen.set(None)
                            }
                        }
                    },
                    value <-- modelId.signal.map(_.fold("")(_.value)),
                    children <-- models
                        .combineWith(keptRetired)
                        .map((ms, retired) =>
                            modelOptions(ms, modelId.now()) ++
                                retired
                                    .map(id =>
                                        option(
                                          value := id.value,
                                          selected := modelId.now().contains(id),
                                          "Current format (no longer offered)"
                                        )
                                    )
                        )
                  )
                ),
                child <-- models.combineWith(chosen.signal).map {
                    case (ms, _) if ms.isEmpty =>
                        p(cls := "empty", "This game offers no competition formats yet; its admins add them.")
                    case (_, Some(m)) => p(cls := "detail", m.description)
                    case _            => emptyNode
                },
                child <-- keptRetired.combineWith(modelId.signal).map {
                    case (Some(id), Some(current)) if id == current =>
                        p(
                          cls := "detail",
                          "This game no longer offers the format this competition was made from. It keeps it " +
                              "unless you choose another."
                        )
                    case _ => emptyNode
                },
                if (game.isSeatedWithoutRoles) playersControl else emptyNode,
                number(
                  caption = labelOf("competition.poolSize", Labels.competition.poolSize),
                  v = poolSize,
                  tip = tipOf("competition.poolSize", Tips.competition.poolSize)
                ),
                // Everyone entered so far in one pool: as many as the game takes in a match, when it has a most.
                if (game.isSeatedWithoutRoles && entered > 0) {
                    val everyone = game.mostSeats.fold(entered)(math.min(entered, _))
                    button(
                      tpe := "button",
                      // Gone with the pool size it fills in.
                      Views.shownWhen(labelOf("competition.poolSize", Labels.competition.poolSize)),
                      Buttons.tournaments.onePool(count = everyone.toString),
                      onClick --> { _ =>
                          withdrawNotice(); poolSize.set(everyone.toString)
                      }
                    )
                } else emptyNode
              ),
          h4(Headings.tournaments.settings),
          checkbox(
            caption = labelOf("competition.invitational", Labels.competition.invitational),
            v = invitational,
            tip = tipOf("competition.invitational", Tips.competition.invitational)
          ),
          // Only for an admin of the game, who alone may make a competition rated.
          child <-- Store.currentPlayer.flatMapSwitch {
              case Some(player) =>
                  Store
                      .administers(game.gameId, player)
                      .map(isAdmin =>
                          if (!isAdmin) emptyNode
                          else
                              checkbox(
                                caption = labelOf("competition.friendly", Labels.competition.friendly),
                                v = friendly,
                                tip = tipOf("competition.friendly", Tips.competition.friendly)
                              )
                      )
              case None => Val(emptyNode)
          },
          checkbox(
            caption = labelOf("competition.lateEntries", Labels.competition.lateEntries),
            v = lateEntries,
            tip = tipOf("competition.lateEntries", Tips.competition.lateEntries)
          ),
          checkbox(
            caption = labelOf("competition.concurrentRounds", Labels.competition.concurrentRounds),
            v = concurrentRounds,
            tip = tipOf("competition.concurrentRounds", Tips.competition.concurrentRounds)
          ),
          checkbox(
            caption = labelOf("competition.isPublic", Labels.competition.isPublic),
            v = isPublic,
            tip = tipOf("competition.isPublic", Tips.competition.isPublic)
          ),
          // Nothing to choose in a game without roles.
          if (game.isSeatedWithoutRoles) emptyNode
          else
              checkbox(
                caption = labelOf("competition.chooseRoles", Labels.competition.chooseRoles),
                v = chooseRoles,
                tip = tipOf("competition.chooseRoles", Tips.competition.chooseRoles)
              ),
          checkbox(
            caption = labelOf("competition.live", Labels.competition.live),
            v = live,
            tip = tipOf("competition.live", Tips.competition.live)
          ),
          number(
            caption = labelOf("competition.maxEntrants", Labels.competition.maxEntrants),
            v = maxEntrants,
            tip = tipOf("competition.maxEntrants", Tips.competition.maxEntrants)
          ),
          checkbox(
            caption = labelOf("competition.autoStart", Labels.competition.autoStart),
            v = autoStart,
            tip = tipOf("competition.autoStart", Tips.competition.autoStart)
          ),
          Views.tipField(
            id = freshTipId(s"$key-tip"),
            caption = labelOf("competition.plannedStart", Labels.competition.plannedStart),
            text = tipOf("competition.plannedStart", Tips.competition.plannedStart)
          )(
            input(
              tpe := "datetime-local",
              controlled(value <-- plannedStart.signal, onInput.mapToValue --> plannedStart)
            )
          ),
          // The game's own settings, as a challenge offers them: copied to every match, and so to the engine.
          GameSettingsFields.parameterFields(game, parameters),
          number(
            caption = labelOf("competition.minRating", Labels.competition.minRating),
            v = minRating,
            tip = tipOf("competition.minRating", Tips.competition.minRating)
          ),
          number(
            caption = labelOf("competition.maxRating", Labels.competition.maxRating),
            v = maxRating,
            tip = tipOf("competition.maxRating", Tips.competition.maxRating)
          ),
          number(
            caption = labelOf("competition.entries", Labels.competition.entries),
            v = entries,
            tip = tipOf("competition.entries", Tips.competition.entries)
          ),
          Views.tipField(
            id = freshTipId(s"$key-tip"),
            caption = labelOf("competition.timeLimit", Labels.competition.timeLimit),
            text = tipOf("competition.timeLimit", Tips.competition.timeLimit)
          )(
            span(
              cls := "time-limit",
              input(
                tpe := "number",
                inputMode := "decimal",
                minAttr := "0",
                required := true,
                controlled(value <-- timeLimit.signal, onInput.mapToValue --> timeLimit)
              ),
              select(
                aria.label <-- labelOf("competition.timeUnit", Labels.competition.timeUnit)
                    .map(_.getOrElse(Labels.competition.timeUnit)),
                value <-- timeUnit.signal,
                onChange.mapToValue --> timeUnit,
                timeUnits.map((unit, _) => option(value := unit, unit))
              )
            )
          ),
          if (game.moveType == MoveType.Simultaneous && game.maxTurns.isEmpty)
              number(
                caption = labelOf("competition.maxTurns", Labels.competition.maxTurns),
                v = maxTurns,
                tip = tipOf("competition.maxTurns", Tips.competition.maxTurns)
              )
          else emptyNode,
          if (existing.isDefined) emptyNode
          else {
              val joinCaption = labelOf("competition.join", Labels.competition.join)
              div(
                checkbox(
                  caption = joinCaption,
                  v = join,
                  tip = tipOf("competition.join", Tips.competition.join),
                  onChoose = _ => hasChosenJoin = true
                ),
                if (!isCharacterGame) emptyNode
                else
                    field(
                      labelOf("competition.enterWith", Labels.competition.enterWith)
                          .combineWith(join.signal, joinCaption)
                          .map((caption, isOn, shown) => caption.filter(_ => isOn && shown.isDefined)),
                      select(
                        onChange.mapToValue --> (v => joinWith.set(v.toLongOption.map(CharacterId(_)))),
                        option(value := "", "Choose a character"),
                        children <-- Store.charactersByGame.signal.map(
                          _.getOrElse(game.gameId, Seq.empty).map(c =>
                              option(
                                value := c.characterId.value.toString,
                                selected <-- joinWith.signal.map(_.contains(c.characterId)),
                                c.name
                              )
                          )
                        )
                      )
                    )
              )
          },
          div(
            cls := "row",
            busyButton(
              label = if (existing.isDefined) Buttons.tournaments.save else Buttons.tournaments.create,
              busy = savingBusy(game.gameId, existing)
            )(busy =>
                hasTriedSaving.set(true)
                check()
                val errors = problems.now().filterNot(_.isWarning)
                if (name.now().trim.isEmpty) raiseNotice(ErrorText.competitionForm.noName)
                // Asked for where it is shown; a format that leaves it out saves the default.
                else if (duration.isEmpty && !isLeftOut("competition.timeLimit"))
                    raiseNotice(ErrorText.competitionForm.noTimeLimit)
                else if (errors.nonEmpty) raiseNotice(errors.map(_.describe).mkString("; "))
                else if (isJoining && isCharacterGame && joinWith.now().isEmpty)
                    raiseNotice(ErrorText.competitionForm.noCharacter)
                else
                    modelId.now().foreach { id =>
                        val request = TournamentRequest(
                          name = name.now().trim,
                          tournamentModelId = id,
                          description = unlessLeftOut(
                            key = "competition.description",
                            value = description.now().trim,
                            default = ""
                          ),
                          defaultPoolSize = poolSizeAsked,
                          settings = settings,
                          entry = Option.when(isJoining)(TournamentEntry(joinWith.now()))
                        )
                        val saving: Future[Tournament] = existing match {
                            case Some(t) =>
                                ApiClient.updateTournament(
                                  gameId = t.gameId,
                                  tournamentId = t.tournamentId,
                                  request = request
                                )
                            case None => ApiClient.createTournament(game.gameId, request)
                        }
                        // Where the save was asked from. The answer takes the player to the tournament only if they
                        // are still there, and is dropped whole after a sign-out: it is not the next player's.
                        val from = Store.page.now()
                        Store.runSignedIn(saving, busy) { saved =>
                            Store.reloadMyTournaments()
                            Store.reloadTournaments(game.gameId)
                            if (Store.page.now() == from) {
                                if (existing.isDefined) Views.announce("Changes saved.")
                                onSaved()
                                Store.show(Store.Page.OneTournament(saved.gameId, saved.tournamentId))
                                // After the navigation, which forgets the invitee as it leaves the game's screen.
                                Store.invitee.set(asked)
                                Store.reloadTournament(saved.gameId, saved.tournamentId)
                            }
                        }
                    }
            )
          ),
          ul(
            aria.live := "polite",
            // Checked again whenever a field it reads changes, once typing pauses so a number half typed is not judged.
            onMountCallback { _ =>
                acceptedProblems = found
                check()
            },
            wholePool.signal
                .combineWith(perMatch.signal, chosen.signal, modelId.signal, maxTurns.signal, poolSize.signal)
                .changes
                .debounce(500) --> (_ => checkChange()),
            children <-- problems.signal.map(
              _.map(pr =>
                  li(
                    cls := (if (pr.isWarning) "notice" else "error"),
                    (if (pr.isWarning) "Note: " else "") + pr.describe
                  )
              )
            )
          )
        )
    }

    // ---- the formats a game offers, for its admins ------------------------------------------

    /** Every format a game could offer, for an admin of it, each with whether the game offers it; and, for a site
      * admin, the button that makes the formats those the code defines.
      */
    private def formatsSection(game: Game, player: Player): HtmlElement = {
        val said = Var("")
        // Judged as the server judges an offer: a game without roles at its smallest matches.
        val tg = TournamentGame.of(game, game.fewestPlayers)
        val offered: Signal[Set[TournamentModelId]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty).map(_.tournamentModelId).toSet)
        val tipId = freshTipId("formats-tip")
        div(
          withTip(
            id = tipId,
            subject = "Competition Formats",
            text = Tips.competition.formats
          )(h3(Headings.tournaments.formats)),
          div(aria.live := "polite", cls := "formats-said", child.text <-- said.signal),
          listing(Store.allTournamentModels.signal, Store.loading(Store.Fetch.AllTournamentModels))(
            p(cls := "empty", "There are no formats yet.")
          )(models =>
              ul(
                cls := "plain",
                aria.describedBy := tipId,
                models.map { m =>
                    val busy = Var(false)
                    val unplayable =
                        Validation
                            .designProblems(design = m.design, game = tg, defaultPoolSize = m.poolSize)
                            .filterNot(_.isWarning)
                            .headOption
                    def change(on: Boolean): Unit = {
                        val call =
                            if (on) ApiClient.offerTournamentModel(game.gameId, m.tournamentModelId)
                            else ApiClient.withdrawTournamentModel(game.gameId, m.tournamentModelId)
                        Store.run(call, busy) { _ =>
                            said.set(if (on) s"${m.name} is offered." else s"${m.name} is no longer offered.")
                            Store.reloadTournamentModels(game.gameId)
                        }
                    }
                    li(
                      label(
                        input(
                          tpe := "checkbox",
                          // A format this game cannot play may still be unticked, never ticked.
                          disabled <-- offered.map(on => unplayable.isDefined && !on(m.tournamentModelId)),
                          // Busy is `aria-disabled`, not `disabled`, which would drop the focus mid-request; a press
                          // meanwhile is refused here, and the box stays as the store has it.
                          aria.disabled <-- busy.signal,
                          controlled(
                            checked <-- offered.map(_.contains(m.tournamentModelId)),
                            onClick.mapToChecked --> (on => if (!busy.now()) change(on))
                          )
                        ),
                        m.name
                      ),
                      m.classification.fold(emptyNode)(c => span(cls := "detail", s" — $c")),
                      unplayable.fold(emptyNode)(problem =>
                          p(cls := "detail", ErrorText.competitionForm.unplayable(problem.describe))
                      )
                    )
                }
              )
          ),
          if (!player.isAdmin) emptyNode
          else {
              val syncTip = freshTipId("formats-tip")
              withTip(
                id = syncTip,
                subject = "Sync Formats",
                text = Tips.competition.syncFormats
              )(
                busyButton(label = Buttons.tournaments.syncFormats)(busy =>
                    Store.run(ApiClient.syncTournamentModels(), busy) { done =>
                        def count(n: Int, what: String) = Option.when(n > 0)(s"$n $what")
                        val parts = List(
                          count(done.added.size, "added"),
                          count(done.updated.size, "updated"),
                          count(done.deactivated.size, "taken off the lists"),
                          count(done.deleted.size, "removed")
                        ).flatten
                        said.set(
                          if (parts.isEmpty) "The formats were already up to date."
                          else s"Formats synced: ${parts.mkString(", ")}."
                        )
                        Store.reloadAllTournamentModels()
                        Store.reloadTournamentModels(game.gameId)
                    }
                ).amend(aria.describedBy := syncTip)
              )
          }
        )
    }
}
