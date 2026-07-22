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
  private val participantId = SkunkIdCodecs.participantId
  private val gameId = SkunkIdCodecs.gameId
  private val matchId = SkunkIdCodecs.matchId
  private val playerId = SkunkIdCodecs.playerId
  private val gameRoleId = SkunkIdCodecs.gameRoleId
  private val characterId = SkunkIdCodecs.characterId
  private val instant = SkunkCodecs.instant

  private val insertParticipant: Query[(GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant]), ParticipantId] =
    sql"""INSERT INTO participant (game_id, match_id, player_id, pending, completed, due)
          VALUES ($gameId, $matchId, $playerId, $bool, $bool, ${instant.opt})
          RETURNING participant_id""".query(participantId)

  private val insertPlayerParticipant: Command[(ParticipantId, GameId, MatchId, GameRoleId)] =
    sql"""INSERT INTO player_participant (participant_id, game_id, match_id, game_role_id)
          VALUES ($participantId, $gameId, $matchId, $gameRoleId)""".command

  private val insertCharacterParticipant: Command[(ParticipantId, GameId, MatchId, CharacterId)] =
    sql"""INSERT INTO character_participant (participant_id, game_id, match_id, character_id)
          VALUES ($participantId, $gameId, $matchId, $characterId)""".command

  private val selectParticipant
      : Query[ParticipantId, (GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[CharacterId])] =
    sql"""SELECT p.game_id, p.match_id, p.player_id, p.pending, p.completed, p.due,
                 pp.game_role_id, cp.character_id
          FROM participant p
          LEFT JOIN player_participant pp ON pp.participant_id = p.participant_id
          LEFT JOIN character_participant cp ON cp.participant_id = p.participant_id
          WHERE p.participant_id = $participantId"""
      .query(gameId *: matchId *: playerId *: bool *: bool *: instant.opt *: gameRoleId.opt *: characterId.opt)

  private val updateParticipant: Command[(PlayerId, Boolean, Boolean, Option[Instant], ParticipantId)] =
    sql"""UPDATE participant SET player_id = $playerId, pending = $bool, completed = $bool, due = ${instant.opt}
          WHERE participant_id = $participantId""".command

  private val updatePlayerParticipant: Command[(GameRoleId, ParticipantId)] =
    sql"UPDATE player_participant SET game_role_id = $gameRoleId WHERE participant_id = $participantId".command

  private val updateCharacterParticipant: Command[(CharacterId, ParticipantId)] =
    sql"UPDATE character_participant SET character_id = $characterId WHERE participant_id = $participantId".command

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

  def read(id: ParticipantId): IO[Option[Participant]] =
    session.option(selectParticipant)(id).map(_.map {
      case (gameId, matchId, playerId, pending, completed, due, gameRoleId, characterId) =>
        gameRoleId match {
          case Some(roleId) => PlayerParticipant(id, gameId, matchId, playerId, pending, completed, due, roleId)
          case None => CharacterParticipant(id, gameId, matchId, playerId, pending, completed, due, characterId.get)
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
