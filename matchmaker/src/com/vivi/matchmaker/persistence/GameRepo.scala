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
  private val gameId = SkunkIdCodecs.gameId
  private val gameRoleId = SkunkIdCodecs.gameRoleId
  private val gameParameterId = SkunkIdCodecs.gameParameterId
  private val value: Codec[T] = SkunkCodecs.plainText[T]

  private val insertGameRow: Query[(String, String, String, Boolean, String, Int, Int), GameId] =
    sql"""INSERT INTO game (name, description, url, active, external_id, min_players, max_players)
          VALUES ($text, $text, $text, $bool, $text, $int4, $int4)
          RETURNING game_id""".query(gameId)

  private val updateGameRow: Command[(String, String, String, Boolean, String, Int, Int, GameId)] =
    sql"""UPDATE game SET name = $text, description = $text, url = $text, active = $bool, external_id = $text,
          min_players = $int4, max_players = $int4
          WHERE game_id = $gameId""".command

  private val selectGameRow: Query[GameId, (String, String, String, Boolean, String, Int, Int)] =
    sql"""SELECT name, description, url, active, external_id, min_players, max_players
          FROM game
          WHERE game_id = $gameId""".query(text *: text *: text *: bool *: text *: int4 *: int4)

  private val insertRoleStmt: Query[(GameId, String, Boolean), GameRoleId] =
    sql"""INSERT INTO game_role (game_id, name, optional) VALUES ($gameId, $text, $bool)
          RETURNING game_role_id""".query(gameRoleId)

  private val selectRoles: Query[GameId, (GameRoleId, String, Boolean)] =
    sql"SELECT game_role_id, name, optional FROM game_role WHERE game_id = $gameId"
      .query(gameRoleId *: text *: bool)

  private val deleteRoles: Command[GameId] =
    sql"DELETE FROM game_role WHERE game_id = $gameId".command

  private val insertParameterStmt: Query[(GameId, String), GameParameterId] =
    sql"""INSERT INTO game_parameter (game_id, name) VALUES ($gameId, $text)
          RETURNING game_parameter_id""".query(gameParameterId)

  private val setDefaultValueStmt: Command[(T, GameId, GameParameterId)] =
    sql"UPDATE game_parameter SET default_value = $value WHERE game_id = $gameId AND game_parameter_id = $gameParameterId".command

  private val clearDefaultValues: Command[GameId] =
    sql"UPDATE game_parameter SET default_value = NULL WHERE game_id = $gameId".command

  private val insertParameterValueStmt: Command[(GameId, GameParameterId, T)] =
    sql"INSERT INTO game_parameter_value (game_id, game_parameter_id, value) VALUES ($gameId, $gameParameterId, $value)".command

  private val deleteParameterValues: Command[GameId] =
    sql"DELETE FROM game_parameter_value WHERE game_id = $gameId".command

  private val deleteParameters: Command[GameId] =
    sql"DELETE FROM game_parameter WHERE game_id = $gameId".command

  private val selectParameters: Query[GameId, (GameParameterId, String, Option[T])] =
    sql"SELECT game_parameter_id, name, default_value FROM game_parameter WHERE game_id = $gameId"
      .query(gameParameterId *: text *: value.opt)

  private val selectParameterValues: Query[(GameId, GameParameterId), T] =
    sql"SELECT value FROM game_parameter_value WHERE game_id = $gameId AND game_parameter_id = $gameParameterId".query(value)

  def create(game: Game): IO[Game] =
    session.transaction.use { _ =>
      for {
        gameId <- session.unique(insertGameRow)(
          (game.name, game.description, game.url, game.active, game.externalId, game.minPlayers, game.maxPlayers)
        )
        roles <- game.roles.toList.traverse(insertRole(gameId, _))
        parameters <- game.parameters.toList.traverse(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
      } yield game.copy(gameId = gameId, roles = roles, parameters = parameters)
    }

  def read(id: GameId): IO[Option[Game]] =
    session.option(selectGameRow)(id).flatMap {
      case None => IO.pure(None)
      case Some((name, description, url, active, externalId, minPlayers, maxPlayers)) =>
        for {
          roles <- readRoles(id)
          parameters <- readParameters(id)
        } yield Some(Game(id, name, description, url, active, roles, parameters, externalId, minPlayers, maxPlayers))
    }

  def update(game: Game): IO[Unit] =
    session.transaction.use { _ =>
      for {
        _ <- session.execute(updateGameRow)(
          (game.name, game.description, game.url, game.active, game.externalId, game.minPlayers, game.maxPlayers, game.gameId)
        )
        _ <- replaceRoles(game.gameId, game.roles)
        _ <- replaceParameters(game.gameId, game.parameters)
      } yield ()
    }

  private def insertRole(gameId: GameId, role: GameRole): IO[GameRole] =
    session
      .unique(insertRoleStmt)((gameId, role.name, role.optional))
      .map(id => role.copy(gameRoleId = id, gameId = gameId))

  private def readRoles(gameId: GameId): IO[Seq[GameRole]] =
    session.execute(selectRoles)(gameId).map(_.map { case (id, name, optional) => GameRole(id, gameId, name, optional) })

  private def replaceRoles(gameId: GameId, roles: Seq[GameRole]): IO[Unit] =
    for {
      _ <- session.execute(deleteRoles)(gameId)
      _ <- roles.toList.traverse(insertRole(gameId, _))
    } yield ()

  // game_parameter.default_value has a composite FK to game_parameter_value(game_id,
  // game_parameter_id, value), so the parameter row must be inserted before its values
  // exist, and default_value can only be set once a matching value row is present.
  private def insertParameter(gameId: GameId, parameter: GameParameter[T]): IO[GameParameter[T]] =
    for {
      parameterId <- session.unique(insertParameterStmt)((gameId, parameter.name))
      values <- parameter.values.toList.traverse(v => insertParameterValue(gameId, parameterId, v))
      _ <- parameter.defaultValue match {
        case Some(v) => session.execute(setDefaultValueStmt)((v, gameId, parameterId)).void
        case None    => IO.unit
      }
    } yield parameter.copy(gameId = gameId, gameParameterId = parameterId, values = values)

  private def insertParameterValue(gameId: GameId, parameterId: GameParameterId, value: GameParameterValue[T]): IO[GameParameterValue[T]] =
    session
      .execute(insertParameterValueStmt)((gameId, parameterId, value.value))
      .as(value.copy(gameId = gameId, gameParameterId = parameterId))

  private def readParameters(gameId: GameId): IO[Seq[GameParameter[T]]] =
    session.execute(selectParameters)(gameId).flatMap(_.traverse { case (parameterId, name, defaultValue) =>
      readParameterValues(gameId, parameterId).map(values => GameParameter(gameId, parameterId, name, defaultValue, values))
    })

  private def readParameterValues(gameId: GameId, parameterId: GameParameterId): IO[Seq[GameParameterValue[T]]] =
    session
      .execute(selectParameterValues)((gameId, parameterId))
      .map(_.map(v => GameParameterValue(gameId, parameterId, v)))

  /** One row of the listing join: a game, optionally one of its roles, and optionally one of its
    * parameters together with one of that parameter's values.
    *
    * The role and parameter columns are nullable because the joins are outer — a game with no
    * roles still has to appear — and the ids are decoded as raw ints because a twiddle ending in
    * an opaque id does not reduce to a tuple (an opaque type cannot be shown to be disjoint from
    * Tuple outside the scope that defines it).
    */
  private case class GameListRow(
      gameId: GameId,
      name: String,
      description: String,
      url: String,
      active: Boolean,
      externalId: String,
      minPlayers: Int,
      maxPlayers: Int,
      roleId: Option[Int],
      roleName: Option[String],
      roleOptional: Option[Boolean],
      parameterId: Option[Int],
      parameterName: Option[String],
      parameterDefault: Option[T],
      parameterValue: Option[T]
  )

  // Roles and parameter values are independent one-to-many branches off game, so joining both in
  // one statement yields their cross product: a game with 2 roles and 3 parameter values comes
  // back as 6 rows. That is what `list` de-duplicates below. It is the right trade while these
  // collections stay small — which the schema encourages, since both describe a game's rules
  // rather than its activity — but it is the reason to revisit this if they ever grow.
  private val selectGameAggregate =
    sql"""SELECT g.game_id, g.name, g.description, g.url, g.active, g.external_id,
                 g.min_players, g.max_players,
                 r.game_role_id, r.name, r.optional,
                 p.game_parameter_id, p.name, p.default_value,
                 v.value
          FROM game g
          LEFT JOIN game_role r ON r.game_id = g.game_id
          LEFT JOIN game_parameter p ON p.game_id = g.game_id
          LEFT JOIN game_parameter_value v
                 ON v.game_id = p.game_id AND v.game_parameter_id = p.game_parameter_id
          WHERE (NOT $bool OR g.active)
          ORDER BY g.name, g.game_id"""
      .query(
        gameId *: text *: text *: text *: bool *: text *: int4 *: int4 *:
          int4.opt *: text.opt *: bool.opt *:
          int4.opt *: text.opt *: value.opt *: value.opt
      )

  /** All games with their roles and parameters, in a single query regardless of how many games
    * there are.
    *
    * @param activeOnly restrict to games flagged active
    */
  def list(activeOnly: Boolean): IO[List[Game]] =
    session.execute(selectGameAggregate)(activeOnly).map { rows =>
      val listRows = rows.map(GameListRow.apply.tupled)

      // groupBy loses ordering, so the ORDER BY is honoured by walking the ids in the order the
      // rows arrived rather than by iterating the resulting map.
      val byGame = listRows.groupBy(_.gameId)
      listRows.map(_.gameId).distinct.map { id =>
        val gameRows = byGame(id)
        val head = gameRows.head

        val roles = gameRows.flatMap { row =>
          for {
            roleId <- row.roleId
            name <- row.roleName
            optional <- row.roleOptional
          } yield GameRole(GameRoleId(roleId), id, name, optional)
        }.distinctBy(_.gameRoleId)

        val parameters = gameRows
          .flatMap(row => row.parameterId.zip(row.parameterName).map { case (pid, name) => (pid, name, row) })
          .groupBy(_._1)
          .toList
          .map { case (parameterId, parameterRows) =>
            val (_, name, first) = parameterRows.head
            GameParameter(
              id,
              GameParameterId(parameterId),
              name,
              first.parameterDefault,
              parameterRows.flatMap(_._3.parameterValue).distinct.map(GameParameterValue(id, GameParameterId(parameterId), _))
            )
          }
          .sortBy(_.gameParameterId.value)

        Game(
          id,
          head.name,
          head.description,
          head.url,
          head.active,
          roles,
          parameters,
          head.externalId,
          head.minPlayers,
          head.maxPlayers
        )
      }
    }

  private def replaceParameters(gameId: GameId, parameters: Seq[GameParameter[_]]): IO[Unit] =
    for {
      _ <- session.execute(clearDefaultValues)(gameId)
      _ <- session.execute(deleteParameterValues)(gameId)
      _ <- session.execute(deleteParameters)(gameId)
      _ <- parameters.toList.traverse(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
    } yield ()
}
