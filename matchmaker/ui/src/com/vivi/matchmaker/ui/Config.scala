package com.vivi.matchmaker.ui

import scala.scalajs.js
import org.scalajs.dom

/** Where this build of the UI points: the API, and the Cognito pool in front of it.
  *
  * Read from a `window.matchmakerConfig` object rather than compiled in, so that the same
  * `main.js` can be served against dev or prod by changing one script tag. Every value here is
  * public — the client id is a query parameter of the authorize URL — so none of this is a
  * secret being handed to the browser.
  *
  * The values come from the terraform outputs of the matching environment:
  * `api_endpoint`, `hosted_login_url` and `user_pool_client_id`.
  */
case class Config(
    apiEndpoint: String,
    hostedLoginUrl: String,
    clientId: String,
    redirectUri: String,
    authMode: String,
    localExternalId: String
) {

  /** True in the local development mode, where there is no Cognito and the caller's identity is
    * asserted rather than proved. See `Config.HeaderAuth`.
    */
  def headerAuth: Boolean = authMode == Config.HeaderAuth
}

object Config {

  /** Tokens from Cognito hosted login. The only mode that is safe anywhere real. */
  val CognitoAuth = "cognito"

  /** Development only: the identity is sent in `X-External-Id` and believed. It exists to click
    * through the UI against `LocalServer` with no AWS involved.
    *
    * It is worth being blunt about what this is: **authentication turned off**. Anyone who can
    * reach the API can be anyone. It is usable only because `LocalServer` binds to loopback and
    * the deployed function does not accept it — `Handler` defaults to `gateway`, and the
    * terraform sets `AUTH_MODE=gateway` explicitly.
    */
  val HeaderAuth = "header"

  /** Fails loudly and immediately on a missing field. A UI that started up and then failed every
    * request with an opaque error would be far harder to diagnose than one that does not start.
    */
  lazy val current: Config = {
    val raw = js.Dynamic.global.window.matchmakerConfig

    if (js.isUndefined(raw) || raw == null)
      throw new IllegalStateException(
        "window.matchmakerConfig is not set; index.html must define it before loading main.js"
      )

    def optional(name: String): Option[String] = {
      val value = raw.selectDynamic(name)
      if (js.isUndefined(value) || value == null || value.toString.isEmpty) None
      else Some(value.toString)
    }

    def field(name: String): String =
      optional(name).getOrElse(throw new IllegalStateException(s"matchmakerConfig.$name is not set"))

    val mode = optional("authMode").getOrElse(CognitoAuth)

    if (mode != CognitoAuth && mode != HeaderAuth)
      throw new IllegalStateException(s"matchmakerConfig.authMode is '$mode'; expected '$CognitoAuth' or '$HeaderAuth'")

    Config(
      apiEndpoint = field("apiEndpoint").stripSuffix("/"),
      // The Cognito settings are only needed to sign in, so header mode does not require them —
      // the point of that mode is to run with no pool at all.
      hostedLoginUrl = (if (mode == HeaderAuth) optional("hostedLoginUrl") else Some(field("hostedLoginUrl")))
        .getOrElse("")
        .stripSuffix("/"),
      clientId = (if (mode == HeaderAuth) optional("clientId") else Some(field("clientId"))).getOrElse(""),
      // Defaults to wherever the page is served from, which is what makes a local build work
      // against the dev pool without editing anything — provided this exact URL is one of the
      // pool's callback_urls, which Cognito matches literally.
      redirectUri = optional("redirectUri").getOrElse(defaultRedirectUri),
      authMode = mode,
      // Whoever you are when there is nothing to prove it. Configurable so two browser profiles
      // can be two players, which is what testing a challenge and an acceptance needs.
      localExternalId = optional("localExternalId").getOrElse("local-dev-1")
    )
  }

  private def defaultRedirectUri: String = {
    val location = dom.window.location
    s"${location.protocol}//${location.host}${location.pathname}"
  }
}
