package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import com.vivi.matchmaker.model._

/** Finding players, a player's page and a character's page, and inviting someone from them. */
object Players {

    import Views.{busyButton, currentPlayer, field, freshTipId, listing, refresh, refreshableSection, withTip}

    /* Whether a search is in flight, so the box can say so. At this level because the search is
     * submitted two ways -- the button, and Enter in the field -- and both mean the same thing. */
    private val searchingPlayers: Var[Boolean] = Var(false)

    private def runPlayerSearch(): Unit = refresh(searchingPlayers, () => Store.searchPlayers())

    /** The screen that finds a player by the start of their nickname.
      *
      * A prefix rather than a substring — the start of a name, not any part of it — and nothing else to explain: case
      * is ignored, and so is how the name was spaced, so there is no rule for the searcher to get right. Nicknames
      * themselves are still case sensitive, which is why two players whose names differ only in case both appear here,
      * each spelled as they registered.
      */
    private[ui] def findPlayersPage: HtmlElement =
        sectionTag(
          cls := "refreshable",
          div(cls := "section-head", h2(Headings.players.find)),
          div(
            cls := "section-body",
            form(
              cls := "search",
              // Enter in the field submits, which is what a search box does; without this it would
              // reload the page and sign the player out of the screen they are looking at.
              onSubmit.preventDefault.filter(_ => !searchingPlayers.now()) --> (_ => runPlayerSearch()),
              field(
                "Nickname begins with",
                input(
                  tpe := "search",
                  // A nickname is not a word the browser has seen before, and a dropdown of the
                  // player's own past searches over the results is in the way rather than helpful.
                  autoComplete := "off",
                  controlled(value <-- Store.playerSearch.signal, onInput.mapToValue --> Store.playerSearch)
                )
              ),
              button(
                tpe := "submit",
                disabled <-- Store.playerSearch.signal.map(_.trim.isEmpty),
                // Not disabled while searching, which would drop the keyboard's focus: the form refuses
                // a second search meanwhile instead.
                aria.disabled <-- searchingPlayers.signal,
                child <-- searchingPlayers.signal.map(
                  if (_) span(cls := "spinner", aria.hidden := true) else emptyNode
                ),
                "Search"
              )
            ),
            // The live region is this container, which is mounted once, rather than the message
            // inside it: a region created at the moment it has something to say is announced by
            // nothing. So the results are replaced *within* an element that was already there.
            div(
              aria.live := "polite",
              child <-- Store.playerResults.signal.map(playerResults)
            )
          )
        )

    private def playerResults(found: Option[PlayerSearchResult]): HtmlElement = found match {
        // Nothing has been searched for yet, which is not the same as nothing having been found.
        case None                                   => p(cls := "empty", "Search for the start of a nickname.")
        case Some(result) if result.players.isEmpty => p(cls := "empty", "No player's nickname starts with that.")
        case Some(result) =>
            div(
              ul(result.players.map(playerResultRow)),
              // Said only when there are others, and said as the remedy rather than as a limit: the
              // searcher cannot ask for page two, and would not want it — the next page of a name
              // search is a longer prefix, which is the one thing they can do from here.
              if (result.hasMore)
                  div(
                    cls := "detail",
                    s"Showing the first ${result.players.length}. Type more of the nickname to narrow it."
                  )
              else emptyNode
            )
    }

    /* The nickname is the link, rather than a name beside a "View" button: the row has one thing to
     * do, and a control whose name is the player being opened needs no other label. */
    private def playerResultRow(player: PublicPlayer): HtmlElement =
        li(cls := "row", playerLink(player))

    /** A player's nickname as the way to their page: the page a "Find Players" search opens onto. */
    private[ui] def playerLink(player: PublicPlayer): HtmlElement =
        button(
          tpe := "button",
          cls := "link",
          player.nickname,
          onClick --> (_ => Store.show(Store.Page.OnePlayer(player)))
        )

    /** Who played an opposing seat, as the way to their page; a seat whose character had no owner was nobody's. */
    private[ui] def seatPlayer(o: Opponent): HtmlElement =
        o.player.fold(span(cls := "detail", "nobody"))(playerLink)

