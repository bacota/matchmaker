package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** A game's ratings and rankings, and an admin's list of a player's matches with the ratings they can set. */
object Ratings {

    import Views.{currentPlayer, refreshableSection, scopeAttr}

    /** `player`'s matches in the game, for an admin of it looking at that player's page: where they say whether each is
      * friendly. Every match the player has a seat in, public or not — an admin manages them all — and nothing at all
      * for a reader who is not an admin of the game: not a heading, not a request beyond the one that finds out.
      *
      * On the player's page rather than the game's, so that an admin deals with one player's matches at a time, from
      * the page that is about that player.
      */
    private[ui] def adminMatchesSection(game: Game, player: PublicPlayer): HtmlElement =
        div(
          child <-- currentPlayer.map(
            _.fold(emptyNode)(reader => adminMatches(game = game, player = player, reader = reader))
          )
        )

    /* Not on the admin's own page: it is another player an admin is there to manage -- their rating in
     * the game, and whether each of their matches is friendly. */
    private def adminMatches(game: Game, player: PublicPlayer, reader: Player): HtmlElement =
        if (reader.playerId == player.playerId) div()
        else
            div(
              child <-- Store.administers(game.gameId, reader).map {
                  case true  => div(ratePlayer(game, player), adminMatchList(game, player))
                  case false => emptyNode
              }
            )

    /** The list itself, asked for when it is shown — which is only to an admin of the game. */
    private def adminMatchList(game: Game, player: PublicPlayer): HtmlElement = {
        // `None` until the list has come back.
        val matches = Var(Option.empty[Seq[GameMatch]])
        val refreshing = Var(false)
        // Mounts of this section, so that an answer to an earlier one is not written into a later one.
        var mount = 0

        /* Written only into this section, never the store: the page this sits in is rebuilt when the
         * store changes, and an answer that changed the store would rebuild it, remount this, and ask
         * again. Dropped if it belongs to another mount or another sign-in. */
        def fetch(asked: Int): Future[Unit] = {
            val signIn = Store.currentSignIn
            ApiClient.gameMatches(game.gameId, Some(player.playerId)).map { found =>
                if (asked == mount && Store.isStillSignedInAs(signIn)) matches.set(Some(found))
            }
        }

        div(
          onMountCallback { _ =>
              mount += 1
              fetch(mount)
          },
          onUnmountCallback(_ => mount += 1),
          child <-- matches.signal.map {
              case None => emptyNode
              case Some(found) =>
                  refreshableSection(
                    heading = "Friendly or Rated",
                    refreshing = refreshing,
                    reload = () => fetch(mount),
                    subsection = true,
                    tip = Some(
                      s"friendly-admin-tip-${game.gameId.value}" ->
                          (s"Every match ${player.nickname} has played or is playing in this game, public or not. As " +
                              "an admin of this game, you say whether each is friendly — even once it is over, until " +
                              "one of its players has played another match of this game since.")
                    )
                  )(
                    if (found.isEmpty) p(cls := "empty", "None yet.")
                    else ul(found.map(adminMatchRow(game, _)))
                  )
          }
        )
    }

