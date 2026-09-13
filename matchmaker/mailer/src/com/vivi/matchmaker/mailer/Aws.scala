package com.vivi.matchmaker.mailer

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.identity.spi.{AwsCredentialsIdentity, AwsSessionCredentialsIdentity}

/** The credentials to sign with, from the standard variables the Lambda runtime populates from
  * the execution role. `None` anywhere else, which is what makes a local run need no AWS at all.
  */
case class AwsCredentials(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String])

object AwsCredentials {
  def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): Option[AwsCredentials] =
    for {
      accessKeyId <- env("AWS_ACCESS_KEY_ID")
      secretAccessKey <- env("AWS_SECRET_ACCESS_KEY")
    } yield AwsCredentials(accessKeyId, secretAccessKey, env("AWS_SESSION_TOKEN"))
}

class AwsError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** Signed HTTP to an AWS service, without an AWS service client.
  *
  * The SDK's *signer*, and the JDK's own HTTP client. A service client would bring Netty and
  * Apache HttpClient — some 8 MB — into a Lambda jar whose cold start is proportional to its
  * size, for what is here one JSON call per mail with no paging and no waiters.
  *
  * The third copy of this in the repository, after matchmaker's `util.SigV4` and each engine's
  * `Aws.scala`, and copied for the same reason the message type above is: this module is deployed
  * on its own and depends on nothing. It is some ninety lines of a signer being handed the four
  * properties it needs, and sharing it would cost far more than restating it.
  */
class SigV4(
    credentials: Option[AwsCredentials],
    region: String,
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    timeout: Duration = Duration.ofSeconds(10)
) {

  private val signer = AwsV4HttpSigner.create()

  /** POSTs `body` to `url`, signed for `service` when there are credentials to sign with.
    *
    * Unsigned when there are none. That cannot weaken a deployed call: AWS rejects an unsigned
    * request rather than accepting it, so the failure is loud and immediate — and it is what lets
    * a developer point this at a local fake with no credentials in the environment.
    */
  def post(url: String, body: String, service: String, headers: Map[String, String]): String = {
    val uri = URI.create(url)
    val signed = credentials match {
      case Some(creds) => sign(uri, headers, body, service, creds)
      case None        => Map.empty[String, String]
    }

    val builder = HttpRequest.newBuilder(uri).timeout(timeout)
    (headers ++ signed)
      // Host is the JDK client's to set, and its value is the one already signed.
      .filterNot((name, _) => name.equalsIgnoreCase("host"))
      .foreach((name, value) => builder.header(name, value))

    val request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()

    val response =
      try httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      catch { case e: Exception => throw AwsError(s"POST $url failed: ${e.getMessage}", e) }

    if (response.statusCode >= 200 && response.statusCode < 300) response.body
    else throw AwsError(s"POST $url returned ${response.statusCode}: ${response.body}")
  }

  private def sign(
      uri: URI,
      headers: Map[String, String],
      body: String,
      service: String,
      creds: AwsCredentials
  ): Map[String, String] = {
    val identity = creds.sessionToken match {
      case Some(token) => AwsSessionCredentialsIdentity.create(creds.accessKeyId, creds.secretAccessKey, token)
      case None        => AwsCredentialsIdentity.create(creds.accessKeyId, creds.secretAccessKey)
    }

    val request = headers
      .foldLeft(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.POST)) { case (b, (name, value)) =>
        b.putHeader(name, value)
      }
      .build()

    val result = signer.sign { r =>
      r.identity(identity)
        .request(request)
        .payload(ContentStreamProvider.fromUtf8String(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
        // The path is signed exactly as sent; the signer's default is to encode it a second time.
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
    }

    result.request.headers.asScala.view.mapValues(_.asScala.mkString(",")).toMap
  }
}
