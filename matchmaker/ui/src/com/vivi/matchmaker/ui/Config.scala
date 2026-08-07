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
    redirectUri: String
)

object Config {

  /** Fails loudly and immediately on a missing field. A UI that started up and then failed every
    * request with an opaque error would be far harder to diagnose than one that does not start.
    */
  lazy val current: Config = {
    val raw = js.Dynamic.global.window.matchmakerConfig

    if (js.isUndefined(raw) || raw == null)
      throw new IllegalStateException(
        "window.matchmakerConfig is not set; index.html must define it before loading main.js"
      )

    def field(name: String): String = {
      val value = raw.selectDynamic(name)
      if (js.isUndefined(value) || value == null || value.toString.isEmpty)
        throw new IllegalStateException(s"matchmakerConfig.$name is not set")
      value.toString
    }

    Config(
      apiEndpoint = field("apiEndpoint").stripSuffix("/"),
      hostedLoginUrl = field("hostedLoginUrl").stripSuffix("/"),
      clientId = field("clientId"),
      // Defaults to wherever the page is served from, which is what makes a local build work
      // against the dev pool without editing anything — provided this exact URL is one of the
      // pool's callback_urls, which Cognito matches literally.
      redirectUri = {
        val configured = raw.selectDynamic("redirectUri")
        if (js.isUndefined(configured) || configured == null) defaultRedirectUri
        else configured.toString
      }
    )
  }

  private def defaultRedirectUri: String = {
    val location = dom.window.location
    s"${location.protocol}//${location.host}${location.pathname}"
  }
}
