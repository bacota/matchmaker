package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** A game's challenges: the caller's own, the open ones, and inviting players and characters to them. */
object Challenges {

    import Views.{busyButton, currentPlayer, errorBanner, field, friendlyLabel, openSignedIn, refreshableSection}

    /** What can be played in this game right now: the open challenges, and the form that offers one. A game that needs
      * characters needs one of this player's before either is possible, so that form stands in for both until there is
      * one.
      */
    private[ui] def gameChallenges(game: Game): HtmlElement =
        if (game.gameType == GameType.Plain)
            div(
              child <-- currentPlayer.map {
                  case None         => p(cls := "empty", "Loading…")
                  case Some(player) => challengePanel(game = game, player = player, characters = Seq.empty)
              }
            )
        else {
            /* What the last "check again" found. Said here, outside the panel it is about, because a
             * check that finds a character replaces that panel with the challenge panel -- taking any
             * message it held, and the button that had focus, with it. */
            val checked = Var(Option.empty[String])
            var status: Option[dom.html.Element] = None

            div(
              child <-- currentPlayer.combineWith(Store.charactersByGame.signal).map {
                  case (None, _) => p(cls := "empty", "Loading…")
                  case (Some(player), byGame) =>
                      byGame.get(game.gameId) match {
                          case None => p(cls := "empty", "Loading…")
                          // A character is needed before this player can either offer or accept a
                          // challenge, so there is nothing to show until there is one.
                          // An admin of the game gets the challenge panel as well: they may offer a
                          // match for other players' characters without one of their own.
                          case Some(Nil) =>
                              val admin = Store.administers(game.gameId, player)
                              div(
                                characterPrompt(
                                  game = game,
                                  admin = admin,
                                  checked = checked,
                                  focusStatus = () => status.foreach(_.focus())
                                ),
                                child <-- admin.map(
                                  if (_) challengePanel(game = game, player = player, characters = Seq.empty)
                                  else emptyNode
                                )
                              )
                          case Some(characters) =>
                              div(
                                // Characters are renamed where they were made, so the way to do it
                                // is the game's own page, as making one is.
                                game.characterUrl.fold(emptyNode)(url =>
                                    button(
                                      cls := "link",
                                      s"Manage your characters in ${game.displayName}",
                                      onClick --> (_ => openSignedIn(url))
                                    )
                                ),
                                challengePanel(game = game, player = player, characters = characters)
                              )
                      }
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
      * What a check finds is said in `checked`, which [[gameChallenges]] shows outside this panel; `focusStatus` moves
      * focus there before a successful check replaces the panel.
      */
    private def characterPrompt(
        game: Game,
        admin: Signal[Boolean],
        checked: Var[Option[String]],
        focusStatus: () => Unit
    ): HtmlElement =
        div(
          cls := "card",
          h3(s"Make Your Character for ${game.displayName}"),
          // An admin of the game has the challenge panel below this, so for them a character is
          // needed only to play, not to offer a match. Text rather than a re-rendered card, so the
          // buttons here keep focus if the admin list arrives while one has it.
          p(
            child.text <-- admin.map {
                case true =>
                    s"You need a character in ${game.displayName} to play or to accept a challenge. " +
                        "As an admin of this game you can still host a match for other players' characters below. "
                case false =>
                    s"You need a character in ${game.displayName} before you can offer or accept a challenge. "
            },
            "Characters are made in the game itself, which tells matchmaker once yours exists."
          ),
          game.characterUrl match {
              case Some(url) =>
                  div(
                    button(
                      s"Make a character in ${game.displayName}",
                      onClick --> (_ => openSignedIn(url))
                    ),
                    busyButton(label = "I've made one — check again", classes = Some("link")) { busy =>
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
                            // reload -- and reloading would only re-render this panel for no reason.
                            case Some(found) if found.isEmpty =>
                                checked.set(Some("No character yet. Finish making one in the game."))
                            case Some(found) =>
                                checked.set(
                                  Some(
                                    s"Found ${found.map(_.name).mkString(", ")}. " +
                                        "You can now offer or accept a challenge."
                                  )
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

    /* `characters` is every character this player has in the game, empty for a plain game. A challenge
     * is offered, or entered, as whichever of them the player chooses. */
    private def challengePanel(game: Game, player: Player, characters: Seq[Character[String]]): HtmlElement = {
        // One flag for both lists, held out here rather than inside either of them: they are two
        // views of one request, and this element is rebuilt when that request answers.
        val refreshingChallenges = Var(false)

        div(
          child <-- Store.challengesByGame.signal.map(_.get(game.gameId)).map {
              case None             => p(cls := "empty", "Loading challenges…")
              case Some(challenges) =>
                  // `ui.txt` asks for the player's own challenges in a separate list, because what you
                  // can do with them is different: start or cancel yours, enter someone else's.
                  val (mine, others) = challenges.partition(_.summary.isOwner)
                  div(
                    // A button rather than a form standing open: offering a challenge is one of several
                    // things to do on this screen, and a form is what the screen looks like it is for.
                    // The form itself is a dialog over the screen, so the lists stay where they were.
                    button(
                      htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "dialog",
                      aria.expanded <-- Store.showChallengeForm.signal,
                      "Create Challenge",
                      onMountCallback(context => challengeTrigger = Some(context.thisNode.ref)),
                      onUnmountCallback(_ => challengeTrigger = None),
                      onClick --> (_ => Store.showChallengeForm.set(true))
                    ),
                    // Built from the slot as it stands when the form opens: a challenge is composed
                    // for one player, and one being looked up while the form is open is a different
                    // challenge, offered by opening it again.
                    child <-- Store.showChallengeForm.signal.map {
                        if (_)
                            challengeDialog(
                              ChallengeForm.newChallengeForm(
                                game = game,
                                player = player,
                                characters = characters,
                                invitee = Store.invitee.now()
                              )
                            )
                        else emptyNode
                    },
                    refreshableSection(
                      heading = "Your Open Challenges",
                      refreshing = refreshingChallenges,
                      reload = () => Store.reloadChallenges(game.gameId),
                      subsection = true
                    )(
                      if (mine.isEmpty) p(cls := "empty", "You have none open.")
                      else ul(mine.map(myChallengeRow(game, _)))
                    ),
                    refreshableSection(
                      heading = "Open Challenges",
                      refreshing = refreshingChallenges,
                      reload = () => Store.reloadChallenges(game.gameId),
                      subsection = true
                    )(
                      if (others.isEmpty) p(cls := "empty", "Nobody is waiting for an opponent.")
                      else ul(others.map(openChallengeRow(game, _, characters, player)))
                    )
                  )
          }
        )
    }

    /* The button the challenge dialog is opened from, so closing it can put focus back. A dialog
     * opened from somebody's page (`Store.showGameToInvite`) returns focus here too: it is where the
     * form lives on this screen. */
    private var challengeTrigger: Option[dom.html.Element] = None

    /* Closing the form abandons the invitation it was opened for. Left set, it would address the next
     * challenge offered from this screen to whoever was looked at before -- and nothing on the closed
     * form would say so. */
    private def closeChallengeForm(): Unit = {
        Store.invitee.set(None)
        Store.showChallengeForm.set(false)
        challengeTrigger.foreach(_.focus())
    }

    /* The offer form as a modal dialog: the same scrim and card the notification cascade uses, closed
     * by Escape, by a click on the backdrop, or by its own Close button. */
    private def challengeDialog(form: HtmlElement): HtmlElement =
        div(
          cls := "modal-scrim",
          Modal.inertBehind,
          onClick --> (event => if (event.target == event.currentTarget) closeChallengeForm()),
          form.amend(
            cls := "modal",
            role := "dialog",
            htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
            aria.labelledBy := "offer-challenge-heading",
            tabIndex := -1,
            inContext(node => onMountCallback(_ => node.ref.focus())),
            onKeyDown.filter(_.key == "Escape") --> { event =>
                event.stopPropagation()
                closeChallengeForm()
            },
            errorBanner,
            div(
              cls := "alternatives",
              button(tpe := "button", cls := "link", "Close", onClick --> (_ => closeChallengeForm()))
            )
          )
        )

    /** Who has entered a challenge, by name: shown on every row, since who you would be playing is most of what decides
      * whether to enter.
      */
    private def entrantsDetail(c: ChallengeSummary): Node =
        if (c.entries.isEmpty) div(cls := "detail", "nobody has entered yet")
        else
            div(
              cls := "detail",
              "entered: " + c.entries
                  .map(e => e.characterName.fold(e.nickname)(name => s"$name (${e.nickname})"))
                  .mkString(", ")
            )

    /** One of the reader's own open challenges: its terms, who is in it, and what its owner may do — start it once
      * enough have entered, cancel it, and change who is invited.
      */
    private[ui] def myChallengeRow(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        val least = math.max(2, game.requiredSeats)
        li(
          cls := "row",
          div(cls := "title", MatchSections.challengeTerms(t, c.entries.size)),
          entrantsDetail(c),
          parameterDetail(game, t.settings.gameSettings),
          if (t.settings.isPublic) div(cls := "detail", "public") else emptyNode,
          if (t.settings.isFriendly) friendlyLabel() else emptyNode,
          // A game's admin offering a match for others to play: said, because nothing else on the row
          // tells it from one they are seated in.
          if (!c.summary.isEntered) div(cls := "detail", "you are not playing in it") else emptyNode,
          // Starting is the owner's call, once enough have entered to fill the game's required roles: a
          // challenge that seats more may be worth starting without them. The players then choose their
          // roles, the strongest first.
          if (c.entries.size >= least)
              busyButton(label = "Start") { busy =>
                  Store.run(
                    action =
                        ApiClient.tournamentStep(gameId = game.gameId, tournamentId = t.tournamentId, step = "start"),
                    busy = busy,
                    onFailure = MatchSections.challengeStale(t)
                  )(_ => MatchSections.reloadAfterStart(game.gameId))
              }
          else div(cls := "detail", s"needs at least $least players to start"),
          busyButton(label = "Cancel") { busy =>
              Store.run(
                action = ApiClient.stopTournament(gameId = game.gameId, tournamentId = t.tournamentId),
                busy = busy,
                onFailure = MatchSections.challengeStale(t)
              )(_ => MatchSections.reloadChallengeSections(game.gameId))
          },
          invitedList(game, c),
          invitePanel(game, c)
        )
    }

    /** Who has been asked to this challenge already, and its owner's way of taking an invitation back. An invitation is
      * permission and nothing more, so one taken up stays here; revoking it then changes nothing about the entry.
      */
    private def invitedList(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        if (c.invited.isEmpty && c.invitedCharacters.isEmpty) div()
        else
            div(
              cls := "detail-panel",
              h4("Invited"),
              ul(
                c.invited.map { invitation =>
                    val entered = c.entries.exists(_.playerId.contains(invitation.playerId))
                    li(
                      cls := "row",
                      div(cls := "title", invitation.nickname),
                      div(cls := "detail", if (entered) "entered" else "not answered yet"),
                      if (entered) emptyNode
                      else
                          busyButton(label = "Revoke", classes = Some("link")) { busy =>
                              Store.run(
                                action = ApiClient.revokeTournamentInvitation(
                                  gameId = game.gameId,
                                  tournamentId = t.tournamentId,
                                  playerId = invitation.playerId
                                ),
                                busy = busy,
                                onFailure = MatchSections.challengeStale(t)
                              )(_ => Store.refreshChallenges(game.gameId))
                          }
                    )
                } ++ c.invitedCharacters.map { invitation =>
                    val id = invitation.character.characterId
                    val entered = c.entries.exists(_.characterId.contains(id))
                    li(
                      cls := "row",
                      div(cls := "title", invitation.character.name),
                      div(
                        cls := "detail",
                        invitation.owner.fold("")(o => s"${o.nickname}'s · ") +
                            (if (entered) "entered" else "not answered yet")
                      ),
                      if (entered) emptyNode
                      else
                          busyButton(label = "Revoke", classes = Some("link")) { busy =>
                              Store.run(
                                action = ApiClient.revokeTournamentCharacterInvitation(
                                  gameId = game.gameId,
                                  tournamentId = t.tournamentId,
                                  characterId = id
                                ),
                                busy = busy,
                                onFailure = MatchSections.challengeStale(t)
                              )(_ => Store.refreshChallenges(game.gameId))
                          }
                    )
                }
              )
            )
    }

    /** Inviting somebody to a challenge that already exists: find them, and ask.
      *
      * The other half of [[inviteControl]], which invites at the moment a challenge is created. This one exists because
      * an owner's mind changes after the fact — somebody who should have been asked in the first place — and because
      * the alternative is cancelling the challenge and offering it again.
      *
      * Its own search box rather than the one on "Find Players": that box and its results belong to that screen, and
      * are deliberately kept across a visit to a player's page, so borrowing them here would clear a search somebody
      * means to come back to. The panel is rebuilt, and so collapses, when the challenge list is re-read — which a
      * successful invitation causes, and which is the intended end of it: the invitation now shows in the list above.
      */
    private def invitePanel(game: Game, c: ChallengeSummary): HtmlElement = {
        val t = c.summary.tournament
        val open = Var(false)
        val prefix = Var("")
        val found = Var(Option.empty[PlayerSearchResult])
        // Nobody already invited or in it, and not its owner: none of them has anything to be asked to, so a name that
        // cannot be acted on is left out of the results. In a character game it is characters that are asked, so a
        // player stays findable, and the characters already asked or in it are what is left out.
        val characterGame = game.gameType == GameType.Character
        val alreadyAsked =
            if (characterGame) Set(t.owner)
            else c.invited.map(_.playerId).toSet ++ c.entries.flatMap(_.playerId) + t.owner
        val charactersAsked =
            c.invitedCharacters.map(_.character.characterId).toSet ++ c.entries.flatMap(_.characterId)

        div(
          button(
            tpe := "button",
            cls := "link",
            aria.expanded <-- open.signal,
            child.text <-- open.signal.map(if (_) "Close" else "Invite a player"),
            onClick --> { _ =>
                // Emptied on the way out, so re-opening it is a fresh question.
                if (open.now()) { prefix.set(""); found.set(None) }
                open.update(!_)
            }
          ),
          child <-- open.signal.map { showing =>
              if (!showing) emptyNode
              else
                  div(
                    cls := "detail-panel",
                    // How the search went, from a region mounted with the panel: the results list is built at the
                    // moment it has something to say, and a region nobody has seen yet has no change to report.
                    p(
                      cls := "sr-only",
                      role := "status",
                      aria.live := "polite",
                      child.text <-- found.signal.map {
                          case None => ""
                          case Some(result) =>
                              val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                              if (askable.isEmpty) "Nobody new by that name."
                              else if (askable.size == 1) "One player found."
                              else s"${askable.size} players found."
                      }
                    ),
                    field(
                      "Find a player",
                      input(tpe := "search", controlled(value <-- prefix.signal, onInput.mapToValue --> prefix))
                    ),
                    busyButton(label = "Search", disabledWhen = prefix.signal.map(_.trim.isEmpty)) { busy =>
                        // Guarded by the sign-in counter, because this writes into the store: an answer after a
                        // sign-out would teach the next player's session who the previous one was looking for.
                        val signIn = Store.currentSignIn
                        Store.run(ApiClient.searchPlayers(prefix.now().trim), busy) { result =>
                            if (Store.isStillSignedInAs(signIn)) {
                                Store.remember(result.players)
                                found.set(Some(result))
                            }
                        }
                    },
                    child <-- found.signal.map {
                        case None => emptyNode
                        case Some(result) =>
                            val askable = result.players.filterNot(p => alreadyAsked.contains(p.playerId))
                            if (askable.isEmpty)
                                p(
                                  cls := "empty",
                                  "Nobody new by that name. Anyone already invited is not listed again."
                                )
                            else
                                ul(
                                  askable.map(candidate =>
                                      if (characterGame)
                                          li(
                                            cls := "row",
                                            div(cls := "title", candidate.nickname),
                                            Players.characterInviteButtons(
                                              gameId = game.gameId,
                                              owner = candidate,
                                              exclude = charactersAsked
                                            ) { (ch, busy) =>
                                                Store.run(
                                                  action = ApiClient.inviteCharacterToTournament(
                                                    gameId = game.gameId,
                                                    tournamentId = t.tournamentId,
                                                    characterId = ch.characterId
                                                  ),
                                                  busy = busy,
                                                  onFailure = MatchSections.challengeStale(t)
                                                )(_ => Store.refreshChallenges(game.gameId))
                                            }
                                          )
                                      else
                                          li(
                                            cls := "row",
                                            div(cls := "title", candidate.nickname),
                                            busyButton(
                                              label = s"Invite ${candidate.nickname}",
                                              classes = Some("link")
                                            ) { busy =>
                                                Store.run(
                                                  action = ApiClient.inviteToTournament(
                                                    gameId = game.gameId,
                                                    tournamentId = t.tournamentId,
                                                    playerId = candidate.playerId
                                                  ),
                                                  busy = busy,
                                                  onFailure = MatchSections.challengeStale(t)
                                                )(_ => Store.refreshChallenges(game.gameId))
                                            }
                                          )
                                  )
                                )
                    }
                  )
          }
        )
    }

    /** A challenge somebody else is offering, and this player's way into it — or out of it, once they are in.
      *
      * Entering names nothing but, in a character game, the character: a challenge's players choose their roles once it
      * starts. A character already in it is not offered again, and a player with none left to enter is told so.
      */
    private def openChallengeRow(
        game: Game,
        c: ChallengeSummary,
        characters: Seq[Character[String]],
        me: Player
    ): HtmlElement = {
        val t = c.summary.tournament
        val characterGame = game.gameType == GameType.Character
        val mine = c.entries.find(_.playerId.contains(me.playerId))
        // Not already in it, and -- by invitation, in a character game -- invited: it is the character that is.
        val eligible = characters
            .filterNot(ch => c.entries.exists(_.characterId.contains(ch.characterId)))
            .filter(ch =>
                !t.settings.isInvitational || c.invitedCharacters.exists(_.character.characterId == ch.characterId) ||
                    !characterGame
            )
        val character = Var(eligible.headOption.map(_.characterId))
        val full = t.settings.maxEntrants.exists(c.entries.size >= _)

        li(
          cls := "row",
          div(cls := "title", s"${c.summary.ownerNickname}'s challenge"),
          div(cls := "detail", MatchSections.challengeTerms(t, c.entries.size)),
          entrantsDetail(c),
          parameterDetail(game, t.settings.gameSettings),
          if (t.settings.isFriendly) friendlyLabel() else emptyNode,
          if (c.summary.isInvited && mine.isEmpty) div(cls := "detail", "you were invited") else emptyNode,
          mine match {
              case Some(entry) =>
                  div(
                    div(cls := "detail", "you have entered"),
                    busyButton(label = "Withdraw", classes = Some("link")) { busy =>
                        Store.run(
                          action = ApiClient.withdrawFromTournament(
                            gameId = game.gameId,
                            tournamentId = t.tournamentId,
                            entrantId = entry.entrantId
                          ),
                          busy = busy,
                          onFailure = MatchSections.challengeStale(t)
                        )(_ => MatchSections.reloadChallengeSections(game.gameId))
                    }
                  )
              case None if full => div(cls := "detail", "it is full")
              case None if characterGame && characters.isEmpty =>
                  div(cls := "detail", "you need a character in this game to enter")
              case None if characterGame && eligible.isEmpty && t.settings.isInvitational =>
                  div(cls := "detail", "none of your characters is invited")
              case None if characterGame && eligible.isEmpty =>
                  div(cls := "detail", "your characters in this game have all entered it")
              case None =>
                  div(
                    // Shown even when there is only one, so the player can see who they are entering.
                    if (characterGame)
                        field(
                          "Enter as",
                          select(
                            onChange.mapToValue --> { raw =>
                                character.set(
                                  raw.toLongOption
                                      .map(CharacterId.apply)
                                      .filter(id => eligible.exists(_.characterId == id))
                                )
                            },
                            value <-- character.signal.map(_.map(_.value.toString).getOrElse("")),
                            eligible.map(ch => option(value := ch.characterId.value.toString, ch.name))
                          )
                        )
                    else emptyNode,
                    busyButton(label = "Enter", disabledWhen = character.signal.map(_.isEmpty && characterGame)) {
                        busy =>
                            Store.run(
                              action = ApiClient
                                  .enterTournament(
                                    gameId = game.gameId,
                                    tournamentId = t.tournamentId,
                                    characterId = character.now().filter(_ => characterGame)
                                  ),
                              busy = busy,
                              onFailure = MatchSections.challengeStale(t)
                            ) { _ =>
                                // Entering may have filled one that starts itself: the start's reloads cover both.
                                MatchSections.reloadAfterStart(game.gameId)
                            }
                    }
                  )
          }
        )
    }

    /** What was chosen for each of the game's parameters, read back out of `settings`, as the parameter's label and the
      * value chosen.
      */
    private def chosenParameters(game: Game, settings: String): Seq[(String, String)] = {
        val stored = ChallengeForm.storedChoices(settings)
        ChallengeForm.parameterChoices(game).flatMap(choice => stored.get(choice.name).map(choice.label -> _))
    }

    /** The terms a challenge was offered on beyond its clock — "rounds: 12" — so that whoever accepts it knows what
      * they are agreeing to. Nothing at all for a game with no parameters, or a challenge that chose none.
      */
    private def parameterDetail(game: Game, settings: String): Node =
        chosenParameters(game, settings) match {
            case Seq()  => emptyNode
            case chosen => div(cls := "detail", chosen.map((label, v) => s"$label: $v").mkString(" · "))
        }
}
