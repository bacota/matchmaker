package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._

class ParticipantRepo(session: Session[IO]) {
  private val instant = SkunkCodecs.instant

  private val insertParticipant: Query[(Int, String, Long, Boolean, Boolean, Option[Instant]), Long] =
    sql"""INSERT INTO participant (game_id, match_id, player_id, pending, completed, due)
          VALUES ($int4, $varchar, $int8, $bool, $bool, ${instant.opt})
          RETURNING participant_id""".query(int8)

  private val insertPlayerParticipant: Command[(Long, Int, String, Int)] =
    sql"""INSERT INTO player_participant (participant_id, game_id, match_id, game_role_id)
          VALUES ($int8, $int4, $varchar, $int4)""".command

  private val insertCharacterParticipant: Command[(Long, Int, String, Long)] =
    sql"""INSERT INTO character_participant (participant_id, game_id, match_id, character_id)
          VALUES ($int8, $int4, $varchar, $int8)""".command

  private val selectParticipant
      : Query[Long, (Int, String, Long, Boolean, Boolean, Option[Instant], Option[Int], Option[Long])] =
    sql"""SELECT p.game_id, p.match_id, p.player_id, p.pending, p.completed, p.due,
                 pp.game_role_id, cp.character_id
          FROM participant p
          LEFT JOIN player_participant pp ON pp.participant_id = p.participant_id
          LEFT JOIN character_participant cp ON cp.participant_id = p.participant_id
          WHERE p.participant_id = $int8"""
      .query(int4 *: varchar *: int8 *: bool *: bool *: instant.opt *: int4.opt *: int8.opt)

  private val updateParticipant: Command[(Long, Boolean, Boolean, Option[Instant], Long)] =
    sql"""UPDATE participant SET player_id = $int8, pending = $bool, completed = $bool, due = ${instant.opt}
          WHERE participant_id = $int8""".command

  private val updatePlayerParticipant: Command[(Int, Long)] =
    sql"UPDATE player_participant SET game_role_id = $int4 WHERE participant_id = $int8".command

  private val updateCharacterParticipant: Command[(Long, Long)] =
    sql"UPDATE character_participant SET character_id = $int8 WHERE participant_id = $int8".command

  def create(p: Participant): IO[Participant] =
    session.transaction.use { _ =>
      for {
        participantId <- session.unique(insertParticipant)((p.gameId, p.matchId, p.playerId, p.pending, p.completed, p.due))
        result <- p match {
          case pp: PlayerParticipant =>
            session
              .execute(insertPlayerParticipant)((participantId, pp.gameId, pp.matchId, pp.gameRoleId))
              .as(pp.copy(participantId = participantId): Participant)
          case cp: CharacterParticipant =>
            session
              .execute(insertCharacterParticipant)((participantId, cp.gameId, cp.matchId, cp.characterId))
              .as(cp.copy(participantId = participantId): Participant)
        }
      } yield result
    }

  def read(participantId: Long): IO[Option[Participant]] =
    session.option(selectParticipant)(participantId).map(_.map {
      case (gameId, matchId, playerId, pending, completed, due, gameRoleId, characterId) =>
        gameRoleId match {
          case Some(roleId) => PlayerParticipant(participantId, gameId, matchId, playerId, pending, completed, due, roleId)
          case None => CharacterParticipant(participantId, gameId, matchId, playerId, pending, completed, due, characterId.get)
        }
    })

  def update(p: Participant): IO[Unit] =
    session.transaction.use { _ =>
      for {
        _ <- session.execute(updateParticipant)((p.playerId, p.pending, p.completed, p.due, p.participantId))
        _ <- p match {
          case pp: PlayerParticipant    => session.execute(updatePlayerParticipant)((pp.gameRoleId, pp.participantId))
          case cp: CharacterParticipant => session.execute(updateCharacterParticipant)((cp.characterId, cp.participantId))
        }
      } yield ()
    }
}
