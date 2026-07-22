package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Game, its roles, its parameters, and its parameter values are always read, written,
  * and updated together, so this repo persists the whole aggregate in one call.
  */
class GameRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
  private val value: Codec[T] = SkunkCodecs.jsonAsText[T]

  private val insertGameRow: Query[(String, String, String, Boolean), Int] =
    sql"""INSERT INTO game (name, description, url, active) VALUES ($varchar, $varchar, $varchar, $bool)
          RETURNING game_id""".query(int4)

  private val updateGameRow: Command[(String, String, String, Boolean, Int)] =
    sql"""UPDATE game SET name = $varchar, description = $varchar, url = $varchar, active = $bool
          WHERE game_id = $int4""".command

  private val insertPlayerGame: Command[Int] =
    sql"INSERT INTO player_game (game_id) VALUES ($int4)".command

  private val insertCharacterGame: Command[Int] =
    sql"INSERT INTO character_game (game_id) VALUES ($int4)".command

  private val selectGameRow: Query[Int, (String, String, String, Boolean, Boolean)] =
    sql"""SELECT g.name, g.description, g.url, g.active, (pg.game_id IS NOT NULL) AS is_player_game
          FROM game g LEFT JOIN player_game pg ON pg.game_id = g.game_id
          WHERE g.game_id = $int4""".query(varchar *: varchar *: varchar *: bool *: bool)

  private val insertRoleStmt: Query[(Int, String, Boolean), Int] =
    sql"""INSERT INTO game_role (game_id, name, optional) VALUES ($int4, $varchar, $bool)
          RETURNING game_role_id""".query(int4)

  private val selectRoles: Query[Int, (Int, String, Boolean)] =
    sql"SELECT game_role_id, name, optional FROM game_role WHERE game_id = $int4"
      .query(int4 *: varchar *: bool)

  private val deleteRoles: Command[Int] =
    sql"DELETE FROM game_role WHERE game_id = $int4".command

  private val insertParameterStmt: Query[(Int, String), Int] =
    sql"""INSERT INTO game_parameter (game_id, name) VALUES ($int4, $varchar)
          RETURNING game_parameter_id""".query(int4)

  private val setDefaultValueStmt: Command[(T, Int, Int)] =
    sql"UPDATE game_parameter SET default_value = $value WHERE game_id = $int4 AND game_parameter_id = $int4".command

  private val clearDefaultValues: Command[Int] =
    sql"UPDATE game_parameter SET default_value = NULL WHERE game_id = $int4".command

  private val insertParameterValueStmt: Command[(Int, Int, T)] =
    sql"INSERT INTO game_parameter_value (game_id, game_parameter_id, value) VALUES ($int4, $int4, $value)".command

  private val deleteParameterValues: Command[Int] =
    sql"DELETE FROM game_parameter_value WHERE game_id = $int4".command

  private val deleteParameters: Command[Int] =
    sql"DELETE FROM game_parameter WHERE game_id = $int4".command

  private val selectParameters: Query[Int, (Int, String, Option[T])] =
    sql"SELECT game_parameter_id, name, default_value FROM game_parameter WHERE game_id = $int4"
      .query(int4 *: varchar *: value.opt)

  private val selectParameterValues: Query[(Int, Int), T] =
    sql"SELECT value FROM game_parameter_value WHERE game_id = $int4 AND game_parameter_id = $int4".query(value)

  def create(game: Game): IO[Game] =
    session.transaction.use { _ =>
      for {
        gameId <- session.unique(insertGameRow)((game.name, game.description, game.url, game.active))
        _ <- game match {
          case _: PlayerGame    => session.execute(insertPlayerGame)(gameId)
          case _: CharacterGame => session.execute(insertCharacterGame)(gameId)
        }
        roles <- game.roles.toList.traverse(insertRole(gameId, _))
        parameters <- game.parameters.toList.traverse(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
      } yield build(game, gameId, roles, parameters)
    }

  def read(gameId: Int): IO[Option[Game]] =
    session.option(selectGameRow)(gameId).flatMap {
      case None => IO.pure(None)
      case Some((name, description, url, active, isPlayerGame)) =>
        for {
          roles <- readRoles(gameId)
          parameters <- readParameters(gameId)
        } yield Some(
          if (isPlayerGame) PlayerGame(gameId, name, description, url, active, roles, parameters)
          else CharacterGame(gameId, name, description, url, active, roles, parameters)
        )
    }

  def update(game: Game): IO[Unit] =
    session.transaction.use { _ =>
      for {
        _ <- session.execute(updateGameRow)((game.name, game.description, game.url, game.active, game.gameId))
        _ <- replaceRoles(game.gameId, game.roles)
        _ <- replaceParameters(game.gameId, game.parameters)
      } yield ()
    }

  private def insertRole(gameId: Int, role: GameRole): IO[GameRole] =
    session
      .unique(insertRoleStmt)((gameId, role.name, role.optional))
      .map(id => role.copy(gameRoleId = id, gameId = gameId))

  private def readRoles(gameId: Int): IO[Seq[GameRole]] =
    session.execute(selectRoles)(gameId).map(_.map { case (id, name, optional) => GameRole(id, gameId, name, optional) })

  private def replaceRoles(gameId: Int, roles: Seq[GameRole]): IO[Unit] =
    for {
      _ <- session.execute(deleteRoles)(gameId)
      _ <- roles.toList.traverse(insertRole(gameId, _))
    } yield ()

  // game_parameter.default_value has a composite FK to game_parameter_value(game_id,
  // game_parameter_id, value), so the parameter row must be inserted before its values
  // exist, and default_value can only be set once a matching value row is present.
  private def insertParameter(gameId: Int, parameter: GameParameter[T]): IO[GameParameter[T]] =
    for {
      parameterId <- session.unique(insertParameterStmt)((gameId, parameter.name))
      values <- parameter.values.toList.traverse(v => insertParameterValue(gameId, parameterId, v))
      _ <- parameter.defaultValue match {
        case Some(v) => session.execute(setDefaultValueStmt)((v, gameId, parameterId)).void
        case None    => IO.unit
      }
    } yield parameter.copy(gameId = gameId, gameParameterId = parameterId, values = values)

  private def insertParameterValue(gameId: Int, parameterId: Int, value: GameParameterValue[T]): IO[GameParameterValue[T]] =
    session
      .execute(insertParameterValueStmt)((gameId, parameterId, value.value))
      .as(value.copy(gameId = gameId, gameParameterId = parameterId))

  private def readParameters(gameId: Int): IO[Seq[GameParameter[T]]] =
    session.execute(selectParameters)(gameId).flatMap(_.traverse { case (parameterId, name, defaultValue) =>
      readParameterValues(gameId, parameterId).map(values => GameParameter(gameId, parameterId, name, defaultValue, values))
    })

  private def readParameterValues(gameId: Int, parameterId: Int): IO[Seq[GameParameterValue[T]]] =
    session
      .execute(selectParameterValues)((gameId, parameterId))
      .map(_.map(v => GameParameterValue(gameId, parameterId, v)))

  private def replaceParameters(gameId: Int, parameters: Seq[GameParameter[_]]): IO[Unit] =
    for {
      _ <- session.execute(clearDefaultValues)(gameId)
      _ <- session.execute(deleteParameterValues)(gameId)
      _ <- session.execute(deleteParameters)(gameId)
      _ <- parameters.toList.traverse(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
    } yield ()

  private def build(game: Game, gameId: Int, roles: Seq[GameRole], parameters: Seq[GameParameter[T]]): Game =
    game match {
      case g: PlayerGame    => g.copy(gameId = gameId, roles = roles, parameters = parameters)
      case g: CharacterGame => g.copy(gameId = gameId, roles = roles, parameters = parameters)
    }
}
