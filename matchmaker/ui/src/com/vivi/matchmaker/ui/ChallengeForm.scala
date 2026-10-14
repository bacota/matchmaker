package com.vivi.matchmaker.ui

import com.raquo.laminar.api.L.{*, given}
import com.vivi.matchmaker.model._

/** The form that offers a challenge, and the parameter and time-limit helpers it shares with the tournament form. */
object ChallengeForm {

    import Views.{busyButton, field, withTip}

    /** [[theirCharacters]] as a picker writing the chosen one into `chosen`, the first of them to begin with. */
    private def characterChooser(
        gameId: GameId,
        owner: PublicPlayer,
        exclude: Set[CharacterId],
        chosen: Var[Option[CharacterId]]
    ): HtmlElement =
        Players.theirCharacters(gameId = gameId, owner = owner, exclude = exclude) { names =>
            field(
              "Their character",
              select(
                onMountCallback(_ =>
                    if (!chosen.now().exists(id => names.exists(_.characterId == id))) {
                        chosen.set(names.headOption.map(_.characterId))
                    }
                ),
                onChange.mapToValue --> { raw =>
                    chosen.set(raw.toLongOption.map(CharacterId.apply).filter(id => names.exists(_.characterId == id)))
                },
                value <-- chosen.signal.map(_.map(_.value.toString).getOrElse("")),
                names.map(c => option(value := c.characterId.value.toString, c.name))
              )
            )
        }

    /** One of a game's parameters as a challenger picks it: `name` is the key it is stored and sent to the engine
      * under, `label` what the challenger is shown for it.
      */
    private[ui] case class ParameterChoice(name: String, label: String, values: Seq[String], default: Option[String])

    /** The values a challenger may pick for each of the game's parameters, in the order [[Format.parameterValues]] puts
      * them. Parameters with no values to choose between are left out: there is nothing to offer.
      */
    private[ui] def parameterChoices(game: Game): Seq[ParameterChoice] =
        game.parameters
            .map(p =>
                ParameterChoice(
                  name = p.name,
                  label = p.displayName,
                  values = Format.parameterValues(p.values.map(_.value.toString)),
                  default = p.defaultValue.map(_.toString)
                )
            )
            .filter(_.values.nonEmpty)