    /** One match in [[adminMatchList]], with the box that says whether it is friendly.
      *
      * The box's state is the row's own, set as it is clicked and put back if the server refuses: changing it does not
      * rebuild the list, which would take the keyboard's focus with it.
      */
    private def adminMatchRow(game: Game, m: GameMatch): HtmlElement = {
        val friendly = Var(m.isFriendly)
        val busy = Var(false)
        li(
          cls := "row",
          div(cls := "title", if (m.description.trim.nonEmpty) m.description else "an unnamed match"),
          div(cls := "detail", if (m.players.isEmpty) "nobody seated yet" else m.players.mkString(", ")),
          div(
            cls := "detail",
            m.completedAt.map(when => s"completed ${Format.date(when)}").getOrElse {
                if (m.isCancelled) "cancelled" else s"started ${Format.date(m.start)}"
            }
          ),
          // A completed match can change too, and is re-rated when it does: said beside the box, and read
          // out with it, since that is a bigger thing than the box looks. The server refuses a change that
          // would reach into a later match, and the banner says why.
          if (m.completedAt.isDefined)
              div(idAttr := s"friendly-note-${m.matchId.value}", cls := "detail", "Changing this re-rates the match.")
          else emptyNode,
          label(
            input(
              tpe := "checkbox",
              if (m.completedAt.isDefined) aria.describedBy := s"friendly-note-${m.matchId.value}" else emptyMod,
              // Not disabled while saving: a disabled box drops the keyboard's focus, so a click made
              // meanwhile is ignored instead, and the box keeps showing what is being saved.
              aria.busy <-- busy.signal,
              controlled(
                checked <-- friendly.signal,
                onClick.mapToChecked.filter(_ => !busy.now()) --> { on =>
                    friendly.set(on)
                    Store.run(
                      action = ApiClient.setFriendly(gameId = game.gameId, matchId = m.matchId, friendly = on),
                      busy = busy,
                      onFailure = _ => friendly.set(!on)
                    )(saved => friendly.set(saved.isFriendly))
                }
              )
            ),
            "Friendly"
          )
        )
    }

    /** The game's leaderboard, [[Leaderboard.pageSize]] places at a time, best first, for anybody signed in; and for
      * its admins, a box on each row that sets that player's rating, and a form that rates a player who has none yet.
      *
      * Held in the section rather than the store, as [[adminMatchList]] holds its matches and for the same reason: an
      * answer written into the store would rebuild the page this sits in, remount it, and ask again.
      */
    private[ui] def ratingsSection(game: Game): HtmlElement =
        div(
          child <-- currentPlayer.map(
            _.fold(emptyNode)(player =>
                div(ratingsList(game = game, player = player, role = None), roleRankings(game, player))
            )
          )
        )

    /** Under the overall rankings of a game with roles, a dropdown that shows one role's rankings — asked for, rather
      * than shown, since most visitors want the overall ones. Not there for a game without roles, which keeps no
      * ratings by role, nor for a game of one role, whose only role's rankings would be the overall ones again.
      */
    private def roleRankings(game: Game, player: Player): Node =
        if (game.roles.sizeIs < 2) emptyNode
        else {
            val chosen = Var(Option.empty[GameRole])
            val id = s"role-rankings-${game.gameId.value}"
            div(
              cls := "role-rankings",
              label(
                cls := "field",
                forId := id,
                "Rankings by role",
                select(
                  idAttr := id,
                  onChange.mapToValue --> (v => chosen.set(game.roles.find(_.gameRoleId.value.toString == v))),
                  option(value := "", "Choose a role"),
                  game.roles.map(r => option(value := r.gameRoleId.value.toString, r.displayName))
                )
              ),
              // A section of its own once a role is chosen, which announces itself as the overall one does.
              child <-- chosen.signal.distinct.map(
                _.fold(emptyNode)(role => ratingsList(game = game, player = player, role = Some(role)))
              )
            )
        }

