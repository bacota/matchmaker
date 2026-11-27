package com.vivi.matchmaker.service

import cats.effect.IO
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Base64
import javax.crypto.{Cipher, Mac}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import com.vivi.matchmaker.ErrorText
import com.vivi.matchmaker.model.GameId
import com.vivi.matchmaker.persistence.Repos
import com.vivi.matchmaker.persistence.HandOffRepo.NewHandOff

/** A one-time code for a sign-in, and when it stops being any use. */
case class HandOff(code: String, expiresAt: Instant)

/** The tokens a redeemed code was made with. */
case class HandedOff(idToken: String, refreshToken: Option[String])

/** Handing a player's sign-in from matchmaker's UI to a game engine's page (design/sign-in-handoff.md).
  *
  * The UI asks for a one-time code with the player's tokens ([[create]]) and opens the engine's page with the code in
  * its url's fragment. The engine's server redeems the code here with the game's API key ([[redeem]]) and hands the
  * tokens on to its page. No token is ever put in a url: a browser keeps every url it visits in its history, where it
  * can be synced or read by an extension, and a code found there is spent or has expired.
  *
  * The engine's server redeems it, not its page, because the redeem call carries the game's key: a code made for one
  * game cannot be spent by another game's engine. At its own game's engine the code is the credential, as the page has
  * no token yet, and what limits it there is that it is single-use and lives a minute.
  *
  * Neither call logs a token or a code: only the game, who made the code, and what became of it.
  */
class HandOffService(
    sessionPool: SessionPool,
    now: () => Instant = () => Instant.now(),
    log: String => IO[Unit] = message => IO.blocking(System.err.println(message))
) {
    import HandOffService._

    /** A code for `idToken`, and for `refreshToken` too if game `gameId` is trusted with one (`Game.isTrusted`).
      *
      * `cognitoId` is the caller, as the JWT authorizer verified them, and the ID token must be theirs: its `sub` is
      * `cognitoId`. Otherwise a player could make a code that signs someone else in. The token's signature is not
      * checked here: the engine verifies it on every request.
      *
      * The tokens are stored sealed under a key derived from the code ([[seal]]), and the code only as its hash, so the
      * row signs nobody in without the code. Rows already expired are deleted first, so the table holds only the last
      * minute's and needs no scheduled job.
      */
    def create(cognitoId: String, gameId: GameId, idToken: String, refreshToken: Option[String]): IO[HandOff] =
        for {
            _ <- IO.raiseUnless(subjectOf(idToken).contains(cognitoId))(
              UnauthorizedError(ErrorText.handOff.notYourToken)
            )
            at <- IO(now())
            code = newCode()
            expiresAt = at.plus(Lifetime)
            _ <- sessionPool.use { session =>
                val repos = Repos(session)
                session.transaction.use { _ =>
                    for {
                        _ <- repos.handOffs.deleteExpired(at)
                        // Read plainly: the game is a reference table read on the way to a write elsewhere, and the
                        // foreign key keeps the row from outliving a game deleted meanwhile.
                        isTrusted <- repos.handOffs
                            .gameTrust(gameId)
                            .flatMap(IO.fromOption(_)(NotFoundError(ErrorText.game.notFound)))
                        _ <- repos.handOffs.insert(
                          NewHandOff(
                            codeHash = hashOf(code),
                            gameId = gameId,
                            cognitoId = cognitoId,
                            idTokenSealed = seal(code = code, purpose = IdTokenPurpose, token = idToken),
                            refreshTokenSealed = refreshToken
                                .filter(_ => isTrusted)
                                .map(seal(code = code, purpose = RefreshTokenPurpose, _)),
                            expiresAt = expiresAt
                          )
                        )
                    } yield ()
                }
            }
            _ <- log(s"sign-in hand-off made for game ${gameId.value} by $cognitoId")
        } yield HandOff(code = code, expiresAt = expiresAt)

    /** The tokens `code` was made with, for the engine whose identity is `callerExternalId` (the name `ApiKeys.nameOf`
      * gave for the key it presented).
      *
      * The code's row is deleted whatever the answer, so a code is redeemed at most once, and a wrong engine trying it
      * leaves nothing for a right one. Every refusal is the same 404: a spent code, an expired one, another game's and
      * a made-up one look alike to the caller.
      *
      * Rows already expired are deleted here too, not only by [[create]], so a code nobody redeemed does not keep its
      * row until the next one is made: on a quiet day that could be long enough to reach a snapshot. The take and the
      * sweep are one transaction, so a sweep that fails rolls the take back and the code can still be redeemed, rather
      * than being spent on an answer the engine never got.
      */
    def redeem(code: String, callerExternalId: String): IO[HandedOff] =
        for {
            takenAt <- sessionPool.use { session =>
                val handOffs = Repos(session).handOffs
                session.transaction.use { _ =>
                    for {
                        taken <- handOffs.take(hashOf(code))
                        at <- IO(now())
                        _ <- handOffs.deleteExpired(at)
                    } yield (taken, at)
                }
            }
            (taken, at) = takenAt
            answer <- taken match {
                case Some(t) if t.engineExternalId == callerExternalId && t.expiresAt.isAfter(at) =>
                    // The refresh token again only if the game is still trusted: an admin may have withdrawn its
                    // trust in the minute since the code was made, perhaps for the very reason of this engine.
                    for {
                        idToken <- IO(open(code = code, purpose = IdTokenPurpose, sealedToken = t.idTokenSealed))
                        refreshToken <- IO(
                          t.refreshTokenSealed
                              .filter(_ => t.isGameTrusted)
                              .map(open(code = code, purpose = RefreshTokenPurpose, _))
                        )
                        _ <- log(s"sign-in hand-off redeemed for game ${t.gameId.value} by ${t.cognitoId}")
                    } yield HandedOff(idToken = idToken, refreshToken = refreshToken)
                case Some(t) =>
                    val why = if (t.engineExternalId != callerExternalId) "another engine" else "expired"
                    log(s"sign-in hand-off for game ${t.gameId.value} by ${t.cognitoId} refused: $why") >>
                        IO.raiseError(NotFoundError(ErrorText.handOff.unknown))
                case None =>
                    IO.raiseError(NotFoundError(ErrorText.handOff.unknown))
            }
        } yield answer
}

