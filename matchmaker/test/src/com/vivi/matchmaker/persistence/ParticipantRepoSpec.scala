package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vivi.matchmaker.PropertySuite
import org.scalacheck.Prop._
import skunk.implicits._
import skunk.codec.all.int8
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{CharacterParticipant, EloRating, MatchId, Participant, PlainParticipant}

class ParticipantRepoSpec extends PropertySuite {
    property("create then read returns the participant just created") {
        forAll(Generators.genMatchId, Generators.genPlayer) { (mid, player) =>
            TestSession.resource
                .use { session =>
                    val gameRepo = new GameRepo[String](session)
                    val matchRepo = new MatchRepo(session)
                    val playerRepo = new PlayerRepo(session)
                    val characterRepo = new CharacterRepo[String](session)
                    val participantRepo = new ParticipantRepo(session)

                    for {
                        createdGame <- gameRepo.create(Generators.genGameWithRole.sample.get)
                        owner <- Generators.ownerIn(session, createdGame)
                        matchId = mid
                        m <- TestMatches.writeMatch(
                          session = session,
                          m = Generators.matchFrom(createdGame.gameId, matchId),
                          owner = owner
                        )
                        createdPlayer <- playerRepo.create(player)
                        createdCharacter <- characterRepo.create(
                          Generators.genCharacter(createdGame.gameId, None).sample.get
                        )
                        participant <- IO.pure(
                          Generators
                              .genParticipant(
                                gameId = createdGame.gameId,
                                matchId = matchId,
                                playerId = createdPlayer.playerId,
                                characterId = createdCharacter.characterId,
                                gameRoleId = createdGame.roles.head.gameRoleId
                              )
                              .sample
                              .get
                        )
                        created <- TestMatches.writeSeat(
                          session = session,
                          p = participant,
                          eloStart = EloRating.initial
                        )
                        found <- participantRepo.read(created.gameId, created.participantId)
                    } yield found == Some(created)
                }
                .unsafeRunSync()
        }
    }

    /* A seat finished is a time, stamped by the database when the seat is first marked
     * finished and kept through every later write: completing is sticky, and a repeated callback is
     * not a second ending. */
    test("finishing a seat stamps when, and later writes to the finished seat keep that time") {
        def finished(p: Participant, done: Boolean): Participant = p match {
            case cp: CharacterParticipant => cp.copy(isCompleted = done, isPending = false)
            case pp: PlainParticipant     => pp.copy(isCompleted = done, isPending = false)
        }
        val (before, first, again, read) = TestSession.resource
            .use { session =>
                val participantRepo = new ParticipantRepo(session)
                val completedAt =
                    sql"SELECT completed_at FROM participant WHERE participant_id = $int8"
                        .query(SkunkCodecs.instant.opt)
                def stamp(p: Participant) = session.unique(completedAt)(p.participantId.value)
                for {
                    game <- new GameRepo[String](session).create(Generators.genGameWithRole.sample.get)
                    owner <- Generators.ownerIn(session, game)
                    matchId = Generators.newMatchId()
                    _ <- TestMatches.writeMatch(
                      session = session,
                      m = Generators.matchFrom(game.gameId, matchId),
                      owner = owner
                    )
                    player <- new PlayerRepo(session).create(Generators.genPlayer.sample.get)
                    character <- new CharacterRepo[String](session).create(
                      Generators.genCharacter(game.gameId, None).sample.get
                    )
                    seat <- TestMatches.writeSeat(
                      session = session,
                      p = finished(
                        Generators
                            .genParticipant(
                              gameId = game.gameId,
                              matchId = matchId,
                              playerId = player.playerId,
                              characterId = character.characterId,
                              gameRoleId = game.roles.head.gameRoleId
                            )
                            .sample
                            .get,
                        done = false
                      ),
                      eloStart = EloRating.initial
                    )
                    before <- stamp(seat)
                    _ <- participantRepo.update(finished(seat, done = true))
                    first <- stamp(seat)
                    _ <- participantRepo.update(finished(seat, done = true))
                    again <- stamp(seat)
                    read <- participantRepo.read(seat.gameId, seat.participantId)
                } yield (before, first, again, read)
            }
            .unsafeRunSync()
        assertEquals(before, None)
        assert(first.isDefined)
        assertEquals(again, first)
        assertEquals(read.map(_.isCompleted), Some(true))
    }
}
