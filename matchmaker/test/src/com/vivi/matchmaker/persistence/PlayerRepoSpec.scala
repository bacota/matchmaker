package com.vivi.matchmaker.persistence

import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._

class PlayerRepoSpec extends PropertySuite {
    property("create then read returns the player just created") {
        forAll(Generators.genPlayer) { player =>
            TestSession.resource
                .use { session =>
                    val repo = new PlayerRepo(session)
                    for {
                        created <- repo.create(player)
                        found <- repo.read(created.playerId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    /* The address has one writer, `updateEmail`, and `update` is not it.
     *
     * Cognito owns the address and matchmaker's copy is written only from a verified claim at
     * sign-in; a general update that carried it would let every caller restate it from whatever
     * `Player` it happened to be holding. These two properties are that split. */

    property("update leaves the address alone, even when handed a Player that says otherwise") {
        forAll(Generators.genPlayer) { player =>
            TestSession.resource
                .use { session =>
                    val repo = new PlayerRepo(session)
                    for {
                        created <- repo.create(
                          player.copy(email = Some(s"original-${java.util.UUID.randomUUID()}@example.com"))
                        )
                        // A rename, with a stale address in the same object — exactly the shape of the mistake.
                        _ <- repo.update(
                          created.copy(nickname = s"renamed-${java.util.UUID.randomUUID()}", email = None)
                        )
                        found <- repo.read(created.playerId)
                    } yield found.flatMap(_.email) == created.email && found.map(_.nickname) != Some(created.nickname)
                }
                .unsafeRunSync()
        }
    }

    property("updateEmail writes the address and nothing else") {
        forAll(Generators.genPlayer) { player =>
            val address = s"changed-${java.util.UUID.randomUUID()}@example.com"
            TestSession.resource
                .use { session =>
                    val repo = new PlayerRepo(session)
                    for {
                        created <- repo.create(player)
                        _ <- repo.updateEmail(created.playerId, Some(address))
                        found <- repo.read(created.playerId)
                    } yield found == Some(created.copy(email = Some(address)))
                }
                .unsafeRunSync()
        }
    }

    // Nullable for a reason: a player registered before the column existed has none, and nothing
    // can invent one. Clearing is therefore something the column has to permit.
    property("updateEmail can clear an address") {
        forAll(Generators.genPlayer) { player =>
            TestSession.resource
                .use { session =>
                    val repo = new PlayerRepo(session)
                    for {
                        created <- repo.create(
                          player.copy(email = Some(s"gone-${java.util.UUID.randomUUID()}@example.com"))
                        )
                        _ <- repo.updateEmail(created.playerId, None)
                        found <- repo.read(created.playerId)
                    } yield found.flatMap(_.email).isEmpty
                }
                .unsafeRunSync()
        }
    }
}