    /** A character's name as the way to its page, as [[playerLink]] is a player's. */
    private[ui] def characterLink(character: CharacterName): HtmlElement =
        button(
          tpe := "button",
          cls := "link",
          character.name,
          onClick --> (_ => Store.show(Store.Page.OneCharacter(character)))
        )

    /* One flag for the whole of a player's page, because one button reloads all of it: both lists
     * come of the one action, and dimming half a screen that is being replaced whole would be a lie
     * to anything reading it. */
    private val refreshingPublicMatches: Var[Boolean] = Var(false)

    /** Somebody else's page: a row for every game, each opening onto what that player has played of it.
      *
      * Only their public matches, which is their own statement about which ones may be looked at — the "Public" box on
      * the challenge the match was started from. Nothing here is filtered in the browser: the server answers with the
      * public ones and nothing else, so a private match is not among the rows this hides.
      *
      * A row per game rather than a row per game they have played, so the page is the same shape for every player and
      * the count on each row is the answer to "have they played this?" — which is a thing worth being told without
      * having to open anything.
      */
    private[ui] def playerPage(player: PublicPlayer): HtmlElement =
        div(
          h2(player.nickname),
          // Back to the results that opened this, which are still held: the store keeps the box and
          // the answer, so the way back is the list as it was rather than a search to type again.
          button(
            tpe := "button",
            cls := "link",
            "Back to search",
            onClick --> (_ => Store.show(Store.Page.FindPlayers))
          ),
          inviteControl(player),
          // Their matches in every game, as the main page lists the reader's own -- but only the ones
          // they made public. One reload fills both, so both are marked as reloading by it.
          refreshableSection(
            heading = Headings.matches.current,
            refreshing = refreshingPublicMatches,
            reload = () => Store.reloadPublicMatches(player.playerId),
            subsection = false
          )(
            listing(Store.publicActive.signal, Store.publicMatchesLoading.signal)(
              p(cls := "empty", "None being played in public.")
            )(
              MatchTables.matchTable(
                _,
                showGame = true,
                showParameters = false,
                outcome = MatchTables.waitingFor,
                view = MatchTables.watchControl,
                dateHeading = "Started",
                dated = summary => Some(summary.start),
                showResult = false
              )
            )
          ),
          refreshableSection(
            heading = Headings.matches.recentlyCompleted,
            refreshing = refreshingPublicMatches,
            reload = () => Store.reloadPublicMatches(player.playerId),
            subsection = false
          )(
            MatchSections
                .completedWindow(Store.CompletedList.Public(player.playerId, None), "None finished in public.")(
                  MatchTables.matchTable(
                    _,
                    showGame = true,
                    showParameters = false,
                    outcome = MatchTables.publicOutcome,
                    view = MatchTables.watchControl
                  )
                )
          ),
          // A game at a time, for what is about one game: the challenges the reader has offered them in
          // it, and an admin's view of their matches there.
          sectionTag(
            h2(Headings.players.games),
            listing(Store.games.signal, Store.loading(Store.Fetch.Games))(p(cls := "empty", "No games yet."))(games =>
                ul(games.map(publicGameRow(player, _)))
            )
          )
        )

