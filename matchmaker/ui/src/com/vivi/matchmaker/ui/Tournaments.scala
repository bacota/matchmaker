package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.Duration
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._
import Views.{busyButton, field, openSignedIn, refreshableSection, tipField, withTip}

/** Tournaments in the browser (tournament-plan Phase 6): a game's list and the form to create one, a tournament's page
  * with its field, rounds and pools, and the caller's own tournaments on the home screen.
  *
  * Every fetch goes through `Store`, so an answer that comes back after a sign-out writes nothing. Pools and matches
  * are named by their place — "Pool A", "Pool B, match 2" — never by an id. Explanations are tips beside the controls
  * they explain.
  */
object Tournaments {

    // ---- the game screen ----------------------------------------------------------------------------

    /** A game's tournaments, and — for a signed-in player — the form to create one. */
    def gameSection(game: Game): HtmlElement = {
        val creating = Var(false)
        refreshableSection("Tournaments", () => Store.reloadGameTournaments(game.gameId), subsection = false)(
          child <-- Store.gameTournaments.signal.map(_.getOrElse(game.gameId, Seq.empty)).map { ts =>
              if (ts.isEmpty) p(cls := "empty", "No tournaments yet.")
              else ul(cls := "rows", ts.map(t => li(cls := "row", summaryLine(t))))
          },
          child <-- Store.currentPlayer.combineWith(creating.signal).map {
              case (Some(player), false) =>
                  button(
                    tpe := "button",
                    "Create tournament",
                    aria.expanded := false,
                    onClick --> (_ => creating.set(true))
                  )
              case (Some(player), true) => createForm(game, player, () => creating.set(false))
              case (None, _)            => emptyNode
          }
        )
    }

    private def summaryLine(t: Tournament): HtmlElement =
        div(
          button(
            tpe := "button",
            cls := "link title",
            t.name,
            onClick --> (_ => Store.show(Store.Page.OneTournament(t.gameId, t.tournamentId, t.name)))
          ),
          div(cls := "detail", describe(t))
        )

    private def describe(t: Tournament): String = {
        val kind = t.elimination.map(_.tournamentType.label).getOrElse(t.tournamentClass.label)
        val state =
            if (t.ended) "over"
            else if (t.started) "under way"
            else if (t.invitational) "by invitation, taking entries"
            else "taking entries"
        s"$kind, $state" + (if (t.live) ", live" else "") + (if (!t.friendly) ", rated" else "")
    }

