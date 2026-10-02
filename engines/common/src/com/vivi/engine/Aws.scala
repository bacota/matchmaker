package com.vivi.engine

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.identity.spi.{AwsCredentialsIdentity, IdentityProvider}

/** Where the engine's signing credentials come from. */
object AwsCredentials {

    /** The SDK's default credential chain when there are AWS credentials to be had, and `None` otherwise — which is
      * what makes the local server work with no AWS involved at all.
      *
      * A provider asked at each signature rather than credentials read once, because of SnapStart. Java fixes
      * `System.getenv` at JVM start, and under SnapStart JVM start is publish time: a restored engine that had read the
      * execution role's keys from the environment would sign with credentials that were never there or have long
      * expired, and DynamoDB would refuse every call. A SnapStart function is not given those keys at all; it gets a
      * container credentials endpoint instead, which the SDK's chain knows how to ask, caches and refreshes. It is the
      * same reason matchmaker's `SqsNotifier` uses the SDK's provider rather than signing by hand.
      *
      * "There are credentials" is being in Lambda, or having keys in the environment for a local run against real AWS.
      * Nothing is resolved here: the chain does its first lookup at the first signature, which is after any restore.
      */
    def provider(
        env: String => Option[String] = k => Option(System.getenv(k))
    ): Option[IdentityProvider[? <: AwsCredentialsIdentity]] =
        Option.when(env("AWS_LAMBDA_FUNCTION_NAME").isDefined || env("AWS_ACCESS_KEY_ID").isDefined)(
          DefaultCredentialsProvider.builder().build()
        )
}

class AwsError(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** Signed HTTP, for the two AWS things this engine does itself: storing matches in DynamoDB, and calling matchmaker's
  * `AWS_IAM`-authorized callback routes.
  *
  * The SDK's signer, but not an SDK service client — the same trade matchmaker's own `SigV4` makes. A DynamoDB client
  * would bring Netty and Apache HttpClient for what is here two JSON calls with no paging, no waiters and no retries
  * beyond the one below.
  */
class SignedHttp(
    credentials: Option[IdentityProvider[? <: AwsCredentialsIdentity]],
    region: String,
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    timeout: Duration = Duration.ofSeconds(10)
) {

    private val signer = AwsV4HttpSigner.create()

    /** POSTs `body` to `url`, signed for `service` when credentials are available.
      *
      * Unsigned when they are not, which is the local case: nothing local is behind `AWS_IAM`, and a request that
      * should have been signed is rejected by the service rather than quietly accepted, so this cannot weaken a
      * deployed call.
      */
    def post(url: String, body: String, service: String, headers: Map[String, String]): String =
        send("POST", url, Some(body), service, headers)

    def send(
        method: String,
        url: String,
        body: Option[String],
        service: String,
        headers: Map[String, String]
    ): String = {
        val (status, answer) = exchange(method, url, body, service, headers)
        if (status >= 200 && status < 300) answer
        else throw AwsError(s"$method $url returned $status: $answer")
    }

    /** [[send]], answering with the status rather than refusing every one that is not a success — for a caller to whom
      * a particular refusal means something, as a 410 from a closed Play Live connection does.
      */
    def exchange(
        method: String,
        url: String,
        body: Option[String],
        service: String,
        headers: Map[String, String]
    ): (Int, String) = {
        val uri = URI.create(url)
        val payload = body.getOrElse("")
        val signed = credentials match {
            case Some(provider) =>
                // `join` on a provider that answers from its cache almost always; a refresh is a
                // local call to the Lambda credentials endpoint.
                sign(method, uri, headers, payload, service, provider.resolveIdentity().join())
            case None => Map.empty[String, String]
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

        (response.statusCode, response.body)
    }

    private def sign(
        method: String,
        uri: URI,
        headers: Map[String, String],
        body: String,
        service: String,
        identity: AwsCredentialsIdentity
    ): Map[String, String] = {

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

/** DynamoDB's JSON API, one signed call at a time — for the match store and Play Live's connections, which between them
  * need five of its operations and none of its client's machinery.
  */
class DynamoDb(http: SignedHttp, region: String) {

    private val endpoint = s"https://dynamodb.$region.amazonaws.com"

    def call(target: String, payload: ujson.Obj): ujson.Value =
        ujson.read(
          http.post(
            endpoint,
            ujson.write(payload),
            "dynamodb",
            Map("content-type" -> "application/x-amz-json-1.0", "x-amz-target" -> s"DynamoDB_20120810.$target")
          )
        )
}
