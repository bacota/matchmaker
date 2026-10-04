package com.vivi.matchmaker.archive

import cats.effect.IO
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.Base64
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.services.s3.model.{
    ChecksumAlgorithm,
    ChecksumMode,
    CopyObjectRequest,
    DeleteObjectRequest,
    GetObjectRequest,
    HeadObjectRequest,
    NoSuchKeyException,
    PutObjectRequest,
    S3Exception
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{GetObjectPresignRequest, PutObjectPresignRequest}
import com.vivi.matchmaker.service.UnavailableError

/** Which of the two archive buckets a match's archive is in: a friendly match's archive is kept for 30 days and every
  * other one permanently, and only the friendly bucket's lifecycle rule expires anything current. See
  * archiving-matches-plan.md. Where an archive *is* is recorded (V44) rather than worked out from the match, since a
  * match whose friendliness changes after it is archived has its archive moved, and a move takes time.
  */
enum ArchiveBucket {
    case Permanent, Friendly
}

object ArchiveBucket {
    def of(friendly: Boolean): ArchiveBucket = if (friendly) Friendly else Permanent

    extension (bucket: ArchiveBucket) {

        /** Whether this is the bucket whose archives expire: the column's form of it (V44). */
        def isFriendly: Boolean = bucket == Friendly
    }
}

/** A signed upload: the engine sends the archive to `url` with `method`, carrying every one of `headers` exactly as
  * given — they are part of what was signed. `content-length` among them is set by most HTTP clients from the body
  * itself, and only has to agree with it.
  */
case class SignedUpload(url: String, method: String, headers: Map[String, String], expiresAt: Instant)

/** A signed download: the engine GETs the archive from `url` before `expiresAt`. Never handed to a browser — an archive
  * holds what the engine hides from players.
  */
case class SignedDownload(url: String, expiresAt: Instant)

/** What is stored under a key: its size, and its SHA-256 (base64, as S3 reports it) when the store knows it. */
case class StoredObject(size: Long, sha256: Option[String])

/** Where completed matches' archives live, from matchmaker's side: it signs urls for the engine to move the bytes with,
  * and checks what arrived. Matchmaker never reads or writes an archive itself.
  */
trait ArchiveStore {

    /** A url the engine may upload `size` bytes whose SHA-256 is `sha256` (base64) to, once, under `key`. The checksum
      * is signed, so the store refuses any other content. `formatVersion` is the engine's own name for the format it
      * wrote, kept with the object so that a later engine can still read it.
      */
    def signUpload(
        bucket: ArchiveBucket,
        key: String,
        size: Long,
        sha256: String,
        formatVersion: Option[String]
    ): IO[SignedUpload]

    def signDownload(bucket: ArchiveBucket, key: String): IO[SignedDownload]

    /** What is stored under `key`, or `None` if nothing is. Any other failure is raised: a check that could not be made
      * is not evidence that the object is gone.
      */
    def head(bucket: ArchiveBucket, key: String): IO[Option[StoredObject]]

    /** Copies the archive under `key` from one bucket to the other, keeping its checksum and its metadata, and answers
      * whether there was anything to copy: `false` when nothing is stored under `key` in `from` — a friendly archive
      * its bucket has expired. Any other failure is raised.
      */
    def copy(from: ArchiveBucket, to: ArchiveBucket, key: String): IO[Boolean]

    /** Removes what is stored under `key`, if anything is: the other half of a move. */
    def remove(bucket: ArchiveBucket, key: String): IO[Unit]
}

object ArchiveStore {

    /** How long a signed url is good for. Long enough for an engine on a slow connection to move a few kilobytes, and
      * short enough that one that leaked into a log is soon worthless.
      */
    val urlLifetime: Duration = Duration.ofMinutes(5)

    /** For a deployment with no buckets configured: every operation is refused as unavailable, so that an engine is
      * told archiving is off rather than that it did something wrong, and keeps its live copy.
      */
    object Unavailable extends ArchiveStore {
        private def off[A]: IO[A] = IO.raiseError(UnavailableError("archiving is not configured on this matchmaker"))

        def signUpload(b: ArchiveBucket, k: String, s: Long, sha: String, v: Option[String]): IO[SignedUpload] = off
        def signDownload(bucket: ArchiveBucket, key: String): IO[SignedDownload] = off
        def head(bucket: ArchiveBucket, key: String): IO[Option[StoredObject]] = off
        def copy(from: ArchiveBucket, to: ArchiveBucket, key: String): IO[Boolean] = off
        def remove(bucket: ArchiveBucket, key: String): IO[Unit] = off
    }

    /** The store a deployment gets: S3, when `ARCHIVE_BUCKET` and `FRIENDLY_ARCHIVE_BUCKET` name its two buckets, and
      * [[Unavailable]] otherwise. `ARCHIVE_ENDPOINT` points the client somewhere other than AWS — MinIO, locally.
      */
    def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): ArchiveStore = {
        def setting(name: String) = env(name).map(_.trim).filter(_.nonEmpty)
        (setting("ARCHIVE_BUCKET"), setting("FRIENDLY_ARCHIVE_BUCKET")) match {
            case (Some(permanent), Some(friendly)) =>
                val region = setting("AWS_REGION").orElse(setting("AWS_DEFAULT_REGION")).getOrElse("us-east-1")
                new S3ArchiveStore(permanent, friendly, region, setting("ARCHIVE_ENDPOINT").map(URI.create))
            case _ => Unavailable
        }
    }

    /** The SHA-256 of `bytes`, base64 — the form S3 takes it in and reports it in. */
    def sha256(bytes: Array[Byte]): String =
        Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** The two buckets in S3.
  *
  * Path-style urls throughout (`https://s3.<region>.amazonaws.com/<bucket>/<key>`): the bucket names end in
  * `.vivi.com`, and a dotted name in a virtual-hosted url (`<bucket>.s3.<region>.amazonaws.com`) is not covered by S3's
  * wildcard certificate, so the engine's upload would fail certificate validation.
  *
  * The presigner and the client are built on first use, for the reason `SqsNotifier` gives: under SnapStart anything
  * built while the services are assembled may be inside the snapshot, credentials included. Signing is a local
  * computation; only [[head]] goes over the network.
  */
class S3ArchiveStore(permanent: String, friendly: String, region: String, endpoint: Option[URI] = None)
    extends ArchiveStore {

    private def bucketName(bucket: ArchiveBucket): String = bucket match {
        case ArchiveBucket.Permanent => permanent
        case ArchiveBucket.Friendly  => friendly
    }

    private lazy val presigner: S3Presigner = {
        val builder = S3Presigner
            .builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        endpoint.fold(builder)(builder.endpointOverride).build()
    }

    private lazy val client: S3Client = {
        val builder = S3Client
            .builder()
            .region(Region.of(region))
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.create())
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
        endpoint.fold(builder)(builder.endpointOverride).build()
    }

    def signUpload(
        bucket: ArchiveBucket,
        key: String,
        size: Long,
        sha256: String,
        formatVersion: Option[String]
    ): IO[SignedUpload] =
        IO {
            val put = PutObjectRequest
                .builder()
                .bucket(bucketName(bucket))
                .key(key)
                .contentType("application/json")
                .contentLength(size)
                .checksumSHA256(sha256)
                .metadata(formatVersion.map("format-version" -> _).toMap.asJava)
                .build()
            val signed = presigner.presignPutObject(
              PutObjectPresignRequest
                  .builder()
                  .signatureDuration(ArchiveStore.urlLifetime)
                  .putObjectRequest(put)
                  .build()
            )
            SignedUpload(
              signed.url.toString,
              "PUT",
              signed.signedHeaders.asScala.toMap.collect {
                  // Host is in the url, and every client sets it from there.
                  case (name, values) if !name.equalsIgnoreCase("host") =>
                      name.toLowerCase -> values.asScala.mkString(",")
              },
              signed.expiration
            )
        }

    def signDownload(bucket: ArchiveBucket, key: String): IO[SignedDownload] =
        IO {
            val get = GetObjectRequest.builder().bucket(bucketName(bucket)).key(key).build()
            val signed = presigner.presignGetObject(
              GetObjectPresignRequest
                  .builder()
                  .signatureDuration(ArchiveStore.urlLifetime)
                  .getObjectRequest(get)
                  .build()
            )
            SignedDownload(signed.url.toString, signed.expiration)
        }

    /* A 404 needs `s3:ListBucket` on the bucket: without it S3 answers a missing key with 403, which
     * would be raised here as a failure, and an expired archive could never be told from a check that
     * did not work. The terraform grants it. */
    def head(bucket: ArchiveBucket, key: String): IO[Option[StoredObject]] =
        IO.blocking {
            try {
                val found = client.headObject(
                  HeadObjectRequest
                      .builder()
                      .bucket(bucketName(bucket))
                      .key(key)
                      .checksumMode(ChecksumMode.ENABLED)
                      .build()
                )
                Some(StoredObject(found.contentLength, Option(found.checksumSHA256)))
            } catch {
                case _: NoSuchKeyException                   => None
                case e: S3Exception if e.statusCode() == 404 => None
            }
        }

    /* Server side: the bytes never pass through matchmaker, and the SHA-256 is asked for again so
     * that the copy carries the checksum `confirm` checked the original against. Metadata --
     * the engine's format version -- is copied by default. Needs s3:GetObject on the source and
     * s3:PutObject on the destination, which the terraform grants on both buckets. */
    def copy(from: ArchiveBucket, to: ArchiveBucket, key: String): IO[Boolean] =
        IO.blocking {
            try {
                client.copyObject(
                  CopyObjectRequest
                      .builder()
                      .sourceBucket(bucketName(from))
                      .sourceKey(key)
                      .destinationBucket(bucketName(to))
                      .destinationKey(key)
                      .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                      .build()
                )
                true
            } catch {
                case _: NoSuchKeyException                   => false
                case e: S3Exception if e.statusCode() == 404 => false
            }
        }

    /* Deleting a key that is not there succeeds in S3, which is what a repeated move wants. */
    def remove(bucket: ArchiveBucket, key: String): IO[Unit] =
        IO.blocking {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName(bucket)).key(key).build())
        }.void
}

