package com.vivi.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** S3's REST API, one signed call at a time: put, get and copy an object, and a presigned GET for a browser — all a
  * game's files need, without the SDK's client.
  *
  * Objects are addressed virtual-hosted style, `https://<bucket>.s3.<region>.amazonaws.com/<key>`, the key encoded once
  * as S3 signs it: every byte but the unreserved characters and `/`. A bucket whose name has a dot, such as one named
  * after the site it serves, is addressed path style, `https://s3.<region>.amazonaws.com/<bucket>/<key>`, as the SDKs
  * do: S3's certificate is for `*.s3.<region>.amazonaws.com`, and a wildcard matches one label, so TLS refuses a host
  * with a dotted bucket name in it.
  */
class S3(http: SignedHttp, region: String) {

    def url(bucket: String, key: String): String =
        if (bucket.contains('.')) s"https://s3.$region.amazonaws.com/$bucket/${S3.encodeKey(key)}"
        else s"https://$bucket.s3.$region.amazonaws.com/${S3.encodeKey(key)}"

    def put(bucket: String, key: String, bytes: Array[Byte], contentType: String): Unit = {
        val answer = http.exchangeBytes(
          method = "PUT",
          url = url(bucket, key),
          body = Some(bytes),
          service = "s3",
          headers = Map("content-type" -> contentType)
        )
        if (answer.status != 200) throw AwsError(s"PUT s3://$bucket/$key returned ${answer.status}: ${answer.text}")
    }

    /** The object's bytes, or `None` when there is no such key. */
    def get(bucket: String, key: String): Option[Array[Byte]] = {
        val answer =
            http.exchangeBytes(method = "GET", url = url(bucket, key), body = None, service = "s3", headers = Map.empty)
        answer.status match {
            case 200   => Some(answer.body)
            case 404   => None
            case other => throw AwsError(s"GET s3://$bucket/$key returned $other: ${answer.text}")
        }
    }

    /** Copies an object, perhaps between buckets, on S3's side.
      *
      * A copy can fail after S3 has answered 200: the status is sent before the copy is done, and a failure then
      * arrives as an `<Error>` in the body. So the body is read for one.
      */
    def copy(fromBucket: String, fromKey: String, toBucket: String, toKey: String): Unit = {
        val answer = http.exchangeBytes(
          method = "PUT",
          url = url(toBucket, toKey),
          body = Some(Array.emptyByteArray),
          service = "s3",
          headers = Map("x-amz-copy-source" -> s"/$fromBucket/${S3.encodeKey(fromKey)}")
        )
        if (answer.status != 200 || answer.text.contains("<Error>"))
            throw AwsError(
              s"copying s3://$fromBucket/$fromKey to s3://$toBucket/$toKey returned ${answer.status}: ${answer.text}"
            )
    }

    /** A url a browser can GET the object from for `expiresIn`, with no credentials of its own. */
    def presignedGet(bucket: String, key: String, expiresIn: Duration): String =
        http.presign(method = "GET", url = url(bucket, key), service = "s3", expiresIn = expiresIn)
}

object S3 {

    /** `key` as S3 signs and expects it in a path: unreserved characters and `/` as they are, every other byte of its
      * UTF-8 as `%XX`. Not `URLEncoder`, which writes a space as `+` and is for forms.
      */
    def encodeKey(key: String): String = {
        val out = new StringBuilder
        key.getBytes(UTF_8).foreach { b =>
            val c = (b & 0xff).toChar
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.~/".contains(c))
                out += c
            else out ++= f"%%${b & 0xff}%02X"
        }
        out.toString
    }
}
