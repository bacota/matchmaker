package com.vivi.matchmaker.service

import java.security.{KeyFactory, Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, PlayerRepo, TextCodec}

/** Creates characters. The character's state and the owning player's externalId must be
  * signed by the game's private key; the corresponding public key is stored on the
  * CharacterGame row (`signing_key`, base64-encoded X.509) and used to verify the signature
  * passed in by the caller.
  */
class CharacterService[T](config: DbConfig)(using codec: TextCodec[T]) {

  private val signatureAlgorithm = "SHA256withRSA"

  /** @param signature base64-encoded signature over `codec.encode(state) + externalId`,
    *                   produced by the game's private key
    */
  def create(gameId: GameId, name: String, description: String, state: T, externalId: String, signature: String): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        game <- gameRepo.read(gameId).flatMap {
          case Some(g: CharacterGame) => IO.pure(g)
          case Some(_)                => IO.raiseError(ValidationError(s"game ${gameId.value} is not a character game"))
          case None                   => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        _ <- verifySignature(game.signingKey, codec.encode(state), externalId, signature)
        character <- characterRepo.create(
          Character(CharacterId(0), gameId, name, description, state, Some(player.playerId))
        )
      } yield character
    }

  private def verifySignature(publicKeyBase64: String, state: String, externalId: String, signature: String): IO[Unit] =
    IO {
      val keyBytes = Base64.getDecoder.decode(publicKeyBase64)
      val publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes))
      val verifier = Signature.getInstance(signatureAlgorithm)
      verifier.initVerify(publicKey)
      verifier.update((state + externalId).getBytes("UTF-8"))
      verifier.verify(Base64.getDecoder.decode(signature))
    }.handleErrorWith(_ => IO.pure(false)).flatMap { valid =>
      IO.raiseUnless(valid)(UnauthorizedError("invalid signature"))
    }
}
