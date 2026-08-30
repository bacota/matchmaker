package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._

/** Entry point.
  *
  * The whole UI is one page with no router: every screen in `ui.txt` is a section of it, and the
  * only URL that ever matters is the one Cognito redirects back to.
  */
object Main {

  @JSExportTopLevel("main")
  def main(): Unit = {
    // Must run before anything reads the token: this may be the page load that carries an
    // authorization code back from the hosted sign-up or password-reset pages, and until it has
    // been redeemed there is no session. An ordinary load has no code and falls straight through.
    if (Config.current.headerAuth) {
      // Nothing to sign in to: the identity is whatever the config says. Straight to the app.
      Store.signedIn.set(true)
      Store.loadAll()
    } else
      Auth.completeSignIn() match {
        case Some(signIn) =>
          signIn.onComplete { outcome =>
            outcome.failed.foreach(Store.report)
            Store.signedIn.set(Auth.isSignedIn)
            if (Auth.isSignedIn) Store.loadAll()
          }
        case None =>
          Store.signedIn.set(Auth.isSignedIn)
          if (Auth.isSignedIn) Store.loadAll()
      }

    renderOnDomContentLoaded(dom.document.getElementById("app"), Views.app)
  }
}

object Views {

  def app: HtmlElement =
    div(
      cls := "app",
      header,
      child <-- Store.signedIn.signal.map(if (_) signedInBody else signedOutBody)
    )

  /** A button that starts a request, and says so while it is running.
    *
    * Every one of these calls the API, and some of those calls are slow enough that a click with
    * no visible answer reads as a button that did nothing — so the button disables itself and
    * shows a spinner until the request comes back. The handler is handed the flag to pass to
    * `Store.run`, which clears it however the request ends: a failure re-enables the button
    * rather than leaving it spinning on an answer that already arrived.
    *
    * `disabledWhen` is combined here rather than passed in as another `disabled` binding, since
    * two bindings writing the same property would fight over it.
    */
  private def busyButton(
      label: String,
      classes: Option[String] = None,
      disabledWhen: Signal[Boolean] = Val(false)
  )(action: Var[Boolean] => Unit): HtmlElement = {
    val busy = Var(false)

    button(
      classes.map(cls := _).getOrElse(emptyMod),
      disabled <-- disabledWhen.combineWith(busy.signal).map { case (blocked, waiting) => blocked || waiting },
      // Before the label rather than after it, so the label does not shift as the spinner appears.
      child <-- busy.signal.map(if (_) span(cls := "spinner", aria.hidden := true) else emptyNode),
      label,
      // The disabled binding above already refuses a second click; this covers the instant
      // between the click and the flag being seen.
      onClick --> (_ => if (!busy.now()) action(busy))
    )
  }

  // -------------------------------------------------------------------------
  // Chrome
  // -------------------------------------------------------------------------

  private def header: HtmlElement =
    div(
      cls := "header",
      h1("Matchmaker"),
      // Unmissable, because a page that looks like the real thing but authenticates nobody is
      // exactly the page you do not want to be confused about.
      if (Config.current.headerAuth)
        div(cls := "banner", s"local mode — signed in as ${Config.current.localExternalId}, no authentication")
      else emptyNode,
      child <-- Store.player.signal.map {
        case Store.PlayerState.Registered(player) =>
          div(
            cls := "who",
            span(player.nickname),
            if (Config.current.headerAuth) emptyNode
            else button(cls := "link", "Sign out", onClick --> (_ => Store.signOut()))
          )
        case _ => emptyNode
      },
      child <-- Store.error.signal.map {
        case Some(message) =>
          div(
            cls := "error",
            span(message),
            button(cls := "link", "Dismiss", onClick --> (_ => Store.error.set(None)))
          )
        case None => emptyNode
      }
    )

  /** The sign-in form itself, not a button that navigates to one: signing in happens on this
    * page now, so that the password is asked for first. Sign-up and password reset are still
    * links out to the hosted pages, from inside `SignIn.view`.
    */
  private def signedOutBody: HtmlElement = SignIn.view

  /** Either registration or the application proper, decided by whether this Cognito identity has
    * a player. `ui.txt`: self-registration in Cognito triggers a player set-up.
    */
  private def signedInBody: HtmlElement =
    div(
      child <-- Store.player.signal.map {
        case Store.PlayerState.Loading             => p(cls := "loading", "Loading…")
        case Store.PlayerState.Unregistered        => registration
        case Store.PlayerState.Registered(_)       => home
        case Store.PlayerState.Unavailable(reason) => unavailable(reason)
      }
    )