/** Archives kept in a directory, for the local server and the tests: no AWS, and no signing.
  *
  * Its urls are the local server's own (`{baseUrl}/local-archive/{bucket}/{key}`), which hands the request to [[put]]
  * and [[get]] here. Nothing is checked about who is asking — it is a local server — but the upload is held to what it
  * was signed for, size and checksum, so that an engine that gets those wrong fails locally as it would against S3.
  */
class LocalArchiveStore(dir: Path, baseUrl: String, now: () => Instant = () => Instant.now()) extends ArchiveStore {

    private val base = baseUrl.stripSuffix("/")

    private def bucketName(bucket: ArchiveBucket): String = bucket.toString.toLowerCase

    private def urlOf(bucket: ArchiveBucket, key: String): String =
        s"$base/${LocalArchiveStore.prefix}/${bucketName(bucket)}/" +
            key.split('/').map(URLEncoder.encode(_, StandardCharsets.UTF_8).replace("+", "%20")).mkString("/")

    private def fileOf(bucket: String, key: String): Option[Path] = {
        val root = dir.resolve(bucket).normalize()
        val file = root.resolve(key).normalize()
        // A key is the store's to choose, but the local server takes it from a url, and `..` there must
        // not reach outside the directory.
        Option.when(ArchiveBucket.values.exists(bucketName(_) == bucket) && file.startsWith(root))(file)
    }