    /** The form a tournament is created with. Only a game's admin is offered "rated": everybody else's is friendly. */
    private def createForm(game: Game, player: Player, close: () => Unit): HtmlElement = {
        val key = s"new-tournament-${game.gameId.value}"
        val name = Var("")
        val kind = Var[TournamentType](TournamentType.SingleElim)
        // A ladder is a class of its own rather than a kind of elimination, but it is offered as one more kind.
        val ladder = Var(false)
        // Only a character game's tournament may repeat: a character can be handed on to a new player between cycles.
        val cyclic = Var(false)
        val ladderCode = "Ladder"
        val poolSize = Var(math.max(2, game.roles.count(!_.optional)).toString)
        val advance = Var("1")
        val roundHours = Var("48")
        val tiebreaker = Var[Tiebreaker](Tiebreaker.Score)
        val rotations = Var("0")
        val eliminationRotations = Var("0")
        val invitational = Var(false)
        val isPublic = Var(false)
        val live = Var(false)
        val rated = Var(false)
        val minRating = Var("")
        val maxRating = Var("")
        val administers = Store.administers(game.gameId, player)

        def number(raw: String): Option[Int] = raw.trim.toIntOption

        form(
          cls := "card",
          aria.label := "Create a tournament",
          onSubmit.preventDefault --> (_ => ()),
          h3("New tournament"),
          field("Name", input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          withTip(
            s"$key-kind-tip",
            "Kind",
            "Single elimination: pools play off, and the best of each go through to the next round until one pool " +
                "is left for the final, with a consolation pool beside it. Round robin: everybody plays everybody once. " +
                "Playoff: pools of three or more each play round robin, then the best of them, seeded again by how " +
                "they did, play off in pairs. Double elimination: a pool's winner goes on, its second place drops to a " +
                "losers' bracket, and nobody is out until they have lost twice; the losers' champion meets the " +
                "winners' in the grand final. Repechage: single elimination in pairs, and then everybody beaten by " +
                "either finalist plays on in a chain, the two chains' winners meeting for third. Ladder: everybody starts at rank 0 and plays somebody near their rank " +
                "each round, going up one for a win and down one for a loss; players may join at any time, and it " +
                "never ends."
          )(
            field(
              "Kind",
              select(
                value <-- kind.signal.combineWith(ladder.signal).map((k, l) => if (l) ladderCode else k.code),
                onChange.mapToValue --> { code =>
                    ladder.set(code == ladderCode)
                    if (code != ladderCode) {
                        val chosen = TournamentType.fromCode(code)
                        // A playoff's pools hold more than two.
                        if (chosen == TournamentType.Playoff && number(poolSize.now()).forall(_ < 3)) poolSize.set("4")
                        // A repechage is played in pairs.
                        if (chosen == TournamentType.Repechage) poolSize.set("2")
                        kind.set(chosen)
                    }
                },
                option(value := TournamentType.SingleElim.code, TournamentType.SingleElim.label),
                option(value := TournamentType.RoundRobin.code, TournamentType.RoundRobin.label),
                option(value := TournamentType.Playoff.code, TournamentType.Playoff.label),
                option(value := TournamentType.DoubleElim.code, TournamentType.DoubleElim.label),
                option(value := TournamentType.Repechage.code, TournamentType.Repechage.label),
                option(value := ladderCode, TournamentClass.Ladder.label)
              )
            )
          ),
          child.maybe <-- kind.signal
              .combineWith(ladder.signal)
              .map((k, l) =>
                  Option.when(!l && k != TournamentType.RoundRobin)(
                    div(
                      tipField(
                        s"$key-pool-tip",
                        "Players per pool",
                        "How many players each pool holds. Usually as many as one match has seats; a larger pool plays " +
                            "everyone in it against everyone else."
                      )(
                        input(
                          tpe := "number",
                          minAttr := (if (k == TournamentType.Playoff) "3" else "2"),
                          controlled(value <-- poolSize.signal, onInput.mapToValue --> poolSize)
                        )
                      ),
                      tipField(
                        s"$key-advance-tip",
                        "Going through from each pool",
                        "How many of each pool's best go on to the next round, at least. More go through when the next " +
                            "round's pools would otherwise be short."
                      )(
                        input(
                          tpe := "number",
                          minAttr := "1",
                          controlled(value <-- advance.signal, onInput.mapToValue --> advance)
                        )
                      ),
                      Option.when(k == TournamentType.Playoff)(
                        tipField(
                          s"$key-elimination-rotations-tip",
                          "Rotations in the pairs",
                          "Rotations for the rounds of pairs after the pools: 0, and roles go by seed; more, and both " +
                              "players play every role that many times."
                        )(
                          input(
                            tpe := "number",
                            minAttr := "0",
                            controlled(
                              value <-- eliminationRotations.signal,
                              onInput.mapToValue --> eliminationRotations
                            )
                          )
                        )
                      )
                    )
                  )
              ),
          tipField(
            s"$key-round-tip",
            "Round length in hours",
            "How long a round lasts. Each player in a match has half of it on a chess clock."
          )(
            input(
              tpe := "number",
              minAttr := "1",
              controlled(value <-- roundHours.signal, onInput.mapToValue --> roundHours)
            )
          ),
          child.maybe <-- ladder.signal.map(l =>
              Option.when(!l && game.gameType == GameType.Character)(
                checkbox(
                  s"$key-cyclic-tip",
                  "Repeat in cycles",
                  cyclic,
                  "When the final is over, the tournament begins again with the same first-round pools and seeds, " +
                      "and goes on like that until you end it."
                )
              )
          ),
          child.maybe <-- ladder.signal.map(l =>
              Option.when(!l)(
                withTip(
                  s"$key-tiebreaker-tip",
                  "Tiebreaker",
                  "How players level on points in a pool are separated. Score adds up each match's score difference; " +
                      "Rematch has them play once more, with no tie allowed."
                )(
                  field(
                    "Tiebreaker",
                    select(
                      value <-- tiebreaker.signal.map(_.code),
                      onChange.mapToValue --> (code => tiebreaker.set(Tiebreaker.fromCode(code))),
                      Tiebreaker.values.toSeq.map(t => option(value := t.code, t.label))
                    )
                  )
                )
              )
          ),
          tipField(
            s"$key-rotations-tip",
            "Rotations",
            "0, and roles are chosen by seed, best first. More, and every player plays every role that many times " +
                "in each pool."
          )(
            input(
              tpe := "number",
              minAttr := "0",
              controlled(value <-- rotations.signal, onInput.mapToValue --> rotations)
            )
          ),
          checkbox(s"$key-invitational-tip", "By invitation", invitational, "Only the players you invite may enter."),
          checkbox(s"$key-public-tip", "Public", isPublic, "Anyone may watch its matches, without signing in."),
          checkbox(
            s"$key-live-tip",
            "Live",
            live,
            "Matches are played in real time, on the game engine's clock. Changing this takes effect from the next " +
                "round started."
          ),
          child <-- administers.map(admin =>
              if (!admin) emptyNode
              else
                  checkbox(
                    s"$key-rated-tip",
                    "Rated",
                    rated,
                    "Its matches move players' Elo ratings. Only a game's admin may run a rated tournament, and it " +
                        "cannot change once the tournament has started."
                  )
          ),
          child.maybe <-- invitational.signal.map(byInvitation =>
              Option.when(!byInvitation)(
                div(
                  tipField(
                    s"$key-min-tip",
                    "Lowest rating allowed",
                    "Left blank, there is no lower limit. A player with no rating counts as starting rated."
                  )(input(tpe := "number", controlled(value <-- minRating.signal, onInput.mapToValue --> minRating))),
                  tipField(s"$key-max-tip", "Highest rating allowed", "Left blank, there is no upper limit.")(
                    input(tpe := "number", controlled(value <-- maxRating.signal, onInput.mapToValue --> maxRating))
                  )
                )
              )
          ),
          div(
            cls := "controls",
            busyButton("Create", disabledWhen = name.signal.map(_.trim.isEmpty)) { busy =>
                val hours = number(roundHours.now()).getOrElse(48)
                val draft = Tournament(
                  gameId = game.gameId,
                  tournamentId = TournamentId.unassigned,
                  tournamentClass =
                      if (ladder.now()) TournamentClass.Ladder
                      else if (cyclic.now() && game.gameType == GameType.Character) TournamentClass.Cyclic
                      else TournamentClass.Elimination,
                  name = name.now().trim,
                  owner = player.playerId,
                  invitational = invitational.now(),
                  roundDuration = Duration.ofHours(hours.toLong),
                  elimination = Option.unless(ladder.now())(
                    EliminationSettings(
                      kind.now(),
                      number(poolSize.now()).getOrElse(2),
                      number(advance.now()).getOrElse(1),
                      eliminationRotations = number(eliminationRotations.now()).getOrElse(0),
                      tiebreaker = tiebreaker.now()
                    )
                  ),
                  isPublic = isPublic.now(),
                  friendly = !rated.now(),
                  live = live.now(),
                  minRating = number(minRating.now()),
                  maxRating = number(maxRating.now()),
                  rotations = number(rotations.now()).getOrElse(0)
                )
                Store.runSignedIn(ApiClient.createTournament(draft), busy) { created =>
                    close()
                    Store.reloadGameTournaments(game.gameId)
                    Store.show(Store.Page.OneTournament(created.gameId, created.tournamentId, created.name))
                }
            },
            button(tpe := "button", cls := "link", "Cancel", onClick --> (_ => close()))
          )
        )
    }

    private def checkbox(tipId: String, caption: String, state: Var[Boolean], text: String): HtmlElement =
        withTip(tipId, caption, text)(
          label(
            input(
              tpe := "checkbox",
              aria.describedBy := tipId,
              controlled(checked <-- state.signal, onClick.mapToChecked --> state)
            ),
            caption
          )
        )

    // ---- the home screen ----------------------------------------------------------------------------

    /** The caller's tournaments — run, entered, or invited to. Absent when there are none. */
    def homeSection: HtmlElement =
        div(
          child <-- Store.myTournaments.signal.map { mine =>
              if (mine.isEmpty) emptyNode
              else
                  refreshableSection("Your Tournaments", () => Store.reloadMyTournaments(), subsection = false)(
                    ul(
                      cls := "rows",
                      mine.map { s =>
                          val roles = List(
                            Option.when(s.owned)("you run it"),
                            Option.when(s.entered)("you have entered"),
                            Option.when(s.invited && !s.entered)("you are invited")
                          ).flatten
                          li(cls := "row", summaryLine(s.tournament), div(cls := "detail", roles.mkString(", ")))
                      }
                    )
                  )
          }
        )

    // ---- a tournament's page ------------------------------------------------------------------------

    /** What the page has to say after an action: announced, since nothing else on the page moves to say it. */
    private val said: Var[String] = Var("")

    def page(gameId: GameId, tournamentId: TournamentId, name: String): HtmlElement =
        div(
          h2(name),
          // Announced: what an action did, and the round's state as it changes.
          p(role := "status", aria.live := "polite", cls := "detail", child.text <-- said.signal),
          child <-- Store.tournament.signal.combineWith(Store.currentPlayer).map {
              case (None, _)               => p("Loading…")
              case (Some(d), None)         => body(d, None)
              case (Some(d), Some(player)) => body(d, Some(player))
          }
        )

    private def reload(d: TournamentDetail): Future[Unit] =
        Store.reloadTournament(d.tournament.gameId, d.tournament.tournamentId)

    private def body(d: TournamentDetail, player: Option[Player]): HtmlElement = {
        val t = d.tournament
        val owner = player.exists(_.playerId == t.owner)
        val mine = player.flatMap(p => d.entrants.find(_.player.playerId == p.playerId))
        val nameOf: Map[TournamentParticipantId, String] = d.entrants.flatMap { e =>
            e.participant.map(p => p.tournamentParticipantId -> e.character.map(_.name).getOrElse(e.player.nickname))
        }.toMap
        div(
          p(cls := "detail", describe(t) + s", owned by ${d.owner.nickname}"),
          roundStatus(d),
          entrantsSection(d, player, mine),
          if (owner) ownerControls(d) else emptyNode,
          roundsSection(d, player, owner, nameOf)
        )
    }

    private def roundStatus(d: TournamentDetail): HtmlElement = {
        val text =
            if (d.tournament.ended) "The tournament is over."
            else if (!d.tournament.started) s"Not started yet; ${d.entrants.size} entered."
            else
                d.rounds.find(!_.completed) match {
                    case Some(r) if r.started => s"Round ${r.round} is under way."
                    case Some(r)              => s"Round ${r.round} is waiting to be started."
                    case None if isLadder(d.tournament) =>
                        s"Round ${nextLadderRound(d)} is waiting to be started."
                    case None => "Every round is over."
                }
        p(cls := "detail", text)
    }

    private def entrantsSection(
        d: TournamentDetail,
        player: Option[Player],
        mine: Option[TournamentEntrant]
    ): HtmlElement = {
        val t = d.tournament
        sectionTag(
          h3("Entrants"),
          if (d.entrants.isEmpty) p(cls := "empty", "Nobody has entered yet.")
          else
              ol(
                cls := "rows",
                // A ladder's entrants stand in rank order, highest first; anybody else's in the order they come.
                (if (isLadder(t)) d.entrants.sortBy(e => e.participant.flatMap(_.ladderRank).map(-_)) else d.entrants)
                    .map { e =>
                        val seat = e.participant
                        li(
                          cls := "row",
                          span(
                            cls := "title",
                            e.character.map(c => s"${c.name} (${e.player.nickname})").getOrElse(e.player.nickname)
                          ),
                          seat
                              .map(p =>
                                  span(
                                    cls := "detail",
                                    p.ladderRank.filter(_ => isLadder(t)).fold(s" seed ${p.seed}")(r => s" rank $r")
                                  )
                              )
                              .getOrElse(emptyNode),
                          seat.flatMap(_.finalRank)
                              .map(r => span(cls := "detail", s", finished $r"))
                              .getOrElse(emptyNode),
                          if (seat.exists(_.withdrawn)) span(cls := "detail", ", withdrawn") else emptyNode
                        )
                    }
              ),
          player.fold(emptyNode)(p => entryControls(d, p, mine))
        )
    }

    private def entryControls(d: TournamentDetail, player: Player, mine: Option[TournamentEntrant]): Node = {
        val t = d.tournament
        mine match {
            case Some(e) if !e.participant.exists(_.withdrawn) && !t.ended =>
                busyButton("Withdraw", classes = Some("link")) { busy =>
                    val warning =
                        if (isLadder(t)) "Leave the ladder? You may rejoin it later, at the rank you leave with."
                        else "Withdraw from the tournament? You cannot rejoin it."
                    if (dom.window.confirm(warning))
                        Store.run(ApiClient.withdrawFromTournament(t.gameId, t.tournamentId, e.entryId), busy) { _ =>
                            said.set("You have withdrawn.")
                            reload(d)
                        }
                    else busy.set(false)
                }
            case Some(e) if e.participant.exists(_.withdrawn) && isLadder(t) && !t.ended =>
                busyButton("Rejoin the ladder") { busy =>
                    Store.run(
                      ApiClient.enterTournament(t.gameId, t.tournamentId, e.character.map(_.characterId)),
                      busy
                    ) { _ =>
                        said.set("You are back on the ladder.")
                        reload(d)
                    }
                }
            case None if !t.started || (isLadder(t) && !t.ended) =>
                val characters = Store.charactersByGame.signal.map(_.getOrElse(t.gameId, Seq.empty))
                val chosen = Var(Option.empty[CharacterId])
                div(
                  child.maybe <-- characters.map(cs =>
                      Option.when(cs.nonEmpty)(
                        field(
                          "Enter as",
                          select(
                            onMountCallback(_ => chosen.set(cs.headOption.map(_.characterId))),
                            onChange.mapToValue --> (v => chosen.set(v.toLongOption.map(CharacterId(_)))),
                            cs.map(c => option(value := c.characterId.value.toString, c.name))
                          )
                        )
                      )
                  ),
                  busyButton("Enter") { busy =>
                      Store.run(ApiClient.enterTournament(t.gameId, t.tournamentId, chosen.now()), busy) { _ =>
                          said.set("You have entered the tournament.")
                          reload(d)
                          Store.reloadMyTournaments()
                      }
                  },
                  if (t.invitational)
                      busyButton("Decline the invitation", classes = Some("link")) { busy =>
                          Store.run(ApiClient.declineTournament(t.gameId, t.tournamentId, player.playerId), busy) { _ =>
                              said.set("You have declined the invitation.")
                              Store.reloadMyTournaments()
                          }
                      }
                  else emptyNode
                )
            case _ => emptyNode
        }
    }

    /** The owner's buttons: start the tournament, start, check or resume the current round, and invite players. */
    private def ownerControls(d: TournamentDetail): HtmlElement = {
        val t = d.tournament
        val current = d.rounds.find(!_.completed)
        sectionTag(
          h3("Running it"),
          if (!t.started)
              withTip(
                s"start-tip-${t.tournamentId.value}",
                "Start the tournament",
                if (isLadder(t))
                    "Puts everybody who has entered on the ladder at rank 0. Players may still join afterwards; each " +
                        "round waits for you to start it."
                else
                    "Seeds the entrants by rating and lays out every round. Nobody can enter after this; the first " +
                        "round waits for you to start it."
              )(
                busyButton("Start the tournament") { busy =>
                    Store.run(ApiClient.startTournament(t.gameId, t.tournamentId), busy) { detail =>
                        Store.tournament.set(Some(detail))
                        said.set("The tournament has started. Start round 1 when you are ready.")
                    }
                }
              )
          else emptyNode,
          // A ladder's next round is not there until it is started, so it is offered by number.
          current
              .orElse(Option.when(isLadder(t))(TournamentRound(t.gameId, t.tournamentId, nextLadderRound(d))))
              .filter(_ => t.started && !t.ended)
              .fold(emptyNode)(r => roundControls(d, r)),
          if (t.started && !t.ended && t.tournamentClass == TournamentClass.Cyclic && current.forall(!_.started))
              withTip(
                s"end-tip-${t.tournamentId.value}",
                "End the tournament",
                "Stops it beginning another cycle. Everybody keeps the final rank of the last cycle finished."
              )(
                busyButton("End the tournament", classes = Some("link")) { busy =>
                    if (dom.window.confirm("End the tournament? No more rounds or cycles will be played."))
                        Store.run(ApiClient.endTournament(t.gameId, t.tournamentId), busy) { detail =>
                            Store.tournament.set(Some(detail))
                            said.set("The tournament is over.")
                        }
                    else busy.set(false)
                }
              )
          else emptyNode,
          if (!t.ended) inviteControl(d) else emptyNode
        )
    }

    private def isLadder(t: Tournament): Boolean = t.tournamentClass == TournamentClass.Ladder

    private def nextLadderRound(d: TournamentDetail): Int = d.rounds.map(_.round).maxOption.getOrElse(0) + 1

    private def roundControls(d: TournamentDetail, r: TournamentRound): HtmlElement = {
        val t = d.tournament
        val hours = Var("")
        val key = s"round-${t.tournamentId.value}-${r.round}"
        if (!r.started)
            div(
              tipField(
                s"$key-hours-tip",
                s"Length of round ${r.round} in hours",
                "Left blank, the round lasts as long as the tournament says."
              )(
                input(tpe := "number", minAttr := "1", controlled(value <-- hours.signal, onInput.mapToValue --> hours))
              ),
              busyButton(s"Start round ${r.round}") { busy =>
                  val overrides =
                      RoundOverrides(duration = hours.now().trim.toIntOption.map(h => Duration.ofHours(h.toLong)))
                  Store.run(ApiClient.startRound(t.gameId, t.tournamentId, r.round, overrides), busy) { work =>
                      said.set(s"Round ${r.round} has started: ${work.queued} match(es) are being set up.")
                      reload(d)
                  }
              }
            )
        else
            div(
              cls := "controls",
              withTip(
                s"$key-check-tip",
                "Check the round",
                "Asks every match whose clock may have run out to end it. A player who has run out of time loses."
              )(
                busyButton("Check the round") { busy =>
                    Store.run(ApiClient.checkRound(t.gameId, t.tournamentId, r.round), busy) { work =>
                        said.set(s"${work.queued} match(es) are being checked.")
                        reload(d)
                    }
                }
              ),
              withTip(
                s"$key-resume-tip",
                "Resume the round",
                "Sets up again any match of this round that was never made — after the game engine failed, say."
              )(
                busyButton("Resume the round", classes = Some("link")) { busy =>
                    Store.run(ApiClient.resumeRound(t.gameId, t.tournamentId, r.round), busy) { work =>
                        said.set(s"${work.queued} match(es) are being set up again.")
                        reload(d)
                    }
                }
              )
            )
    }

    private def inviteControl(d: TournamentDetail): HtmlElement = {
        val t = d.tournament
        val prefix = Var("")
        val found = Var(Seq.empty[PublicPlayer])
        div(
          h4("Invite a player"),
          if (d.invitedPlayers.nonEmpty)
              p(cls := "detail", "Invited: " + d.invitedPlayers.map(_.nickname).mkString(", "))
          else emptyNode,
          field(
            "Nickname begins with",
            input(
              controlled(value <-- prefix.signal, onInput.mapToValue --> prefix),
              onInput.mapToValue --> (raw =>
                  if (raw.trim.length >= 2) Store.run(ApiClient.searchPlayers(raw.trim))(r => found.set(r.players))
                  else found.set(Seq.empty)
              )
            )
          ),
          ul(
            cls := "rows",
            aria.live := "polite",
            children <-- found.signal.map(_.map { p =>
                li(
                  cls := "row",
                  span(p.nickname),
                  busyButton(s"Invite ${p.nickname}", classes = Some("link")) { busy =>
                      Store.run(ApiClient.inviteToTournament(t.gameId, t.tournamentId, Some(p.playerId), None), busy) {
                          _ =>
                              said.set(s"${p.nickname} is invited.")
                              found.set(Seq.empty)
                              prefix.set("")
                              reload(d)
                      }
                  }
                )
            })
          )
        )
    }

    // ---- rounds, pools and matches ------------------------------------------------------------------

    private def poolName(position: Int): String =
        if (position <= 26) s"Pool ${('A' + position - 1).toChar}" else s"Pool $position"

    private def roundsSection(
        d: TournamentDetail,
        player: Option[Player],
        owner: Boolean,
        nameOf: Map[TournamentParticipantId, String]
    ): Node =
        if (d.rounds.isEmpty) emptyNode
        else
            sectionTag(
              h3("Rounds"),
              div(
                cls := "bracket",
                d.rounds.map { r =>
                    div(
                      cls := "bracket-round",
                      h4(
                        s"Round ${r.round}" +
                            (if (d.tournament.tournamentClass == TournamentClass.Cyclic) s", cycle ${r.cycle}"
                             else "") +
                            (if (r.completed) " (over)" else if (r.started) " (under way)" else "")
                      ),
                      d.pools
                          .filter(_.fixture.round == r.round)
                          .sortBy(_.fixture.position)
                          .map(pool => poolBlock(d, r, pool, player, owner, nameOf))
                    )
                }
              )
            )

    private def poolBlock(
        d: TournamentDetail,
        r: TournamentRound,
        pool: TournamentPool,
        player: Option[Player],
        owner: Boolean,
        nameOf: Map[TournamentParticipantId, String]
    ): HtmlElement = {
        val f = pool.fixture
        val label = poolName(f.position)
        val standings = d.progress.standings.find(_.fixtureId == f.fixtureId).map(_.lines).getOrElse(Nil)
        val matches = d.progress.matches.filter(_.fixtureId == f.fixtureId).sortBy(_.matchNo)
        div(
          cls := "row pool",
          role := "group",
          aria.label := label,
          div(cls := "title", label),
          if (standings.nonEmpty && matches.nonEmpty)
              table(
                caption(cls := "sr-only", s"$label standings"),
                thead(tr(th("Player"), th("Points"))),
                tbody(standings.map(l => tr(td(nameOf.getOrElse(l.entrant, "—")), td(l.points.toString))))
              )
          else
              ul(
                cls := "parameters",
                pool.slots.map(s =>
                    li(
                      s.occupant
                          .flatMap(nameOf.get)
                          .getOrElse(s.source match {
                              case SlotSource.Bye     => "bye"
                              case SlotSource.Seed(n) => s"seed $n"
                              case SlotSource.Winner(from, rank) =>
                                  val earlier = d.pools.find(_.fixture.fixtureId == from).map(_.fixture)
                                  val named = earlier.fold("a pool")(e => s"round ${e.round}'s ${poolName(e.position)}")
                                  if (rank == 1) s"the winner of $named" else s"number $rank in $named"
                          })
                    )
                )
              ),
          ul(cls := "parameters", matches.map(m => matchLine(d, r, label, m, player, owner, nameOf)))
        )
    }

    private def matchLine(
        d: TournamentDetail,
        r: TournamentRound,
        pool: String,
        m: TournamentMatchView,
        player: Option[Player],
        owner: Boolean,
        nameOf: Map[TournamentParticipantId, String]
    ): HtmlElement = {
        val t = d.tournament
        val names = m.seats.map(s => s.entrant.flatMap(nameOf.get).getOrElse("—"))
        val state =
            if (m.cancelled && m.seats.exists(_.manual)) "cancelled, ranked by the tournament owner"
            else if (m.cancelled) "cancelled"
            else if (m.completed) "finished"
            else "being played"
        val outcome =
            if (m.seats.exists(_.rank.isDefined))
                m.seats
                    .map(s => s"${s.entrant.flatMap(nameOf.get).getOrElse("—")} ${s.rank.fold("–")(_.toString)}")
                    .mkString(", ")
            else ""
        // The caller's own place in the field, if they have one: whether a seat here is theirs.
        val mine = player.toList.flatMap(p =>
            d.entrants.filter(_.player.playerId == p.playerId).flatMap(_.participant.map(_.tournamentParticipantId))
        )
        val playing = m.seats.exists(_.entrant.exists(mine.contains))
        li(
          div(s"$pool, match ${m.matchNo}: ${names.mkString(" v ")} — $state"),
          if (outcome.nonEmpty) div(cls := "detail", outcome) else emptyNode,
          if (playing && !m.completed && !m.cancelled)
              busyButton("Play", classes = Some("link")) { busy =>
                  Store.run(ApiClient.matchDetail(t.gameId, m.matchId), busy)(found =>
                      found.playUrl.fold(Store.reportProblem("This match is still being set up."))(openSignedIn)
                  )
              }
          else emptyNode,
          // Cancel a match still being played; correct the ranks of one cancelled, until its round is over.
          if (owner && !m.completed && !r.completed) rankDialogButton(d, pool, m, names, editing = m.cancelled)
          else emptyNode
        )
    }

    /** The owner's Cancel, or — for a cancelled match whose round is not over — Edit ranks: a dialog with a rank for
      * every seat (D12).
      */
    private def rankDialogButton(
        d: TournamentDetail,
        pool: String,
        m: TournamentMatchView,
        names: List[String],
        editing: Boolean
    ): HtmlElement = {
        val open = Var(false)
        var trigger: Option[dom.html.Element] = None
        div(
          button(
            tpe := "button",
            cls := "link",
            htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
            if (editing) "Edit ranks" else "Cancel",
            onMountCallback(ctx => trigger = Some(ctx.thisNode.ref)),
            onClick --> (_ => open.set(true))
          ),
          child <-- open.signal.map(isOpen =>
              if (!isOpen) emptyNode
              else
                  rankDialog(d, pool, m, names, editing, () => { open.set(false); trigger.foreach(_.focus()) })
          )
        )
    }

    private def rankDialog(
        d: TournamentDetail,
        pool: String,
        m: TournamentMatchView,
        names: List[String],
        editing: Boolean,
        close: () => Unit
    ): HtmlElement = {
        val t = d.tournament
        val headingId = s"ranks-${m.matchId.value}"
        val ranks: Map[ParticipantId, Var[Int]] =
            m.seats.zipWithIndex.map((s, i) => s.participantId -> Var(s.rank.getOrElse(i + 1))).toMap
        def chosen = ranks.view.mapValues(_.now()).toMap
        def done(message: String): Unit = {
            said.set(message)
            close()
            reload(d)
        }
        div(
          cls := "modal-scrim",
          Modal.inertBehind,
          onClick --> (event => if (event.target == event.currentTarget) close()),
          div(
            cls := "modal card",
            role := "dialog",
            htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
            aria.labelledBy := headingId,
            tabIndex := -1,
            inContext(node => onMountCallback(_ => node.ref.focus())),
            onKeyDown.filter(_.key == "Escape") --> { event =>
                event.stopPropagation()
                close()
            },
            h3(
              idAttr := headingId,
              if (editing) s"Ranks for $pool, match ${m.matchNo}" else s"Cancel $pool, match ${m.matchNo}"
            ),
            p(
              cls := "detail",
              "Rank each player as the match should count in the pool's standings. Players may share a rank. " +
                  "Ratings are not changed."
            ),
            m.seats.zip(names).map { (s, n) =>
                field(
                  s"Rank for $n",
                  select(
                    value <-- ranks(s.participantId).signal.map(_.toString),
                    onChange.mapToValue --> (v => v.toIntOption.foreach(ranks(s.participantId).set)),
                    (1 to m.seats.size).map(i => option(value := i.toString, i.toString))
                  )
                )
            },
            div(
              cls := "controls",
              if (editing)
                  busyButton("Save ranks") { busy =>
                      Store.run(ApiClient.setRanks(t.gameId, m.matchId, chosen), busy)(_ =>
                          done("The ranks are saved.")
                      )
                  }
              else
                  busyButton("Cancel and rank") { busy =>
                      Store.run(ApiClient.cancelRanked(t.gameId, m.matchId, Some(chosen)), busy)(_ =>
                          done("The match is cancelled and ranked.")
                      )
                  },
              if (editing) emptyNode
              else
                  busyButton("Cancel without ranking", classes = Some("link")) { busy =>
                      Store.run(ApiClient.cancelRanked(t.gameId, m.matchId, None), busy)(_ =>
                          done("The match is cancelled. It scores nothing for anybody.")
                      )
                  },
              button(tpe := "button", cls := "link", "Close", onClick --> (_ => close()))
            )
          )
        )
    }
}
