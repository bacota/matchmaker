package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** One game's screen, the new-game screen, and the dialog that edits a game. */
object Games {

    import Views.{currentPlayer, refreshableSection}

    /** One game's screen: its open challenges, a way to offer one, and this player's history in it. An admin also gets
      * the edit form.
      *
      * Taken from the games list rather than fetched, so a game id the list does not know about — an inactive game, or
      * a menu that has outlived a reload — says so instead of showing an empty screen that looks like a game with
      * nothing in it.
      */
    private[ui] def gamePage(gameId: GameId): HtmlElement = {
        // Through `Store.game`, which answers from the active games and from the deactivated one this
        // screen may have been linked to -- an invitation outlives its game being deactivated, and
        // reading the active list alone left such a page saying "Loading…" for ever. The two states are
        // still told apart: "Loading…" while something is on its way, and a sentence saying so once
        // nothing is.
        val state = Store
            .game(gameId)
            .combineWith(Store.loading(Store.Fetch.Games).combineWith(Store.lookingForGame(gameId)).map(_ || _))

        val message = state.map {
            case (None, true)  => "Loading…"
            case (None, false) => ErrorText.loading.gameUnavailable
            case (Some(_), _)  => ""
        }

        div(
          /* What the lookup came to, in a region that is mounted before it is asked.
           *
           * The sentence used to arrive as part of a freshly built element, which is the one way of
           * putting text in a live region that is not reliably announced: a reader watches regions it
           * already knows about for changes, and a region that did not exist a moment ago has no
           * change to report. So the region stands here for the life of the screen and only its words
           * come and go -- the same shape the invitations section uses, and for the same reason.
           *
           * A `div` rather than a `p` because it is empty whenever there is a game to draw, and an
           * empty `p` would leave its margins behind above the heading. The class comes and goes with
           * the text so the styling applies to a sentence and to nothing.
           */
          div(
            aria.live := "polite",
            cls <-- message.map(said => if (said.isEmpty) "" else "empty"),
            child.text <-- message
          ),
          // Drawn again only when the game itself changes. Every emission of the games list, or of what is being
          // looked up, used to rebuild the whole screen -- and the focus, a half-typed field and an open form
          // with it.
          child <-- state
              .map(_._1)
              .distinct
              .map {
                  case None => emptyNode
                  case Some(game) =>
                      div(
                        h2(game.displayName),
                        p(cls := "detail", game.description),
                        editGamePanel(game),
                        // What is waiting on this player in this game, before what they could join: a turn
                        // they owe somebody is more urgent than a competition they might enter.
                        MatchSections.dueSection(Some(game)),
                        MatchSections.myMatchesSection(Some(game)),
                        Characters.gameSection(game),
                        Tournaments.gameSection(game),
                        Ratings.ratingsSection(game),
                        gameHistory(game)
                      )
              },
          // The edit dialog, outside the screen drawn above it: that is rebuilt whenever the game is emitted again --
          // a refresh, or the answer to a save made from an earlier opening -- and a dialog inside it would be rebuilt
          // with it, losing whatever was being typed. Built once per opening, from the game as it was when opened.
          child <-- Store.editingGame.signal
              .combineWith(Store.game(gameId))
              .map {
                  case (Some(editing), Some(game)) if editing == gameId => Some(game)
                  case _                                                => None
              }
              .distinctBy(_.map(_.gameId))
              .map(_.fold(emptyNode)(editGameDialog))
        )
    }

    /** The link to the admin's edit form for a game, which opens as a dialog, as the challenge form does. Nothing for
      * anyone else: the server answers a non-admin with a 403, so the link is not there to press.
      */
    private def editGamePanel(game: Game): HtmlElement =
        div(
          child <-- currentPlayer.combineWith(Store.editingGame.signal).map {
              case (Some(player), editing) if player.isAdmin =>
                  div(
                    button(
                      cls := "link",
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded := editing.contains(game.gameId),
                      Buttons.game.edit,
                      onMountCallback(context => editGameTrigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => editGameTrigger = None),
                      onClick --> (_ => Store.editingGame.set(Some(game.gameId)))
                    )
                    // The dialog it opens is drawn by `gamePage`, which outlasts this.
                  )
              case _ => emptyNode
          }
        )

    /* The link the edit dialog is opened from, so closing it can put focus back -- `challengeTrigger`'s
     * counterpart. */
    private var editGameTrigger: Option[dom.html.Element] = None

    /* Which opening of the edit dialog is the current one. A save can outlive the dialog it was made from -- it can be
     * closed while the save is waiting, and opened again -- and the save's answer closing the dialog is only right for
     * the opening that made it. Moved on by every close, so an answer from before one is never current. */
    private var editGameOpening = 0

    /* Refused while its save is out: reopened meanwhile, the form would be drafted from the game as it was, and a
     * second save from it could add again what the first added. */
    private def closeEditGame(game: Game): Unit =
        if (!GameForm.savingBusy(Some(game)).now()) {
            editGameOpening += 1
            Store.editingGame.set(None)
            editGameTrigger.foreach(_.focus())
        }

