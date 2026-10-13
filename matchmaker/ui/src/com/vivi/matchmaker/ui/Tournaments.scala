package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
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

/** The tournament screens (V52): a game's tournaments, the caller's own on the main page, a tournament's page, and the
  * form that creates or edits one.
  *
  * Built from `Views`' own pieces — `field`, `withTip`, `busyButton`, `listing` — so that a tournament's page reads
  * like every other screen: labelled controls, explanations behind a "?", buttons that say they are busy. Everything
  * that writes into the store goes through `Store`'s loads, so a late answer from an earlier session or an earlier
  * tournament is dropped.
  */
object Tournaments {

    import Views.{busyButton, field, freshTipId, listing, withTip}

    private val scopeAttr = htmlAttr("scope", com.raquo.laminar.codecs.StringAsIsCodec)

    // ---- lists -------------------------------------------------------------------------------

    /** A game's tournaments, on its screen, with the button that creates one. */
    def gameSection(game: Game): HtmlElement =
        Views.refreshableSection("Tournaments", () => Store.reloadTournaments(game.gameId), subsection = false)(
          listing(
            Store.tournamentsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty)),
            Store.loading(Store.Fetch.Tournaments(game.gameId))
          )(p(cls := "empty", "No tournaments in this game yet."))(rows => summaryList(rows, showGame = false)),
          button(
            tpe := "button",
            "New Tournament",
            onClick --> (_ => Store.show(Store.Page.NewTournament(game.gameId)))
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
                  Views.refreshableSection("Choose a Role", () => Store.reloadTournamentChoices(), subsection = false)(
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
          child <-- Store.myTournaments.signal.map { mine =>
              val waiting = mine.filter(waitsOnMe)
              if (waiting.isEmpty) emptyNode
              else
                  Views.refreshableSection("Rounds to Start", () => Store.reloadMyTournaments(), subsection = false)(
                    ul(
                      aria.live := "polite",
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
                            busyButton("Start Next Round")(busy =>
                                Store.run(ApiClient.startRound(t.gameId, t.tournamentId), busy) { _ =>
                                    Store.reloadMyTournaments()
                                    Store.reloadTournamentChoices()
                                }
                            ).amend(aria.label := s"Start the next round of ${t.name}")
                          )
                      }
                    )
                  )
          },
          child <-- Store.myTournaments.signal.map { mine =>
              if (mine.isEmpty) emptyNode
              else
                  Views.refreshableSection("My Tournaments", () => Store.reloadMyTournaments(), subsection = false)(
                    summaryList(mine, showGame = true)
                  )
          },
          child <-- Store.tournamentStandings.signal.map { rows =>
              if (rows.isEmpty) emptyNode
              else
                  Views.refreshableSection(
                    "Where You Stand",
                    () => Store.reloadTournamentStandings(),
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
            caption(cls := "sr-only", "Your place in each tournament you entered"),
            thead(
              tr(
                th(scopeAttr := "col", "Tournament"),
                th(scopeAttr := "col", "Game"),
                th(scopeAttr := "col", "Entry"),
                th(scopeAttr := "col", "Standing")
              )
            ),
            tbody(rows.map { r =>
                val standing = r.finalRank match {
                    case Some(rank)           => s"finished ${ordinal(rank)}"
                    case None if r.eliminated => "out"
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
    private def waitsOnMe(s: TournamentSummary): Boolean =
        s.isOwner && s.roundOver && !s.tournament.settings.autoRounds

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
                      Option.when(waitsOnMe(s))("round over") ++
                      Option.when(s.isOwner)("yours") ++ Option.when(s.entered)("entered") ++
                      Option.when(s.invited && !s.entered)("invited")).mkString(" · ", " · ", "")
                )
              )
          }
        )

    // ---- a tournament's page ----------------------------------------------------------------

    def page(gameId: GameId, tournamentId: TournamentId): HtmlElement =
        div(
          child <-- Store.tournament.signal.map {
              case Some(d) if d.summary.tournament.tournamentId == tournamentId => detail(d)
              case _                                                            => p(cls := "empty", "Loading…")
          }
        )

    private def reload(d: TournamentDetail): Unit = {
        val t = d.summary.tournament
        Store.reloadTournament(t.gameId, t.tournamentId)
        Store.reloadMyTournaments()
        Store.reloadTournamentChoices()
    }

    private def detail(d: TournamentDetail): HtmlElement = {
        val t = d.summary.tournament
        val game = Store.games.now().find(_.gameId == t.gameId)
        val me = Store.player.now() match {
            case Store.PlayerState.Registered(p) => Some(p)
            case _                               => None
        }
        val names = nameOf(d)
        val editing = Var(false)

        div(
          h2(t.name),
          p(cls := "detail", s"${d.summary.gameName} · ${t.status.label} · run by ${d.summary.ownerNickname}"),
          if (t.description.nonEmpty) p(t.description) else emptyNode,
          if (d.summary.isOwner && t.status != TournamentStatus.Finished && t.status != TournamentStatus.Cancelled)
              ownerControls(d, editing)
          else emptyNode,
          child <-- editing.signal.map(
            if (_) game.fold(emptyNode)(g => form(g, Some(t), () => editing.set(false))) else emptyNode
          ),
          entriesSection(d, game, me),
          if (d.summary.isOwner && t.settings.invitational) invitationsSection(d) else emptyNode,
          roundSection(d, game, me, names),
          d.state.fold(emptyNode)(s => standings(s.root, names, Some("Standings"))),
          d.state.flatMap(_.root.ranking).fold(emptyNode)(r => finalRanking(r, names))
        )
    }

    /** Each entrant by what they are shown as: the character, in a character game, with its player. */
    private def nameOf(d: TournamentDetail): Long => String = {
        val byId = d.entries.map(e => e.entrantId.value -> e).toMap
        id => byId.get(id).fold(s"entry $id")(e => e.characterName.fold(e.nickname)(c => s"$c (${e.nickname})"))
    }

    private def ownerControls(d: TournamentDetail, editing: Var[Boolean]): HtmlElement = {
        val t = d.summary.tournament
        def step(label: String, name: String) =
            busyButton(label)(busy =>
                Store.run(ApiClient.tournamentStep(t.gameId, t.tournamentId, name), busy)(_ => reload(d))
            )
        val roundOpen = d.round.exists(_.status != TournamentMatchStatus.Done)
        // Set once a Check round is queued: the check finishes after the request does, so the page says so.
        val checking = Var(false)
        // This round's own length, in hours; blank for the tournament's.
        val roundHours = Var("")
        def roundLength =
            roundHours.now().trim.toDoubleOption.filter(_ > 0).map(h => java.time.Duration.ofMinutes((h * 60).round))
        div(
          cls := "card",
          h3("Run This Tournament"),
          div(
            cls := "row",
            t.status match {
                case TournamentStatus.Draft => step("Open for Entries", "open")
                case TournamentStatus.Open  => step("Start Tournament", "start")
                case TournamentStatus.Running =>
                    span(
                      if (roundOpen) emptyNode
                      else
                          span(
                            Views.tipField(
                              freshTipId("round-length-tip"),
                              "This round's length (hours)",
                              "How long this round lasts. Blank uses the tournament's round length."
                            )(
                              input(
                                tpe := "number",
                                inputMode := "decimal",
                                minAttr := "0",
                                controlled(value <-- roundHours.signal, onInput.mapToValue --> roundHours)
                              )
                            ),
                            busyButton("Start Next Round")(busy =>
                                Store.run(ApiClient.startRound(t.gameId, t.tournamentId, roundLength), busy)(_ =>
                                    reload(d)
                                )
                            )
                          ),
                      if (roundOpen)
                          withTip(
                            freshTipId("check-round-tip"),
                            "Check Round",
                            "Ends any match of this round whose clock has run out, as a player's look at it would, and " +
                                "forfeits any seat that ran out of time to choose its role. It runs in the background, " +
                                "so what it changes shows when the page is next refreshed."
                          )(
                            busyButton("Check Round")(busy =>
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
                      if (d.design.stages.exists(_.repeat == Repetition.UntilStopped))
                          busyButton("Stop Repeating")(busy =>
                              Store.run(ApiClient.stopTournamentStage(t.gameId, t.tournamentId), busy)(_ => reload(d))
                          )
                      else emptyNode
                    )
                case _ => emptyNode
            },
            if (t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled) emptyNode
            else
                button(
                  tpe := "button",
                  child.text <-- editing.signal.map(if (_) "Close Edit" else "Edit"),
                  onClick --> (_ => editing.update(!_))
                ),
            if (t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled) emptyNode
            else step("Cancel Tournament", "cancel").amend(cls := "warn")
          )
        )
    }

    private def entriesSection(d: TournamentDetail, game: Option[Game], me: Option[Player]): HtmlElement = {
        val t = d.summary.tournament
        val open = t.status == TournamentStatus.Open || (t.status == TournamentStatus.Running && t.settings.lateEntries)
        val mine = d.entries.filter(e => me.exists(_.playerId == e.playerId))
        val characterGame = game.exists(_.gameType == GameType.Character)
        val chosen: Var[Option[CharacterId]] = Var(None)
        sectionTag(
          h3(s"Entries (${d.entries.count(!_.withdrawn)})"),
          if (d.entries.isEmpty) p(cls := "empty", "Nobody has entered yet.")
          else
              ul(
                d.entries.map { e =>
                    li(
                      cls := "row",
                      span(nameOf(d)(e.entrantId.value)),
                      span(cls := "detail", (if (e.withdrawn) " · withdrawn" else "") + s" · rated ${e.ratingAtEntry}"),
                      if (
                        !e.withdrawn && (mine.contains(e) || d.summary.isOwner) && t.status != TournamentStatus.Finished
                      )
                          busyButton("Withdraw")(busy =>
                              Store.run(ApiClient.withdrawFromTournament(t.gameId, t.tournamentId, e.entrantId), busy)(
                                _ => reload(d)
                              )
                          ).amend(aria.label := s"Withdraw ${nameOf(d)(e.entrantId.value)}")
                      else emptyNode
                    )
                }
              ),
          // Offered while the caller has an entry left to make, or one withdrawn to take back up.
          if (
            !open || me.isEmpty || (mine.count(!_.withdrawn) >= t.settings.entriesPerPlayer && !mine
                .exists(_.withdrawn))
          )
              emptyNode
          else
              div(
                cls := "row",
                if (characterGame)
                    field(
                      "Enter with",
                      select(
                        onMountCallback(_ => game.foreach(g => Store.refreshCharacters(g.gameId))),
                        onChange.mapToValue --> (v => chosen.set(v.toLongOption.map(CharacterId(_)))),
                        option(value := "", "Choose a character"),
                        children <-- Store.charactersByGame.signal.map(
                          _.getOrElse(t.gameId, Seq.empty).map(c =>
                              option(value := c.characterId.value.toString, c.name)
                          )
                        )
                      )
                    )
                else emptyNode,
                busyButton(if (mine.exists(_.withdrawn)) "Enter Again" else "Enter")(busy =>
                    Store.run(ApiClient.enterTournament(t.gameId, t.tournamentId, chosen.now()), busy)(_ => reload(d))
                )
              )
        )
    }

    private def invitationsSection(d: TournamentDetail): HtmlElement = {
        val t = d.summary.tournament
        val prefix = Var("")
        val found: Var[Seq[PublicPlayer]] = Var(Seq.empty)
        sectionTag(
          h3("Invitations"),
          if (d.invited.isEmpty) p(cls := "empty", "Nobody is invited yet.")
          else
              ul(
                d.invited.map(inv =>
                    li(
                      cls := "row",
                      span(inv.nickname),
                      busyButton("Remove")(busy =>
                          Store.run(ApiClient.revokeTournamentInvitation(t.gameId, t.tournamentId, inv.playerId), busy)(
                            _ => reload(d)
                          )
                      ).amend(aria.label := s"Remove the invitation of ${inv.nickname}")
                    )
                )
              ),
          div(
            cls := "row",
            field("Find a player", input(controlled(value <-- prefix.signal, onInput.mapToValue --> prefix))),
            busyButton("Search", disabledWhen = prefix.signal.map(_.trim.isEmpty))(busy =>
                Store.run(ApiClient.searchPlayers(prefix.now().trim), busy)(r => found.set(r.players))
            )
          ),
          ul(
            aria.live := "polite",
            children <-- found.signal.map(_.map { p =>
                li(
                  cls := "row",
                  span(p.nickname),
                  busyButton("Invite")(busy =>
                      Store.run(ApiClient.inviteToTournament(t.gameId, t.tournamentId, p.playerId), busy)(_ =>
                          reload(d)
                      )
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
              if (d.summary.roundOver)
                  p(
                    cls := "detail",
                    "Every match of this round is over." +
                        (if (t.settings.autoRounds) ""
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
                        s.choosing && entryOf.get(s.entrantId).exists(e => me.exists(_.playerId == e.playerId))
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
                              m.seats.find(_.choosing).flatMap(_.chooseBy).fold("")(by => s" · choose by ${when(by)}")
                        )
                      ),
                      myTurn.fold(emptyNode)(_ => chooser(t, m, game)),
                      if (d.summary.isOwner && m.status != TournamentMatchStatus.Done) decider(t, m, names, d)
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
          busyButton("Choose Role", disabledWhen = picked.signal.map(_.isEmpty))(busy =>
              picked
                  .now()
                  .foreach(r =>
                      Store.run(ApiClient.chooseRole(t.gameId, t.tournamentId, m.matchId, r), busy)(_ =>
                          Store.tournament.now().foreach(reload)
                      )
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
            freshTipId("decide-tip"),
            "Decide by hand",
            "For a match that cannot be played, such as one a player never turned up to: it is called off, and its " +
                "seats ranked as you say. The ranks decide the tournament and move nobody's rating."
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
                    busyButton("Save Ranks")(busy =>
                        val typed = ranks.map((e, v) => v.now().trim.toIntOption.filter(_ >= 1).map(EntrantRank(e, _)))
                        if (typed.exists(_.isEmpty)) Store.reportProblem("Give every seat a rank of 1 or more.")
                        else
                            Store.run(
                              ApiClient
                                  .decideMatch(t.gameId, t.tournamentId, m.matchId, typed.flatten.toList),
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
                (if (run.completed) "" else " (being played)")
          ),
          run.pools.zipWithIndex.map((pool, i) => poolView(pool, poolName(i), names)),
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
          nested.fold(emptyNode)(sub => div(cls := "detail-panel", standings(sub, names, None)))
        )
    }

    private def outcomeOf(pool: Pool, m: PoolMember): String =
        if (!pool.completed) ""
        else if (m.eliminated) "out"
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
          h2("New Tournament"),
          child <-- Store.games.signal.map(_.find(_.gameId == gameId)).map {
              case Some(g) => form(g, None, () => ())
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
      */
    def form(game: Game, existing: Option[Tournament], onSaved: () => Unit): HtmlElement = {
        val key = existing.fold("new-tournament")(t => s"tournament-${t.tournamentId.value}")
        val running = existing.exists(_.status == TournamentStatus.Running)
        val r = game.poolSeats
        val s0 = existing.map(_.settings).getOrElse(TournamentSettings())
        val name = Var(existing.map(_.name).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val models: Signal[Seq[TournamentModel]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty))
        // The model chosen, by id; none until the models have come, for a new tournament.
        val modelId: Var[Option[TournamentModelId]] = Var(existing.map(_.tournamentModelId))
        val poolSize = Var(existing.fold("")(_.defaultPoolSize.toString))
        val invitational = Var(s0.invitational)
        val autoRounds = Var(s0.autoRounds)
        val friendly = Var(s0.friendly)
        val lateEntries = Var(s0.lateEntries)
        val concurrentRounds = Var(s0.concurrentRounds)
        val isPublic = Var(s0.isPublic)
        val minRating = Var(s0.minRating.fold("")(_.toString))
        val maxRating = Var(s0.maxRating.fold("")(_.toString))
        val entries = Var(s0.entriesPerPlayer.toString)
        val hours = Var(s0.roundDuration.fold("48")(d => (d.toMinutes / 60.0).toString.stripSuffix(".0")))
        val maxTurns = Var(s0.maxTurns.fold("")(_.toString))
        val problems: Var[List[Problem]] = Var(Nil)
        val chosen: Var[Option[TournamentModel]] = Var(None)
        // The model an existing tournament was made from, when the game no longer offers it -- its admins took it off,
        // or the code no longer defines it: not among those offered, and kept unless another is chosen.
        val kept = existing.map(_.tournamentModelId)
        val keptRetired: Signal[Option[TournamentModelId]] =
            models.map(ms => kept.filter(id => ms.nonEmpty && !ms.exists(_.tournamentModelId == id)))
        def keeping: Boolean = chosen.now().isEmpty && modelId.now().exists(kept.contains)

        /* Choosing a model fills in the pool size it suggests -- the game's R where it suggests none -- and whether it
         * takes late entries; both stay the creator's to change. */
        def choose(m: TournamentModel): Unit = {
            modelId.set(Some(m.tournamentModelId))
            chosen.set(Some(m))
            poolSize.set(m.poolSize.getOrElse(r).toString)
            lateEntries.set(m.lateEntries)
        }

        def int(v: Var[String]) = v.now().trim.toIntOption
        def settings = TournamentSettings(
          autoRounds = autoRounds.now(),
          friendly = friendly.now(),
          invitational = invitational.now(),
          lateEntries = lateEntries.now(),
          minRating = int(minRating),
          maxRating = int(maxRating),
          entriesPerPlayer = int(entries).getOrElse(1),
          roundDuration =
              hours.now().trim.toDoubleOption.filter(_ > 0).map(h => java.time.Duration.ofMinutes((h * 60).round)),
          maxTurns = int(maxTurns),
          concurrentRounds = concurrentRounds.now(),
          isPublic = isPublic.now()
        )
        def check(): Unit =
            problems.set(chosen.now() match {
                // The format it was made from, checked again as it is saved.
                case None if keeping => Nil
                case None            => List(Problem("", "choose a format"))
                case Some(m) =>
                    Validation.validate(
                      m.design,
                      TournamentGame.of(game),
                      settings.maxTurns,
                      int(poolSize).orElse(Some(r))
                    )
            })

        def checkbox(caption: String, v: Var[Boolean], tip: String) = {
            val id = freshTipId(s"$key-tip")
            withTip(id, caption, tip)(
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
            Views.tipField(freshTipId(s"$key-tip"), caption, tip)(
              input(tpe := "number", inputMode := "numeric", controlled(value <-- v.signal, onInput.mapToValue --> v))
            )

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
          if (running) p(cls := "detail", "The format of a tournament under way cannot be changed.")
          else
              div(
                Views.tipField(
                  freshTipId(s"$key-tip"),
                  "Format",
                  "The formats this game's admins offer, grouped by kind."
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
                        p(cls := "empty", "This game offers no tournament formats yet; its admins add them.")
                    case (_, Some(m)) => p(cls := "detail", m.description)
                    case _            => emptyNode
                },
                child <-- keptRetired.combineWith(modelId.signal).map {
                    case (Some(id), Some(current)) if id == current =>
                        p(
                          cls := "detail",
                          "This game no longer offers the format this tournament was made from. It keeps it " +
                              "unless you choose another."
                        )
                    case _ => emptyNode
                },
                number(
                  "Default pool size",
                  poolSize,
                  "How many play in a pool of any stage that does not set its own size. Filled in from the format."
                )
              ),
          h4("Settings"),
          checkbox(
            "Invitational",
            invitational,
            "Only players you invite may enter. You may always enter your own tournament."
          ),
          checkbox(
            "Automatic rounds",
            autoRounds,
            "Each round begins as soon as the last one ends, rather than when you start it."
          ),
          checkbox("Friendly", friendly, "Its matches change nobody's rating."),
          checkbox("Late entries", lateEntries, "Players may enter after it has started, joining from its next stage."),
          checkbox(
            "Concurrent rounds",
            concurrentRounds,
            "A round-robin pool plays all of its matches in one round, rather than each player one match a round."
          ),
          checkbox("Public", isPublic, "Anybody may see it listed."),
          number("Minimum rating", minRating, "Leave blank for no minimum."),
          number("Maximum rating", maxRating, "Leave blank for no maximum."),
          number("Entries per player", entries, "How many times one player may enter."),
          Views.tipField(
            freshTipId(s"$key-tip"),
            "Round length (hours)",
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
                "Turns per match",
                maxTurns,
                "This game does not say how many turns it lasts; a round's time is divided among this many."
              )
          else emptyNode,
          div(
            cls := "row",
            button(tpe := "button", "Check", onClick --> (_ => check())),
            busyButton(if (existing.isDefined) "Save Changes" else "Create Tournament")(busy =>
                check()
                val errors = problems.now().filterNot(_.warning)
                if (name.now().trim.isEmpty) Store.reportProblem("A tournament needs a name.")
                else if (errors.nonEmpty) Store.reportProblem(errors.map(_.describe).mkString("; "))
                else
                    modelId.now().foreach { id =>
                        val request = TournamentRequest(
                          name.now().trim,
                          id,
                          description.now().trim,
                          defaultPoolSize = int(poolSize),
                          settings = settings
                        )
                        val saving: Future[Tournament] = existing match {
                            case Some(t) => ApiClient.updateTournament(t.gameId, t.tournamentId, request)
                            case None    => ApiClient.createTournament(game.gameId, request)
                        }
                        Store.run(saving, busy) { saved =>
                            onSaved()
                            Store.reloadMyTournaments()
                            Store.reloadTournaments(game.gameId)
                            Store.show(Store.Page.OneTournament(saved.gameId, saved.tournamentId))
                            Store.reloadTournament(saved.gameId, saved.tournamentId)
                        }
                    }
            )
          ),
          ul(
            aria.live := "polite",
            children <-- problems.signal.map(
              _.map(pr =>
                  li(cls := (if (pr.warning) "notice" else "error"), (if (pr.warning) "Note: " else "") + pr.describe)
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
        val tg = TournamentGame.of(game)
        val offered: Signal[Set[TournamentModelId]] =
            Store.tournamentModelsByGame.signal.map(_.getOrElse(game.gameId, Seq.empty).map(_.tournamentModelId).toSet)
        val tipId = freshTipId("formats-tip")
        div(
          withTip(
            tipId,
            "Tournament Formats",
            "The formats ticked here are the ones offered when a tournament of this game is created. A tournament " +
                "already made keeps its format whatever you change here."
          )(h3("Tournament Formats")),
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
                        Validation.designProblems(m.design, tg, m.poolSize).filterNot(_.warning).headOption
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
                          disabled <-- busy.signal
                              .combineWith(offered)
                              .map((b, on) => b || (unplayable.isDefined && !on(m.tournamentModelId))),
                          controlled(
                            checked <-- offered.map(_.contains(m.tournamentModelId)),
                            onClick.mapToChecked --> (on => change(on))
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
                syncTip,
                "Sync Formats",
                "Makes every game's formats the ones the code defines: adds new ones and updates changed ones. A " +
                    "format the code no longer has is removed with its tournaments if nobody has entered one, and " +
                    "otherwise only taken off every list."
              )(
                busyButton("Sync Formats")(busy =>
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
