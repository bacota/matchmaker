package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** A character game's screen: the way to the player's characters, or to making their first one. */
object Characters {

    import Views.{busyButton, openSignedIn}

    /** Nothing for a plain game. For a character game, the way to manage the player's characters there or, while they
      * have none, to make one: entering a competition needs one.
      */
    private[ui] def gameSection(game: Game): HtmlElement =
        if (game.gameType == GameType.Plain) div()
        else {
            /* What the last "check again" found. Said here, outside the prompt it is about, because a
             * check that finds a character replaces the prompt -- taking any message it held, and the
             * button that had focus, with it. */
            val checked = Var(Option.empty[String])
            var status: Option[dom.html.Element] = None

            div(
              child <-- Store.charactersByGame.signal.map(_.get(game.gameId)).distinct.map {
                  case None => p(cls := "empty", "Loading…")
                  case Some(Nil) =>
                      prompt(game = game, checked = checked, focusStatus = () => status.foreach(_.focus()))
                  // Characters are renamed where they were made, so the way to do it is the game's own page, as
                  // making one is.
                  case Some(_) =>
                      game.characterUrl.fold(div())(url =>
                          div(
                            button(
                              cls := "link",
                              Buttons.characters.manage(game = game.displayName),
                              onClick --> (_ => openSignedIn(url))
                            )
                          )
                      )
              },
              // Focusable but not tabbable: focus is put here when the button that had it goes away.
              p(
                role := "status",
                tabIndex := -1,
                onMountCallback(context => status = Some(context.thisNode.ref)),
                onUnmountCallback(_ => status = None),
                child.text <-- checked.signal.map(_.getOrElse(""))
              )
            )
        }

    /** Where a player with no character in this game is sent to make one.
      *
      * Characters are made in their game engine, not here: the engine builds one with the player and then reports it to
      * matchmaker, which is how it comes to be in [[Store.charactersByGame]]. So all this offers is the way there — the
      * game's `characterUrl`, opened beside this page — and a way to look again once the player is back. A game nobody
      * has given a url says so rather than offering a button that goes nowhere.
      *
      * What a check finds is said in `checked`, which [[gameSection]] shows outside this prompt; `focusStatus` moves
      * focus there before a successful check replaces it.
      */
    private def prompt(game: Game, checked: Var[Option[String]], focusStatus: () => Unit): HtmlElement =
        div(
          cls := "card",
          h3(Headings.characters.make(game.displayName)),
          p(
            s"You need a character in ${game.displayName} to enter a competition. ",
            "Characters are made in the game itself, which tells matchmaker once yours exists."
          ),
          game.characterUrl match {
              case Some(url) =>
                  div(
                    button(
                      Buttons.characters.make(game = game.displayName),
                      onClick --> (_ => openSignedIn(url))
                    ),
                    busyButton(label = Buttons.characters.checkAgain, classes = Some("link")) { busy =>
                        // Cleared first, so that a second "no character yet" is a change the live
                        // region announces rather than the same text it already holds.
                        checked.set(None)
                        // The session that asked, checked when the answer comes rather than when it is
                        // used: an answer that outlives it -- a sign-out, or somebody else signed in
                        // since -- is about the player who is gone, and must not reload, announce or
                        // move focus for the one who is here. It comes back as `None` whether it
                        // succeeded or failed, so not even its failure is reported to them.
                        val signIn = Store.currentSignIn
                        val asked = ApiClient.characters(game.gameId).transform {
                            case _ if !Store.isStillSignedInAs(signIn) => scala.util.Success(None)
                            case answered                              => answered.map(Some(_))
                        }
                        Store.run(asked, busy) {
                            case None => ()
                            // Nothing found is what the store already holds, so there is nothing to
                            // reload -- and reloading would only re-render this prompt for no reason.
                            case Some(found) if found.isEmpty =>
                                checked.set(Some("No character yet. Finish making one in the game."))
                            case Some(found) =>
                                checked.set(
                                  Some(s"Found ${found.map(_.name).mkString(", ")}. You can now enter a competition.")
                                )
                                focusStatus()
                                // Through the store's own load, which guards its own answer the same way.
                                Store.refreshCharacters(game.gameId)
                        }
                    }
                  )
              case None =>
                  p(cls := "detail", "This game has no page to make a character on yet; ask an administrator.")
          }
        )
}
