package com.vivi.matchmaker.ui

import com.vivi.matchmaker.ErrorText
import com.raquo.laminar.api.L.{*, given}
import com.vivi.matchmaker.model._

/** The form that creates or edits a game: its roles, its parameters and its limits. */
object GameForm {

    import Views.{busyButton, field, heldBusy, tipField, withTip}

    /** "An admin user should be able to create a new game."
      *
      * The form asks for everything a game is: its own fields, the roles a player can be seated in, and the parameters
      * the game engine is configured with. Roles are not optional extras — every seat of a match names one, so a game
      * with none is a game nothing can be played in, and the server refuses it.
      */
    /* One row of the role or parameter editor, held as Vars rather than plain values so that
     * typing in a row does not rebuild the list and take the cursor with it: the rendered children
     * change only when a row is added or removed. */
    /* A role draft carries the id of the role it edits, because that is what tells an edit from an
     * addition on the way back — and an existing role can never be removed, only renamed or made
     * optional. Parameters carry no id: they are replaced wholesale, and deleting one is allowed. */
    private case class RoleDraft(
        gameRoleId: GameRoleId,
        name: Var[String],
        displayName: Var[String],
        roleType: Var[RoleType],
        // As typed: blank is no preference.
        preferenceLevel: Var[String]
    )
    private case class ParameterDraft(
        name: Var[String],
        displayName: Var[String],
        values: Var[String],
        default: Var[String]
    )

    private def emptyRole: RoleDraft =
        RoleDraft(
          gameRoleId = GameRoleId.unassigned,
          name = Var(""),
          displayName = Var(""),
          roleType = Var(RoleType.Required),
          preferenceLevel = Var("")
        )
    private def emptyParameter: ParameterDraft =
        ParameterDraft(name = Var(""), displayName = Var(""), values = Var(""), default = Var(""))

    private def draftOf(role: GameRole): RoleDraft =
        RoleDraft(
          gameRoleId = role.gameRoleId,
          name = Var(role.name),
          displayName = Var(role.displayName),
          roleType = Var(role.roleType),
          preferenceLevel = Var(role.preferenceLevel.fold("")(_.toString))
        )

    private def draftOf(parameter: GameParameter[String]): ParameterDraft =
        ParameterDraft(
          name = Var(parameter.name),
          displayName = Var(parameter.displayName),
          values = Var(parameter.values.map(_.value).mkString(", ")),
          default = Var(parameter.defaultValue.getOrElse(""))
        )

    /** The possible values of a parameter, as typed: one comma-separated list, because a parameter with three values is
      * a sentence an admin can type and a list of three inputs is not.
      */
    private def splitValues(raw: String): Seq[String] =
        raw.split(',').map(_.trim).filter(_.nonEmpty).toSeq.distinct