    def signUpload(
        bucket: ArchiveBucket,
        key: String,
        size: Long,
        sha256: String,
        formatVersion: Option[String]
    ): IO[SignedUpload] =
        IO.pure(
          SignedUpload(
            urlOf(bucket, key),
            "PUT",
            Map("content-type" -> "application/json", "x-amz-checksum-sha256" -> sha256) ++
                formatVersion.map("x-amz-meta-format-version" -> _),
            now().plus(ArchiveStore.urlLifetime)
          )
        )

    def signDownload(bucket: ArchiveBucket, key: String): IO[SignedDownload] =
        IO.pure(SignedDownload(urlOf(bucket, key), now().plus(ArchiveStore.urlLifetime)))

    def head(bucket: ArchiveBucket, key: String): IO[Option[StoredObject]] =
        IO.blocking(fileOf(bucketName(bucket), key).filter(Files.isRegularFile(_)).map { file =>
            val bytes = Files.readAllBytes(file)
            StoredObject(bytes.length.toLong, Some(ArchiveStore.sha256(bytes)))
        })

    def copy(from: ArchiveBucket, to: ArchiveBucket, key: String): IO[Boolean] =
        IO.blocking {
            (fileOf(bucketName(from), key), fileOf(bucketName(to), key)) match {
                case (Some(source), Some(target)) if Files.isRegularFile(source) =>
                    Files.createDirectories(target.getParent)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    true
                case _ => false
            }
        }

    def remove(bucket: ArchiveBucket, key: String): IO[Unit] = IO.blocking(delete(bucket, key))

    /** Stores an upload made to one of this store's urls: `path` is what follows `/local-archive/`. Refused, with the
      * reason, when the body is not the one whose checksum was signed.
      */
    def put(path: String, body: Array[Byte], headers: Map[String, String]): Either[String, Unit] =
        locate(path).flatMap { file =>
            headers.get("x-amz-checksum-sha256") match {
                case Some(expected) if expected != ArchiveStore.sha256(body) =>
                    Left("the body does not match the signed x-amz-checksum-sha256")
                case _ =>
                    Files.createDirectories(file.getParent)
                    val temp = Files.createTempFile(file.getParent, ".upload", "")
                    Files.write(temp, body)
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    Right(())
            }
        }

    /** The archive at one of this store's urls, if there is one. */
    def get(path: String): Option[Array[Byte]] =
        locate(path).toOption.filter(Files.isRegularFile(_)).map(Files.readAllBytes)

    /** Removes an archive, as the friendly bucket's lifecycle rule would — for the tests and for trying the expiry by
      * hand.
      */
    def delete(bucket: ArchiveBucket, key: String): Unit =
        fileOf(bucketName(bucket), key).foreach(Files.deleteIfExists)

    private def locate(path: String): Either[String, Path] = {
        val decoded = path.split('/').map(java.net.URLDecoder.decode(_, StandardCharsets.UTF_8)).toList
        decoded match {
            case bucket :: key if key.nonEmpty => fileOf(bucket, key.mkString("/")).toRight(s"no such archive '$path'")
            case _                             => Left(s"no such archive '$path'")
        }
    }
}

object LocalArchiveStore {

    /** The path the local server serves this store's urls under. */
    val prefix: String = "local-archive"
}
