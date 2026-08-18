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
  private val gameRoleId = SkunkIdCodecs.gameRoleId
  private val instant = SkunkCodecs.instant

  private val insertParticipant
      : Query[(GameId, MatchId, GameType, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId]), ParticipantId] =
    sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, pending, completed, due, game_role_id)
          VALUES ($gameId, $matchId, $gameType, $playerId, $bool, $bool, ${instant.opt}, ${gameRoleId.opt})
          RETURNING participant_id""".query(participantId)

  private val insertCharacterParticipant: Command[(GameId, ParticipantId, CharacterId)] =
    sql"""INSERT INTO character_participant (game_id, participant_id, game_type, character_id)
          VALUES ($gameId, $participantId, 'C', $characterId)""".command

  // participant_id is only unique within its game_id — the table's primary key is the composite
  // (game_id, participant_id), with no separate UNIQUE(participant_id) the way character has —
  // so both columns are required in the WHERE clause here, not participant_id alone.
  private val participantRow
      : Codec[(GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])] =
    gameType *: matchId *: playerId *: bool *: bool *: instant.opt *: gameRoleId.opt *: int8.opt

  private val selectParticipant: Query[
    (GameId, ParticipantId),
    (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])
  ] =
    sql"""SELECT p.game_type, p.match_id, p.player_id, p.pending, p.completed, p.due, p.game_role_id,
                 cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.participant_id = $participantId"""
      .query(participantRow)

  // Everyone in one match, with the player's external (Cognito) id, which is what the game engine
  // knows a player by — it authenticates them itself and never sees matchmaker's player ids.
  private val selectParticipantsForMatch: Query[
    (GameId, MatchId),
    (ParticipantId, GameType, PlayerId, String, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[String], Option[Long])
  ] =
    sql"""SELECT p.participant_id, p.game_type, p.player_id, pl.external_id, p.pending, p.completed,
                 p.due, p.game_role_id, r.name, cp.character_id
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN game_role r ON r.game_id = p.game_id AND r.game_role_id = p.game_role_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
      .query(
        participantId *: gameType *: playerId *: text *: bool *: bool *: instant.opt *: gameRoleId.opt *: text.opt *: int8.opt
      )

  private val updateParticipant
      : Command[(PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], GameId, ParticipantId)] =
    sql"""UPDATE participant SET player_id = $playerId, pending = $bool, completed = $bool,
          due = ${instant.opt}, game_role_id = ${gameRoleId.opt}
          WHERE game_id = $gameId AND participant_id = $participantId""".command

  private def toParticipant(
      id: ParticipantId,
      gameId: GameId,
      row: (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])
  ): Participant = {
    val (gameType, matchId, playerId, pending, completed, due, roleId, characterIdValue) = row
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"participant ${id.value} is game_type 'C' but has no character_participant row")
        )
        CharacterParticipant(id, gameId, matchId, playerId, pending, completed, due, CharacterId(cid), roleId)
      case GameType.Plain =>
        PlainParticipant(id, gameId, matchId, playerId, pending, completed, due, roleId)
    }
  }

  def create(p: Participant): IO[Participant] = {
    val gt = p match {
      case _: CharacterParticipant => GameType.Character
      case _: PlainParticipant     => GameType.Plain
    }
    for {
      id <- session.unique(insertParticipant)((p.gameId, p.matchId, gt, p.playerId, p.pending, p.completed, p.due, p.gameRoleId))
      _ <- p match {
        case cp: CharacterParticipant => session.execute(insertCharacterParticipant)((p.gameId, id, cp.characterId)).void
        case _: PlainParticipant      => IO.unit
      }
    } yield p match {
      case cp: CharacterParticipant => cp.copy(participantId = id)
      case pp: PlainParticipant     => pp.copy(participantId = id)
    }
  }

  def read(gameId: GameId, id: ParticipantId): IO[Option[Participant]] =
    session.option(selectParticipant)((gameId, id)).map(_.map(row => toParticipant(id, gameId, row)))

  def update(p: Participant): IO[Unit] =
    session
      .execute(updateParticipant)((p.playerId, p.pending, p.completed, p.due, p.gameRoleId, p.gameId, p.participantId))
      .void

  // character_participant has a FK to participant, so its rows go first.
  private val deleteCharacterParticipantsForMatch: Command[(GameId, MatchId)] =
    sql"""DELETE FROM character_participant cp
          USING participant p
          WHERE p.game_id = cp.game_id AND p.participant_id = cp.participant_id
            AND p.game_id = $gameId AND p.match_id = $matchId""".command

  private val deleteParticipantsForMatch: Command[(GameId, MatchId)] =
    sql"DELETE FROM participant WHERE game_id = $gameId AND match_id = $matchId".command

  /** Removes every participant in a match. Only used to undo a match whose game the engine
    * failed to create; participants of a match that is actually being played are completed,
    * never deleted.
    */
  def deleteForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterParticipantsForMatch)((gameId, matchId))
      _ <- session.execute(deleteParticipantsForMatch)((gameId, matchId))
    } yield ()

  /** Everyone playing one match, together with the player's external id and role name.
    *
    * The two extra columns are there for the game-engine calls: the engine is told which Cognito
    * identity plays which role, and knows nothing of matchmaker's own player or role ids.
    */
  def listForMatch(gameId: GameId, matchId: MatchId): IO[List[(Participant, String, Option[String])]] =
    session.execute(selectParticipantsForMatch)((gameId, matchId)).map(_.map {
      case (id, gt, playerId, externalId, pending, completed, due, roleId, roleName, characterIdValue) =>
        val participant = toParticipant(id, gameId, (gt, matchId, playerId, pending, completed, due, roleId, characterIdValue))
        (participant, externalId, roleName)
    })
}