  /** The API could not be reached, or answered in a way that says nothing about this account.
    *
    * Deliberately not the registration form: the player may well exist, and inviting them to
    * register again would be wrong as well as confusing. A retry is all this can honestly offer.
    */
  private def unavailable(reason: String): HtmlElement =
    div(
      cls := "card",
      h2("Could not load your account"),
      p(reason),
      p(cls := "detail", "This is a problem reaching the server, not a problem with your sign-in."),
      button("Try again", onClick --> (_ => Store.loadAll()))
    )

  private def registration: HtmlElement = {
    val nickname = Var("")

    div(
      cls := "card",
      h2("Choose a nickname"),
      p("You are signed in, but you do not have a player yet. Your nickname is what other players see."),
      input(
        placeholder := "nickname",
        controlled(value <-- nickname.signal, onInput.mapToValue --> nickname)
      ),
      busyButton("Create player", disabledWhen = nickname.signal.map(_.trim.isEmpty)) { busy =>
        Store.run(ApiClient.register(nickname.now().trim), busy) { player =>
          Store.player.set(Store.PlayerState.Registered(player))
          Store.refreshMatches()
          Store.refreshGames()
        }
      }
    )
  }

  private def home: HtmlElement =
    div(
      dueSection,
      myMatchesSection,
      gamesSection,
      completedSection
    )

  // -------------------------------------------------------------------------
  // Matches
  // -------------------------------------------------------------------------

  /** "List of all matches a player has a turn due" — the first thing in `ui.txt`, and the only
    * list shown expanded from the start, because it is the one that needs acting on.
    */
  private def dueSection: HtmlElement =
    sectionTag(
      h2("Your turn"),
      child <-- Store.due.signal.map {
        case Nil     => p(cls := "empty", "Nothing is waiting on you.")
        case matches => ul(matches.map(matchRow(_, showDue = true)))
      }
    )

  private def myMatchesSection: HtmlElement =
    sectionTag(
      h2(
        button(
          cls := "toggle",
          child.text <-- Store.showActive.signal.map(if (_) "▾" else "▸"),
          " Your matches",
          onClick --> (_ => Store.showActive.update(!_))
        )
      ),
      child <-- Store.showActive.signal.map {
        case false => emptyNode
        case true =>
          div(
            child <-- Store.active.signal.map {
              case Nil     => p(cls := "empty", "You are not in any matches.")
              case matches => ul(matches.map(matchRow(_, showDue = false)))
            },
            pendingAcceptances
          )
      }
    )

  /** "Also shows pending acceptances with option to back out."
    *
    * These are challenges the player has accepted that have not yet filled up into a match, which
    * is why they appear beside the matches rather than in them. The game name is looked up from
    * the games list; an acceptance whose game is not in that list — an inactive game, say — still
    * shows, named by its id rather than dropped.
    */
  private def pendingAcceptances: HtmlElement =
    div(
      h3("Waiting to start"),
      child <-- Store.acceptances.signal.combineWith(Store.games.signal).map {
        case (Nil, _) => p(cls := "empty", "You have not accepted anything that is still waiting.")
        case (acceptances, games) =>
          val namesById = games.map(game => game.gameId -> game.name).toMap
          ul(acceptances.map(pending => acceptanceRow(pending, namesById.get(pending.acceptance.gameId))))
      }
    )

