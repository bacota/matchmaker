package com.vivi.matchmaker.ui

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import org.scalajs.dom

/** Proof Key for Code Exchange (RFC 7636).
  *
  * A public client cannot keep a secret, so an authorization code on its own is not enough:
  * anything that could intercept the redirect could redeem the code. PKCE closes that by having
  * the client invent a `code_verifier`, send only its SHA-256 hash when starting the flow, and
  * present the verifier itself when redeeming the code. Cognito then only redeems for whoever
  * started the flow.
  *
  * S256 is the only method used here. `plain` sends the verifier as the challenge, which defeats
  * the purpose, and Cognito accepts S256 everywhere.
  */
object Pkce {

  /** 32 bytes from the platform CSPRNG, base64url-encoded to 43 characters — within RFC 7636's
    * 43..128 range, and well above the 256 bits of entropy it asks for.
    */
  def newVerifier(): String = {
    val bytes = new Uint8Array(32)
    dom.crypto.getRandomValues(bytes)
    base64Url(bytes)
  }

  /** Opaque value round-tripped through the redirect and compared on return, so that a callback
    * this UI did not initiate is rejected rather than acted on (RFC 6749 §10.12).
    */
  def newState(): String = newVerifier()

  /** SHA-256 of the verifier's ASCII bytes, base64url-encoded. Asynchronous because
    * `crypto.subtle` is, which is also why starting a login is a `Future`.
    *
    * `crypto.subtle` is only available in a secure context: https, or http on localhost. Any
    * other host silently has no `subtle` at all, which is why that is checked here rather than
    * failing later with `undefined is not a function`.
    */
  def challengeFor(verifier: String): Future[String] = {
    if (js.isUndefined(dom.crypto.subtle) || dom.crypto.subtle == null)
      Future.failed(
        new IllegalStateException(
          "crypto.subtle is unavailable, so PKCE cannot be used; serve this over https or from localhost"
        )
      )
    else {
      val data = new Uint8Array(verifier.length)
      verifier.zipWithIndex.foreach { case (c, i) => data(i) = c.toShort }

      import scala.scalajs.js.Thenable.Implicits._
      import scala.concurrent.ExecutionContext.Implicits.global

      dom.crypto.subtle
        .digest("SHA-256", data.buffer)
        .map(digest => base64Url(new Uint8Array(digest.asInstanceOf[ArrayBuffer])))
    }
  }

  /** base64url without padding, as RFC 7636 §A requires: `+` and `/` would need escaping in the
    * query string, and `=` is not allowed in the challenge at all.
    */
  private def base64Url(bytes: Uint8Array): String = {
    val chars = new StringBuilder(bytes.length)
    for (i <- 0 until bytes.length) chars.append((bytes(i) & 0xff).toChar)

    dom.window
      .btoa(chars.toString)
      .replace("+", "-")
      .replace("/", "_")
      .replace("=", "")
  }
}