    /** The leaderboard — the game's overall one, or `role`'s. A role's has no rating to set: an admin sets the overall
      * rating, and a role's is moved only by play.
      */
    private def ratingsList(game: Game, player: Player, role: Option[GameRole]): HtmlElement = {
        val roleId = role.map(_.gameRoleId)
        // What tells this board's ids apart from the overall one's, on the same page.
        val idSuffix = role.fold("")(r => s"-role-${r.gameRoleId.value}")
        // `None` until a page has come back.
        val board = Var(Option.empty[Leaderboard])
        // The page on screen: moved only when the page asked for arrives, so that what the section says
        // about the rows, and where Previous, Next and a refresh go from, are about the rows it shows.
        val page = Var(0)
        val refreshing = Var(false)
        val busy = Var(false)
        // What the last save came to. Said aloud as well as shown: the box that was changed does not
        // say by itself that the change took.
        val said = Var("")
        // Why the last request for a page failed, if it did: so that the section says so rather than
        // "Loading…" for ever. Apart from `said`, and cleared by the next answer, so that a page that
        // loads on a retry is not shown beside the failure before it -- and a save's confirmation does
        // not overwrite why the page is missing.
        val loadError = Var(Option.empty[String])
        // Requests for a page, so that only the newest may write it. Moved on by an unmount too, so
        // that an answer to an earlier mount is not written into a later one, and by a row's save, so
        // that a page asked for before the save cannot answer after it with the rating it changed.
        var request = 0
        // The signed-in player's own standing, under the table: `None` until it has come back, and then
        // either it or what to say instead -- that they have no rating here, or that it could not be had.
        val mine = Var(Option.empty[Either[String, EloRating]])
        // Requests for it, apart from the page's: a save of somebody else's row says nothing about it, and
        // must not drop an answer still on its way. A save of the player's own row moves this on, as the
        // save is newer than anything asked before it.
        var mineRequest = 0
        // The last Find a Player answer: the game's rated players whose nickname starts with what was typed,
        // each shown as a line of its own at the foot of the table -- or why there is no answer.
        val found = Var(Option.empty[Either[String, Leaderboard]])

        /* Dropped if a newer request, mount or save has overtaken it, or it belongs to another sign-in --
         * its failure as well as its answer: the section is the next session's by then. Asks for the
         * player's own standing alongside the page, so that a refresh refreshes both. */
        def fetch(at: Int = page.now()): Future[Unit] = {
            request += 1
            val asked = request
            val signIn = Store.currentSignIn
            def current = asked == request && Store.isStillSignedInAs(signIn)
            mineRequest += 1
            val askedMine = mineRequest
            busy.set(true)
            ApiClient
                .standing(gameId = game.gameId, playerId = player.playerId, role = roleId)
                .map(Right(_))
                .recover {
                    case ApiError(404, _) =>
                        Left(
                          role.fold("You have no rating in this game yet.")(r =>
                              s"You have no rating as ${r.displayName} yet."
                          )
                        )
                    case error => Left(s"Your standing could not be loaded: ${error.getMessage}")
                }
                .foreach(answer =>
                    if (askedMine == mineRequest && Store.isStillSignedInAs(signIn)) mine.set(Some(answer))
                )
            ApiClient
                .leaderboard(gameId = game.gameId, page = at, role = roleId)
                .map { found =>
                    if (current) {
                        loadError.set(None)
                        // The page first: what is said about the rows is worked out as the rows arrive.
                        page.set(at)
                        board.set(Some(found))
                    }
                }
                .recover { case error =>
                    if (current) {
                        // "Refreshed" when an older page is still on screen: it is still there, and
                        // still what it was.
                        val what = if (board.now().isDefined) "refreshed" else "loaded"
                        loadError.set(Some(s"The leaderboard could not be $what: ${error.getMessage}"))
                    }
                }
                .andThen(_ => if (asked == request) busy.set(false))
        }

        /* A row's save is put in the row's place rather than re-sorted into the page: moving the row
         * the keyboard is in would take the focus with it, and its place is the listener's to work out
         * a moment later. The next refresh shows where it went. */
        def savedRow(saved: EloRating): Unit = {
            request += 1
            busy.set(false)
            said.set(s"${saved.player.nickname} is now rated ${saved.rating}.")
            // The rating as saved, and the place with everything it was worked out from as they were, so
            // that the row's place, ranked rating and matches still agree with each other and with the
            // page: the listener places the player again, and the next refresh shows where.
            def updated(r: EloRating) =
                if (r.player.playerId != saved.player.playerId) r
                else saved.copy(rank = r.rank, rankedRating = r.rankedRating, rankedMatches = r.rankedMatches)
            board.update(_.map(b => b.copy(ratings = b.ratings.map(updated))))
            // The player's own standing is the save's answer whole: it was read as the save committed, so
            // it is newer than any standing asked for before it, which is dropped.
            if (saved.player.playerId == player.playerId) {
                mineRequest += 1
                mine.set(Some(Right(saved)))
            }
        }

        val administers: Signal[Boolean] = if (role.isDefined) Val(false) else Store.administers(game.gameId, player)
        val rows = board.signal.map(_.fold(List.empty[EloRating])(_.ratings))

        // The line that says which places the page covers, for the focus to go to when a step it was on goes.
        var placesLine: Option[dom.html.Element] = None

        /* There only when there is somewhere to go: no Previous on the first page, no Next on the last.
         * Built once and mounted or not, so that it is the same element whenever it is there. A step that
         * goes while it has the focus -- Next, pressed onto the last page -- hands the focus to the line
         * saying which places are now shown, rather than letting it fall to the top of the page. While a
         * page is on its way it stays, marked busy, and a press is ignored. */
        def step(caption: String, label: String, side: String, possible: Signal[Boolean], to: Int => Int) = {
            val control = button(
              tpe := "button",
              cls := s"link $side",
              aria.label := label,
              aria.disabled <-- busy.signal,
              caption,
              onClick --> (_ => if (!busy.now()) fetch(to(page.now()))),
              onUnmountCallback(node => if (dom.document.activeElement == node.ref) placesLine.foreach(_.focus()))
            )
            child <-- possible.distinct.map(if (_) control else emptyNode)
        }

        div(
          onMountCallback(_ => fetch(0)),
          onUnmountCallback(_ => request += 1),
          refreshableSection(
            heading = role.fold("Player Rankings")(r => s"${r.displayName} Rankings"),
            refreshing = refreshing,
            reload = () => fetch(),
            subsection = role.isDefined,
            tip = Some(
              s"ratings-tip-${game.gameId.value}$idSuffix" -> role.fold(
                s"Players are ranked by Elo rating, and by rated matches between equal ratings. " +
                    s"Every rated match of this game moves its players' ratings; a friendly one does not. " +
                    s"A player's first rated match starts them at ${EloRating.initial}."
              )(r =>
                  s"Players are ranked by their Elo rating as ${r.displayName}, which a rated match moves against " +
                      s"the other players' ratings in the roles they played. So a role that wins more than its " +
                      s"players' ratings say it should is rated higher than the others. A player's first rated " +
                      s"match as ${r.displayName} starts them at ${EloRating.initial}."
              )
            )
          )(
            // Two regions, mounted with the section so that what arrives in them is announced: how
            // loading the page went, and what the last save came to.
            div(aria.live := "polite", cls := "empty", child.text <-- loadError.signal.map(_.getOrElse(""))),
            div(aria.live := "polite", cls := "detail", child.text <-- said.signal),
            // Which places the page covers: said when Previous or Next brings a new one. A page is a range of
            // places rather than of players, so a long tie can leave one with nobody on it.
            p(
              cls := "detail",
              aria.live := "polite",
              // Focusable from script only: where the focus goes when the step it was on goes.
              tabIndex := -1,
              onMountCallback(context => placesLine = Some(context.thisNode.ref)),
              onUnmountCallback(_ => placesLine = None),
              child.text <-- board.signal.combineWith(loadError.signal).map {
                  // A failure is said in the region above; this only stops promising a page.
                  case (None, Some(_)) => ""
                  case (None, None)    => "Loading…"
                  case (Some(b), _) =>
                      val first = page.now() * Leaderboard.pageSize + 1
                      val last = first + Leaderboard.pageSize - 1
                      if (b.ratings.nonEmpty) s"Ranks $first to $last"
                      else if (page.now() == 0) "Nobody is ranked yet."
                      else s"Nobody is ranked $first to $last."
              }
            ),
            // Shown when the page has anybody on it, or there is a line of its own to show at its foot -- the
            // player's own standing, or one they searched for -- whoever is on the page.
            child <-- rows
                .map(_.nonEmpty)
                .combineWith(
                  mine.signal.map(_.exists(_.isRight)),
                  found.signal.map(_.exists(_.exists(_.ratings.nonEmpty)))
                )
                .map(_ || _ || _)
                .distinct
                .map {
                    case false => emptyNode
                    case true  =>
                        // Its own scroll, so that a phone scrolls the table sideways rather than the page.
                        div(
                          cls := "table-scroll",
                          table(
                            cls := "leaderboard",
                            caption(
                              cls := "sr-only",
                              s"Player rankings in ${game.name}" + role.fold("")(r => s" as ${r.displayName}")
                            ),
                            thead(
                              tr(
                                th(scopeAttr := "col", "Rank"),
                                th(scopeAttr := "col", "Player"),
                                th(scopeAttr := "col", "Ranked rating"),
                                th(scopeAttr := "col", "Matches"),
                                th(scopeAttr := "col", "Record"),
                                child <-- administers.map(if (_) th(scopeAttr := "col", "Set rating") else emptyNode)
                              )
                            ),
                            // Split by player, so that a row keeps its element -- and whatever is typed in its
                            // box -- when the page around it is answered again.
                            tbody(
                              children <-- rows.split(_.player.playerId)((_, first, rating) =>
                                  ratingRow(
                                    game = game,
                                    rated = first.player,
                                    rating = rating,
                                    administers = administers,
                                    onSaved = savedRow
                                  )
                              )
                            ),
                            // The player's own line, after the page and set apart from it: their place, with their
                            // rating and matches as they stand now rather than as they were placed by, which a match
                            // that has just finished may have moved.
                            tfoot(
                              child <-- mine.signal.combineWith(administers).map {
                                  case (Some(Right(you)), admin) =>
                                      footRow(standing = you, kind = "mine", note = "you, now", administers = admin)
                                  case _ => emptyNode
                              },
                              children <-- found.signal.combineWith(administers).map {
                                  case (Some(Right(them)), admin) =>
                                      them.ratings.map(footRow(_, "found", "found, now", admin))
                                  case _ => Nil
                              }
                            )
                          )
                        )
                },
            // What there is to say instead of the player's own line: that it is on its way, that they have no
            // rating here, or that it could not be had. Mounted with the section, so that it is announced.
            div(
              aria.live := "polite",
              child <-- mine.signal.map {
                  case None            => p(cls := "empty", "Loading your standing…")
                  case Some(Left(why)) => p(cls := "empty", why)
                  case Some(Right(_))  => emptyNode
              }
            ),
            div(
              cls := "completed-steps",
              // Named for their board, since a role's sits on the page beside the overall one.
              step(
                caption = "Previous",
                label = "Previous 20 ranks" + role.fold("")(r => s" as ${r.displayName}"),
                side = "backward",
                possible = page.signal.map(_ > 0),
                to = _ - 1
              ),
              step(
                caption = "Next",
                label = "Next 20 ranks" + role.fold("")(r => s" as ${r.displayName}"),
                side = "forward",
                possible = board.signal.map(_.exists(_.hasMore)),
                to = _ + 1
              )
            ),
            findPlayer(game = game, found = found, role = role)
          )
        )
    }