    /** A character's page: what its owner says about it, and its matches -- the public ones, as on a player's page, and
      * for the same reason. Each is listed against the opposing character, with the player who owned that character for
      * the match, since a character can change hands and its old matches are still its own.
      */
    private[ui] def characterPage(character: CharacterName): HtmlElement =
        div(
          // By the name the profile gives, which is the character's name now; the link's until it has come.
          h2(
            child.text <-- Store.characterProfile.signal.map(
              _.filter(_.character.characterId == character.characterId).fold(character.name)(_.character.name)
            )
          ),
          child <-- Store.characterProfile.signal.combineWith(Store.publicMatchesLoading.signal).map {
              // Only the answer about this character: another one's, still held, is not it.
              case (Some(profile), _) if profile.character.characterId == character.characterId =>
                  div(
                    cls := "character-profile",
                    p(
                      cls := "detail",
                      s"A character in ${profile.gameName}, ",
                      profile.owner.fold[Modifier[HtmlElement]]("owned by nobody now")(owner =>
                          span("owned by ", playerLink(owner))
                      )
                    ),
                    withTip(
                      id = freshTipId("record-tip"),
                      subject = "record",
                      text = Tips.character.record
                    )(p(cls := "character-record", "Record: ", Ratings.recordText(profile.record))),
                    if (profile.description.trim.isEmpty) emptyNode
                    else p(cls := "character-description", profile.description)
                  )
              case (_, true) => p(cls := "detail", "Loading…")
              case _         => emptyNode
          },
          refreshableSection(
            heading = Headings.matches.current,
            refreshing = refreshingPublicMatches,
            reload = () => Store.reloadCharacterMatches(character),
            subsection = false
          )(
            listing(Store.publicActive.signal, Store.publicMatchesLoading.signal)(
              p(cls := "empty", "None being played in public.")
            )(
              MatchTables.matchTable(
                _,
                showGame = false,
                showParameters = true,
                outcome = MatchTables.waitingFor,
                view = MatchTables.watchControl,
                dateHeading = "Started",
                dated = summary => Some(summary.start),
                showResult = false,
                aboutCharacter = true
              )
            )
          ),
          refreshableSection(
            heading = Headings.matches.recentlyCompleted,
            refreshing = refreshingPublicMatches,
            reload = () => Store.reloadCharacterMatches(character),
            subsection = false
          )(
            MatchSections.completedWindow(Store.CompletedList.OfCharacter(character), "None finished in public.")(
              MatchTables.matchTable(
                _,
                showGame = false,
                showParameters = true,
                outcome = MatchTables.publicOutcome,
                view = MatchTables.watchControl,
                aboutCharacter = true
              )
            )
          )
        )

    /** Asking this player to play: pick the game, and go and make a competition of it that invites them -- the New
      * Competition dialog on the game's screen, which carries them to the competition's invitations once it is made.
      *
      * Not shown to a player looking at their own page, nor when no games have loaded, since there would be nothing to
      * choose.
      */
    private def inviteControl(invitee: PublicPlayer): HtmlElement = {
        val chosen = Var(Option.empty[GameId])

        div(
          child <-- Store.games.signal.combineWith(currentPlayer).map { (games, me) =>
              if (games.isEmpty || me.exists(_.playerId == invitee.playerId)) emptyNode
              else {
                  // Pre-selected rather than left blank: one game is the usual case, and a select whose
                  // first entry is "choose one" is a step that answers nothing.
                  if (chosen.now().isEmpty) chosen.set(games.headOption.map(_.gameId))
                  div(
                    cls := "card",
                    h3(Headings.players.invite(invitee.nickname)),
                    field(
                      "Game",
                      select(
                        onChange.mapToValue --> { raw =>
                            chosen.set(raw.toIntOption.map(GameId.apply).filter(id => games.exists(_.gameId == id)))
                        },
                        value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                        games.map(game => option(value := game.gameId.value.toString, game.displayName))
                      )
                    ),
                    button(
                      tpe := "button",
                      disabled <-- chosen.signal.map(_.isEmpty),
                      s"Invite ${invitee.nickname} to a new competition",
                      // One call, because `Store.show` forgets the invitee, so that arriving at a game any other way
                      // cannot inherit one.
                      onClick --> (_ => chosen.now().foreach(gameId => Store.showGameToInvite(gameId, invitee)))
                    )
                  )
              }
          }
        )
    }

