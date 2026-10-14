package com.vivi.matchmaker.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** Entry point.
  *
  * The whole UI is one page with no router: every screen in `ui.txt` is a section of it, and the only URL that ever
  * matters is the one Cognito redirects back to.
  */
object Main {

    @JSExportTopLevel("main")
    def main(): Unit = {
        // Must run before anything reads the token: this may be the page load that carries an
        // authorization code back from the hosted sign-up or password-reset pages, and until it has
        // been redeemed there is no session. An ordinary load has no code and falls straight through.
        if (Config.current.isHeaderAuth) {
            // Nothing to sign in to: the identity is whatever the config says. Straight to the app.
            Store.signedIn.set(true)
            Store.loadAll()
        } else
            Auth.completeSignIn() match {
                // A code came back from the hosted pages, so this load *is* a sign-in: the token was
                // issued seconds ago and its email claim is what Cognito currently holds, which is the one
                // moment matchmaker's copy of the address is reconciled with it.
                case Some(signIn) =>
                    signIn.onComplete { outcome =>
                        outcome.failed.foreach(Store.report)
                        Store.signedIn.set(Auth.isSignedIn)
                        if (Auth.isSignedIn) Store.loadAll(justSignedIn = true)
                    }
                // An ordinary load, resuming a session that may be an hour old. Deliberately not treated
                // as a sign-in: the claims in a token that old can be out of date, and a reload is exactly
                // when reconciling from them would undo a change made since.
                case None =>
                    Store.signedIn.set(Auth.isSignedIn)
                    if (Auth.isSignedIn) Store.loadAll()
            }

        renderOnDomContentLoaded(dom.document.getElementById("app"), Views.app)
    }
}
