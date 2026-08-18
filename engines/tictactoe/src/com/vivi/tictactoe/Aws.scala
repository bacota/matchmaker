package com.vivi.tictactoe

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.identity.spi.{AwsCredentialsIdentity, AwsSessionCredentialsIdentity}

/** The credentials the engine signs with, from the standard environment variables the Lambda
  * runtime populates from the execution role. `None` anywhere else, which is what makes the
  * local server work with no AWS involved at all.
  */
case class AwsCredentials(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String])

object AwsCredentials {
  def fromEnvironment(env: String => Option[String] = k => Option(System.getenv(k))): Option[AwsCredentials] =
    for {
      accessKeyId <- env("AWS_ACCESS_KEY_ID")
      secretAccessKey <- env("AWS_SECRET_ACCESS_KEY")
    } yield AwsCredentials(accessKeyId, secretAccessKey, env("AWS_SESSION_TOKEN"))
}

class AwsError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** Signed HTTP, for the two AWS things this engine does itself: storing matches in DynamoDB, and
  * calling matchmaker's `AWS_IAM`-authorized callback routes.
  *
  * The SDK's signer, but not an SDK service client — the same trade matchmaker's own `SigV4`
  * makes. A DynamoDB client would bring Netty and Apache HttpClient for what is here two JSON
  * calls with no paging, no waiters and no retries beyond the one below.
  */
class SignedHttp(
    credentials: Option[AwsCredentials],
    region: String,
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    timeout: Duration = Duration.ofSeconds(10)
) {

  private val signer = AwsV4HttpSigner.create()

  /** POSTs `body` to `url`, signed for `service` when credentials are available.
    *
    * Unsigned when they are not, which is the local case: nothing local is behind `AWS_IAM`, and
    * a request that should have been signed is rejected by the service rather than quietly
    * accepted, so this cannot weaken a deployed call.
    */
  def post(url: String, body: String, service: String, headers: Map[String, String]): String =
    send("POST", url, Some(body), service, headers)

  def send(method: String, url: String, body: Option[String], service: String, headers: Map[String, String]): String = {
    val uri = URI.create(url)
    val payload = body.getOrElse("")
    val signed = credentials match {
      case Some(creds) => sign(method, uri, headers, payload, service, creds)
      case None        => Map.empty[String, String]
    }

    val builder = HttpRequest.newBuilder(uri).timeout(timeout)
    (headers ++ signed)
      // Host is the JDK client's to set, and its value is the one already signed.
      .filterNot((name, _) => name.equalsIgnoreCase("host"))
      .foreach((name, value) => builder.header(name, value))

    val request = body match {
      case Some(payload) => builder.method(method, HttpRequest.BodyPublishers.ofString(payload)).build()
      case None          => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
    }

    val response =
      try httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      catch { case e: Exception => throw AwsError(s"$method $url failed: ${e.getMessage}", e) }

    if (response.statusCode >= 200 && response.statusCode < 300) response.body
    else throw AwsError(s"$method $url returned ${response.statusCode}: ${response.body}")
  }

  private def sign(
      method: String,
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
      .foldLeft(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method.toUpperCase))) {
        case (b, (name, value)) => b.putHeader(name, value)
      }
      .build()

    val result = signer.sign { r =>
      r.identity(identity)
        .request(request)
        .payload(ContentStreamProvider.fromUtf8String(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
        // API Gateway signs the path exactly as sent; the signer's default is to encode it a
        // second time. Harmless for DynamoDB, whose path is always "/", and required for
        // execute-api — see matchmaker's SigV4 for the same two properties.
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
    }

    result.request.headers.asScala.view.mapValues(_.asScala.mkString(",")).toMap
  }
}