    /* The edit form as a modal dialog: closed by Escape, by a click on the backdrop, or by its own Close button, and
     * closed by a save that succeeds. */
    private def editGameDialog(game: Game): HtmlElement = {
        val opening = editGameOpening
        gameDialog(
          existing = Some(game),
          headingId = s"edit-game-${game.gameId.value}-heading",
          title = Headings.games.edit(game.displayName),
          close = () => closeEditGame(game),
          onSaved = _ => if (editGameOpening == opening) closeEditGame(game)
        )
    }

    /* The game form as a modal dialog, for adding a game or editing one. */
    private def gameDialog(
        existing: Option[Game],
        headingId: String,
        title: String,
        close: () => Unit,
        onSaved: Game => Unit = _ => ()
    ): HtmlElement =
        Modal.dialog(
          content = GameForm.gameForm(existing = existing, heading = Some(headingId -> title), onSaved = onSaved),
          labelledBy = headingId,
          close = close,
          saving = GameForm.savingBusy(existing).signal
        )

    /** This player's finished matches in one game, most recently finished first.
      *
      * The same list the main page shows the top of, filtered rather than fetched again: it is already in the order
      * this asks for, and a second request would only be a second chance for the two screens to disagree.
      */
    private def gameHistory(game: Game): HtmlElement =
        refreshableSection(
          heading = Headings.games.completedMatches,
          refreshing = MatchSections.refreshingCompleted,
          reload = () => Store.reloadCompleted(),
          subsection = false
        )(
          MatchSections.completedWindow(
            Store.CompletedList.Mine(Some(game.gameId)),
            "You have not finished a match of this yet."
          )(
            MatchTables.matchTable(
              _,
              showGame = false,
              showParameters = true,
              outcome = MatchTables.playerOutcome,
              view = MatchTables.reviewControl
            )
          )
        )

    /** The admin's add-a-game screen, reached from the menu. The same form the edit link opens, with nothing to start
      * from.
      */
    private[ui] def newGamePage: HtmlElement = {
        // Open on arriving, since adding a game is what the menu item was pressed for; closed, the page is left with
        // the button to open it again and the disabled games. Page-local, so leaving the page is closing it -- a
        // create does, for the new game's screen.
        val adding = Var(true)
        var trigger: Option[dom.html.Element] = None
        // Which opening of the dialog is current, as `editGameOpening` is for the edit dialog: moved on by a close and
        // by leaving the page, so that a create answered after either does not pull the admin to the new game's
        // screen from wherever they have gone since. The game is recorded all the same.
        var opening = 0
        // Refused while a save is out, as the edit dialog's is: a second create would make a second game.
        def close(): Unit =
            if (!GameForm.savingBusy(None).now()) {
                opening += 1
                adding.set(false)
                trigger.foreach(_.focus())
            }
        div(
          onUnmountCallback(_ => opening += 1),
          h2(Headings.games.add),
          child <-- currentPlayer.map {
              case Some(player) if player.isAdmin =>
                  div(
                    button(
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded <-- adding.signal,
                      Buttons.game.`new`,
                      onMountCallback(context => trigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => trigger = None),
                      onClick --> (_ => adding.set(true))
                    ),
                    child <-- adding.signal.map { open =>
                        if (open) {
                            val mine = opening
                            // Straight to the game that was just created: it is now in the menu, and its own
                            // screen is where anything else is done with it.
                            gameDialog(
                              existing = None,
                              headingId = "add-game-heading",
                              title = Headings.games.add,
                              close = close,
                              onSaved = saved => if (opening == mine) Store.show(Store.Page.OneGame(saved.gameId))
                            )
                        } else emptyNode
                    },
                    disabledGames
                  )
              case _ => p(cls := "empty", "Only an administrator can add a game.")
          }
        )
    }

    /** The games an admin has disabled, each a link to its own screen, where its edit form can enable it again.
      *
      * Here because nowhere else lists them: a disabled game is out of the menu and every picker, which is the point of
      * disabling it, and without this the only way back to one would be a link somebody kept. Fetched each time the
      * page opens — it is the one screen that wants the disabled games as a list.
      */
    private def disabledGames: HtmlElement =
        div(
          cls := "detail-panel",
          h3(Headings.games.disabled),
          // Through the store, like every fetch: the Coalescer, and nothing committed after a sign-out.
          onMountCallback(_ => Store.reloadDisabledGames()),
          div(
            // Announced when it arrives, rather than left for a reader to find has changed.
            aria.live := "polite",
            child <-- Store.disabledGames.signal.combineWith(Store.failed(Store.Fetch.DisabledGames)).map {
                case (_, true)        => p(cls := "error", ErrorText.loading.disabledGames)
                case (None, _)        => p(cls := "detail", "Loading…")
                case (Some(Seq()), _) => p(cls := "empty", "None.")
                case (Some(games), _) =>
                    ul(
                      games.map(game =>
                          li(
                            button(
                              cls := "link",
                              game.displayName,
                              onClick --> (_ => Store.show(Store.Page.OneGame(game.gameId)))
                            )
                          )
                      )
                    )
            }
          )
        )
}
