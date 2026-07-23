package com.vivi.matchmaker.service

import java.security.{KeyFactory, Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{CharacterRepo, GameRepo, PlayerRepo, TextCodec}

/** Creates and updates characters. The character's state and the owning player's externalId
  * must be signed by the game's private key; the corresponding public key is stored on the
  * CharacterGame row (`verificationKey`, base64-encoded X.509, backed by the `signing_key`
  * column) and used to verify the signature passed in by the caller.
  *
  * Both methods additionally take `callerExternalId`, identifying the player making the
  * request, as an authorization check independent of the signature: for `create` it must
  * match `externalId` (the player the signed state is for), and for `update` it must match
  * the externalId of the character's current owner, i.e. before the update is applied.
  */
class CharacterService[T](config: DbConfig)(using codec: TextCodec[T]) {

  private val signatureAlgorithm = "SHA256withRSA"

  /** @param signature base64-encoded signature over `codec.encode(state) + externalId`,
    *                   produced by the game's private key
    */
  def create(
      gameId: GameId,
      name: String,
      description: String,
      state: T,
      externalId: String,
      callerExternalId: String,
      signature: String
  ): IO[Character[T]] =
    DbSession.resource(config).use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      for {
        _ <- IO.raiseUnless(callerExternalId == externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not create a character for '$externalId'")
        )
        game <- gameRepo.read(gameId).flatMap {
          case Some(g: CharacterGame) => IO.pure(g)
          case Some(_)                => IO.raiseError(ValidationError(s"game ${gameId.value} is not a character game"))
          case None                   => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        _ <- verifySignature(game.verificationKey, codec.encode(state), externalId, signature)
        character <- characterRepo.create(
          Character(CharacterId(0), gameId, name, description, state, Some(player.playerId))
        )
      } yield character
    }

  /** @param signature base64-encoded signature over `codec.encode(state) + externalId`,
    *                   produced by the game's private key
    */
  def update(
      characterId: CharacterId,
      gameId: GameId,
      name: String,
      description: String,
      state: T,
      externalId: String,
      callerExternalId: String,
      signature: String
  ): IO[Character[T]] =
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
        existing <- characterRepo.read(characterId).flatMap {
          case Some(c) if c.gameId == gameId => IO.pure(c)
          case Some(_)                       => IO.raiseError(ValidationError(s"character ${characterId.value} does not belong to game ${gameId.value}"))
          case None                          => IO.raiseError(NotFoundError(s"no character with id ${characterId.value}"))
        }
        currentOwner <- existing.playerId match {
          case Some(id) =>
            playerRepo.read(id).flatMap {
              case Some(p) => IO.pure(p)
              case None    => IO.raiseError(NotFoundError(s"no player with id ${id.value}"))
            }
          case None => IO.raiseError(UnauthorizedError(s"character ${characterId.value} has no owning player"))
        }
        _ <- IO.raiseUnless(callerExternalId == currentOwner.externalId)(
          UnauthorizedError(s"caller '$callerExternalId' may not update character ${characterId.value}")
        )
        player <- playerRepo.readByExternalId(externalId).flatMap {
          case Some(p) => IO.pure(p)
          case None    => IO.raiseError(NotFoundError(s"no player with externalId '$externalId'"))
        }
        _ <- verifySignature(game.verificationKey, codec.encode(state), externalId, signature)
        updated = existing.copy(name = name, description = description, state = state, playerId = Some(player.playerId))
        _ <- characterRepo.update(updated)
      } yield updated
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