  private def acceptanceRow(pending: PendingAcceptance, gameName: Option[String]): HtmlElement = {
    val acceptance = pending.acceptance

    li(
      cls := "row",
      div(cls := "title", gameName.getOrElse(s"game ${acceptance.gameId.value}")),
      // Creating a challenge accepts it, so the challenger has a row here like everyone else —
      // and this is where they are looking while they wait for it to fill up. Offering the Start
      // here as well as on the game's own challenge list saves opening the game to find the same
      // button. Starting stays the challenger's call, as it is there.
      //
      // Both facts come from the acceptances response: whether every required role is taken is
      // the server's answer, not re-derived from roles here, so this list needs nothing loaded
      // per game to draw itself.
      child <-- currentPlayer.map {
        case Some(player) if pending.readyToStart && pending.challenger == player.playerId =>
          div(
            div(cls := "detail", "every role is taken — ready to start"),
            busyButton("Start") { busy =>
              Store.run(ApiClient.startChallenge(acceptance.gameId, acceptance.challengeId), busy) { _ =>
                Store.refreshMatches()
                // The challenge is no longer open, so the game's list is stale if it is on screen.
                if (Store.expandedGames.now().contains(acceptance.gameId))
                  Store.refreshChallenges(acceptance.gameId)
              }
            }
          )
        // Full, but somebody else offered it: nothing for this player to do but wait, which is
        // worth saying rather than leaving them looking for a button that is not theirs.
        case _ if pending.readyToStart =>
          div(cls := "detail", "every role is taken — waiting for the challenger to start it")
        case _ => div(cls := "detail", "accepted, waiting for the other players")
      },
      child <-- currentPlayer.map {
        case None => emptyNode
        case Some(player) =>
          busyButton("Back out", classes = Some("link")) { busy =>
            Store.run(ApiClient.withdraw(acceptance.gameId, acceptance.challengeId, player.playerId), busy) { _ =>
              Store.refreshMatches()
              // The challenge is open again, so the game's list is stale if it is on screen.
              if (Store.expandedGames.now().contains(acceptance.gameId))
                Store.refreshChallenges(acceptance.gameId)
            }
          }
      }
    )
  }

  private def completedSection: HtmlElement =
    sectionTag(
      h2(
        button(
          cls := "toggle",
          child.text <-- Store.showCompleted.signal.map(if (_) "▾" else "▸"),
          " Completed matches",
          onClick --> (_ => Store.showCompleted.update(!_))
        )
      ),
      child <-- Store.showCompleted.signal.map {
        case false => emptyNode
        case true =>
          div(
            child <-- Store.completed.signal.map {
              case Nil     => p(cls := "empty", "Nothing finished yet.")
              case matches => ul(matches.map(matchRow(_, showDue = false)))
            }
          )
      }
    )

  private def matchRow(summary: MatchSummary, showDue: Boolean): HtmlElement =
    li(
      cls := "row",
      div(cls := "title", summary.gameName),
      div(cls := "detail", summary.description),
      if (showDue) summary.due.map(when => div(cls := "due", s"due ${Format.instant(when)}")).getOrElse(emptyNode)
      else emptyNode,
      // `pending` marks a participation not yet settled — the player has accepted, but the match
      // has not started. `ui.txt` wants those visible in this list.
      if (summary.pending) div(cls := "pending", "awaiting other players") else emptyNode,
      // A cancelled match is over and has no result, so it sits in the completed list; without
      // this it would be indistinguishable from one that was played to an end.
      if (summary.cancelled) div(cls := "detail", "cancelled by its creator") else emptyNode,
      // The play url lives on the match rather than the summary, and is the game engine's, not
      // matchmaker's — so it is fetched when asked for and opened directly.
      busyButton("Play", classes = Some("link")) { busy =>
        Store.run(ApiClient.matchDetail(summary.gameId, summary.matchId), busy) { m =>
          m.playUrl match {
            case Some(url) => dom.window.open(url, "_blank", "noopener,noreferrer")
            case None      => Store.error.set(Some("This match has no play url yet."))
          }
        }
      },
      // Step 4 of the engine flow: any participant may ask matchmaker to re-check with the
      // engine, which is what recovers from a callback that never arrived.
      busyButton("Refresh", classes = Some("link")) { busy =>
        Store.run(ApiClient.refreshMatch(summary.gameId, summary.matchId), busy)(_ => Store.refreshMatches())
      },
      // Only the creator's, and only while there is still something to call off. The engine is
      // not told — its board stays playable — so the confirmation says what actually happens.
      if (summary.isCreator && !summary.completed && !summary.cancelled)
        busyButton("Cancel", classes = Some("link")) { busy =>
          if (dom.window.confirm("Cancel this match? It will stop counting here, but the game board stays open."))
            Store.run(ApiClient.cancelMatch(summary.gameId, summary.matchId), busy)(_ => Store.refreshMatches())
        }
      else emptyNode
    )

  // -------------------------------------------------------------------------
  // Games and challenges
  // -------------------------------------------------------------------------

  private def gamesSection: HtmlElement =
    sectionTag(
      h2("Games"),
      child <-- Store.games.signal.map {
        case Nil   => p(cls := "empty", "No games are set up yet.")
        case games => ul(games.map(gameRow))
      },
      // Only for admins, because only an admin can create one — the server rejects anyone else
      // with a 403, and offering a button that always fails would be worse than not offering it.
      child <-- currentPlayer.map {
        case Some(player) if player.isAdmin => newGameSection
        case _                              => emptyNode
      }
    )

