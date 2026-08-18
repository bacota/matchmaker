package com.vivi.matchmaker.engine

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The credentials a signed request is made with.
  *
  * On Lambda these come from the environment, which the runtime populates from the function's
  * execution role and rotates before they expire — so there is nothing to fetch and nothing to
  * cache, and `sessionToken` is always present. It is optional here only because long-lived IAM
  * user keys (a local test run against a real API, say) have none.
  */
case class AwsCredentials(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String])

object AwsCredentials {

  /** Credentials from the standard AWS environment variables, or `None` if they are not set —
    * which is the case anywhere but inside Lambda or a shell that has exported them.
    */
  def fromEnvironment(env: String => Option[String] = k => Option(System.getenv(k))): Option[AwsCredentials] =
    for {
      accessKeyId <- env("AWS_ACCESS_KEY_ID")
      secretAccessKey <- env("AWS_SECRET_ACCESS_KEY")
    } yield AwsCredentials(accessKeyId, secretAccessKey, env("AWS_SESSION_TOKEN"))
}

/** AWS Signature Version 4 for API Gateway requests.
  *
  * An `AWS_IAM`-authorized route rejects a request that carries no credentials, whatever the
  * caller's role is allowed to do: the IAM policy decides what an identity may do, and the
  * signature is what establishes the identity in the first place. So matchmaker's Lambda role
  * being granted `execute-api:Invoke` on the game API is necessary but not sufficient — every
  * request it sends has to be signed as well, which is what this does.
  *
  * Implemented directly rather than via the AWS SDK because the SDK would pull Netty and Apache
  * HttpClient (about 8 MB) into a Lambda artifact deliberately kept small — see the `api` module
  * in `build.mill`. The algorithm is fully specified by AWS and is exercised in `SigV4Spec`
  * against the published test-suite vector, so hand-rolling it is not a guess.
  */
object SigV4 {

  private val algorithm = "AWS4-HMAC-SHA256"

  private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
  private val dateStampFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

  /** The headers to add to an otherwise-unsigned request so that API Gateway will accept it:
    * `Host`, `X-Amz-Date`, `Authorization`, and `X-Amz-Security-Token` when the credentials are
    * temporary. Any header already on the request that is also returned here must be replaced,
    * not duplicated — a signature covers the exact header values that are sent.
    *
    * @param headers headers that are part of the request and must therefore be signed; `Host`,
    *                `X-Amz-Date` and `X-Amz-Security-Token` are added here and need not be given
    */
  def sign(
      method: String,
      uri: URI,
      headers: Map[String, String],
      body: String,
      credentials: AwsCredentials,
      region: String,
      service: String = "execute-api",
      now: Instant = Instant.now()
  ): Map[String, String] = {
    val amzDate = amzDateFormat.format(now)
    val dateStamp = dateStampFormat.format(now)
    val host = if (uri.getPort > 0) s"${uri.getHost}:${uri.getPort}" else uri.getHost

    val signedRequestHeaders =
      headers ++ Map("host" -> host, "x-amz-date" -> amzDate) ++
        credentials.sessionToken.map("x-amz-security-token" -> _)

    // Canonical headers are lower-cased, sorted by name, and have runs of whitespace in their
    // values collapsed — the signature is over this normalized form, not over the wire bytes,
    // so that a proxy reformatting a header does not invalidate it.
    val canonicalHeaderPairs =
      signedRequestHeaders.toList.map((name, value) => (name.toLowerCase, value.trim.replaceAll("\\s+", " "))).sortBy(_._1)
    val canonicalHeaders = canonicalHeaderPairs.map((name, value) => s"$name:$value\n").mkString
    val signedHeaders = canonicalHeaderPairs.map(_._1).mkString(";")

    val payloadHash = hex(sha256(body))

    val canonicalRequest =
      List(
        method.toUpperCase,
        canonicalPath(uri),
        canonicalQuery(uri),
        canonicalHeaders,
        signedHeaders,
        payloadHash
      ).mkString("\n")

    val credentialScope = s"$dateStamp/$region/$service/aws4_request"
    val stringToSign = List(algorithm, amzDate, credentialScope, hex(sha256(canonicalRequest))).mkString("\n")

    val signature = hex(hmac(signingKey(credentials.secretAccessKey, dateStamp, region, service), stringToSign))

    Map(
      "Host" -> host,
      "X-Amz-Date" -> amzDate,
      "Authorization" ->
        s"$algorithm Credential=${credentials.accessKeyId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    ) ++ credentials.sessionToken.map("X-Amz-Security-Token" -> _)
  }

  // An empty path signs as "/", and each segment is encoded — except that the separators
  // themselves are not, which is why this cannot just be a whole-string encode.
  private def canonicalPath(uri: URI): String = {
    val path = Option(uri.getPath).filter(_.nonEmpty).getOrElse("/")
    path.split("/", -1).map(uriEncode).mkString("/")
  }

  // Query parameters are sorted by encoded name (then by value), which is what makes the
  // canonical form independent of the order they happen to appear in the url.
  private def canonicalQuery(uri: URI): String =
    Option(uri.getRawQuery).filter(_.nonEmpty) match {
      case None => ""
      case Some(raw) =>
        raw
          .split("&")
          .map { pair =>
            pair.split("=", 2) match {
              case Array(name)        => (uriEncode(decode(name)), "")
              case Array(name, value) => (uriEncode(decode(name)), uriEncode(decode(value)))
            }
          }
          .sorted
          .map((name, value) => s"$name=$value")
          .mkString("&")
    }

  private def decode(s: String): String = java.net.URLDecoder.decode(s, UTF_8)

  /* AWS's encoding rules, which are not those of URLEncoder: space is %20 rather than '+',
   * and the unreserved set is exactly A-Z a-z 0-9 - _ . ~ */
  private def uriEncode(s: String): String =
    s.getBytes(UTF_8).map { b =>
      val c = (b & 0xff).toChar
      if (c.isLetterOrDigit && c < 128 || c == '-' || c == '_' || c == '.' || c == '~') c.toString
      else f"%%${b & 0xff}%02X"
    }.mkString

  private def signingKey(secretAccessKey: String, dateStamp: String, region: String, service: String): Array[Byte] = {
    val kDate = hmac(s"AWS4$secretAccessKey".getBytes(UTF_8), dateStamp)
    val kRegion = hmac(kDate, region)
    val kService = hmac(kRegion, service)
    hmac(kService, "aws4_request")
  }

  private def hmac(key: Array[Byte], data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))
  }

  private def sha256(s: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8))

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
}
