package com.vivi.matchmaker.archive

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/** What the S3 store signs, checked without S3: signing is a local computation, so these need credentials to sign with
  * but nothing to send to. The SDK's default chain reads them from system properties before anywhere else.
  */
class S3ArchiveStoreSpec extends FunSuite {

    override def beforeAll(): Unit = {
        System.setProperty("aws.accessKeyId", "AKIAEXAMPLE")
        System.setProperty("aws.secretAccessKey", "secret")
    }

    private val store =
        S3ArchiveStore("matchmaker-dev-archive.vivi.com", "matchmaker-dev-friendly-archive.vivi.com", "us-east-1")

    private val sha = ArchiveStore.sha256("{}".getBytes)

    test("urls are path-style, because the bucket names have dots in them") {
        val upload =
            store.signUpload(ArchiveBucket.Permanent, "stratego/2026-10-03/m.json", 2, sha, Some("v1")).unsafeRunSync()
        assert(
          upload.url.startsWith(
            "https://s3.amazonaws.com/matchmaker-dev-archive.vivi.com/stratego/2026-10-03/m.json?"
          ) ||
              upload.url.startsWith(
                "https://s3.us-east-1.amazonaws.com/matchmaker-dev-archive.vivi.com/stratego/2026-10-03/m.json?"
              ),
          upload.url
        )
        val download = store.signDownload(ArchiveBucket.Friendly, "stratego/2026-10-03/m.json").unsafeRunSync()
        assert(
          download.url.contains("/matchmaker-dev-friendly-archive.vivi.com/stratego/2026-10-03/m.json?"),
          download.url
        )
    }

    test("an upload is signed for its checksum, its length and its format, and the engine is told to send them") {
        val upload = store.signUpload(ArchiveBucket.Permanent, "k.json", 2, sha, Some("v1")).unsafeRunSync()
        assertEquals(upload.method, "PUT")
        assertEquals(upload.headers.get("x-amz-checksum-sha256"), Some(sha))
        assertEquals(upload.headers.get("content-length"), Some("2"))
        assertEquals(upload.headers.get("x-amz-meta-format-version"), Some("v1"))
        assert(!upload.headers.contains("host"))
        val signedHeaders = upload.url.split('&').find(_.startsWith("X-Amz-SignedHeaders=")).getOrElse("")
        assert(signedHeaders.contains("x-amz-checksum-sha256"), signedHeaders)
        assert(signedHeaders.contains("content-length"), signedHeaders)
    }

    test("a signed url lasts five minutes") {
        val before = java.time.Instant.now()
        val download = store.signDownload(ArchiveBucket.Permanent, "k.json").unsafeRunSync()
        assert(!download.expiresAt.isAfter(before.plus(ArchiveStore.urlLifetime).plusSeconds(5)))
        assert(download.url.contains("X-Amz-Expires=300"), download.url)
    }
}
