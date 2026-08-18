package com.vivi.matchmaker.engine

import java.net.URI
import java.time.{Clock, Instant, ZoneOffset}
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import software.amazon.awssdk.identity.spi.{AwsCredentialsIdentity, AwsSessionCredentialsIdentity}

/** The credentials a signed request is made with.
  *
  * On Lambda these come from the environment, which the runtime populates from the function's
  * execution role and rotates before they expire — so there is nothing to fetch and nothing to
  * cache, and `sessionToken` is always present. It is optional here only because long-lived IAM
  * user keys (a local test run against a real API, say) have none.
  */
case class AwsCredentials(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String]) {

  private[engine] def identity: AwsCredentialsIdentity = sessionToken match {
    case Some(token) => AwsSessionCredentialsIdentity.create(accessKeyId, secretAccessKey, token)
    case None        => AwsCredentialsIdentity.create(accessKeyId, secretAccessKey)
  }
}

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
  * The algorithm itself comes from the AWS SDK's `AwsV4HttpSigner`. That is the signer only —
  * `http-auth-aws` and its spi/utils dependencies, no service client — so none of the SDK's
  * HTTP machinery (Netty, Apache HttpClient) is pulled into the Lambda artifact.
  */
object SigV4 {

  private val signer = AwsV4HttpSigner.create()

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
    val request = headers
      .foldLeft(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method.toUpperCase))) {
        case (builder, (name, value)) => builder.putHeader(name, value)
      }
      .build()

    val signed = signer.sign { r =>
      r.identity(credentials.identity)
        .request(request)
        .payload(ContentStreamProvider.fromUtf8String(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
        // API Gateway signs the path exactly as it is sent. The signer's default is to encode
        // it a second time, which every other service expects and which `execute-api` rejects.
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
        .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(now, ZoneOffset.UTC))
    }

    // The signer returns the whole request's headers, the ones passed in included; only the
    // ones it added are of interest to a caller that already has the rest.
    signed.request.headers.asScala.view
      .mapValues(_.asScala.mkString(","))
      .toMap
      .filterNot((name, _) => headers.contains(name))
  }
}