  /** "An admin user should be able to create a new game."
    *
    * The form asks for everything a game is: its own fields, the roles a player can be seated in,
    * and the parameters the game engine is configured with. Roles are not optional extras — every
    * acceptance names one, so a game with none is a game nothing can be offered for, and the
    * server refuses it.
    */
  private def newGameSection: HtmlElement =
    div(
      h3(
        button(
          cls := "toggle",
          child.text <-- Store.showNewGame.signal.map(if (_) "▾" else "▸"),
          " Add a game",
          onClick --> (_ => Store.showNewGame.update(!_))
        )
      ),
      child <-- Store.showNewGame.signal.map(if (_) newGameForm else emptyNode)
    )

  /* One row of the role or parameter editor, held as Vars rather than plain values so that
   * typing in a row does not rebuild the list and take the cursor with it: the rendered children
   * change only when a row is added or removed. */
  /* A role draft carries the id of the role it edits, because that is what tells an edit from an
   * addition on the way back — and an existing role can never be removed, only renamed or made
   * optional. Parameters carry no id: they are replaced wholesale, and deleting one is allowed. */
  private case class RoleDraft(gameRoleId: GameRoleId, name: Var[String], optional: Var[Boolean])
  private case class ParameterDraft(name: Var[String], values: Var[String], default: Var[String])

  private def emptyRole: RoleDraft = RoleDraft(GameRoleId.unassigned, Var(""), Var(false))
  private def emptyParameter: ParameterDraft = ParameterDraft(Var(""), Var(""), Var(""))

  private def draftOf(role: GameRole): RoleDraft =
    RoleDraft(role.gameRoleId, Var(role.name), Var(role.optional))

  private def draftOf(parameter: GameParameter[String]): ParameterDraft =
    ParameterDraft(
      Var(parameter.name),
      Var(parameter.values.map(_.value).mkString(", ")),
      Var(parameter.defaultValue.getOrElse(""))
    )

  /** The possible values of a parameter, as typed: one comma-separated list, because a parameter
    * with three values is a sentence an admin can type and a list of three inputs is not.
    */
  private def splitValues(raw: String): Seq[String] =
    raw.split(',').map(_.trim).filter(_.nonEmpty).toSeq.distinct

