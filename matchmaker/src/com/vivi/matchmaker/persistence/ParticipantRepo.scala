package com.vivi.matchmaker.persistence

import cats.effect.IO
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
  private val characterId = SkunkIdCodecs.characterId
  private val instant = SkunkCodecs.instant

  private val insertParticipant: Query[(GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant], CharacterId), ParticipantId] =
    sql"""INSERT INTO participant (game_id, match_id, player_id, pending, completed, due, character_id)
          VALUES ($gameId, $matchId, $playerId, $bool, $bool, ${instant.opt}, $characterId)
          RETURNING participant_id""".query(participantId)

  private val selectParticipant: Query[ParticipantId, (CharacterId, GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant])] =
    sql"""SELECT character_id, game_id, match_id, player_id, pending, completed, due
          FROM participant
          WHERE participant_id = $participantId"""
      .query(characterId *: gameId *: matchId *: playerId *: bool *: bool *: instant.opt)

  private val updateParticipant: Command[(PlayerId, Boolean, Boolean, Option[Instant], CharacterId, ParticipantId)] =
    sql"""UPDATE participant SET player_id = $playerId, pending = $bool, completed = $bool, due = ${instant.opt},
          character_id = $characterId
          WHERE participant_id = $participantId""".command

  def create(p: Participant): IO[Participant] =
    session
      .unique(insertParticipant)((p.gameId, p.matchId, p.playerId, p.pending, p.completed, p.due, p.characterId))
      .map(id => p.copy(participantId = id))

  def read(id: ParticipantId): IO[Option[Participant]] =
    session.option(selectParticipant)(id).map(_.map {
      case (characterId, gameId, matchId, playerId, pending, completed, due) =>
        Participant(id, gameId, matchId, playerId, pending, completed, due, characterId)
    })

  def update(p: Participant): IO[Unit] =
    session
      .execute(updateParticipant)((p.playerId, p.pending, p.completed, p.due, p.characterId, p.participantId))
      .void
}