object HandOffService {

    /** How long a code can be redeemed for. Long enough for a new tab to load the engine's page and ask, and short
      * enough that a code read from the browser's history is long dead.
      */
    val Lifetime: Duration = Duration.ofSeconds(60)

    private val random = new SecureRandom()

    /** 32 random bytes, base64url without padding: 43 characters, safe in a url's fragment as they are. */
    private[service] def newCode(): String = {
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }

    /** The SHA-256 of `code`: what it is stored by, so a copy of the table cannot be redeemed. */
    def hashOf(code: String): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))

    /** What a sealed token is, bound into its seal as associated data, so the ID token's seal cannot be passed off as
      * the refresh token's or the other way round.
      */
    private[service] val IdTokenPurpose = "id-token"
    private[service] val RefreshTokenPurpose = "refresh-token"

    private val NonceLength = 12
    private val TagBits = 128

    /** `token` sealed with AES-256-GCM under a key derived from `code` ([[keyOf]]): a fresh 12-byte nonce, then the
      * ciphertext and its tag. Only the code's hash is stored beside it, and the code is 32 random bytes, so the row
      * holds nothing that signs anyone in without the code itself, which only the url it was put in ever held.
      */
    private[service] def seal(code: String, purpose: String, token: String): Array[Byte] = {
        val nonce = new Array[Byte](NonceLength)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyOf(code), new GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8))
        nonce ++ cipher.doFinal(token.getBytes(StandardCharsets.UTF_8))
    }

    /** The token [[seal]] sealed with `code` for `purpose`. Throws if the seal was not made with them, or was altered.
      */
    private[service] def open(code: String, purpose: String, sealedToken: Array[Byte]): String = {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyOf(code), new GCMParameterSpec(TagBits, sealedToken, 0, NonceLength))
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8))
        new String(cipher.doFinal(sealedToken, NonceLength, sealedToken.length - NonceLength), StandardCharsets.UTF_8)
    }

    /** The sealing key for `code`: HMAC-SHA256 keyed by the code, of a fixed label. The code is 32 uniformly random
      * bytes, so this is a sound key derivation (in the manner of HKDF's expand step, the code being the pseudorandom
      * key), and a key apart from the SHA-256 the row is looked up by.
      */
    private def keyOf(code: String): SecretKeySpec = {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        new SecretKeySpec(mac.doFinal("matchmaker sign-in hand-off key".getBytes(StandardCharsets.UTF_8)), "AES")
    }

    /** The `sub` claim of JWT `token`, read without checking its signature, or `None` if it has none. */
    private[service] def subjectOf(token: String): Option[String] =
        token.split('.') match {
            case Array(_, payload, _*) =>
                scala.util
                    .Try(ujson.read(Base64.getUrlDecoder.decode(payload.replace('+', '-').replace('/', '_'))))
                    .toOption
                    .flatMap(_.objOpt)
                    .flatMap(_.get("sub"))
                    .flatMap(_.strOpt)
            case _ => None
        }
}