    /** A line of its own at the foot of the leaderboard — the signed-in player's (`mine`), or one they searched for
      * (`found`): their place, and their rating and matches as they stand now. Told apart from the page by its colour,
      * and in words, `note`, which a screen reader hears where it cannot see the colour.
      */
    private def footRow(standing: EloRating, kind: String, note: String, administers: Boolean): HtmlElement =
        tr(
          cls := kind,
          td(standing.rank.fold[Modifier[HtmlElement]](notRanked)(_.toString)),
          th(scopeAttr := "row", Players.playerLink(standing.player), span(cls := "foot-note", s" ($note)")),
          td(standing.rating.toString),
          td(standing.matches.toString),
          recordCell(standing.record),
          // Nothing to set here: an admin sets a rating from the player's row in the page, if it is on it.
          if (administers) td() else emptyNode
        )

    /** The game's rated players by the start of a nickname, as "Find Players" finds players: what is found goes into
      * `found`, which the leaderboard shows as lines at its foot. One row of a form, under the table, since it is a way
      * into the table rather than a section of its own.
      */
    private def findPlayer(
        game: Game,
        found: Var[Option[Either[String, Leaderboard]]],
        role: Option[GameRole]
    ): HtmlElement = {
        val prefix = Var("")
        val busy = Var(false)
        val id = s"find-in-rankings-${game.gameId.value}" + role.fold("")(r => s"-role-${r.gameRoleId.value}")

        form(
          cls := "search inline-form",
          // Enter in the field submits, as on "Find Players".
          onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
              val typed = prefix.now().trim
              if (typed.nonEmpty)
                  Store.runSignedIn(
                    ApiClient.findInRankings(gameId = game.gameId, prefix = typed, role = role.map(_.gameRoleId)),
                    busy
                  )(answer => found.set(Some(Right(answer))))
          },
          label(forId := id, "Find a Player"),
          div(
            cls := "compound",
            input(
              idAttr := id,
              tpe := "search",
              placeholder := "Nickname begins with",
              autoComplete := "off",
              controlled(value <-- prefix.signal, onInput.mapToValue --> prefix)
            ),
            // Not disabled while searching: a disabled button drops the keyboard's focus.
            button(tpe := "submit", aria.busy <-- busy.signal, "Find")
          ),
          // Mounted before it has anything to say, so that what arrives in it is announced. The lines
          // themselves are in the table, which is not announced; this says how many and where.
          div(
            aria.live := "polite",
            child <-- found.signal.map {
                case None            => emptyNode
                case Some(Left(why)) => p(cls := "empty", why)
                case Some(Right(none)) if none.ratings.isEmpty =>
                    p(cls := "empty", "No rated player's nickname starts with that.")
                case Some(Right(some)) =>
                    val count = some.ratings.size
                    val who = if (count == 1) "1 player" else s"$count players"
                    p(
                      cls := "detail",
                      s"$who found, shown at the foot of the rankings." +
                          (if (some.hasMore) " Type more of the nickname to narrow it." else "")
                    )
            }
          )
        )
    }

    /** The rank of a player not yet placed: a dash to the eye, and words to a screen reader. Text rather than an
      * `aria-label`, which a screen reader ignores on a span.
      */
    private def notRanked: HtmlElement =
        span(span(aria.hidden := true, "—"), span(cls := "sr-only", "not ranked yet"))

    /** A player's win–loss–draw record, as the rankings show it: "12 (2)–5 (1)–3", the numbers in parentheses the wins
      * and losses that came by forfeit, and left out when there are none. Said in words to a screen reader, which would
      * otherwise read the dashes and parentheses as punctuation.
      */
    private[ui] def recordText(r: MatchRecord): HtmlElement = {
        def byForfeit(n: Int) = if (n > 0) s" ($n)" else ""
        def count(n: Int, one: String, many: String) = s"$n ${if (n == 1) one else many}"
        def spoken(said: String, forfeits: Int) = said + (if (forfeits > 0) s", $forfeits by forfeit" else "")
        // The words as text a screen reader reads in place of the figures, rather than as an `aria-label`, which it
        // ignores on a span.
        span(
          span(
            aria.hidden := true,
            s"${r.wins}${byForfeit(r.forfeitWins)}–${r.losses}${byForfeit(r.forfeitLosses)}–${r.draws}"
          ),
          span(
            cls := "sr-only",
            List(
              spoken(count(n = r.wins, one = "win", many = "wins"), r.forfeitWins),
              spoken(count(n = r.losses, one = "loss", many = "losses"), r.forfeitLosses),
              count(n = r.draws, one = "draw", many = "draws")
            ).mkString("; ")
          )
        )
    }

    private def recordCell(r: MatchRecord): HtmlElement = td(recordText(r))

    /** One player's row of the leaderboard; for an admin of the game, with the box that sets their rating. */
    private def ratingRow(
        game: Game,
        rated: PublicPlayer,
        rating: Signal[EloRating],
        administers: Signal[Boolean],
        onSaved: EloRating => Unit
    ): HtmlElement =
        tr(
          td(
            child <-- rating.map(_.rank match {
                case Some(rank) => span(rank.toString)
                // Rated a moment ago, and placed a moment from now.
                case None => notRanked
            })
          ),
          // Their page, as a "Find Players" search would open it.
          th(scopeAttr := "row", Players.playerLink(rated)),
          // The rating and matches the place was worked out from, so that the columns read in order with the
          // places -- matches telling equal ratings apart; the rating as it stands now is under the table, for
          // the player and for anybody searched for.
          td(child.text <-- rating.map(r => r.rankedRating.getOrElse(r.rating).toString)),
          td(child.text <-- rating.map(r => r.rankedMatches.getOrElse(r.matches).toString)),
          td(child <-- rating.map(r => recordText(r.record))),
          child <-- administers.map {
              case false => emptyNode
              case true =>
                  val typed = Var("")
                  val busy = Var(false)
                  val id = s"rating-${game.gameId.value}-${rated.playerId.value}"
                  td(
                    form(
                      cls := "compound",
                      onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
                          typed.now().trim.toIntOption.foreach { n =>
                              Store.run(
                                ApiClient.setRating(gameId = game.gameId, playerId = rated.playerId, rating = n),
                                busy
                              ) { saved =>
                                  typed.set("")
                                  onSaved(saved)
                              }
                          }
                      },
                      label(cls := "sr-only", forId := id, s"New rating for ${rated.nickname}"),
                      ratingInput(id, typed, placeholder := "New rating"),
                      // Not disabled while saving: a disabled button drops the keyboard's focus. A press
                      // made meanwhile is ignored by the filter above instead.
                      button(tpe := "submit", aria.busy <-- busy.signal, "Set")
                    )
                  )
          }
        )

    /** For an admin of the game, on a player's page: setting that player's rating in it, whether they have one yet or
      * not. On the page a "Find Players" search opens, where the player is already chosen, so there is no nickname to
      * type.
      */
    private def ratePlayer(game: Game, player: PublicPlayer): HtmlElement = {
        val typed = Var("")
        val busy = Var(false)
        // What the last save came to: said aloud, since the box being emptied does not say it took.
        val said = Var("")
        val id = s"rate-${game.gameId.value}-${player.playerId.value}"

        form(
          cls := "search inline-form",
          onSubmit.preventDefault.filter(_ => !busy.now()) --> { _ =>
              typed.now().trim.toIntOption.foreach { n =>
                  Store.run(ApiClient.setRating(gameId = game.gameId, playerId = player.playerId, rating = n), busy) {
                      saved =>
                          typed.set("")
                          said.set(s"${saved.player.nickname} is now rated ${saved.rating} in ${game.displayName}.")
                  }
              }
          },
          label(forId := id, s"Rate ${player.nickname}"),
          div(
            cls := "compound",
            ratingInput(id, typed, placeholder := "New rating"),
            // Not disabled while saving: a disabled button drops the keyboard's focus.
            button(tpe := "submit", aria.busy <-- busy.signal, "Set rating")
          ),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal)
        )
    }

    /** A box for a rating: a whole number in the range the server accepts, which the browser checks before sending. */
    private def ratingInput(id: String, typed: Var[String], modifiers: Modifier[Input]*): Input =
        input(
          idAttr := id,
          tpe := "number",
          required := true,
          minAttr := EloRating.minimum.toString,
          maxAttr := EloRating.maximum.toString,
          stepAttr := "1",
          // The number pad on a phone, rather than the full keyboard.
          inputMode := "numeric",
          controlled(value <-- typed.signal, onInput.mapToValue --> typed),
          modifiers
        )
}
