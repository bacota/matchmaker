package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import com.vivi.matchmaker.model._
import org.scalacheck.Prop._
import skunk.Session
import skunk.exception.PostgresErrorException

/** What the `invitation` table itself enforces, as opposed to what `ChallengeService` refuses before it gets here.
  *
  * The service checks all of this and reports it properly, so these are the second line: they are what keeps the rules
  * true if a future caller reaches the table by another route, and what says which of them the schema is actually
  * holding up.
  */
class InvitationRepoSpec extends PropertySuite {

    private case class Fixture(game: Game, challenger: Player, invitee: Player, challenge: Challenge)

    /* A challenge with its challenger's own acceptance, which is the state every challenge is in --
     * see ChallengeRepoSpec's note on the same fixture. */
    private def fixture(session: Session[IO]): IO[Fixture] =
        for {
            game <- new GameRepo[String](session).create(Generators.genGame().sample.get)
            challenger <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            invitee <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
            character <- new CharacterRepo[String](session).create(
              Generators.genCharacter(game.gameId, None).sample.get
            )
            challenge <- new ChallengeRepo(session).create(
              Generators
                  .genChallenge(challenger.playerId, game.gameId, character.characterId, game.roles.head.gameRoleId)
                  .sample
                  .get
            )
            _ <- new AcceptanceRepo(session).create(
              CharacterAcceptance(
                challenge.challengeId,
                challenger.playerId,
                game.gameId,
                character.characterId,
                challenge.gameRoleId
              )
            )
        } yield Fixture(game, challenger, invitee, challenge)

    property("an invitation round-trips, with the role it names and without one") {
        forAll(org.scalacheck.Gen.oneOf(true, false)) { withRole =>
            TestSession.resource
                .use { session =>
                    val invitations = new InvitationRepo(session)
                    for {
                        f <- fixture(session)
                        role = Option.when(withRole)(f.game.roles.head.gameRoleId)
                        created <- invitations.create(
                          Invitation(f.game.gameId, f.challenge.challengeId, f.invitee.playerId, role)
                        )
                        read <- invitations.read(f.game.gameId, f.challenge.challengeId, f.invitee.playerId)
                        listed <- invitations.listForChallenge(f.game.gameId, f.challenge.challengeId)
                        // reservedRoles is the same rows asked a narrower question, and an invitation with
                        // no role reserves nothing.
                        reserved <- invitations.reservedRoles(f.game.gameId, f.challenge.challengeId)
                    } yield read == Some(created) && listed == List(created) &&
                        reserved == role.toList.map(r => (r, f.invitee.playerId))
                }
                .unsafeRunSync()
        }
    }

    property("one player cannot hold two invitations to one challenge") {
        TestSession.resource
            .use { session =>
                val invitations = new InvitationRepo(session)
                for {
                    f <- fixture(session)
                    _ <- invitations.create(Invitation(f.game.gameId, f.challenge.challengeId, f.invitee.playerId))
                    // The primary key, not the service: a second row for one player in one challenge would
                    // be a second answer to one question, and this is what makes that impossible rather
                    // than merely refused.
                    again <- invitations
                        .create(Invitation(f.game.gameId, f.challenge.challengeId, f.invitee.playerId, None))
                        .attempt
                } yield again.left.exists(_.isInstanceOf[PostgresErrorException])
            }
            .unsafeRunSync()
    }

    property("a role from another game cannot be held by an invitation") {
        TestSession.resource
            .use { session =>
                val invitations = new InvitationRepo(session)
                for {
                    f <- fixture(session)
                    elsewhere <- new GameRepo[String](session).create(Generators.genGame().sample.get)
                    // The composite foreign key. Without it an invitation could hold a seat that does not
                    // exist in the game being played, and nothing would notice until an acceptance was
                    // measured against it.
                    attempt <- invitations
                        .create(
                          Invitation(
                            f.game.gameId,
                            f.challenge.challengeId,
                            f.invitee.playerId,
                            Some(elsewhere.roles.head.gameRoleId)
                          )
                        )
                        .attempt
                } yield attempt.left.exists(_.isInstanceOf[PostgresErrorException])
            }
            .unsafeRunSync()
    }

    property("listForPlayer spans games, and leaves out a challenge that is already being started") {
        TestSession.resource
            .use { session =>
                val invitations = new InvitationRepo(session)
                val challenges = new ChallengeRepo(session)
                for {
                    first <- fixture(session)
                    second <- fixture(session)
                    _ <- invitations.create(
                      Invitation(first.game.gameId, first.challenge.challengeId, first.invitee.playerId)
                    )
                    _ <- invitations.create(
                      Invitation(second.game.gameId, second.challenge.challengeId, first.invitee.playerId)
                    )
                    both <- invitations.listForPlayer(first.invitee.playerId)
                    // A started challenge is not something to accept, so an invitation to one is not
                    // something to show. The row survives the start -- it goes with the challenge -- so
                    // this is a filter in the query rather than a delete at the start.
                    _ <- challenges.claimForStart(second.game.gameId, second.challenge.challengeId, MatchId("m-1"))
                    after <- invitations.listForPlayer(first.invitee.playerId)
                } yield both.map(_.invitation.challengeId).toSet ==
                    Set(first.challenge.challengeId, second.challenge.challengeId) &&
                    after.map(_.invitation.challengeId) == List(first.challenge.challengeId)
            }
            .unsafeRunSync()
    }

    property("deleting a challenge's invitations leaves the other challenges' alone") {
        TestSession.resource
            .use { session =>
                val invitations = new InvitationRepo(session)
                for {
                    f <- fixture(session)
                    other <- fixture(session)
                    _ <- invitations.create(Invitation(f.game.gameId, f.challenge.challengeId, f.invitee.playerId))
                    _ <- invitations.create(
                      Invitation(other.game.gameId, other.challenge.challengeId, other.invitee.playerId)
                    )
                    _ <- invitations.deleteAllForChallenge(f.game.gameId, f.challenge.challengeId)
                    gone <- invitations.listForChallenge(f.game.gameId, f.challenge.challengeId)
                    kept <- invitations.listForChallenge(other.game.gameId, other.challenge.challengeId)
                } yield gone.isEmpty && kept.sizeIs == 1
            }
            .unsafeRunSync()
    }
}
