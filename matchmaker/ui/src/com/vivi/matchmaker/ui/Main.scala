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
    // Must run before anything reads the token: this is the page load that carries the
    // authorization code, and until it has been redeemed there is no session.
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
            else button(cls := "link", "Sign out", onClick --> (_ => Auth.signOut()))
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

  private def signedOutBody: HtmlElement =
    div(
      cls := "card",
      p("Sign in to see your matches. New players can create an account from the same page."),
      button(
        "Sign in",
        // Navigates away, so there is nothing to do on success; only a failure to *start* the
        // flow (no crypto.subtle, say) has anywhere to be reported.
        onClick --> (_ => Auth.signIn().failed.foreach(Store.report))
      )
    )

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
      button(
        "Create player",
        disabled <-- nickname.signal.map(_.trim.isEmpty),
        onClick --> { _ =>
          Store.run(ApiClient.register(nickname.now().trim)) { player =>
            Store.player.set(Store.PlayerState.Registered(player))
            Store.refreshMatches()
            Store.refreshGames()
          }
        }
      )
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
          ul(acceptances.map(acceptance => acceptanceRow(acceptance, namesById.get(acceptance.gameId))))
      }
    )

  private def acceptanceRow(acceptance: Acceptance, gameName: Option[String]): HtmlElement =
    li(
      cls := "row",
      div(cls := "title", gameName.getOrElse(s"game ${acceptance.gameId.value}")),
      div(cls := "detail", "accepted, waiting for the other players"),
      child <-- currentPlayer.map {
        case None => emptyNode
        case Some(player) =>
          button(
            cls := "link",
            "Back out",
            onClick --> { _ =>
              Store.run(ApiClient.withdraw(acceptance.challengeId, player.playerId)) { _ =>
                Store.refreshMatches()
                // The challenge is open again, so the game's list is stale if it is on screen.
                if (Store.expandedGames.now().contains(acceptance.gameId))
                  Store.refreshChallenges(acceptance.gameId)
              }
            }
          )
      }
    )

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
      if (summary.pending) div(cls := "pending", "awaiting other players") else emptyNode
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
      }
    )

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
      child <-- currentPlayer.combineWith(Store.charactersByGame.signal).map {
        case (None, _) => p(cls := "empty", "Loading…")
        case (Some(player), byGame) =>
          byGame.get(game.gameId) match {
            case None                       => p(cls := "empty", "Loading…")
            // A character is needed before this player can either offer or accept a challenge, so
            // there is nothing to show until there is one.
            case Some(Nil)                  => characterForm(game, player)
            case Some(characters)           => challengePanel(game, player, characters.head.characterId)
          }
      }
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
      button(
        "Create character",
        disabled <-- name.signal.map(_.trim.isEmpty),
        onClick --> { _ =>
          val created =
            ApiClient.createCharacter(game.gameId, name.now().trim, description.now().trim, player.externalId)
          Store.run(created)(_ => Store.refreshCharacters(game.gameId))
        }
      )
    )
  }

  private def challengePanel(game: Game, player: Player, characterId: CharacterId): HtmlElement =
    div(
      child <-- Store.challengesByGame.signal.map { byGame =>
        byGame.get(game.gameId) match {
          case None => p(cls := "empty", "Loading challenges…")
          case Some(challenges) =>
            // `ui.txt` asks for the player's own challenges in a separate list, because what you
            // can do with them is different: delete yours, accept someone else's.
            val (mine, others) = challenges.partition(_.challenger == player.playerId)
            div(
              h3("Your open challenges"),
              if (mine.isEmpty) p(cls := "empty", "You have none open.")
              else ul(mine.map(myChallengeRow(game, _))),
              h3("Open challenges"),
              if (others.isEmpty) p(cls := "empty", "Nobody is waiting for an opponent.")
              else ul(others.map(openChallengeRow(game, _, characterId))),
              newChallengeForm(game, player, characterId)
            )
        }
      }
    )

  private def myChallengeRow(game: Game, challenge: OpenChallenge): HtmlElement =
    li(
      cls := "row",
      div(cls := "title", challenge.message),
      div(cls := "detail", s"${challenge.numberOfPlayers} players"),
      button(
        "Delete",
        onClick --> { _ =>
          Store.run(ApiClient.deleteChallenge(challenge.challengeId))(_ => Store.refreshChallenges(game.gameId))
        }
      )
    )

  private def openChallengeRow(game: Game, challenge: OpenChallenge, characterId: CharacterId): HtmlElement =
    li(
      cls := "row",
      div(cls := "title", challenge.message),
      div(cls := "detail", s"${challenge.numberOfPlayers} players"),
      button(
        "Accept",
        onClick --> { _ =>
          Store.run(ApiClient.accept(challenge.challengeId, characterId)) { _ =>
            // Accepting may complete the challenge into a match, which changes the match lists as
            // well as this one, so both are reloaded.
            Store.refreshChallenges(game.gameId)
            Store.refreshMatches()
          }
        }
      )
    )

  private def newChallengeForm(game: Game, player: Player, characterId: CharacterId): HtmlElement = {
    val message = Var("")
    val players = Var(game.minPlayers.toString)

    div(
      cls := "card",
      h3("Offer a challenge"),
      input(
        placeholder := "message",
        controlled(value <-- message.signal, onInput.mapToValue --> message)
      ),
      input(
        tpe := "number",
        minAttr := game.minPlayers.toString,
        maxAttr := game.maxPlayers.toString,
        controlled(value <-- players.signal, onInput.mapToValue --> players)
      ),
      button(
        "Create challenge",
        disabled <-- message.signal.combineWith(players.signal).map { case (m, p) =>
          m.trim.isEmpty || !validPlayerCount(game, p)
        },
        onClick --> { _ =>
          val challenge = OpenChallenge(
            // The server assigns the id; this is the same unassigned-sentinel convention the
            // service layer uses on create.
            challengeId = ChallengeId(0),
            challenger = player.playerId,
            message = message.now().trim,
            numberOfPlayers = players.now().trim.toShort,
            start = None,
            timeLimit = None,
            settings = "{}",
            gameId = game.gameId,
            characterId = characterId
          )

          Store.run(ApiClient.createChallenge(challenge)) { _ =>
            message.set("")
            Store.refreshChallenges(game.gameId)
          }
        }
      )
    )
  }

  private def validPlayerCount(game: Game, raw: String): Boolean =
    raw.trim.toIntOption.exists(count => count >= game.minPlayers && count <= game.maxPlayers)

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