  private def roleEditor(roles: Var[List[RoleDraft]]): HtmlElement =
    div(
      h4("Roles"),
      p(
        cls := "detail",
        "Every seat in a match names a role, so a game needs at least one. An optional role is one " +
          "a match does not wait to see filled before it can start. A role that already exists can " +
          "be renamed but not removed — acceptances and played matches name it, so retire one by " +
          "making it optional."
      ),
      children <-- roles.signal.map(_.map { draft =>
        div(
          cls := "row",
          input(placeholder := "role name", controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)),
          label(
            input(
              tpe := "checkbox",
              controlled(checked <-- draft.optional.signal, onClick.mapToChecked --> draft.optional)
            ),
            "optional"
          ),
          // A role that exists cannot be removed: acceptances and played matches name it. The
          // server refuses it too — this is why the button is not there to press.
          if (draft.gameRoleId == GameRoleId.unassigned)
            button(cls := "link", "Remove", onClick --> (_ => roles.update(_.filterNot(_ eq draft))))
          else emptyNode
        )
      }),
      button(cls := "link", "Add a role", onClick --> (_ => roles.update(_ :+ emptyRole)))
    )

  private def parameterEditor(parameters: Var[List[ParameterDraft]]): HtmlElement =
    div(
      h4("Parameters"),
      p(
        cls := "detail",
        "How the game engine is configured when a match is created. A parameter's default has to " +
          "be one of its values, and a game may have none at all."
      ),
      children <-- parameters.signal.map(_.map { draft =>
        div(
          cls := "row",
          input(placeholder := "parameter name", controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)),
          input(
            placeholder := "values, comma separated",
            controlled(value <-- draft.values.signal, onInput.mapToValue --> draft.values)
          ),
          input(placeholder := "default value", controlled(value <-- draft.default.signal, onInput.mapToValue --> draft.default)),
          button(cls := "link", "Remove", onClick --> (_ => parameters.update(_.filterNot(_ eq draft))))
        )
      }),
      button(cls := "link", "Add a parameter", onClick --> (_ => parameters.update(_ :+ emptyParameter)))
    )

  /** The drafted roles as the model, or the first thing wrong with them.
    *
    * The same rules the server checks, checked here so that a typo is answered by the form rather
    * than by a round trip — the server is still the one that decides, since nothing stops a
    * request being made without this form.
    */
  private def rolesOf(drafts: List[RoleDraft]): Either[String, Seq[GameRole]] = {
    val all = drafts.map(d => (d.gameRoleId, d.name.now().trim, d.optional.now()))
    // A blank new row is one the admin added and did not fill in, and is dropped. A blank
    // existing row is a role whose name has been cleared -- dropping that would ask the server to
    // delete a role, which it refuses, so it is answered here as what it is.
    val (blank, named) = all.partition(_._2.isEmpty)
    if (blank.exists(_._1 != GameRoleId.unassigned)) Left("A role that already exists cannot be left without a name.")
    else if (named.isEmpty) Left("A game needs at least one role: every player accepting a challenge takes one.")
    else if (named.map(_._2).distinct.sizeIs != named.size) Left("Two roles cannot have the same name.")
    else Right(named.map((id, name, optional) => GameRole(id, GameId.unassigned, name, optional)))
  }

  private def parametersOf(drafts: List[ParameterDraft]): Either[String, Seq[GameParameter[String]]] = {
    val named = drafts
      .map(d => (d.name.now().trim, splitValues(d.values.now()), d.default.now().trim))
      .filter(_._1.nonEmpty)
    val badDefault = named.find((_, values, default) => default.nonEmpty && !values.contains(default))
    if (named.map(_._1).distinct.sizeIs != named.size) Left("Two parameters cannot have the same name.")
    else
      badDefault match {
        case Some((name, _, default)) =>
          Left(s"Parameter '$name' has default '$default', which is not one of its values.")
        case None =>
          Right(named.map { (name, values, default) =>
            GameParameter[String](
              GameId.unassigned,
              GameParameterId(0),
              name,
              Option(default).filter(_.nonEmpty),
              values.map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v))
            )
          })
      }
  }

  private def newGameForm: HtmlElement = gameForm(None)

  /** The admin's game form, for creating one (`existing` is None) or editing one.
    *
    * The two are the same form because a game is the same thing either way, and every field is
    * editable in both: name, description, url, whether characters are required, the roles and
    * the parameters. There is no player count to set — a game's roles are its seats, so adding
    * one in [[roleEditor]] is how a game gets bigger. What edit cannot do is delete a role.
    *
    * Two fields are never shown. `externalId` is the game's own credential: kept as it is when
    * editing, generated when creating, and in neither case something to type. `active` is not on
    * this form at all, so editing preserves it and creating sets it true.
    */
  private def gameForm(existing: Option[Game]): HtmlElement = {
    val name = Var(existing.map(_.name).getOrElse(""))
    val description = Var(existing.map(_.description).getOrElse(""))
    val url = Var(existing.map(_.url).getOrElse(""))
    // Plain by default: requiring characters is the additional commitment, so it is the box an
    // admin ticks rather than the one they have to remember to untick.
    val gameType: Var[GameType] = Var(existing.map(_.gameType).getOrElse(GameType.Plain))
    // A new game starts with one empty role, because it cannot be created without one, and no
    // parameters, because plenty of games have none. An existing one starts with what it has.
    val roles = Var(existing.map(_.roles.map(draftOf).toList).getOrElse(List(emptyRole)))
    val parameters = Var(
      existing.map(_.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList).getOrElse(Nil)
    )

    div(
      cls := "card",
      input(placeholder := "name", controlled(value <-- name.signal, onInput.mapToValue --> name)),
      input(
        placeholder := "description",
        controlled(value <-- description.signal, onInput.mapToValue --> description)
      ),
      input(placeholder := "url", controlled(value <-- url.signal, onInput.mapToValue --> url)),
      label(
        "Requires characters ",
        input(
          tpe := "checkbox",
          checked <-- gameType.signal.map(_ == GameType.Character),
          onClick --> (_ => gameType.update(gt => if (gt == GameType.Character) GameType.Plain else GameType.Character))
        )
      ),
      roleEditor(roles),
      parameterEditor(parameters),
      busyButton(
        if (existing.isDefined) "Save changes" else "Create game",
        disabledWhen = name.signal.map(_.trim.isEmpty)
      ) { busy =>
        val drafted = for {
          roleModels <- rolesOf(roles.now())
          parameterModels <- parametersOf(parameters.now())
        } yield (roleModels, parameterModels)

        drafted match {
          case Left(problem) => Store.error.set(Some(problem))
          case Right((roleModels, parameterModels)) =>
          val game = Game(
            // Unassigned means create and the server assigns the real id — the same sentinel
            // the challenge form uses; a real id means update that game.
            gameId = existing.map(_.gameId).getOrElse(GameId.unassigned),
            gameType = gameType.now(),
            name = name.now().trim,
            description = description.now().trim,
            url = url.now().trim,
            // A game nobody can see is not what "create a game" means, and `refreshGames` only
            // asks for active ones — creating it inactive would look like the button did nothing.
            // Editing leaves it as it was, since this form has no control for it.
            active = existing.map(_.active).getOrElse(true),
            roles = roleModels,
            parameters = parameterModels,
            // The game's own shared secret, used to authorize requests the game makes on its own
            // behalf. Generated rather than typed: it is a credential, and one an admin inventing
            // it by hand would invent badly. An edit keeps the one the game already has —
            // regenerating it would silently lock the game engine out.
            externalId = existing.map(_.externalId).getOrElse(Pkce.newSecret())
          )

          Store.run(ApiClient.createGame(game), busy) { saved =>
            if (existing.isEmpty) {
              name.set("")
              description.set("")
              url.set("")
              roles.set(List(emptyRole))
              parameters.set(Nil)
              Store.showNewGame.set(false)
            } else {
              // Re-drafted from what came back, so that roles added by this save carry the ids
              // the insert gave them — without which saving twice would ask to add them again.
              roles.set(saved.roles.map(draftOf).toList)
              parameters.set(saved.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList)
              Store.editingGame.set(None)
            }
            Store.refreshGames()
          }
        }
      }
    )
  }

  private def gameRow(game: Game): HtmlElement =
    li(
      cls := "row",
      div(
        button(
          cls := "toggle",
          child.text <-- Store.expandedGames.signal.map(expanded => if (expanded.contains(game.gameId)) "▾" else "▸"),
          s" ${game.name}",
          onClick --> (_ => Store.toggleGame(game.gameId))
        )
      ),
      div(cls := "detail", game.description),
      child <-- Store.expandedGames.signal.map { expanded =>
        if (expanded.contains(game.gameId)) gameDetail(game) else emptyNode
      }
    )

  private def gameDetail(game: Game): HtmlElement =
    div(
      cls := "detail-panel",
      // Only for admins, for the same reason the create form is: the server answers anyone else
      // with a 403, and a button that always fails is worse than no button.
      child <-- currentPlayer.combineWith(Store.editingGame.signal).map {
        case (Some(player), editing) if player.isAdmin =>
          div(
            button(
              cls := "link",
              if (editing.contains(game.gameId)) "Done editing" else "Edit game",
              onClick --> { _ =>
                Store.editingGame.update(current => if (current.contains(game.gameId)) None else Some(game.gameId))
              }
            ),
            // Keyed on the game so that the form is rebuilt when a different game is opened:
            // its fields are initialised from `game` once, not bound to it.
            if (editing.contains(game.gameId)) gameForm(Some(game)) else emptyNode
          )
        case _ => emptyNode
      },
      // A 'P'-type game never needs a character, so its challenge panel doesn't wait on
      // Store.charactersByGame at all.
      child <-- (if (game.gameType == GameType.Plain)
                   currentPlayer.map {
                     case None         => p(cls := "empty", "Loading…")
                     case Some(player) => challengePanel(game, player, None)
                   }
                 else
                   currentPlayer.combineWith(Store.charactersByGame.signal).map {
                     case (None, _) => p(cls := "empty", "Loading…")
                     case (Some(player), byGame) =>
                       byGame.get(game.gameId) match {
                         case None              => p(cls := "empty", "Loading…")
                         // A character is needed before this player can either offer or accept a
                         // challenge, so there is nothing to show until there is one.
                         case Some(Nil)         => characterForm(game, player)
                         case Some(characters)  => challengePanel(game, player, Some(characters.head.characterId))
                       }
                   })
    )

  private def characterForm(game: Game, player: Player): HtmlElement = {
    val name = Var("")
    val description = Var("")

    div(
      cls := "card",
      h3(s"Create your character for ${game.name}"),
      p("You need a character in this game before you can offer or accept a challenge."),
      input(placeholder := "name", controlled(value <-- name.signal, onInput.mapToValue --> name)),
      input(
        placeholder := "description",
        controlled(value <-- description.signal, onInput.mapToValue --> description)
      ),
      busyButton("Create character", disabledWhen = name.signal.map(_.trim.isEmpty)) { busy =>
        val created =
          ApiClient.createCharacter(game.gameId, name.now().trim, description.now().trim, player.externalId)
        Store.run(created, busy)(_ => Store.refreshCharacters(game.gameId))
      }
    )
  }

  private def challengePanel(game: Game, player: Player, characterId: Option[CharacterId]): HtmlElement =
    div(
      child <-- Store.challengesByGame.signal.combineWith(Store.acceptances.signal).map { (byGame, acceptances) =>
        byGame.get(game.gameId) match {
          case None => p(cls := "empty", "Loading challenges…")
          case Some(challenges) =>
            // `ui.txt` asks for the player's own challenges in a separate list, because what you
            // can do with them is different: delete yours, accept someone else's.
            val (mine, others) = challenges.partition(_.challenge.challenger == player.playerId)
            // A challenge this player has already accepted stays open until it fills up, but
            // offering it again would only produce a duplicate acceptance the service rejects —
            // so it is dropped from the list rather than shown with an Accept that cannot work.
            val accepted = acceptances.map(a => (a.acceptance.gameId, a.acceptance.challengeId)).toSet
            val available = others.filterNot(c => accepted.contains((c.challenge.gameId, c.challenge.challengeId)))
            div(
              h3("Your open challenges"),
              if (mine.isEmpty) p(cls := "empty", "You have none open.")
              else ul(mine.map(myChallengeRow(game, _))),
              h3("Open challenges"),
              if (available.isEmpty) p(cls := "empty", "Nobody is waiting for an opponent.")
              else ul(available.map(openChallengeRow(game, _, characterId))),
              newChallengeForm(game, player, characterId)
            )
        }
      }
    )

  private def myChallengeRow(game: Game, summary: OpenChallengeSummary): HtmlElement = {
    val challenge = summary.challenge
    li(
      cls := "row",
      div(cls := "title", challenge.message),
      div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
      if (challenge.isPublic) div(cls := "detail", "public") else emptyNode,
      // Starting is the challenger's call rather than something that happens on the last
      // acceptance: a game whose remaining roles are optional may be worth starting without
      // them. With a required role nobody has taken the server refuses it outright — so there is
      // no point offering the button, and what the challenger is waiting for is said beside it
      // instead: somebody to play the roles still going begging.
      if (unfilledRoles(game, summary).isEmpty)
        busyButton("Start") { busy =>
          Store.run(ApiClient.startChallenge(game.gameId, challenge.challengeId), busy) { _ =>
            Store.refreshChallenges(game.gameId)
            Store.refreshMatches()
          }
        }
      else div(cls := "detail", s"waiting for ${unfilledRoles(game, summary).map(_.name).mkString(", ")}"),
      busyButton("Delete") { busy =>
        Store.run(ApiClient.deleteChallenge(game.gameId, challenge.challengeId), busy)(_ =>
          Store.refreshChallenges(game.gameId)
        )
      }
    )
  }

  private def openChallengeRow(game: Game, summary: OpenChallengeSummary, characterId: Option[CharacterId]): HtmlElement = {
    val challenge = summary.challenge
    // Only the roles nobody has claimed yet: accepting as a taken role is refused by the server,
    // and there is no reason to offer a choice that cannot work. A challenge with none left is
    // one that is full, and gets no Accept at all.
    val free = freeRoles(game, summary)
    val role = Var(free.headOption.map(_.gameRoleId))
    li(
      cls := "row",
      div(cls := "title", challenge.message),
      div(cls := "detail", s"${summary.acceptances} of ${game.roles.size} roles taken"),
      roleSelect(free, role),
      if (free.isEmpty) div(cls := "detail", "every role is taken")
      else
        busyButton("Accept") { busy =>
          val chosen = role.now().getOrElse(free.head.gameRoleId)
          Store.run(ApiClient.accept(game.gameId, challenge.challengeId, characterId, chosen), busy) { _ =>
            // Accepting may complete the challenge into a match, which changes the match lists
            // as well as this one, so both are reloaded.
            Store.refreshChallenges(game.gameId)
            Store.refreshMatches()
          }
        }
    )
  }

  /** The roles of `game` that no acceptance of `summary` has claimed yet. */
  private def freeRoles(game: Game, summary: OpenChallengeSummary): Seq[GameRole] =
    game.roles.filterNot(r => summary.takenRoles.contains(r.gameRoleId))

  /** The roles a start is still waiting for: required, and unclaimed. Optional roles are exactly
    * the ones a match need not wait for, so they are not counted here even when free.
    */
  private def unfilledRoles(game: Game, summary: OpenChallengeSummary): Seq[GameRole] =
    freeRoles(game, summary).filterNot(_.optional)

  /** A picker for the role a player will play, which matchmaker passes on to the game engine.
    *
    * `choices` are the roles still available. There is no "any role" entry: every seat names a
    * role, so the first available one stands pre-selected and the picker only changes which.
    */
  private def roleSelect(choices: Seq[GameRole], selected: Var[Option[GameRoleId]]): Node =
    if (choices.isEmpty) emptyNode
    else
      select(
        onChange.mapToValue --> { raw =>
          selected.set(raw.toIntOption.map(GameRoleId.apply).filter(id => choices.exists(_.gameRoleId == id)))
        },
        value <-- selected.signal.map(_.map(_.value.toString).getOrElse("")),
        choices.map(r => option(value := r.gameRoleId.value.toString, r.name))
      )

  private def newChallengeForm(game: Game, player: Player, characterId: Option[CharacterId]): HtmlElement = {
    val message = Var("")
    val isPublic = Var(false)
    // A challenge is its challenger's own acceptance, so it names a role like any other. Nothing
    // has been claimed yet, so every role of the game is on offer and the first stands selected.
    val role = Var(game.roles.headOption.map(_.gameRoleId))

    div(
      cls := "card",
      h3("Offer a challenge"),
      input(
        placeholder := "message",
        controlled(value <-- message.signal, onInput.mapToValue --> message)
      ),
      roleSelect(game.roles, role),
      // Public means anyone may watch the match, which the game engine implements by issuing a
      // url that needs no sign-in. It is decided here because it is a property of the game being
      // offered, not of any one player's part in it.
      label(
        input(
          tpe := "checkbox",
          controlled(checked <-- isPublic.signal, onClick.mapToChecked --> isPublic)
        ),
        "anyone may watch"
      ),
      busyButton(
        "Create challenge",
        // A game with no roles at all has nothing an acceptance could name, so no challenge for
        // it can be created. The server refuses one; this keeps the button from offering it.
        disabledWhen = message.signal.combineWith(role.signal).map { case (m, r) => m.trim.isEmpty || r.isEmpty }
        // `foreach` rather than a fallback role: with no role there is no challenge to make, and
        // the disabled button above is what keeps that from being reachable.
      ) { busy =>
        role.now().foreach { chosen =>
          // The server assigns the id; this is the same unassigned-sentinel convention the
          // service layer uses on create.
          val challenge: OpenChallenge = characterId match {
            case Some(cid) =>
              CharacterOpenChallenge(
                challengeId = ChallengeId(0),
                challenger = player.playerId,
                message = message.now().trim,
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                characterId = cid,
                isPublic = isPublic.now(),
                gameRoleId = chosen
              )
            case None =>
              PlainOpenChallenge(
                challengeId = ChallengeId(0),
                challenger = player.playerId,
                message = message.now().trim,
                start = None,
                timeLimit = None,
                settings = "{}",
                gameId = game.gameId,
                isPublic = isPublic.now(),
                gameRoleId = chosen
              )
          }

          Store.run(ApiClient.createChallenge(challenge), busy) { _ =>
            message.set("")
            Store.refreshChallenges(game.gameId)
          }
        }
      }
    )
  }

  private def currentPlayer: Signal[Option[Player]] = Store.currentPlayer
}

/** Formatting that has to be readable rather than exact. */
object Format {

  /** `Instant.toString` is ISO-8601 in UTC, which is precise and unpleasant to read. This trims
    * it to the minute and marks it as UTC rather than pretending to know the user's zone —
    * Scala.js has no time-zone database unless one is bundled, and a wrong local time is worse
    * than an explicit UTC one.
    */
  def instant(value: java.time.Instant): String =
    value.toString.replace("T", " ").takeWhile(_ != '.').stripSuffix("Z") + " UTC"
}
