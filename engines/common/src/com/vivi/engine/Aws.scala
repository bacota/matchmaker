package com.vivi.engine

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration}
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.http.auth.spi.signer.{HttpSigner, SignRequest}
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

/** Signed HTTP, for the AWS things an engine does itself: storing matches in DynamoDB, pushing to Play Live, and
  * keeping files in S3.
  *
  * The SDK's signer, but not an SDK service client — the same trade matchmaker's own `SigV4` makes. A DynamoDB client
  * would bring Netty and Apache HttpClient for what is here a few JSON calls with no paging, no waiters and no retries
  * beyond the one below.
  *
  * S3 is signed as S3 wants it, which differs from every other service in two ways: the path is encoded once, not
  * twice, and the payload's SHA-256 is sent as `x-amz-content-sha256`. A request whose `service` is `s3` gets both.
  */
class SignedHttp(
    credentials: Option[IdentityProvider[? <: AwsCredentialsIdentity]],
    region: String,
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    timeout: Duration = Duration.ofSeconds(10),
    clock: Clock = Clock.systemUTC()
) {

    private val signer = AwsV4HttpSigner.create()

    /** POSTs `body` to `url`, signed for `service` when credentials are available.
      *
      * Unsigned when they are not, which is the local case: nothing local is behind `AWS_IAM`, and a request that
      * should have been signed is rejected by the service rather than quietly accepted, so this cannot weaken a
      * deployed call.
      */
    def post(url: String, body: String, service: String, headers: Map[String, String]): String =
        send(method = "POST", url = url, body = Some(body), service = service, headers = headers)

    def send(
        method: String,
        url: String,
        body: Option[String],
        service: String,
        headers: Map[String, String]
    ): String = {
        val (status, answer) = exchange(method = method, url = url, body = body, service = service, headers = headers)
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
        val answer = exchangeBytes(
          method = method,
          url = url,
          body = body.map(_.getBytes(UTF_8)),
          service = service,
          headers = headers
        )
        (answer.status, String(answer.body, UTF_8))
    }

    /** [[exchange]] with a body of bytes each way, and the response's headers: for S3, whose objects are not text. */
    def exchangeBytes(
        method: String,
        url: String,
        body: Option[Array[Byte]],
        service: String,
        headers: Map[String, String]
    ): HttpAnswer = {
        val uri = URI.create(url)
        val payload = body.getOrElse(Array.emptyByteArray)
        val signed = credentials match {
            case Some(provider) =>
                // `join` on a provider that answers from its cache almost always; a refresh is a
                // local call to the Lambda credentials endpoint.
                sign(
                  method = method,
                  uri = uri,
                  headers = headers,
                  body = payload,
                  service = service,
                  identity = provider.resolveIdentity().join()
                )
            case None => Map.empty[String, String]
        }

        val builder = HttpRequest.newBuilder(uri).timeout(timeout)
        (headers ++ signed)
            // Host is the JDK client's to set, and its value is the one already signed; so is the length.
            .filterNot((name, _) => name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length"))
            .foreach((name, value) => builder.header(name, value))

        val request = body match {
            case Some(bytes) => builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
            case None        => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }

        val response =
            try httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            catch { case e: Exception => throw AwsError(s"$method $url failed: ${e.getMessage}", e) }

        HttpAnswer(
          status = response.statusCode,
          body = response.body,
          headers = response.headers.map.asScala.map((name, values) => name.toLowerCase -> values.asScala.toSeq).toMap
        )
    }

    /** `url` signed in its query string, good for `expiresIn`, for a browser to fetch without credentials of its own:
      * an S3 object's presigned GET. With no credentials, `url` as it is, which is what the local case can use.
      */
    def presign(method: String, url: String, service: String, expiresIn: Duration): String =
        credentials match {
            case None => url
            case Some(provider) =>
                val request =
                    SdkHttpRequest.builder().uri(URI.create(url)).method(SdkHttpMethod.fromValue(method.toUpperCase))
                val result = signer.sign { r =>
                    configure(r.identity(provider.resolveIdentity().join()).request(request.build()), service)
                        .putProperty(
                          AwsV4FamilyHttpSigner.AUTH_LOCATION,
                          AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING
                        )
                        .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, expiresIn)
                        .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, java.lang.Boolean.FALSE)
                }
                result.request.getUri.toString
        }

    /** The headers that sign this request: what [[exchangeBytes]] adds to it. */
    private[engine] def sign(
        method: String,
        uri: URI,
        headers: Map[String, String],
        body: Array[Byte],
        service: String,
        identity: AwsCredentialsIdentity
    ): Map[String, String] = {

        val request = headers
            .foldLeft(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method.toUpperCase))) {
                case (b, (name, value)) => b.putHeader(name, value)
            }
            .build()

        val result = signer.sign { r =>
            configure(r.identity(identity).request(request).payload(ContentStreamProvider.fromByteArray(body)), service)
        }

        result.request.headers.asScala.view.mapValues(_.asScala.mkString(",")).toMap
    }

    private def configure(
        request: SignRequest.Builder[AwsCredentialsIdentity],
        service: String
    ): SignRequest.Builder[AwsCredentialsIdentity] = {
        val configured = request
            .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
            .putProperty(AwsV4HttpSigner.REGION_NAME, region)
            .putProperty(HttpSigner.SIGNING_CLOCK, clock)
        // The signer's defaults otherwise, which encode the path a second time: what every AWS
        // service but S3 expects, and what its own clients do. Turning that off was a no-op for
        // DynamoDB's "/" and matchmaker's callback paths, which have nothing to encode, and broke
        // every Play Live push — a connection id ends in "=", sent as "%3D", and API Gateway's
        // @connections answered each one with a signature mismatch. S3 is the exception: its keys
        // are signed as they are sent, encoded once, and its path is never normalized.
        if (service == "s3")
            configured
                .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, java.lang.Boolean.FALSE)
                .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, java.lang.Boolean.FALSE)
        else configured
    }
}

/** A response of [[SignedHttp.exchangeBytes]]: its header names lowercased. */
case class HttpAnswer(status: Int, body: Array[Byte], headers: Map[String, Seq[String]]) {
    def text: String = String(body, UTF_8)
}

/** DynamoDB's JSON API, one signed call at a time — for the match store and Play Live's connections, which between them
  * need five of its operations and none of its client's machinery.
  */
class DynamoDb(http: SignedHttp, region: String) {

    private val endpoint = s"https://dynamodb.$region.amazonaws.com"

    def call(target: String, payload: ujson.Obj): ujson.Value =
        ujson.read(
          http.post(
            url = endpoint,
            body = ujson.write(payload),
            service = "dynamodb",
            headers =
                Map("content-type" -> "application/x-amz-json-1.0", "x-amz-target" -> s"DynamoDB_20120810.$target")
          )
        )
}
