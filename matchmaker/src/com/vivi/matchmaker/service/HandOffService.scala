package com.vivi.matchmaker.service

import cats.effect.IO
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Base64
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
  * game cannot be spent by another game's engine, or by anyone holding the code alone.
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
      * Rows already expired are deleted first, so the table holds only the last minute's and needs no scheduled job.
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
                            idToken = idToken,
                            refreshToken = refreshToken.filter(_ => isTrusted),
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
      */
    def redeem(code: String, callerExternalId: String): IO[HandedOff] =
        for {
            taken <- sessionPool.use(session => Repos(session).handOffs.take(hashOf(code)))
            at <- IO(now())
            answer <- taken match {
                case Some(t) if t.engineExternalId == callerExternalId && t.expiresAt.isAfter(at) =>
                    log(s"sign-in hand-off redeemed for game ${t.gameId.value} by ${t.cognitoId}")
                        .as(HandedOff(idToken = t.idToken, refreshToken = t.refreshToken))
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

    /** The SHA-256 of `code`: what is stored, so a copy of the table cannot be redeemed. */
    def hashOf(code: String): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))

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