    /** A challenge's parameter choices as its `settings` carries them: `{"rounds":"12"}`. The same flat object of
      * strings `ChallengeSettings` reads on the server.
      */
    private[ui] def settingsOf(choices: Map[String, String]): String =
        ujson.write(ujson.Obj.from(choices.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v))))

    /** The choices `settings` holds, by parameter name: the flat object of strings [[settingsOf]] writes, with a whole
      * number read as one too. Empty for settings that are not such an object.
      */
    private[ui] def storedChoices(settings: String): Map[String, String] =
        try
            ujson.read(settings) match {
                case o: ujson.Obj =>
                    o.value.toMap.collect {
                        case (k, ujson.Str(v))              => k -> v
                        case (k, ujson.Num(n)) if n.isWhole => k -> n.toLong.toString
                    }
                case _ => Map.empty[String, String]
            }
        catch { case _: Throwable => Map.empty[String, String] }

    /** Each of the game's parameters' value for a form to start from: what `settings` chose, where it is still a value
      * the game allows, else the game's default, else its first value.
      */
    private[ui] def startingChoices(game: Game, settings: String = "{}"): Map[String, String] = {
        val stored = storedChoices(settings)
        parameterChoices(game).map { choice =>
            choice.name -> stored
                .get(choice.name)
                .filter(choice.values.contains)
                .orElse(choice.default.filter(choice.values.contains))
                .getOrElse(choice.values.head)
        }.toMap
    }

    /** One select per game parameter, captioned with what players are shown for it and keyed by the name the engine is
      * sent, writing into `parameters`.
      */
    private[ui] def parameterFields(game: Game, parameters: Var[Map[String, String]]): Seq[HtmlElement] =
        parameterChoices(game).map { choice =>
            field(
              if (choice.label == choice.name) choice.name.capitalize else choice.label,
              select(
                onChange.mapToValue --> (chosen => parameters.update(_.updated(choice.name, chosen))),
                value <-- parameters.signal.map(_.getOrElse(choice.name, "")),
                choice.values.map(v => option(value := v, v))
              )
            )
        }

    /** The form that offers a challenge, and — when `invitee` is set — invites one player to it in the same request.
      *
      * `invitee` arrives from that player's own page, which is the only screen that can name somebody: see
      * [[inviteControl]]. With one, the challenge is closed, because the reason to invite a particular player is
      * usually that the game is for them.
      *
      * A challenge names no role: its players choose theirs once it starts, the strongest first. What it does name is
      * how many it seats — between the game's required roles and all of them — which is also when it is full.
      */
    private[ui] def newChallengeForm(
        game: Game,
        player: Player,
        characters: Seq[Character[String]],
        invitee: Option[PublicPlayer] = None
    ): HtmlElement = {
        val isPublic = Var(false)
        // In a character game it is one of the invitee's characters that is invited, chosen here.
        val inviteeCharacter = Var(Option.empty[CharacterId])
        // Whether it starts by itself once it is full, instead of waiting for this challenger to press Start. On by
        // default: the challenge is an offer to play, and having to come back and press Start once everybody has said
        // yes is a step most challengers do not want. One who would go ahead with fewer turns it off.
        val autoStart = Var(true)
        // How long the match may take, blank for no limit, in the unit beside it. Blank by default because an unlimited
        // game is the one nobody can lose by walking away from their desk.
        val timeLimit = Var("")
        val timeLimitUnit = Var(TimeLimitUnit.Hours)
        // Whether the match is played live: the game engine runs every turn against the clock, so a time limit is then
        // required. Off by default -- it asks the players to be there together.
        val live = Var(false)
        // When it is meant to be played, as the browser's date and time input gives it; blank for none.
        val plannedStart = Var("")
        // How many it seats: the game's R to start with, and anything from enough to fill its required roles to every
        // one of its roles.
        val least = math.max(2, game.requiredSeats)
        val most = game.roles.size
        val seats = Var(math.min(most, math.max(least, game.poolSeats)))
        // Whether this player administers the game, which is what lets them offer a match they will not play in, or
        // one that is not friendly: `Store.administers`, held here so that the button can read it when it is pressed.
        val administers = Var(player.isAdmin)
        // Whether the challenger plays. Only a game's admin is offered the choice. In a character game a player plays
        // as one of their characters, so an admin with none has no place to take.
        val canPlay = game.gameType != GameType.Character || characters.nonEmpty
        val plays = Var(canPlay)
        val friendly = Var(true)
        val seated: Signal[Boolean] = plays.signal.combineWith(administers.signal).map((p, a) => p || !a)
        def isSeatedNow: Boolean = plays.now() || !administers.now()
        val characterGame = game.gameType == GameType.Character
        // The challenger's own character: the first of them to start with, which is the only one for most players,
        // but chosen here rather than assumed.
        val ownCharacter = Var(characters.headOption.map(_.characterId))
        // One value per game parameter the challenger may choose — how many rounds a bout is, say — starting from the
        // game's default, or its first value when it names none. Sent as the challenge's settings, and handed to the
        // engine in place of the default when the match starts.
        val parameters = Var(startingChoices(game))

        div(
          cls := "card",
          Store.administers(game.gameId, player) --> administers,
          h3(idAttr := "offer-challenge-heading", "Offer a Challenge"),
          if (most < least)
              p(cls := "empty", s"${game.displayName} has too few roles to be played as a challenge.")
          else emptyNode,
          // Shown even when there is only one, so the challenger can see who they are playing as -- and not at all to
          // an admin who will not play.
          if (characterGame)
              child <-- seated.map(inIt =>
                  if (!inIt) emptyNode
                  else
                      field(
                        "Your character",
                        select(
                          onChange.mapToValue --> { raw =>
                              ownCharacter.set(
                                raw.toLongOption
                                    .map(CharacterId.apply)
                                    .filter(id => characters.exists(_.characterId == id))
                              )
                          },
                          value <-- ownCharacter.signal.map(_.map(_.value.toString).getOrElse("")),
                          characters.map(c => option(value := c.characterId.value.toString, c.name))
                        )
                      )
              )
          else emptyNode,
          // What only a game's admin may offer. Both boxes in one place, because they are the two ways this challenge
          // can differ from one anybody else could make.
          child <-- administers.signal.map { admin =>
              if (!admin) emptyNode
              else
                  div(
                    if (!canPlay)
                        p(
                          cls := "detail",
                          "You have no character in this game, so this is a match you will not play in."
                        )
                    else
                        withTip(
                          id = "plays-tip",
                          subject = "I will play in this match",
                          text =
                              "Leave this unticked to offer a match for other players to play, which you can invite " +
                                  "them to, but have no place in."
                        )(
                          label(
                            input(
                              tpe := "checkbox",
                              aria.describedBy := "plays-tip",
                              controlled(checked <-- plays.signal, onClick.mapToChecked --> plays)
                            ),
                            "I will play in this match"
                          )
                        ),
                    withTip(
                      id = "friendly-tip",
                      subject = "Friendly",
                      text =
                          "A friendly match does not move its players' ratings; untick for a rated one. As an admin of " +
                              "this game you can change it later, until the match is completed."
                    )(
                      label(
                        input(
                          tpe := "checkbox",
                          aria.describedBy := "friendly-tip",
                          controlled(checked <-- friendly.signal, onClick.mapToChecked --> friendly)
                        ),
                        "Friendly"
                      )
                    )
                  )
          },
          // Offered only when there is a choice to make: a game whose roles are all required seats exactly that many.
          if (most > least)
              withTip(
                id = "seats-tip",
                subject = "Players",
                text =
                    s"How many play, from $least to $most. It is full, and starts if you ask it to, when that many have " +
                        "entered; you can also start it with fewer, as long as there are at least " + least + "."
              )(
                label(
                  cls := "field",
                  "Players",
                  select(
                    aria.describedBy := "seats-tip",
                    onChange.mapToValue --> (raw =>
                        raw.toIntOption.filter(n => n >= least && n <= most).foreach(seats.set)
                    ),
                    value <-- seats.signal.map(_.toString),
                    (least to most).map(n => option(value := n.toString, n.toString))
                  )
                )
              )
          else emptyNode,
          // Built once: the game's parameters do not change while the form is open.
          parameterFields(game, parameters),
          invitee.fold(emptyNode)(asked =>
              if (game.gameType == GameType.Character)
                  div(
                    cls := "detail-panel",
                    p(cls := "detail", s"Inviting ${asked.nickname}: only the character you choose may enter."),
                    characterChooser(
                      gameId = game.gameId,
                      owner = asked,
                      exclude = Set.empty,
                      chosen = inviteeCharacter
                    )
                  )
              else p(cls := "detail", s"Inviting ${asked.nickname}: only they may enter.")
          ),
          // The number and the unit it is in, in one field: they are one answer, and a caption over each would read as
          // two questions. How the time is spent is the game's: a chess clock shared out among the players of a game
          // of turns, and a limit on each turn in one where everybody moves at once.
          withTip(
            id = "time-limit-tip",
            subject = "Time to play",
            text = "How long the match may take. In a game of turns each player gets an equal share of it as a chess " +
                "clock; in a game where everybody moves at once it is shared out among the turns. Leave it blank " +
                "for no limit."
          )(
            label(
              cls := "field",
              "Time to play",
              div(
                cls := "compound",
                input(
                  tpe := "number",
                  minAttr := "1",
                  stepAttr := "1",
                  aria.label := "Time to play, blank for no limit",
                  aria.invalid <-- timeLimit.signal
                      .combineWith(live.signal)
                      .map((raw, isLive) => limitProblem(raw, isLive).isDefined.toString),
                  aria.describedBy := "time-limit-tip time-limit-problem",
                  controlled(value <-- timeLimit.signal, onInput.mapToValue --> timeLimit)
                ),
                select(
                  aria.label := "Time to play unit",
                  onChange.mapToValue --> (raw => timeLimitUnit.set(TimeLimitUnit.fromCode(raw))),
                  value <-- timeLimitUnit.signal.map(_.code),
                  TimeLimitUnit.offered.map(unit => option(value := unit.code, unit.label))
                )
              )
            )
          ),
          // Mounted before it has anything to say, and it is the text that arrives: a message built at the moment it
          // becomes true is a region nothing was watching. A `div` that is empty whenever the field is valid, so that
          // it holds no margins open under the input.
          div(
            idAttr := "time-limit-problem",
            aria.live := "polite",
            cls <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => if (limitProblem(raw, isLive).isEmpty) "" else "empty"),
            child.text <-- timeLimit.signal
                .combineWith(live.signal)
                .map((raw, isLive) => limitProblem(raw, isLive).getOrElse(""))
          ),
          withTip(
            id = "live-tip",
            subject = "Live",
            text =
                "Played in real time. The game engine runs the turns and keeps the clock itself, so the time to play " +
                    "is required. A player who runs out of time loses the match."
          )(
            label(
              input(
                tpe := "checkbox",
                aria.describedBy := "live-tip",
                controlled(checked <-- live.signal, onClick.mapToChecked --> live)
              ),
              "Live"
            )
          ),
          withTip(
            id = "public-tip",
            subject = "Public",
            text =
                "Anyone may watch the match, without signing in, and it is listed among the public matches on each " +
                    "player's page. It does not change who may enter the challenge."
          )(
            label(
              input(
                tpe := "checkbox",
                aria.describedBy := "public-tip",
                controlled(checked <-- isPublic.signal, onClick.mapToChecked --> isPublic)
              ),
              "Public"
            )
          ),
          withTip(
            id = "planned-start-tip",
            subject = "When to play",
            text =
                "When you mean the match to be played, shown to anyone deciding whether to enter. It starts the match " +
                    "no sooner and no later. Leave it blank to say nothing."
          )(
            label(
              cls := "field",
              "When to play (optional)",
              input(
                tpe := "datetime-local",
                aria.describedBy := "planned-start-tip",
                controlled(value <-- plannedStart.signal, onInput.mapToValue --> plannedStart)
              )
            )
          ),
          // Ticked and fixed for a challenger who will not play: nobody would be watching for it to fill, so it has to
          // start without them, and the server refuses one that would not. Disabled rather than hidden, so that what
          // the challenge will do is said.
          child <-- seated.map { inIt =>
              div(
                label(
                  input(
                    tpe := "checkbox",
                    disabled := !inIt,
                    aria.describedBy := (if (inIt) "" else "auto-start-why"),
                    controlled(checked <-- autoStart.signal.map(_ || !inIt), onClick.mapToChecked --> autoStart)
                  ),
                  "Start when full"
                ),
                if (inIt) emptyNode
                else
                    p(
                      idAttr := "auto-start-why",
                      cls := "detail",
                      "Always, for a match you will not play in: nobody would be there to start it."
                    )
              )
          },
          busyButton(
            label = "Create Challenge",
            disabledWhen = timeLimit.signal
                .combineWith(live.signal, ownCharacter.signal, seated, inviteeCharacter.signal)
                .map { case (limit, isLive, own, inIt, asked) =>
                    most < least || limitProblem(limit, isLive).isDefined ||
                    // A character game's challenger plays as one of their characters.
                    (characterGame && inIt && own.isEmpty) ||
                    // And its invitee is asked through one of theirs: until one is chosen there is nothing to send.
                    (characterGame && invitee.isDefined && asked.isEmpty)
                }
          ) { busy =>
              val request = ChallengeRequest(
                characterId = if (characterGame && isSeatedNow) ownCharacter.now() else None,
                isPlaying = isSeatedNow,
                poolSize = Some(seats.now()),
                roundDuration = durationOf(timeLimit.now(), timeLimitUnit.now()),
                gameSettings = settingsOf(parameters.now()),
                isLive = live.now(),
                isFriendly = !administers.now() || friendly.now(),
                isPublic = isPublic.now(),
                start = startOf(plannedStart.now()),
                isAutoStarted = autoStart.now() || !isSeatedNow,
                isOpen = invitee.isEmpty,
                invitations = if (characterGame) Nil else invitee.map(_.playerId).toList,
                characterInvitations = if (characterGame) inviteeCharacter.now().toList else Nil
              )
              // Taken before the request, and checked before the store is written: this callback closes the form,
              // clears the invitee and re-reads a game's challenges, and all three of those belong to whoever was on
              // this screen when the button was clicked. `Store.run` drops nothing by design -- a click is always
              // worth an answer -- so the guard belongs here, which is what `currentSignIn` is visible outside the
              // store for.
              val signIn = Store.currentSignIn
              Store.run(ApiClient.createChallenge(game.gameId, request), busy) { _ =>
                  timeLimit.set("")
                  autoStart.set(true)
                  live.set(false)
                  plays.set(true)
                  friendly.set(true)
                  plannedStart.set("")
                  if (Store.isStillSignedInAs(signIn) && Store.page.now() == Store.Page.OneGame(game.gameId)) {
                      // The challenge it was open for now exists and is in the list below it. The invitation went
                      // with it, so the slot is spent.
                      Store.showChallengeForm.set(false)
                      Store.invitee.set(None)
                      Store.refreshChallenges(game.gameId)
                      Store.reloadMyChallenges()
                  }
              }
          }
        )
    }

    /** When to play as the browser's date and time input gives it -- the local time, with no zone -- as an instant, or
      * `None` for blank or anything the browser could not read.
      */
    private[ui] def startOf(raw: String): Option[java.time.Instant] =
        Option(raw.trim).filter(_.nonEmpty).flatMap { local =>
            val millis = new scala.scalajs.js.Date(local).getTime()
            Option.when(!millis.isNaN)(java.time.Instant.ofEpochMilli(millis.toLong))
        }

    /** An instant as the browser's date and time input shows it: the local time, to the minute. */
    private[ui] def localInputOf(at: java.time.Instant): String = {
        val d = new scala.scalajs.js.Date(at.toEpochMilli.toDouble)
        def two(n: Double) = f"${n.toInt}%02d"
        s"${d.getFullYear().toInt}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}"
    }

    /** A time limit as it was typed — `None` for blank, and `None` for anything that is not a positive whole number,
      * which the form treats as not yet a limit rather than as zero.
      *
      * Separate from [[durationOf]] because the two are asked at different moments: whether what has been typed is a
      * number at all is checked on every keystroke, and what it comes to is only wanted when the challenge is made.
      */
    private def amountOf(raw: String): Option[Int] =
        raw.trim match {
            case ""   => None
            case text => text.toIntOption.filter(_ > 0)
        }

    /** What is wrong with the time limit as typed, if anything: it must be a whole number above zero when there is one,
      * and a live match must have one.
      */
    private def limitProblem(raw: String, live: Boolean): Option[String] =
        if (raw.trim.isEmpty) Option.when(live)("A live match needs a time to play.")
        else Option.when(amountOf(raw).isEmpty)("A time limit is a whole number, more than zero.")

    /** A time limit typed in some unit, as a `Duration`. */
    private def durationOf(raw: String, unit: TimeLimitUnit): Option[java.time.Duration] =
        amountOf(raw).map(amount => unit.perUnit.multipliedBy(amount.toLong))
}