    /** One game on a player's page: its name, how many of its matches they are playing, and — when opened — the
      * matches.
      *
      * The count is drawn whether or not the row is open. Finished matches are not counted: they are a list a window of
      * time at a time, fetched for the game that is opened, and the row does not fetch them all to count them.
      */
    private def publicGameRow(player: PublicPlayer, game: Game): HtmlElement = {
        val expanded = Store.expandedPublicGame.signal.map(_.contains(game.gameId))
        val running = Store.publicActive.signal.map(_.filter(_.gameId == game.gameId))

        li(
          cls := "row",
          button(
            tpe := "button",
            cls := "toggle",
            // The state is announced rather than spelled into the label, so the label stays the name
            // of the game — which is what the reader is scanning the list for.
            aria.expanded <-- expanded,
            game.displayName,
            onClick --> { _ =>
                Store.expandedPublicGame.update(current =>
                    if (current.contains(game.gameId)) None else Some(game.gameId)
                )
            }
          ),
          div(
            cls := "detail",
            // The same rule the lists themselves follow: what is held goes on being counted while a
            // reload is in flight — the section dims to say so — and "loading…" is for the case where
            // there is nothing to count yet, which is a page just opened rather than a player with
            // nothing to show. Saying "0 being played" in that moment would be a number about to
            // change.
            child.text <-- running.combineWith(Store.publicMatchesLoading.signal).map {
                case (active, loading) if active.isEmpty && loading => "loading…"
                case (active, _)                                    => s"${active.length} being played"
            }
          ),
          child <-- expanded.map {
              if (_)
                  div(
                    cls := "detail-panel",
                    Ratings.adminMatchesSection(game, player)
                  )
              else emptyNode
          }
        )
    }

    /** Another player's characters in a game, by name only -- which is all another player may see of them -- drawn by
      * `render` once they have come, for inviting one: in a character game it is a character that is invited.
      *
      * The answer is dropped unless this same mount is still the one waiting for it and the session that asked is still
      * signed in: a late answer about the player shown before would otherwise draw their characters under somebody
      * else's name. A counter rather than a mounted flag, because a flag reads true again if the same element is
      * remounted before the answer lands. The status line is announced, because what came back decides whether there is
      * anything to invite at all.
      */
    private[ui] def theirCharacters(gameId: GameId, owner: PublicPlayer, exclude: Set[CharacterId])(
        render: Seq[CharacterName] => HtmlElement
    ): HtmlElement = {
        // None while the request is out; Left when it failed.
        val loaded = Var(Option.empty[Either[String, Seq[CharacterName]]])
        var mount = 0
        div(
          onMountCallback { _ =>
              mount += 1
              val asked = mount
              val signIn = Store.currentSignIn
              ApiClient.characterNames(gameId, owner.playerId).onComplete { answer =>
                  if (asked == mount && Store.isStillSignedInAs(signIn)) answer match {
                      case scala.util.Success(names) =>
                          loaded.set(Some(Right(names.filterNot(c => exclude.contains(c.characterId)))))
                      case scala.util.Failure(_) =>
                          loaded.set(Some(Left(ErrorText.loading.characters(owner.nickname))))
                  }
              }
          },
          onUnmountCallback(_ => mount += 1),
          div(
            role := "status",
            aria.live := "polite",
            child <-- loaded.signal.map {
                case None              => p(cls := "detail", s"Loading ${owner.nickname}'s characters...")
                case Some(Left(error)) => p(cls := "empty", error)
                case Some(Right(names)) if names.isEmpty =>
                    p(cls := "empty", s"${owner.nickname} has no character in this game that could be invited.")
                case Some(Right(_)) => emptyNode
            }
          ),
          child <-- loaded.signal.map {
              case Some(Right(names)) if names.nonEmpty => render(names)
              case _                                    => emptyNode
          }
        )
    }

    /** [[theirCharacters]] as a button per character, each inviting it with `invite`. */
    private[ui] def characterInviteButtons(gameId: GameId, owner: PublicPlayer, exclude: Set[CharacterId])(
        invite: (CharacterName, Var[Boolean]) => Unit
    ): HtmlElement =
        theirCharacters(gameId = gameId, owner = owner, exclude = exclude)(names =>
            ul(
              names.map(c =>
                  li(
                    cls := "row",
                    div(cls := "title", c.name),
                    busyButton(label = s"Invite ${c.name}", classes = Some("link"))(busy => invite(c, busy))
                  )
              )
            )
        )
}
