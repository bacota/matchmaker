package com.vivi.matchmaker.ui

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
  * like every other screen: labelled controls, explanations behind a "?", buttons that say they are busy. Everything
  * that writes into the store goes through `Store`'s loads, so a late answer from an earlier session or an earlier
  * tournament is dropped.
  */
object Tournaments {

    import Views.{busyButton, field, freshTipId, heldBusy, listing, withTip}

    private val scopeAttr = htmlAttr("scope", com.raquo.laminar.codecs.StringAsIsCodec)

    // ---- lists -------------------------------------------------------------------------------

    /** A game's tournaments, on its screen, with the button that creates one: subsections of the screen's
      * "Competitions", beside its challenges, and called competitions wherever a player reads them.
      */
    def gameSection(game: Game): HtmlElement =
        div(
          Views.refreshableSection(
            heading = "Other Competitions",
            reload = () => Store.reloadTournaments(game.gameId),
            subsection = true
          )(
            listing(
              Store.tournamentsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty)),
              Store.loading(Store.Fetch.Tournaments(game.gameId))
            )(p(cls := "empty", "No other competitions in this game yet."))(rows =>
                summaryList(rows, showGame = false)
            ),
            button(
              tpe := "button",
              "New Competition",
              onClick --> (_ => Store.show(Store.Page.NewTournament(game.gameId)))
            )
          ),
          // The formats the game offers, for whoever administers it: nothing at all for anybody else.
          child <-- Store.currentPlayer.flatMapSwitch {
              case Some(player) =>
                  Store.administers(game.gameId, player).map(if (_) formatsSection(game, player) else emptyNode)
              case None => Val(emptyNode)
          }
        )

    /** The main page's tournament sections: seats waiting on the caller to choose a role, and their tournaments. */
    def mainSections: HtmlElement =
        div(
          child <-- Store.tournamentChoices.signal.map { choices =>
              if (choices.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = "Choose a Role",
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
                              "Choose",
                              aria.label := s"Choose your role in ${c.tournamentName}",
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
          child <-- Store.myTournaments.signal.map { mine =>
              val waiting = mine.filter(isWaitingOnMe)
              if (waiting.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = "Rounds to Start",
                    reload = () => Store.reloadMyTournaments(),
                    subsection = false
                  )(
                    ul(
                      waiting.map { s =>
                          val t = s.tournament
                          li(
                            cls := "row",
                            button(
                              cls := "link",
                              tpe := "button",
                              t.name,
                              onClick --> (_ => Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId)))
                            ),
                            span(cls := "detail", s" · ${s.gameName} · every match of the round is over"),
                            busyButton(label = "Start Next Round", busy = startRoundBusy(t))(busy =>
                                Store.run(
                                  ApiClient.startRound(gameId = t.gameId, tournamentId = t.tournamentId),
                                  busy
                                ) { _ =>
                                    Store.reloadMyTournaments()
                                    Store.reloadTournamentChoices()
                                }
                            ).amend(aria.label := s"Start Next Round of ${t.name}")
                          )
                      }
                    )
                  )
          },
          child <-- Store.myTournaments.signal.map { mine =>
              if (mine.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = "My Competitions",
                    reload = () => Store.reloadMyTournaments(),
                    subsection = false
                  )(
                    summaryList(mine, showGame = true)
                  )
          },
          child <-- Store.tournamentStandings.signal.map { rows =>
              if (rows.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    heading = "Where You Stand",
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
        s.isOwner && s.isRoundOver && !s.tournament.settings.hasAutomaticRounds

    private def summaryList(rows: Seq[TournamentSummary], showGame: Boolean): HtmlElement =
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
                )
              )
          }
        )

    // ---- a tournament's page ----------------------------------------------------------------

    /* The page is drawn again whenever the tournament is reloaded, which every owner action does. What must outlive
     * that is held here, above it: whether the Edit form is open, and the form itself, which is drawn once as it opens
     * from the tournament as it then stood -- redrawn with the page, it closed, or lost what had been typed. The
     * buttons' busy flags are held too, by `heldBusy`. */
    def page(gameId: GameId, tournamentId: TournamentId): HtmlElement = {
        val editing = Var(false)
        // Drawn once per answer, and shared by the two parts.
        val parts = Store.tournament.signal.map(
          _.filter(_.summary.tournament.tournamentId == tournamentId).map(detail(_, editing))
        )
        div(
          child <-- parts.map(_.fold(p(cls := "empty", "Loading…"))(_.top)),
          child <-- editing.signal.distinct.map(isOpen =>
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

    /** One flag for both Start Next Round buttons, the Rounds to Start row's and the tournament page's. */
    private def startRoundBusy(t: Tournament): Var[Boolean] = heldBusy(s"start round ${t.tournamentId}")

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
          d.state.fold(emptyNode)(s => standings(node = s.root, names = names, heading = Some("Standings"))),
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
            busyButton(label = label, busy = heldBusy(s"$name tournament ${t.tournamentId}"))(busy =>
                Store.run(
                  ApiClient.tournamentStep(gameId = t.gameId, tournamentId = t.tournamentId, step = name),
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
        // as one match a pool would then start as more than one match.
        val morePools = Option.when(
          t.status == TournamentStatus.Open &&
              Store.games.now().exists(g => g.gameId == t.gameId && g.isRoleless) && entered > t.defaultPoolSize
        )(
          p(
            cls := "detail",
            role := "status",
            s"$entered have entered and pools hold ${t.defaultPoolSize}, so this will start as more than one pool. " +
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
          h3("Run This Competition"),
          morePools.getOrElse(emptyNode),
          div(
            cls := "row",
            t.status match {
                case TournamentStatus.Draft => step("Open for Entries", "open")
                case TournamentStatus.Open  => step("Start Competition", "start")
                case TournamentStatus.Running =>
                    span(
                      if (roundOpen) emptyNode
                      else
                          span(
                            Views.tipField(
                              id = freshTipId("round-length-tip"),
                              caption = "This round's length (hours)",
                              text = "How long this round lasts. Blank uses the competition's round length."
                            )(
                              input(
                                tpe := "number",
                                inputMode := "decimal",
                                minAttr := "0",
                                controlled(value <-- roundHours.signal, onInput.mapToValue --> roundHours)
                              )
                            ),
                            busyButton(label = "Start Next Round", busy = startRoundBusy(t))(busy =>
                                Store.run(
                                  ApiClient.startRound(
                                    gameId = t.gameId,
                                    tournamentId = t.tournamentId,
                                    duration = roundLength
                                  ),
                                  busy
                                )(_ => reload(d))
                            )
                          ),
                      if (roundOpen)
                          withTip(
                            id = freshTipId("check-round-tip"),
                            subject = "Check Round",
                            text = "Ends any match of this round whose clock has run out, as a player's look at it would, and " +
                                "forfeits any seat that ran out of time to choose its role. It runs in the background, " +
                                "so what it changes shows when the page is next refreshed."
                          )(
                            busyButton(label = "Check Round", busy = heldBusy(s"check round ${t.tournamentId}"))(busy =>
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
                          busyButton(label = "Stop Repeating", busy = heldBusy(s"stop repeating ${t.tournamentId}"))(
                            busy =>
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
                  // Whether the form is open, in the state that is announced as well as in the label.
                  aria.expanded <-- editing.signal,
                  child.text <-- editing.signal.map(if (_) "Close Edit" else "Edit"),
                  onClick --> (_ => editing.update(!_))
                ),
            t.status match {
                // Not started, so nothing has been played: stopping it deletes it, which is cancelling it.
                case TournamentStatus.Draft | TournamentStatus.Open =>
                    stopButton(
                      label = "Cancel Competition",
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
                      label = "Stop Competition",
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
          h3(s"Entries (${d.entries.count(!_.isWithdrawn)})"),
          if (d.entries.isEmpty) p(cls := "empty", "Nobody has entered yet.")
          else
              ul(
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
                          busyButton(label = "Withdraw", busy = heldBusy(s"withdraw ${e.entrantId}"))(busy =>
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
                          ).amend(aria.label := s"Withdraw ${nameOf(d)(e.entrantId.value)}")
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
                  label = if (mine.exists(_.isWithdrawn)) "Enter Again" else "Enter",
                  busy = heldBusy(s"enter tournament ${t.tournamentId}")
                )(busy =>
                    Store.run(
                      ApiClient.enterTournament(
                        gameId = t.gameId,
                        tournamentId = t.tournamentId,
                        characterId = chosen.now()
                      ),
                      busy
                    )(_ => reload(d))
                )
              )
        )
    }

    /** Who may enter an invitational tournament, and the owner's means of changing that: players in a plain game, and
      * in a character game characters -- found through their owners, and answered by whoever owns them when they do.
      */
    private def invitationsSection(d: TournamentDetail): HtmlElement = {
        val t = d.summary.tournament
        val characterGame = d.gameType == GameType.Character
        val prefix = Var("")
        val found: Var[Seq[PublicPlayer]] = Var(Seq.empty)
        val invitedCharacters = d.invitedCharacters.map(_.character.characterId).toSet
        sectionTag(
          h3("Invitations"),
          if (d.invited.isEmpty && d.invitedCharacters.isEmpty) p(cls := "empty", "Nobody is invited yet.")
          else
              ul(
                d.invited.map(inv =>
                    li(
                      cls := "row",
                      span(inv.nickname),
                      busyButton(label = "Remove", busy = heldBusy(s"uninvite ${inv.playerId} from ${t.tournamentId}"))(
                        busy =>
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
                      ).amend(aria.label := s"Remove the invitation of ${inv.nickname}")
                    )
                ) ++ d.invitedCharacters.map(inv =>
                    li(
                      cls := "row",
                      span(inv.character.name),
                      span(cls := "detail", inv.owner.fold(" · nobody's")(o => s" · ${o.nickname}'s")),
                      busyButton(
                        label = "Remove",
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
                      ).amend(aria.label := s"Remove the invitation of ${inv.character.name}")
                    )
                )
              ),
          div(
            cls := "row",
            field("Find a player", input(controlled(value <-- prefix.signal, onInput.mapToValue --> prefix))),
            busyButton(label = "Search", disabledWhen = prefix.signal.map(_.trim.isEmpty))(busy =>
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
                      busyButton(label = "Invite", busy = heldBusy(s"invite ${p.playerId} to ${t.tournamentId}"))(
                        busy =>
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
                      ).amend(aria.label := s"Invite ${p.nickname}")
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
              h3(s"Round ${d.state.map(_.round).getOrElse(0)}"),
              if (d.summary.isRoundOver)
                  p(
                    cls := "detail",
                    "Every match of this round is over." +
                        (if (t.settings.hasAutomaticRounds) ""
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
            label = "Choose Role",
            disabledWhen = picked.signal.map(_.isEmpty),
            busy = heldBusy(s"choose role in ${m.matchId}")
          )(busy =>
              picked
                  .now()
                  .foreach(r =>
                      Store.run(
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
            text =
                "For a match that cannot be played, such as one a player never turned up to: it is called off, and its " +
                    "seats ranked as you say. The ranks decide the competition and move nobody's rating."
          )(
            button(
              tpe := "button",
              cls := "link",
              child.text <-- open.signal.map(if (_) "Close" else "Decide by Hand"),
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
                    busyButton(label = "Save Ranks", busy = heldBusy(s"decide ${m.matchId}"))(busy =>
                        val typed = ranks.map((e, v) => v.now().trim.toIntOption.filter(_ >= 1).map(EntrantRank(e, _)))
                        if (typed.exists(_.isEmpty)) Store.reportProblem("Give every seat a rank of 1 or more.")
                        else
                            Store.run(
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
        div(
          h4(
            s"Stage ${run.stage + 1}" + (if (run.repetition > 0) s", play ${run.repetition + 1}" else "") +
                (if (run.isCompleted) "" else " (being played)")
          ),
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
          h3("Final Ranking"),
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

    /** The page that creates a tournament in a game. */
    def newPage(gameId: GameId): HtmlElement =
        div(
          h2("New Competition"),
          child <-- Store.games.signal.map(_.find(_.gameId == gameId)).map {
              case Some(g) => form(game = g, existing = None, onSaved = () => ())
              case None    => p(cls := "empty", "Loading…")
          }
        )

    /** The models offered for a game, grouped by classification, as a select's options. */
    private def modelOptions(models: Seq[TournamentModel]): Seq[HtmlElement] =
        models
            .groupBy(_.classification.getOrElse(""))
            .toSeq
            .sortBy(_._1)
            .map { (classification, inGroup) =>
                optGroup(
                  labelAttr := classification,
                  inGroup
                      .sortBy(_.name.toLowerCase)
                      .map(m => option(value := m.tournamentModelId.value.toString, m.name))
                )
            }

    private val labelAttr = htmlAttr("label", com.raquo.laminar.codecs.StringAsIsCodec)

    /** The form that creates a tournament from one of the models its game offers, or edits `existing`. A running
      * tournament's model and default pool size are not offered for editing, since its stages cannot change.
      *
      * For a game without roles it also asks how many players a match has, and — for a tournament not yet started, with
      * `entered` entries so far — offers the pool size that puts everyone entered in one pool.
      */
    def form(game: Game, existing: Option[Tournament], onSaved: () => Unit, entered: Int = 0): HtmlElement = {
        val key = existing.fold("new-tournament")(t => s"tournament-${t.tournamentId.value}")
        val running = existing.exists(_.status == TournamentStatus.Running)
        val s0 = existing.map(_.settings).getOrElse(TournamentSettings())
        // A game without roles: whether each pool is one match, and otherwise how many players a match has, as typed.
        val wholePool = Var(game.isRoleless && existing.isDefined && s0.playersPerMatch.isEmpty)
        val perMatch = Var(s0.playersPerMatch.orElse(game.minPlayers).fold("")(_.toString))
        val name = Var(existing.map(_.name).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val models: Signal[Seq[TournamentModel]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty))
        // The model chosen, by id; none until the models have come, for a new tournament.
        val modelId: Var[Option[TournamentModelId]] = Var(existing.map(_.tournamentModelId))
        val poolSize = Var(existing.fold("")(_.defaultPoolSize.toString))
        val invitational = Var(s0.isInvitational)
        val autoRounds = Var(s0.hasAutomaticRounds)
        val friendly = Var(s0.isFriendly)
        val lateEntries = Var(s0.hasLateEntries)
        val concurrentRounds = Var(s0.hasConcurrentRounds)
        val isPublic = Var(s0.isPublic)
        val minRating = Var(s0.minRating.fold("")(_.toString))
        val maxRating = Var(s0.maxRating.fold("")(_.toString))
        val entries = Var(s0.entriesPerPlayer.toString)
        val hours = Var(s0.roundDuration.fold("48")(d => (d.toMinutes / 60.0).toString.stripSuffix(".0")))
        val maxTurns = Var(s0.maxTurns.fold("")(_.toString))
        val chooseRoles = Var(s0.hasRoleChoice)
        val live = Var(s0.isLive)
        val maxEntrants = Var(s0.maxEntrants.fold("")(_.toString))
        val autoStart = Var(s0.isAutoStarted)
        val plannedStart = Var(s0.start.fold("")(ChallengeForm.localInputOf))
        val parameters = Var(ChallengeForm.startingChoices(game, s0.gameSettings))
        val problems: Var[List[Problem]] = Var(Nil)
        val chosen: Var[Option[TournamentModel]] = Var(None)
        // The model an existing tournament was made from, when the game no longer offers it -- its admins took it off,
        // or the code no longer defines it: not among those offered, and kept unless another is chosen.
        val kept = existing.map(_.tournamentModelId)
        val keptRetired: Signal[Option[TournamentModelId]] =
            models.map(ms => kept.filter(id => ms.nonEmpty && !ms.exists(_.tournamentModelId == id)))
        def isKeeping: Boolean = chosen.now().isEmpty && modelId.now().exists(kept.contains)

        def playersPerMatch: Option[Int] =
            Option.when(game.isRoleless && !wholePool.now())(perMatch.now().trim.toIntOption).flatten
        // The game's R as this tournament plays it: its roles, or for a game without roles the players per match.
        def r: Int = TournamentGame.of(game, playersPerMatch).poolSeats

        /* Choosing a model fills in the pool size it suggests -- the game's R where it suggests none -- whether it
         * takes late entries, and whether its players choose their roles; all stay the creator's to change, but for a
         * model whose players always choose, which every tournament made from it does. */
        def choose(m: TournamentModel): Unit = {
            modelId.set(Some(m.tournamentModelId))
            chosen.set(Some(m))
            poolSize.set(m.poolSize.getOrElse(r).toString)
            lateEntries.set(m.hasLateEntries)
            chooseRoles.set(m.hasRoleChoice)
        }

        def int(v: Var[String]) = v.now().trim.toIntOption
        def settings = TournamentSettings(
          hasAutomaticRounds = autoRounds.now(),
          isFriendly = friendly.now(),
          isInvitational = invitational.now(),
          hasLateEntries = lateEntries.now(),
          minRating = int(minRating),
          maxRating = int(maxRating),
          entriesPerPlayer = int(entries).getOrElse(1),
          roundDuration =
              hours.now().trim.toDoubleOption.filter(_ > 0).map(h => java.time.Duration.ofMinutes((h * 60).round)),
          maxTurns = int(maxTurns),
          hasConcurrentRounds = concurrentRounds.now(),
          isPublic = isPublic.now(),
          gameSettings = ChallengeForm.settingsOf(parameters.now()),
          isLive = live.now(),
          start = ChallengeForm.startOf(plannedStart.now()),
          hasRoleChoice = chooseRoles.now(),
          maxEntrants = int(maxEntrants),
          isAutoStarted = autoStart.now() && int(maxEntrants).isDefined,
          playersPerMatch = playersPerMatch
        )
        /* "A number of players" with no number is not "everyone in the pool", though both leave `playersPerMatch`
         * empty: refused here, so that a blank or mistyped count is never saved as the other mode. */
        def countProblem: List[Problem] =
            Option
                .when(game.isRoleless && !wholePool.now() && perMatch.now().trim.toIntOption.forall(_ < 1))(
                  Problem(
                    where = "",
                    message = "players per match has to be a whole number above 0, or choose everyone in the pool"
                  )
                )
                .toList
        def check(): Unit =
            problems.set(countProblem ++ (chosen.now() match {
                // The format it was made from, checked again as it is saved.
                case None if isKeeping => Nil
                case None              => List(Problem(where = "", message = "choose a format"))
                case Some(m) =>
                    Validation.validate(
                      design = m.design,
                      game = TournamentGame.of(game, playersPerMatch),
                      maxTurns = settings.maxTurns,
                      defaultPoolSize = int(poolSize).orElse(Some(r))
                    )
            }))

        def checkbox(caption: String, v: Var[Boolean], tip: String) = {
            val id = freshTipId(s"$key-tip")
            withTip(id = id, subject = caption, text = tip)(
              label(
                input(
                  tpe := "checkbox",
                  aria.describedBy := id,
                  controlled(checked <-- v.signal, onClick.mapToChecked --> v)
                ),
                caption
              )
            )
        }
        def number(caption: String, v: Var[String], tip: String) =
            Views.tipField(id = freshTipId(s"$key-tip"), caption = caption, text = tip)(
              input(tpe := "number", inputMode := "numeric", controlled(value <-- v.signal, onInput.mapToValue --> v))
            )

        /* How many players a match of a game without roles has: a number in the game's range, or everyone in the
         * pool. */
        def playersControl: HtmlElement = {
            val range = game.maxPlayers.fold(s"at least ${game.requiredSeats}")(m => s"${game.requiredSeats} to $m")
            val id = freshTipId(s"$key-tip")
            div(
              withTip(
                id = id,
                subject = "Players per match",
                text = s"How many players each match has: $range. Or everyone in the pool in one match — then set " +
                    "the pool size to how many should play together."
              )(
                label(
                  cls := "field",
                  "Players per match",
                  select(
                    aria.describedBy := id,
                    onChange.mapToValue --> (v => wholePool.set(v == "pool")),
                    value <-- wholePool.signal.map(if (_) "pool" else "count"),
                    option(value := "count", "A number of players"),
                    option(value := "pool", "Everyone in the pool")
                  )
                )
              ),
              child <-- wholePool.signal.map(isPool =>
                  if (isPool) emptyNode
                  else
                      field(
                        "Players in each match",
                        input(
                          tpe := "number",
                          inputMode := "numeric",
                          minAttr := game.requiredSeats.toString,
                          game.maxPlayers.fold(emptyMod)(m => maxAttr := m.toString),
                          controlled(value <-- perMatch.signal, onInput.mapToValue --> perMatch)
                        )
                      )
              )
            )
        }

        div(
          cls := "card",
          // The models as they come: an existing tournament's is found among them, and a new one starts on the first.
          models --> { ms =>
              chosen.now() match {
                  case Some(_) => ()
                  case None =>
                      modelId.now() match {
                          case Some(id) => ms.find(_.tournamentModelId == id).foreach(m => chosen.set(Some(m)))
                          case None     => ms.headOption.foreach(choose)
                      }
              }
          },
          field("Name", input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          field("Description", input(controlled(value <-- description.signal, onInput.mapToValue --> description))),
          if (running) p(cls := "detail", "The format of a competition under way cannot be changed.")
          else
              div(
                Views.tipField(
                  id = freshTipId(s"$key-tip"),
                  caption = "Format",
                  text = "The formats this game's admins offer, grouped by kind."
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
                            modelOptions(ms) ++
                                retired
                                    .map(id => option(value := id.value, "Current format (no longer offered)"))
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
                if (game.isRoleless) playersControl else emptyNode,
                number(
                  caption = "Default pool size",
                  v = poolSize,
                  tip =
                      "How many play in a pool of any stage that does not set its own size. Filled in from the format."
                ),
                // Everyone entered so far in one pool: as many as the game takes in a match, when it has a most.
                if (game.isRoleless && entered > 0) {
                    val everyone = game.maxPlayers.fold(entered)(math.min(entered, _))
                    button(
                      tpe := "button",
                      s"One pool of everyone entered ($everyone)",
                      onClick --> (_ => poolSize.set(everyone.toString))
                    )
                } else emptyNode
              ),
          h4("Settings"),
          checkbox(
            caption = "Invitational",
            v = invitational,
            tip = "Only players you invite may enter. You may always enter your own competition."
          ),
          checkbox(
            caption = "Automatic rounds",
            v = autoRounds,
            tip = "Each round begins as soon as the last one ends, rather than when you start it."
          ),
          checkbox(caption = "Friendly", v = friendly, tip = "Its matches change nobody's rating."),
          checkbox(
            caption = "Late entries",
            v = lateEntries,
            tip = "Players may enter after it has started, joining from its next stage."
          ),
          checkbox(
            caption = "Concurrent rounds",
            v = concurrentRounds,
            tip = "A round-robin pool plays all of its matches in one round, rather than each player one match a round."
          ),
          checkbox(caption = "Public", v = isPublic, tip = "Anybody may see it listed, and watch its matches."),
          // Nothing to choose in a game without roles.
          if (game.isRoleless) emptyNode
          else
              checkbox(
                caption = "Players choose roles",
                v = chooseRoles,
                tip =
                    "Every match's players choose their roles before it starts, the strongest seed first, rather than " +
                        "being given them."
              ),
          checkbox(
            caption = "Live",
            v = live,
            tip =
                "Its matches are played in real time: the game engine runs the turns and keeps the clock, so a round " +
                    "length is required."
          ),
          number(caption = "Most entries", v = maxEntrants, tip = "Leave blank for no limit."),
          checkbox(
            caption = "Start when full",
            v = autoStart,
            tip = "It starts by itself as soon as it has its most entries."
          ),
          Views.tipField(
            id = freshTipId(s"$key-tip"),
            caption = "When to play (optional)",
            text =
                "When you mean it to be played, shown to anyone deciding whether to enter. It does not schedule or start " +
                    "the competition."
          )(
            input(
              tpe := "datetime-local",
              controlled(value <-- plannedStart.signal, onInput.mapToValue --> plannedStart)
            )
          ),
          // The game's own settings, as a challenge offers them: copied to every match, and so to the engine.
          ChallengeForm.parameterFields(game, parameters),
          number(caption = "Minimum rating", v = minRating, tip = "Leave blank for no minimum."),
          number(caption = "Maximum rating", v = maxRating, tip = "Leave blank for no maximum."),
          number(caption = "Entries per player", v = entries, tip = "How many times one player may enter."),
          Views.tipField(
            id = freshTipId(s"$key-tip"),
            caption = "Round length (hours)",
            text =
                "How long a round lasts; you may give a round started by hand another. In a game of turns each player has " +
                    "a chess clock of an equal share of it."
          )(
            input(
              tpe := "number",
              inputMode := "decimal",
              minAttr := "0",
              controlled(value <-- hours.signal, onInput.mapToValue --> hours)
            )
          ),
          if (game.moveType == MoveType.Simultaneous && game.maxTurns.isEmpty)
              number(
                caption = "Turns per match",
                v = maxTurns,
                tip = "This game does not say how many turns it lasts; a round's time is divided among this many."
              )
          else emptyNode,
          div(
            cls := "row",
            button(tpe := "button", "Check", onClick --> (_ => check())),
            busyButton(label = if (existing.isDefined) "Save Changes" else "Create Competition")(busy =>
                check()
                val errors = problems.now().filterNot(_.isWarning)
                if (name.now().trim.isEmpty) Store.reportProblem("A competition needs a name.")
                else if (errors.nonEmpty) Store.reportProblem(errors.map(_.describe).mkString("; "))
                else
                    modelId.now().foreach { id =>
                        val request = TournamentRequest(
                          name = name.now().trim,
                          tournamentModelId = id,
                          description = description.now().trim,
                          defaultPoolSize = int(poolSize),
                          settings = settings
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
                                Store.reloadTournament(saved.gameId, saved.tournamentId)
                            }
                        }
                    }
            )
          ),
          ul(
            aria.live := "polite",
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
        val tg = TournamentGame.of(game, game.minPlayers)
        val offered: Signal[Set[TournamentModelId]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty).map(_.tournamentModelId).toSet)
        val tipId = freshTipId("formats-tip")
        div(
          withTip(
            id = tipId,
            subject = "Competition Formats",
            text =
                "The formats ticked here are the ones offered when a competition of this game is created. A competition " +
                    "already made keeps its format whatever you change here."
          )(h3("Competition Formats")),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal),
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
                          p(cls := "detail", s"Can't be played in this game: ${problem.describe}")
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
                text =
                    "Makes every game's formats the ones the code defines: adds new ones and updates changed ones. A " +
                        "format the code no longer has is removed with its competitions if nobody has entered one, and " +
                        "otherwise only taken off every list."
              )(
                busyButton(label = "Sync Formats")(busy =>
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