    /** `formKey` tells this form's tips apart from another form's on the same page, since every tip is found by id. */
    private def roleEditor(formKey: String, roles: Var[List[RoleDraft]]): HtmlElement =
        div(
          withTip(
            id = s"$formKey-roles-tip",
            subject = "Roles",
            text = Tips.game.roles
          )(h4(Headings.gameForm.roles)),
          children <-- roles.signal.map(_.zipWithIndex.map { (draft, i) =>
              val nameTip = s"$formKey-role-$i-name-tip"
              val displayTip = s"$formKey-role-$i-display-tip"
              val typeTip = s"$formKey-role-$i-type-tip"
              val preferenceTip = s"$formKey-role-$i-preference-tip"
              div(
                cls := "row",
                // A caption on each field, with what it means in a tip beside it rather than under it:
                // the explanation is the same on every row, and repeated in full it would bury the rows.
                withTip(
                  id = nameTip,
                  subject = "Role name",
                  text = Tips.game.roleName
                )(
                  label(
                    cls := "field",
                    Labels.game.roleName,
                    input(
                      aria.describedBy := nameTip,
                      controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name)
                    )
                  )
                ),
                withTip(
                  id = displayTip,
                  subject = "Role display name",
                  text = Tips.game.roleDisplayName
                )(
                  label(
                    cls := "field",
                    Labels.game.roleDisplayName,
                    input(
                      aria.describedBy := displayTip,
                      controlled(value <-- draft.displayName.signal, onInput.mapToValue --> draft.displayName)
                    )
                  )
                ),
                withTip(
                  id = typeTip,
                  subject = "Role type",
                  text = Tips.game.roleType
                )(
                  label(
                    cls := "field",
                    Labels.game.roleType,
                    select(
                      aria.describedBy := typeTip,
                      onChange.mapToValue --> (code => draft.roleType.set(RoleType.fromCode(code))),
                      value <-- draft.roleType.signal.map(_.code),
                      RoleType.values.toSeq.map(rt => option(value := rt.code, rt.label))
                    )
                  )
                ),
                withTip(
                  id = preferenceTip,
                  subject = "Role preference level",
                  text = Tips.game.rolePreference
                )(
                  label(
                    cls := "field",
                    Labels.game.rolePreference,
                    input(
                      tpe := "number",
                      inputMode := "numeric",
                      aria.describedBy := preferenceTip,
                      controlled(
                        value <-- draft.preferenceLevel.signal,
                        onInput.mapToValue --> draft.preferenceLevel
                      )
                    )
                  )
                ),
                // A role that exists cannot be removed: acceptances and played matches name it. The
                // server refuses it too — this is why the button is not there to press.
                if (draft.gameRoleId == GameRoleId.unassigned)
                    // Named for what it removes: a column of identical "Remove" links tells a screen
                    // reader nothing about which row it is on.
                    button(
                      cls := "link",
                      aria.label <-- draft.name.signal.map(n =>
                          if (n.trim.isEmpty) Buttons.game.removeUnnamedRole else Buttons.game.removeRoleNamed(role = n)
                      ),
                      Buttons.game.removeRole,
                      onClick --> (_ => roles.update(_.filterNot(_ eq draft)))
                    )
                else emptyNode
              )
          }),
          button(cls := "link", Buttons.game.addRole, onClick --> (_ => roles.update(_ :+ emptyRole)))
        )

    private def parameterEditor(formKey: String, parameters: Var[List[ParameterDraft]]): HtmlElement =
        div(
          withTip(
            id = s"$formKey-parameters-tip",
            subject = "Parameters",
            text = Tips.game.parameters
          )(h4(Headings.gameForm.parameters)),
          children <-- parameters.signal.map(_.zipWithIndex.map { (draft, i) =>
              // A caption on each box, as on a role's: a placeholder is gone at the first keystroke, and with it
              // any way of telling which of four alike boxes is which.
              div(
                cls := "row",
                tipField(
                  id = s"$formKey-parameter-$i-name-tip",
                  caption = Labels.game.parameterName,
                  text = Tips.game.parameterName
                )(input(controlled(value <-- draft.name.signal, onInput.mapToValue --> draft.name))),
                tipField(
                  id = s"$formKey-parameter-$i-display-tip",
                  caption = Labels.game.parameterDisplayName,
                  text = Tips.game.parameterDisplayName
                )(input(controlled(value <-- draft.displayName.signal, onInput.mapToValue --> draft.displayName))),
                tipField(
                  id = s"$formKey-parameter-$i-values-tip",
                  caption = Labels.game.parameterValues,
                  text = Tips.game.parameterValues
                )(input(controlled(value <-- draft.values.signal, onInput.mapToValue --> draft.values))),
                tipField(
                  id = s"$formKey-parameter-$i-default-tip",
                  caption = Labels.game.parameterDefault,
                  text = Tips.game.parameterDefault
                )(input(controlled(value <-- draft.default.signal, onInput.mapToValue --> draft.default))),
                button(
                  cls := "link",
                  aria.label <-- draft.name.signal.map(n =>
                      if (n.trim.isEmpty) Buttons.game.removeUnnamedParameter
                      else Buttons.game.removeParameterNamed(parameter = n)
                  ),
                  Buttons.game.removeParameter,
                  onClick --> (_ => parameters.update(_.filterNot(_ eq draft)))
                )
              )
          }),
          button(cls := "link", Buttons.game.addParameter, onClick --> (_ => parameters.update(_ :+ emptyParameter)))
        )

    /** The drafted roles as the model, or the first thing wrong with them.
      *
      * The same rules the server checks, checked here so that a typo is answered by the form rather than by a round
      * trip — the server is still the one that decides, since nothing stops a request being made without this form.
      */
    private def rolesOf(drafts: List[RoleDraft]): Either[String, Seq[GameRole]] = {
        val all = drafts.map { d =>
            val name = d.name.now().trim
            // Left blank, a role is shown by its name -- the server does the same.
            val displayName = Option(d.displayName.now().trim).filter(_.nonEmpty).getOrElse(name)
            val preference = d.preferenceLevel.now().trim
            (d.gameRoleId, name, d.roleType.now(), displayName, preference)
        }
        // A blank new row is one the admin added and did not fill in, and is dropped. A blank
        // existing row is a role whose name has been cleared -- dropping that would ask the server to
        // delete a role, which it refuses, so it is answered here as what it is.
        val (blank, named) = all.partition(_._2.isEmpty)
        if (blank.exists(_._1 != GameRoleId.unassigned))
            Left(ErrorText.gameForm.unnamedRole)
        else if (named.map(_._2).distinct.sizeIs != named.size) Left(ErrorText.gameForm.sameRoleName)
        else if (named.map(_._4).distinct.sizeIs != named.size) Left(ErrorText.gameForm.sameRoleDisplayName)
        else if (named.exists((_, _, _, _, p) => p.nonEmpty && p.toIntOption.isEmpty))
            Left(ErrorText.gameForm.badPreference)
        else
            Right(
              named.map((id, name, roleType, displayName, preference) =>
                  GameRole(
                    gameRoleId = id,
                    gameId = GameId.unassigned,
                    name = name,
                    roleType = roleType,
                    displayName = displayName,
                    preferenceLevel = preference.toIntOption
                  )
              )
            )
    }

    /** A game's maximum turns as typed: blank for none, otherwise a whole number above 0. */
    /** A game without roles' range of players, as typed: the fewest, a whole number above 0, and the most, blank for no
      * limit or no fewer than the fewest.
      */
    private def playersOf(least: String, most: String): Either[String, (Int, Option[Int])] =
        for {
            fewest <- least.trim.toIntOption
                .filter(_ > 0)
                .toRight(ErrorText.gameForm.badFewest)
            limit <- most.trim match {
                case "" => Right(None)
                case trimmed =>
                    trimmed.toIntOption
                        .filter(_ >= fewest)
                        .map(Some(_))
                        .toRight(ErrorText.gameForm.badMost)
            }
        } yield (fewest, limit)

    private def maxTurnsOf(raw: String): Either[String, Option[Int]] =
        raw.trim match {
            case "" => Right(None)
            case trimmed =>
                trimmed.toIntOption
                    .filter(_ > 0)
                    .map(Some(_))
                    .toRight(ErrorText.gameForm.badMaxTurns)
        }

    private def parametersOf(drafts: List[ParameterDraft]): Either[String, Seq[GameParameter[String]]] = {
        val named = drafts
            .map { d =>
                val name = d.name.now().trim
                GameParameter[String](
                  gameId = GameId.unassigned,
                  gameParameterId = GameParameterId(0),
                  name = name,
                  defaultValue = Option(d.default.now().trim).filter(_.nonEmpty),
                  values = splitValues(d.values.now()).map(v =>
                      GameParameterValue(gameId = GameId.unassigned, gameParameterId = GameParameterId(0), value = v)
                  ),
                  // Left blank, a parameter is shown by its name -- the server does the same.
                  displayName = Option(d.displayName.now().trim).filter(_.nonEmpty).getOrElse(name)
                )
            }
            .filter(_.name.nonEmpty)
        val badDefault = named.find(p => p.defaultValue.exists(d => !p.values.exists(_.value == d)))
        if (named.map(_.name).distinct.sizeIs != named.size) Left(ErrorText.gameForm.sameParameterName)
        else if (named.map(_.displayName).distinct.sizeIs != named.size)
            Left(ErrorText.gameForm.sameParameterDisplayName)
        else
            badDefault match {
                case Some(p) =>
                    Left(ErrorText.gameForm.defaultNotAValue(parameter = p.name, default = p.defaultValue.get))
                case None => Right(named)
            }
    }

    /** The admin's game form, for creating one (`existing` is None) or editing one.
      *
      * The two are the same form because a game is the same thing either way, and every field is editable in both:
      * name, description, url, whether characters are required, the roles and the parameters. There is no player count
      * to set — a game's roles are its seats, so adding one in [[roleEditor]] is how a game gets bigger. What edit
      * cannot do is delete a role.
      *
      * `externalId` is asked for as the engine's identity: the name a callback carrying this game's API key is taken to
      * come from, and what game-authorized requests are matched against. The API key itself is asked for too — the
      * secret the engine was deployed with — and is write-only: required to create a game, left as it is by an edit
      * that leaves the field blank, and never shown, since nothing sends it back. `isActive` is asked only of an edit,
      * as "Disable Game": a new game is created active, and an edit may disable it, which takes it out of the menu and
      * every game picker. It hides the game rather than closing it — its challenges, invitations and matches are left
      * as they are, since `isActive` decides what is listed, not what may be played.
      */
    /** The game form's save, busy for as long as it is out, whatever is drawn again or closed meanwhile: what the
      * dialogs it is shown in refuse to close on.
      */
    private[ui] def savingBusy(existing: Option[Game]): Var[Boolean] =
        heldBusy(existing.fold("create game")(g => s"save game ${g.gameId.value}"))

    private[ui] def gameForm(
        existing: Option[Game],
        heading: Option[(String, String)] = None,
        onSaved: Game => Unit = _ => ()
    ): HtmlElement = {
        val name = Var(existing.map(_.name).getOrElse(""))
        val displayName = Var(existing.map(_.displayName).getOrElse(""))
        val description = Var(existing.map(_.description).getOrElse(""))
        val url = Var(existing.map(_.url).getOrElse(""))
        val engineIdentity = Var(existing.map(_.externalId).getOrElse(""))
        val disabled = Var(existing.exists(!_.isActive))
        // Untrusted by default: handing an engine players' refresh tokens is the box an admin ticks.
        val trusted = Var(existing.exists(_.isTrusted))
        // Played without roles, by any number of players in a range: a tournament says how many each match seats.
        // The range as typed.
        val roleless = Var(existing.exists(_.isRoleless))
        val minPlayers = Var(existing.flatMap(_.minPlayers).fold("2")(_.toString))
        val maxPlayers = Var(existing.flatMap(_.maxPlayers).fold("")(_.toString))
        // How players take turns, and how many a simultaneous game lasts; max turns as typed.
        val moveType: Var[MoveType] = Var(existing.map(_.moveType).getOrElse(MoveType.Sequential))
        val maxTurns = Var(existing.flatMap(_.maxTurns).fold("")(_.toString))
        // What this form's tip ids start with: a game's edit form and the new-game form are different forms.
        val formKey = existing.fold("new-game")(game => s"game-${game.gameId.value}")
        // Write-only: starts empty whether or not a key is stored, because the stored one is never sent here.
        val apiKey = Var("")
        // Where a player makes a character: the engine's page, since characters are made there.
        val characterUrl = Var(existing.flatMap(_.characterUrl).getOrElse(""))
        // Plain by default: requiring characters is the additional commitment, so it is the box an
        // admin ticks rather than the one they have to remember to untick.
        val gameType: Var[GameType] = Var(existing.map(_.gameType).getOrElse(GameType.Plain))
        // What happens when a player takes too long over a turn. A dropdown rather than a checkbox
        // because Forfeit is the first of several planned actions, not the only one there will ever
        // be — the control does not have to change when the second arrives, only the enum.
        val timeoutAction: Var[TimeoutAction] =
            Var(existing.map(_.timeoutAction).getOrElse(TimeoutAction.Forfeit))
        // A new game starts with one empty role, since most games have roles -- left blank, it is dropped, and the game
        // seats its players without roles -- and no parameters, because plenty of games have none. An existing one starts with what it has.
        val roles = Var(existing.map(_.roles.map(draftOf).toList).getOrElse(List(emptyRole)))
        val parameters = Var(
          existing.map(_.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList).getOrElse(Nil)
        )

        div(
          cls := "card",
          // A dialog's title, given as (id, text) so the dialog can be labelled by it.
          heading.fold(emptyNode)((id, text) => h3(idAttr := id, text)),
          // First, because it decides what else the form asks: a character game has a page to make characters on.
          {
              val tipId = s"$formKey-characters-tip"
              withTip(
                id = tipId,
                subject = Labels.game.requiresCharacters,
                text = Tips.game.requiresCharacters
              )(
                label(
                  s"${Labels.game.requiresCharacters} ",
                  input(
                    tpe := "checkbox",
                    aria.describedBy := tipId,
                    checked <-- gameType.signal.map(_ == GameType.Character),
                    onClick --> (_ =>
                        gameType.update(gt => if (gt == GameType.Character) GameType.Plain else GameType.Character)
                    )
                  )
                )
              )
          },
          // Only a character game has characters to make. Kept in `characterUrl` while hidden, so
          // unticking and reticking the box does not lose what was typed; a plain game saves none.
          child.maybe <-- gameType.signal.map(gt =>
              Option.when(gt == GameType.Character)(
                tipField(
                  id = s"$formKey-character-url-tip",
                  caption = Labels.game.characterUrl,
                  text = Tips.game.characterUrl
                )(
                  input(
                    tpe := "url",
                    controlled(value <-- characterUrl.signal, onInput.mapToValue --> characterUrl)
                  )
                )
              )
          ),
          tipField(
            id = s"$formKey-name-tip",
            caption = Labels.game.name,
            text = Tips.game.name
          )(input(controlled(value <-- name.signal, onInput.mapToValue --> name))),
          tipField(
            id = s"$formKey-display-name-tip",
            caption = Labels.game.displayName,
            text = Tips.game.displayName
          )(input(controlled(value <-- displayName.signal, onInput.mapToValue --> displayName))),
          tipField(
            id = s"$formKey-description-tip",
            caption = Labels.game.description,
            text = Tips.game.description
          )(input(controlled(value <-- description.signal, onInput.mapToValue --> description))),
          tipField(
            id = s"$formKey-url-tip",
            caption = Labels.game.url,
            text = Tips.game.url
          )(input(tpe := "url", controlled(value <-- url.signal, onInput.mapToValue --> url))),
          // The name means nothing on its own, and getting it wrong fails nowhere near this form.
          tipField(
            id = s"$formKey-engine-identity-tip",
            caption = Labels.game.engineIdentity,
            text = Tips.game.engineIdentity
          )(
            input(
              autoComplete := "off",
              spellCheck := false,
              controlled(value <-- engineIdentity.signal, onInput.mapToValue --> engineIdentity)
            )
          ),
          // Whether a key is stored is the one thing about it this form can tell, since the key itself never comes
          // back.
          tipField(
            id = s"$formKey-api-key-tip",
            caption = Labels.game.apiKey,
            text = existing match {
                case Some(game) if game.hasApiKey => Tips.game.apiKeySet
                case Some(_)                      => Tips.game.apiKeyMissing
                case None                         => Tips.game.apiKeyNew
            }
          )(
            // A stored key shows as a key's worth of stars over the empty box -- the length terraform gives the
            // deployed engines' keys. Not the value, so that it is never sent back as the key, and not a placeholder,
            // which a screen reader reads out star by star; the tip already says a key is set.
            span(
              cls := "stored-key",
              // A password field, so it is not shown while it is typed, and new-password so that no browser
              // offers to fill it with something saved for this site. Described by the tip itself, since
              // `tipField` points the span wrapped round it there rather than the box.
              input(
                tpe := "password",
                autoComplete := "new-password",
                spellCheck := false,
                aria.describedBy := s"$formKey-api-key-tip",
                controlled(value <-- apiKey.signal, onInput.mapToValue --> apiKey)
              ),
              Option.when(existing.exists(_.hasApiKey))(
                span(cls := "stars", aria.hidden := true, hidden <-- apiKey.signal.map(_.nonEmpty), "*" * 48)
              )
            )
          ),
          // Not asked while there is nothing to choose: Forfeit is the only action so far, and a dropdown of one
          // is a question with no answer to give. It comes back by itself when a second action is added.
          if (TimeoutAction.values.sizeIs > 1)
              tipField(
                id = s"$formKey-timeout-action-tip",
                caption = Labels.game.timeoutAction,
                text = Tips.game.timeoutAction
              )(
                select(
                  onChange.mapToValue --> (code => timeoutAction.set(TimeoutAction.fromCode(code))),
                  value <-- timeoutAction.signal.map(_.code),
                  TimeoutAction.values.toSeq.map(action => option(value := action.code, action.label))
                )
              )
          else emptyNode, {
              val tipId = s"$formKey-trusted-tip"
              withTip(
                id = tipId,
                subject = Labels.game.trusted,
                text = Tips.game.trusted
              )(
                label(
                  s"${Labels.game.trusted} ",
                  input(
                    tpe := "checkbox",
                    aria.describedBy := tipId,
                    controlled(checked <-- trusted.signal, onClick.mapToChecked --> trusted)
                  )
                )
              )
          },
          // Only an existing game can be disabled: a new one is created active, since a game created
          // hidden would look as though the button had done nothing.
          // In the warning colour: of everything on this form it is the one box that makes a game vanish.
          existing.fold(emptyNode) { _ =>
              val tipId = s"$formKey-disable-tip"
              withTip(
                id = tipId,
                subject = Labels.game.disable,
                text = Tips.game.disable
              )(
                label(
                  cls := "warn",
                  input(
                    tpe := "checkbox",
                    aria.describedBy := tipId,
                    controlled(checked <-- disabled.signal, onClick.mapToChecked --> disabled)
                  ),
                  Labels.game.disable
                )
              )
          },
          // Before the roles, because it decides whether there are any.
          {
              val tipId = s"$formKey-seats-tip"
              withTip(
                id = tipId,
                subject = Labels.game.seats,
                text = Tips.game.seats
              )(
                label(
                  cls := "field",
                  Labels.game.seats,
                  select(
                    aria.describedBy := tipId,
                    onChange.mapToValue --> { code =>
                        roleless.set(code == "open")
                        // Nobody goes first without roles: everyone moves at once.
                        if (code == "open") moveType.set(MoveType.Simultaneous)
                    },
                    value <-- roleless.signal.map(if (_) "open" else "roles"),
                    option(value := "roles", "Players take roles"),
                    option(value := "open", "Any number of players")
                  )
                )
              )
          },
          // Kept in their Vars while hidden, so switching back and forth does not lose what was typed.
          child <-- roleless.signal.map { open =>
              if (!open) roleEditor(formKey, roles)
              else
                  div(
                    tipField(
                      id = s"$formKey-min-players-tip",
                      caption = Labels.game.minPlayers,
                      text = Tips.game.minPlayers
                    )(
                      input(
                        tpe := "number",
                        inputMode := "numeric",
                        minAttr := "1",
                        controlled(value <-- minPlayers.signal, onInput.mapToValue --> minPlayers)
                      )
                    ),
                    tipField(
                      id = s"$formKey-max-players-tip",
                      caption = Labels.game.maxPlayers,
                      text = Tips.game.maxPlayers
                    )(
                      input(
                        tpe := "number",
                        inputMode := "numeric",
                        minAttr := "1",
                        controlled(value <-- maxPlayers.signal, onInput.mapToValue --> maxPlayers)
                      )
                    )
                  )
          }, {
              val tipId = s"$formKey-move-type-tip"
              withTip(
                id = tipId,
                subject = Labels.game.moveType,
                text = Tips.game.moveType
              )(
                label(
                  cls := "field",
                  Labels.game.moveType,
                  select(
                    aria.describedBy := tipId,
                    // A game without roles is always simultaneous.
                    // Qualified: `disabled` here is the Var that disables the game.
                    com.raquo.laminar.api.L.disabled <-- roleless.signal,
                    onChange.mapToValue --> (code => moveType.set(MoveType.fromCode(code))),
                    value <-- moveType.signal.map(_.code),
                    MoveType.values.toSeq.map(mt => option(value := mt.code, mt.label))
                  )
                )
              )
          }, {
              val tipId = s"$formKey-max-turns-tip"
              withTip(
                id = tipId,
                subject = Labels.game.maxTurns,
                text = Tips.game.maxTurns
              )(
                label(
                  cls := "field",
                  Labels.game.maxTurns,
                  input(
                    tpe := "number",
                    inputMode := "numeric",
                    minAttr := "1",
                    aria.describedBy := tipId,
                    controlled(value <-- maxTurns.signal, onInput.mapToValue --> maxTurns)
                  )
                )
              )
          },
          parameterEditor(formKey, parameters),
          busyButton(
            label = if (existing.isDefined) Buttons.game.save else Buttons.game.create,
            busy = savingBusy(existing),
            // A new game needs its key; an edit may leave the stored one alone. A key that is typed has
            // to be long enough, as the server checks too.
            disabledWhen = name.signal
                .combineWith(engineIdentity.signal, apiKey.signal)
                .map((n, identity, key) =>
                    n.trim.isEmpty || identity.trim.isEmpty || (existing.isEmpty && key.trim.isEmpty) ||
                        (key.trim.nonEmpty && key.trim.length < 24)
                )
          ) { busy =>
              val drafted = for {
                  // A game without roles has none to send, whatever the hidden role editor holds.
                  roleModels <- if (roleless.now()) Right(Nil) else rolesOf(roles.now())
                  range <-
                      if (roleless.now()) playersOf(minPlayers.now(), maxPlayers.now()).map(Some(_)) else Right(None)
                  parameterModels <- parametersOf(parameters.now())
                  turns <- maxTurnsOf(maxTurns.now())
              } yield (roleModels, range, parameterModels, turns)

              drafted match {
                  case Left(problem) => Store.reportProblem(problem)
                  case Right((roleModels, range, parameterModels, turns)) =>
                      val game = Game(
                        // Unassigned means create and the server assigns the real id — the same sentinel
                        // the challenge form uses; a real id means update that game.
                        gameId = existing.map(_.gameId).getOrElse(GameId.unassigned),
                        gameType = gameType.now(),
                        name = name.now().trim,
                        // Left blank, the game is shown by its name -- the server does the same.
                        displayName = Option(displayName.now().trim).filter(_.nonEmpty).getOrElse(name.now().trim),
                        description = description.now().trim,
                        url = url.now().trim,
                        // A game nobody can see is not what "create a game" means, and `refreshGames` only
                        // asks for active ones — creating it inactive would look like the button did nothing.
                        // An edit says whether it is disabled.
                        isActive = existing.isEmpty || !disabled.now(),
                        roles = roleModels,
                        parameters = parameterModels,
                        // Who the engine is: the name its API key is filed under, which is what a callback
                        // from it is matched against.
                        externalId = engineIdentity.now().trim,
                        timeoutAction = timeoutAction.now(),
                        minPlayers = range.map(_._1),
                        maxPlayers = range.flatMap(_._2),
                        moveType = if (range.isDefined) MoveType.Simultaneous else moveType.now(),
                        maxTurns = turns,
                        isTrusted = trusted.now(),
                        characterUrl = Option
                            .when(gameType.now() == GameType.Character)(characterUrl.now().trim)
                            .filter(_.nonEmpty)
                      )

                      // A save can be answered after a sign-out, and by then the dialog open may be the next
                      // player's: an answer from an earlier session closes nothing, opens nothing, writes
                      // nothing into the store and leaves the banner alone. See `Store.runSignedIn`.
                      Store.runSignedIn(
                        ApiClient.createGame(game, Option(apiKey.now().trim).filter(_.nonEmpty)),
                        busy
                      ) { saved =>
                          // Cleared either way: it has been sent, and an edit saved again should not resend it.
                          apiKey.set("")
                          if (existing.isEmpty) {
                              name.set("")
                              displayName.set("")
                              description.set("")
                              url.set("")
                              engineIdentity.set("")
                              characterUrl.set("")
                              trusted.set(false)
                              roleless.set(false)
                              minPlayers.set("2")
                              maxPlayers.set("")
                              moveType.set(MoveType.Sequential)
                              maxTurns.set("")
                              roles.set(List(emptyRole))
                              parameters.set(Nil)
                          } else {
                              // Re-drafted from what came back, so that roles added by this save carry the ids
                              // the insert gave them — without which saving twice would ask to add them again.
                              roles.set(saved.roles.map(draftOf).toList)
                              parameters.set(
                                saved.parameters.map(p => draftOf(p.asInstanceOf[GameParameter[String]])).toList
                              )
                          }
                          // Both copies of the game list, because a game saved while deactivated is in
                          // neither the active one nor reachable by `ensureGame` — its own screen would
                          // otherwise go on showing what it was before this save, and reopening this
                          // form would submit that. See `Store.gameSaved`. Before `onSaved`, so that a
                          // dialog's next step -- the new game's screen -- finds the game already there.
                          Store.gameSaved(saved)
                          onSaved(saved)
                      }
              }
          }
        )
    }
}
