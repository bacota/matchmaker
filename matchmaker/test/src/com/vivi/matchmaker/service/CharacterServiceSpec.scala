package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

class CharacterServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val characterService = TestServices.services.characters
    private val registrationService = TestServices.services.registration

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private def makeCharacterGame(gameExternalId: String): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                gameId = GameId.unassigned,
                gameType = GameType.Character,
                name = "game",
                displayName = "game",
                description = "description",
                url = "url",
                isActive = true,
                Seq.empty,
                Seq.empty,
                gameExternalId
              )
            )
        }

    private def makePlainGame(gameExternalId: String): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                gameId = GameId.unassigned,
                gameType = GameType.Plain,
                name = "game",
                displayName = "game",
                description = "description",
                url = "url",
                isActive = true,
                Seq.empty,
                Seq.empty,
                gameExternalId
              )
            )
        }

    property("create records a character the game made, owned by the given player with the state it was given") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, state, gameExternalId) =>
                val result = for {
                    player <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = state,
                      callerExternalId = gameExternalId
                    )
                    found <- characterService.listForGame(game.gameId, externalId)
                } yield created.name == name && created.state == state && created.playerId == Some(player.playerId) &&
                    found.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects a player making a character for themselves: characters are made by their game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    attempt <- characterService
                        .create(
                          name = name,
                          description = "description",
                          ownerExternalId = externalId,
                          state = "",
                          callerExternalId = externalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create puts the character in the game whose identity made it, not another") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId, otherGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    other <- makeCharacterGame(otherGameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = otherGameExternalId
                    )
                    inGame <- characterService.listForGame(game.gameId, externalId)
                    inOther <- characterService.listForGame(other.gameId, externalId)
                } yield created.gameId == other.gameId && inGame.isEmpty &&
                    inOther.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects a character in a game not played with characters") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makePlainGame(gameExternalId)
                    attempt <- characterService
                        .create(
                          name = name,
                          description = "description",
                          ownerExternalId = externalId,
                          state = "",
                          callerExternalId = gameExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(_: ValidationError) => true
                    case _                        => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("create rejects an owner who has not registered with matchmaker") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (unknownExternalId, name, gameExternalId) =>
            val result = for {
                game <- makeCharacterGame(gameExternalId)
                attempt <- characterService
                    .create(
                      name = name,
                      description = "description",
                      ownerExternalId = unknownExternalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    .attempt
            } yield attempt match {
                case Left(_: NotFoundError) => true
                case _                      => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("edit changes a character's name and description, but not its state, for its owner via its game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newName, gameExternalId) =>
                val result = for {
                    player <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "s",
                      callerExternalId = gameExternalId
                    )
                    edited <- characterService.edit(
                      characterId = created.characterId,
                      name = s" $newName ",
                      description = " new ",
                      ownerExternalId = externalId,
                      callerExternalId = gameExternalId
                    )
                    found <- characterService.listForGame(game.gameId, externalId)
                } yield edited.name == newName && found.map(c => (c.name, c.description, c.state, c.playerId)) ==
                    List((newName, "new", "s", Some(player.playerId)))
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("edit answers a character the named player does not own as if it did not exist") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- registrationService.register(nickname = otherNickname, externalId = otherExternalId)
                    _ <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = "mine",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    attempt <- characterService
                        .edit(
                          characterId = created.characterId,
                          name = "stolen",
                          description = "d",
                          ownerExternalId = otherExternalId,
                          callerExternalId = gameExternalId
                        )
                        .attempt
                    after <- characterService.listForOwner(externalId, gameExternalId)
                } yield (attempt match {
                    case Left(_: NotFoundError) => true
                    case _                      => false
                }) && after.map(_.name) == List("mine")
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("edit and transfer refuse anyone but the character's own game, its owner included") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId, gameExternalId, otherGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- registrationService.register(nickname = otherNickname, externalId = otherExternalId)
                    _ <- makeCharacterGame(gameExternalId)
                    _ <- makeCharacterGame(otherGameExternalId)
                    created <- characterService.create(
                      name = "mine",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    id = created.characterId
                    attempts <- List(
                      characterService.edit(
                        characterId = id,
                        name = "x",
                        description = "d",
                        ownerExternalId = externalId,
                        callerExternalId = otherGameExternalId
                      ),
                      characterService.edit(
                        characterId = id,
                        name = "x",
                        description = "d",
                        ownerExternalId = externalId,
                        callerExternalId = externalId
                      ),
                      characterService.transfer(
                        characterId = id,
                        toNickname = otherNickname,
                        ownerExternalId = externalId,
                        callerExternalId = otherGameExternalId
                      ),
                      characterService.transfer(
                        characterId = id,
                        toNickname = otherNickname,
                        ownerExternalId = externalId,
                        callerExternalId = externalId
                      )
                    ).traverse(_.attempt)
                } yield attempts.forall {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("edit refuses a blank name") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val result = for {
                _ <- registrationService.register(nickname = nickname, externalId = externalId)
                _ <- makeCharacterGame(gameExternalId)
                created <- characterService.create(
                  name = "mine",
                  description = "description",
                  ownerExternalId = externalId,
                  state = "",
                  callerExternalId = gameExternalId
                )
                attempt <- characterService
                    .edit(
                      characterId = created.characterId,
                      name = "  ",
                      description = "d",
                      ownerExternalId = externalId,
                      callerExternalId = gameExternalId
                    )
                    .attempt
            } yield attempt match {
                case Left(_: ValidationError) => true
                case _                        => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("transfer hands a character to the player with that nickname, who then owns it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, newNickname, newExternalId, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    newOwner <- registrationService.register(nickname = newNickname, externalId = newExternalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = "mine",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "s",
                      callerExternalId = gameExternalId
                    )
                    handed <- characterService.transfer(
                      characterId = created.characterId,
                      toNickname = newNickname,
                      ownerExternalId = externalId,
                      callerExternalId = gameExternalId
                    )
                    formerList <- characterService.listForGame(game.gameId, externalId)
                    newList <- characterService.listForGame(game.gameId, newExternalId)
                } yield handed.playerId == Some(newOwner.playerId) && formerList.isEmpty &&
                    newList.map(c => (c.characterId, c.name, c.state)) == List((created.characterId, "mine", "s"))
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("transfer refuses a nickname nobody has, a character that is not the owner's, and handing it to oneself") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId, unknownNickname, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- registrationService.register(nickname = otherNickname, externalId = otherExternalId)
                    _ <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = "mine",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    id = created.characterId
                    toNobody <- characterService
                        .transfer(
                          characterId = id,
                          toNickname = unknownNickname,
                          ownerExternalId = externalId,
                          callerExternalId = gameExternalId
                        )
                        .attempt
                    notTheirs <- characterService
                        .transfer(
                          characterId = id,
                          toNickname = otherNickname,
                          ownerExternalId = otherExternalId,
                          callerExternalId = gameExternalId
                        )
                        .attempt
                    toSelf <- characterService
                        .transfer(
                          characterId = id,
                          toNickname = nickname,
                          ownerExternalId = externalId,
                          callerExternalId = gameExternalId
                        )
                        .attempt
                    after <- characterService.listForOwner(externalId, gameExternalId)
                } yield ((toNobody, notTheirs, toSelf) match {
                    case (Left(_: ValidationError), Left(_: NotFoundError), Left(_: ValidationError)) => true
                    case _                                                                            => false
                }) && after.map(_.characterId) == List(id)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForOwner lists a player's characters in the calling game only") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- makeCharacterGame(gameExternalId)
                    _ <- makeCharacterGame(otherGameExternalId)
                    here <- characterService.create(
                      name = "here",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    _ <- characterService.create(
                      name = "elsewhere",
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = otherGameExternalId
                    )
                    listed <- characterService.listForOwner(externalId, gameExternalId)
                } yield listed.map(_.characterId) == List(here.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForOwner refuses a caller that is no game, and a player matchmaker does not know") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, unknownExternalId, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- makeCharacterGame(gameExternalId)
                    byPlayer <- characterService.listForOwner(externalId, externalId).attempt
                    unknown <- characterService.listForOwner(unknownExternalId, gameExternalId).attempt
                } yield (byPlayer, unknown) match {
                    case (Left(_: UnauthorizedError), Left(_: NotFoundError)) => true
                    case _                                                    => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("updateState changes the state when authorized by the character's game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newState, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    updated <- characterService.updateState(
                      characterId = created.characterId,
                      state = newState,
                      callerExternalId = gameExternalId
                    )
                } yield updated.characterId == created.characterId && updated.state == newState
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("updateState rejects a caller whose externalId does not match the character's game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, newState, gameExternalId, wrongGameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    attempt <- characterService
                        .updateState(
                          characterId = created.characterId,
                          state = newState,
                          callerExternalId = wrongGameExternalId
                        )
                        .attempt
                } yield attempt match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame returns the caller's characters in that game") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    game <- makeCharacterGame(gameExternalId)
                    created <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    found <- characterService.listForGame(game.gameId, externalId)
                } yield found.map(_.characterId) == List(created.characterId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame shows a player nothing of another player's characters") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, otherNickname, otherExternalId, name, gameExternalId) =>
                val result = for {
                    _ <- registrationService.register(nickname = nickname, externalId = externalId)
                    _ <- registrationService.register(nickname = otherNickname, externalId = otherExternalId)
                    game <- makeCharacterGame(gameExternalId)
                    _ <- characterService.create(
                      name = name,
                      description = "description",
                      ownerExternalId = externalId,
                      state = "",
                      callerExternalId = gameExternalId
                    )
                    found <- characterService.listForGame(game.gameId, otherExternalId)
                } yield found.isEmpty
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("listForGame rejects a caller with no player") {
        forAll(genUniqueString, genUniqueString) { (unknownExternalId, gameExternalId) =>
            val result = for {
                game <- makeCharacterGame(gameExternalId)
                attempt <- characterService.listForGame(game.gameId, unknownExternalId).attempt
            } yield attempt match {
                case Left(_: UnauthorizedError) => true
                case _                          => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }
}
