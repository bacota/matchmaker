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
  private val characterId = SkunkIdCodecs.characterId
  private val gameType = SkunkCodecs.gameType
  private val instant = SkunkCodecs.instant

  private val insertParticipant: Query[(GameId, MatchId, GameType, PlayerId, Boolean, Boolean, Option[Instant]), ParticipantId] =
    sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, pending, completed, due)
          VALUES ($gameId, $matchId, $gameType, $playerId, $bool, $bool, ${instant.opt})
          RETURNING participant_id""".query(participantId)

  // participant_id is GENERATED ALWAYS AS IDENTITY on this table too, but it must actually equal
  // the participant_id its FK references on participant, so it has to be supplied explicitly
  // rather than left to generate its own (unrelated) value — hence OVERRIDING SYSTEM VALUE.
  private val insertCharacterParticipant: Command[(GameId, ParticipantId, CharacterId)] =
    sql"""INSERT INTO character_participant (game_id, participant_id, game_type, character_id)
          OVERRIDING SYSTEM VALUE
          VALUES ($gameId, $participantId, 'C', $characterId)""".command

  private val selectParticipant: Query[
    ParticipantId,
    (GameType, GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[Long])
  ] =
    sql"""SELECT p.game_type, p.game_id, p.match_id, p.player_id, p.pending, p.completed, p.due, cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.participant_id = $participantId"""
      .query(gameType *: gameId *: matchId *: playerId *: bool *: bool *: instant.opt *: int8.opt)

  private val updateParticipant: Command[(PlayerId, Boolean, Boolean, Option[Instant], ParticipantId)] =
    sql"""UPDATE participant SET player_id = $playerId, pending = $bool, completed = $bool, due = ${instant.opt}
          WHERE participant_id = $participantId""".command

  private def toParticipant(
      id: ParticipantId,
      row: (GameType, GameId, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[Long])
  ): Participant = {
    val (gameType, gameId, matchId, playerId, pending, completed, due, characterIdValue) = row
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"participant ${id.value} is game_type 'C' but has no character_participant row")
        )
        CharacterParticipant(id, gameId, matchId, playerId, pending, completed, due, CharacterId(cid))
      case GameType.Plain =>
        PlainParticipant(id, gameId, matchId, playerId, pending, completed, due)
    }
  }

  def create(p: Participant): IO[Participant] = {
    val gt = p match {
      case _: CharacterParticipant => GameType.Character
      case _: PlainParticipant     => GameType.Plain
    }
    session.transaction.use { _ =>
      for {
        id <- session.unique(insertParticipant)((p.gameId, p.matchId, gt, p.playerId, p.pending, p.completed, p.due))
        _ <- p match {
          case cp: CharacterParticipant => session.execute(insertCharacterParticipant)((p.gameId, id, cp.characterId)).void
          case _: PlainParticipant      => IO.unit
        }
      } yield p match {
        case cp: CharacterParticipant => cp.copy(participantId = id)
        case pp: PlainParticipant     => pp.copy(participantId = id)
      }
    }
  }

  def read(id: ParticipantId): IO[Option[Participant]] =
    session.option(selectParticipant)(id).map(_.map(row => toParticipant(id, row)))

  def update(p: Participant): IO[Unit] =
    session
      .execute(updateParticipant)((p.playerId, p.pending, p.completed, p.due, p.participantId))
      .void
}
