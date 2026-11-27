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

    /** A code handed out as a link carries it as `?access=`, so the form opens already filled in. */
    private def codeFromLink: String =
        Option(new dom.URLSearchParams(dom.window.location.search).get("access")).getOrElse("")

    /** An access code box and its button. `onRedeemed` runs once the code has let the player in, after the barred games
      * are read again.
      */
    def redeemForm(onRedeemed: () => Unit = () => ()): HtmlElement = {
        val code = Var(codeFromLink)
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
              if (!busy.now())
                  Store.runSignedIn(ApiClient.redeemAccessCode(code.now()), busy) { opened =>
                      code.set("")
                      said.set(
                        if (opened.games.isEmpty) "You have been let in."
                        else s"You can now play ${opened.games.mkString(", ")}."
                      )
                      Store.refreshBarredGames().foreach(_ => onRedeemed())
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
        val held: Signal[Option[EngineAccess]] = Store.engineAccessByGame.signal.map(_.get(gameId)).distinct
        def refresh(): Unit = { Store.refreshEngineAccess(gameId); () }
        div(
          cls := "engine-access",
          withTip(id = tip, subject = Headings.access.section, text = Tips.access.section)(h3(Headings.access.section)),
          button(
            tpe := "button",
            aria.expanded <-- isOpen.signal,
            aria.describedBy := tip,
            Buttons.access.show,
            onClick --> { _ =>
                val opening = !isOpen.now()
                isOpen.set(opening)
                if (opening) refresh()
            }
          ),
          div(aria.live := "polite", cls := "detail", child.text <-- said.signal),
          div(
            hidden <-- isOpen.signal.map(!_),
            restrictedBox(gameId, held, said),
            h4(Headings.access.players),
            child <-- held.map {
                case None    => p(cls := "empty", "Loading…")
                case Some(a) => playerList(gameId, a.players, said, () => refresh())
            },
            letInForm(gameId, said, () => refresh()),
            h4(Headings.access.codes),
            child <-- held.map {
                case None    => emptyNode
                case Some(a) => codeList(gameId, a.codes, said, () => refresh())
            },
            makeCodeForm(gameId, said, () => refresh())
          )
        )
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
              if (!busy.now())
                  Store.runSignedIn(ApiClient.grantEngineAccess(gameId, nickname.now()), busy) { _ =>
                      said.set(s"${nickname.now().trim} let in.")
                      nickname.set("")
                      refresh()
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
                  val request = Json.AccessCodeRequest(
                    label = labelText.now(),
                    maxUses = maxUses.now().trim.toIntOption,
                    expiresAt =
                        days.now().trim.toIntOption.map(d => java.time.Instant.now().plusSeconds(d.toLong * 86400))
                  )
                  Store.runSignedIn(ApiClient.createAccessCode(gameId, request), busy) { answer =>
                      Store.freshAccessCodes.update(_.updated(gameId, answer.code))
                      said.set(
                        s"Code “${answer.access.label}” made. Copy it now: it is shown only until you make another or sign out."
                      )
                      labelText.set("")
                      maxUses.set("")
                      days.set("")
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
                        onClick --> (_ => dom.window.navigator.clipboard.writeText(fresh))
                      )
                    )
            }
          )
        )
    }
}
