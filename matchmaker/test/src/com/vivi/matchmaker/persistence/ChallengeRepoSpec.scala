package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.model.{CharacterAcceptance, CharacterChallenge}
import org.scalacheck.Prop._

class ChallengeRepoSpec extends PropertySuite {
    property("create then read returns the open challenge just created") {
        forAll(Generators.genPlayer) { player =>
            TestSession.resource
                .use { session =>
                    val gameRepo = new GameRepo[String](session)
                    val playerRepo = new PlayerRepo(session)
                    val characterRepo = new CharacterRepo[String](session)
                    val challengeRepo = new ChallengeRepo(session)
                    val acceptanceRepo = new AcceptanceRepo(session)

                    for {
                        createdGame <- gameRepo.create(Generators.genGame().sample.get)
                        createdPlayer <- playerRepo.create(player)
                        createdCharacter <- characterRepo.create(
                          Generators.genCharacter(createdGame.gameId, None).sample.get
                        )
                        challenge <- IO.pure(
                          Generators
                              .genChallenge(
                                createdPlayer.playerId,
                                createdGame.gameId,
                                createdCharacter.characterId,
                                createdGame.roles.head.gameRoleId
                              )
                              .sample
                              .get
                        )
                        created <- challengeRepo.create(challenge)
                        // The challenger's own acceptance, which the service always writes alongside the
                        // challenge and which is where the challenge's role is stored -- reading a challenge
                        // joins it back in, so a challenge without one is not a state that ever exists.
                        _ <- acceptanceRepo.create(
                          CharacterAcceptance(
                            created.challengeId,
                            createdPlayer.playerId,
                            createdGame.gameId,
                            createdCharacter.characterId,
                            challenge.gameRoleId
                          )
                        )
                        found <- challengeRepo.read(createdGame.gameId, created.challengeId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    property("a challenge remembers whether it is open") {
        forAll(Generators.genPlayer, org.scalacheck.Gen.oneOf(true, false)) { (player, isOpen) =>
            TestSession.resource
                .use { session =>
                    val gameRepo = new GameRepo[String](session)
                    val playerRepo = new PlayerRepo(session)
                    val characterRepo = new CharacterRepo[String](session)
                    val challengeRepo = new ChallengeRepo(session)
                    val acceptanceRepo = new AcceptanceRepo(session)

                    for {
                        createdGame <- gameRepo.create(Generators.genGame().sample.get)
                        createdPlayer <- playerRepo.create(player)
                        createdCharacter <- characterRepo.create(
                          Generators.genCharacter(createdGame.gameId, None).sample.get
                        )
                        challenge = Generators
                            .genChallenge(
                              createdPlayer.playerId,
                              createdGame.gameId,
                              createdCharacter.characterId,
                              createdGame.roles.head.gameRoleId
                            )
                            .sample
                            .get
                            .asInstanceOf[CharacterChallenge]
                            .copy(isOpen = isOpen)
                        created <- challengeRepo.create(challenge)
                        _ <- acceptanceRepo.create(
                          CharacterAcceptance(
                            created.challengeId,
                            createdPlayer.playerId,
                            createdGame.gameId,
                            createdCharacter.characterId,
                            challenge.gameRoleId
                          )
                        )
                        found <- challengeRepo.read(createdGame.gameId, created.challengeId)
                        // Also through the listing, which reads the column by a different query --
                        // the two have disagreed before now, which is why both are asked.
                        listed <- challengeRepo.listByGame(createdGame.gameId, createdPlayer.playerId)
                    } yield found.exists(_.isOpen == isOpen) &&
                        listed.find(_.challenge.challengeId == created.challengeId).exists(_.challenge.isOpen == isOpen)
                }
                .unsafeRunSync()
        }
    }

}
