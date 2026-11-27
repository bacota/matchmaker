package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.api.Json

/** Engines only listed players may play: the form a player redeems an access code with, and the section a game's admins
  * say who may play it from.
  */
object EngineAccessViews {

    import Views.{busyButton, field, freshTipId, tipField, withTip}

    /* Whether the address has been read for a linked code yet: it is read once, by the first redeem form. */
    private var isLinkRead = false

    /** A code handed out as a link carries it as `?access=`, so the form opens already filled in. Read once, by the
      * first redeem form, and then taken out of the address, so that the code is not left in the address bar and the
      * browser's history. Kept in [[Store.linkedAccessCode]], which a redeem of it and the end of the session clear, so
      * that it fills in no form after it has been used or for the next player.
      */
    private def readCodeFromLink(): Unit =
        if (!isLinkRead) {
            isLinkRead = true
            val url = new dom.URL(dom.window.location.href)
            Option(url.searchParams.get("access")).filter(_.nonEmpty).foreach { code =>
                url.searchParams.delete("access")
                dom.window.history.replaceState(dom.window.history.state, "", url.href)
                Store.linkedAccessCode.set(code)
            }
        }

    /** An access code box and its button.
      *
      * `replacedBy` is for a form that letting the player in takes away, as the competition screen's is: its
      * confirmation is said through [[Views.announce]], which outlives it, rather than in its own live region, and
      * `replacedBy` runs once the barred games have been read again, to move focus to what took its place.
      */
    def redeemForm(replacedBy: Option[() => Unit] = None): HtmlElement = {
        readCodeFromLink()
        val code = Var(Store.linkedAccessCode.now())
        val said = Var("")
        val tip = freshTipId("access-code-tip")
        div(
          cls := "row access-redeem",
          tipField(id = tip, caption = Labels.access.code, text = Tips.access.code)(
            input(
              tpe := "text",
              autoComplete := "off",
              spellCheck := false,
              controlled(value <-- code.signal, onInput.mapToValue --> code)
            )
          ),
          busyButton(label = Buttons.access.redeem)(busy =>
              if (!busy.now()) {
                  val sent = code.now()
                  Store.runSignedIn(ApiClient.redeemAccessCode(sent), busy) { opened =>
                      // Cleared only if it still holds what was sent, not what was typed meanwhile.
                      if (code.now() == sent) code.set("")
                      if (Store.linkedAccessCode.now() == sent) Store.linkedAccessCode.set("")
                      val confirmation =
                          if (opened.games.isEmpty) "You have been let in."
                          else s"You can now play ${opened.games.mkString(", ")}."
                      replacedBy match {
                          case None => said.set(confirmation); Store.refreshBarredGames()
                          case Some(moveFocus) =>
                              Views.announce(confirmation)
                              Store.refreshBarredGames().foreach(_ => moveFocus())
                      }
                  }
              }
          ),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal)
        )
    }

    /** Who may play the game's engine, for an admin of it: closed until asked for, as its lists are fetched then. */
    def adminSection(game: Game): HtmlElement = {
        val gameId = game.gameId
        val isOpen = Var(false)
        val said = Var("")
        val tip = freshTipId("access-tip")
        val panelId = freshTipId("access-panel")
        val held: Signal[Option[EngineAccess]] = Store.engineAccessByGame.signal.map(_.get(gameId)).distinct
        def refresh(): Unit = { Store.refreshEngineAccess(gameId); () }
        val letIn = letInForm(gameId, said, () => refresh())
        val makeCode = makeCodeForm(gameId, said, () => refresh())
        lazy val players: HtmlElement = div(child <-- held.map {
            case None => p(cls := "empty", "Loading…")
            case Some(a) =>
                playerList(gameId, a.players, said, refocusing(gameId, list = players, fallback = letIn))
        })
        lazy val codes: HtmlElement = div(child <-- held.map {
            case None    => emptyNode
            case Some(a) => codeList(gameId, a.codes, said, refocusing(gameId, list = codes, fallback = makeCode))
        })
        div(
          cls := "engine-access",
          withTip(id = tip, subject = Headings.access.section, text = Tips.access.section)(h3(Headings.access.section)),
          button(
            tpe := "button",
            aria.expanded <-- isOpen.signal,
            aria.controls := panelId,
            aria.describedBy := tip,
            Buttons.access.show,
            onClick --> { _ =>
                val opening = !isOpen.now()
                isOpen.set(opening)
                // Said once the lists have come, since they arrive after the panel opens with "Loading…" in it.
                if (opening) Store.refreshEngineAccess(gameId).foreach { _ =>
                    if (isOpen.now()) Store.engineAccessByGame.now().get(gameId).foreach(a => said.set(summary(a)))
                }
            }
          ),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal),
          div(
            idAttr := panelId,
            hidden <-- isOpen.signal.map(!_),
            aria.busy <-- held.map(_.isEmpty),
            restrictedBox(gameId, held, said),
            h4(Headings.access.players),
            players,
            letIn,
            h4(Headings.access.codes),
            codes,
            makeCode
          )
        )
    }

    /* What the lists hold, said as they arrive. */
    private def summary(a: EngineAccess): String = {
        def count(n: Int, one: String, many: String) = if (n == 1) s"1 $one" else s"$n $many"
        val live = a.codes.count(_.isRedeemable(java.time.Instant.now()))
        s"${count(a.players.size, "player", "players")} let in, ${count(live, "code", "codes")} that can be used."
    }

    /* A refresh for a button in `list` that the refresh takes away, as Remove and Revoke are: once the list is
     * redrawn, if the focus fell with the button, it goes to the button now in that place (the next row's), else the
     * last, else the first field of `fallback`. */
    private def refocusing(gameId: GameId, list: => HtmlElement, fallback: HtmlElement): () => Unit = () => {
        def buttons = list.ref.querySelectorAll("button").toList.map(_.asInstanceOf[dom.html.Element])
        val place = buttons.indexWhere(_ == dom.document.activeElement)
        Store.refreshEngineAccess(gameId).foreach { _ =>
            if (place >= 0 && Views.hasFocusFallen) {
                val now = buttons
                now.lift(place)
                    .orElse(now.lastOption)
                    .orElse(Option(fallback.ref.querySelector("input")).map(_.asInstanceOf[dom.html.Element]))
                    .foreach(_.focus())
            }
        }
    }

    private def restrictedBox(gameId: GameId, held: Signal[Option[EngineAccess]], said: Var[String]): HtmlElement = {
        val busy = Var(false)
        val tip = freshTipId("access-restricted-tip")
        withTip(id = tip, subject = Labels.access.restricted, text = Tips.access.restricted)(
          label(
            cls := "check",
            input(
              tpe := "checkbox",
              aria.describedBy := tip,
              aria.disabled <-- busy.signal,
              controlled(
                checked <-- held.map(_.exists(_.isRestricted)),
                onClick.mapToChecked --> { on =>
                    if (!busy.now())
                        Store.runSignedIn(ApiClient.setRestricted(gameId, on), busy) { _ =>
                            said.set(
                              if (on) "Only players let in may enter now." else "Every player may enter now."
                            )
                            Store.refreshEngineAccess(gameId)
                        }
                }
              )
            ),
            Labels.access.restricted
          )
        )
    }

    private def playerList(
        gameId: GameId,
        players: List[EngineListedPlayer],
        said: Var[String],
        refresh: () => Unit
    ): HtmlElement =
        if (players.isEmpty) p(cls := "empty", "Nobody has been let in yet.")
        else
            ul(
              cls := "plain",
              players.map { listed =>
                  val how = listed.grantedBy
                      .map(b => s"let in by ${b.nickname}")
                      .orElse(listed.codeLabel.map(l => s"by code “$l”"))
                      .getOrElse("by a code")
                  li(
                    cls := "row",
                    span(listed.player.nickname),
                    span(cls := "detail", s" — $how"),
                    busyButton(label = Buttons.access.revoke)(busy =>
                        if (!busy.now())
                            Store.runSignedIn(ApiClient.revokeEngineAccess(gameId, listed.player.playerId), busy) { _ =>
                                said.set(s"${listed.player.nickname} removed.")
                                refresh()
                            }
                    ).amend(aria.label := Buttons.access.revokeNamed(nickname = listed.player.nickname))
                  )
              }
            )

    private def letInForm(gameId: GameId, said: Var[String], refresh: () => Unit): HtmlElement = {
        val nickname = Var("")
        val tip = freshTipId("access-nickname-tip")
        div(
          cls := "row",
          tipField(id = tip, caption = Labels.access.nickname, text = Tips.access.nickname)(
            input(
              tpe := "text",
              autoComplete := "off",
              controlled(value <-- nickname.signal, onInput.mapToValue --> nickname)
            )
          ),
          busyButton(label = Buttons.access.letIn)(busy =>
              if (!busy.now()) {
                  val sent = nickname.now()
                  Store.runSignedIn(ApiClient.grantEngineAccess(gameId, sent), busy) { _ =>
                      said.set(s"${sent.trim} let in.")
                      if (nickname.now() == sent) nickname.set("")
                      refresh()
                  }
              }
          )
        )
    }

    private def codeList(
        gameId: GameId,
        codes: List[EngineAccessCode],
        said: Var[String],
        refresh: () => Unit
    ): HtmlElement =
        if (codes.isEmpty) p(cls := "empty", "No codes yet.")
        else {
            val now = java.time.Instant.now()
            ul(
              cls := "plain",
              codes.map { c =>
                  val uses = c.maxUses.fold(s"${c.uses} used")(m => s"${c.uses} of $m used")
                  val state =
                      if (c.isRevoked) "revoked"
                      else if (c.expiresAt.exists(!now.isBefore(_))) "expired"
                      else c.expiresAt.fold("never expires")(e => s"expires ${Format.date(e)}")
                  li(
                    cls := "row",
                    span(c.label),
                    span(cls := "detail", s" — $uses, $state"),
                    if (c.isRevoked) emptyNode
                    else
                        busyButton(label = Buttons.access.revokeCode)(busy =>
                            if (!busy.now())
                                Store.runSignedIn(ApiClient.revokeAccessCode(gameId, c.codeId), busy) { _ =>
                                    said.set(s"Code “${c.label}” revoked.")
                                    refresh()
                                }
                        ).amend(aria.label := Buttons.access.revokeCodeNamed(label = c.label))
                  )
              }
            )
        }

    /* Puts the code on the clipboard and says whether it worked. The clipboard is missing without a secure context,
     * and writing may be refused, so a failure says to copy it by hand, which the code's `user-select: all` makes
     * one tap. */
    private def copy(fresh: String, said: Var[String]): Unit = {
        val byHand = "Could not copy it: select the code and copy it yourself."
        val clipboard = dom.window.navigator.asInstanceOf[scalajs.js.Dynamic].clipboard
        if (scalajs.js.isUndefined(clipboard)) said.set(byHand)
        else
            dom.window.navigator.clipboard
                .writeText(fresh)
                .toFuture
                .onComplete(outcome => said.set(if (outcome.isSuccess) "Code copied." else byHand))
    }

    private def makeCodeForm(gameId: GameId, said: Var[String], refresh: () => Unit): HtmlElement = {
        val labelText = Var("")
        val maxUses = Var("")
        val days = Var("")
        // The code just made: it is stored only as a hash, and nobody can see it again.
        val made = Store.freshAccessCodes.signal.map(_.get(gameId)).distinct
        val codeTag = com.raquo.laminar.api.L.code
        def numberBox(v: Var[String]): HtmlElement =
            input(
              tpe := "number",
              minAttr := "1",
              controlled(value <-- v.signal, onInput.mapToValue --> v)
            )
        val labelTip = freshTipId("access-label-tip")
        val usesTip = freshTipId("access-uses-tip")
        val daysTip = freshTipId("access-days-tip")
        div(
          cls := "access-make-code",
          tipField(id = labelTip, caption = Labels.access.label, text = Tips.access.label)(
            input(
              tpe := "text",
              autoComplete := "off",
              controlled(value <-- labelText.signal, onInput.mapToValue --> labelText)
            )
          ),
          tipField(id = usesTip, caption = Labels.access.maxUses, text = Tips.access.maxUses)(numberBox(maxUses)),
          tipField(id = daysTip, caption = Labels.access.expiresDays, text = Tips.access.expiresDays)(numberBox(days)),
          busyButton(label = Buttons.access.makeCode)(busy =>
              if (!busy.now()) {
                  val (sentLabel, sentUses, sentDays) = (labelText.now(), maxUses.now(), days.now())
                  val request = Json.AccessCodeRequest(
                    label = sentLabel,
                    maxUses = sentUses.trim.toIntOption,
                    expiresAt =
                        sentDays.trim.toIntOption.map(d => java.time.Instant.now().plusSeconds(d.toLong * 86400))
                  )
                  Store.runSignedIn(ApiClient.createAccessCode(gameId, request), busy) { answer =>
                      Store.freshAccessCodes.update(_.updated(gameId, answer.code))
                      said.set(
                        s"Code “${answer.access.label}” made. Copy it now: it is shown only until you make another or sign out."
                      )
                      // Each cleared only if it still holds what was sent, not what was typed meanwhile.
                      if (labelText.now() == sentLabel) labelText.set("")
                      if (maxUses.now() == sentUses) maxUses.set("")
                      if (days.now() == sentDays) days.set("")
                      refresh()
                  }
              }
          ),
          div(
            aria.live := "polite",
            child <-- made.map {
                case None => emptyNode
                case Some(fresh) =>
                    p(
                      cls := "row new-code",
                      codeTag(fresh),
                      button(
                        tpe := "button",
                        Buttons.access.copy,
                        onClick --> (_ => copy(fresh, said))
                      )
                    )
            }
          )
        )
    }
}
