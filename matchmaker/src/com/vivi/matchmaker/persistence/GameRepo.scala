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

  // Listing deliberately does not reuse readRoles/readParameters: those are per-game, so
  // calling them for each game would be a query per game (and per parameter). These four
  // statements fetch every game's rows in one pass each, and the aggregate is assembled below.
  private val selectAllGameRows: Query[Boolean, (GameId, String, String, String, Boolean, String, Int, Int)] =
    sql"""SELECT game_id, name, description, url, active, external_id, min_players, max_players
          FROM game
          WHERE (NOT $bool OR active)
          ORDER BY name"""
      .query(gameId *: text *: text *: text *: bool *: text *: int4 *: int4)

  private val selectAllRoles: Query[Boolean, (GameId, GameRoleId, String, Boolean)] =
    sql"""SELECT r.game_id, r.game_role_id, r.name, r.optional
          FROM game_role r JOIN game g ON g.game_id = r.game_id
          WHERE (NOT $bool OR g.active)"""
      .query(gameId *: gameRoleId *: text *: bool)

  private val selectAllParameters: Query[Boolean, (GameId, GameParameterId, String, Option[T])] =
    sql"""SELECT p.game_id, p.game_parameter_id, p.name, p.default_value
          FROM game_parameter p JOIN game g ON g.game_id = p.game_id
          WHERE (NOT $bool OR g.active)"""
      .query(gameId *: gameParameterId *: text *: value.opt)

  // The ids are decoded as raw int4 and wrapped below, and `value` is selected first, so that
  // the twiddle ends in a concrete type: a twiddle ending in the abstract T (or in an opaque id)
  // does not reduce to a tuple, because neither can be shown to be disjoint from Tuple here.
  private val selectAllParameterValues: Query[Boolean, (T, Int, Int)] =
    sql"""SELECT v.value, v.game_id, v.game_parameter_id
          FROM game_parameter_value v JOIN game g ON g.game_id = v.game_id
          WHERE (NOT $bool OR g.active)"""
      .query(value *: int4 *: int4)

  /** All games with their roles and parameters, in four queries regardless of how many games
    * there are.
    *
    * @param activeOnly restrict to games flagged active
    */
  def list(activeOnly: Boolean): IO[List[Game]] =
    for {
      gameRows <- session.execute(selectAllGameRows)(activeOnly)
      roleRows <- session.execute(selectAllRoles)(activeOnly)
      parameterRows <- session.execute(selectAllParameters)(activeOnly)
      valueRows <- session.execute(selectAllParameterValues)(activeOnly)
    } yield {
      val rolesByGame = roleRows.groupMap(_._1) { case (gameId, id, name, optional) => GameRole(id, gameId, name, optional) }
      val valuesByParameter = valueRows
        .map { case (v, gameId, parameterId) => GameParameterValue(GameId(gameId), GameParameterId(parameterId), v) }
        .groupBy(v => (v.gameId, v.gameParameterId))
      val parametersByGame = parameterRows.groupMap(_._1) { case (gameId, parameterId, name, defaultValue) =>
        GameParameter(gameId, parameterId, name, defaultValue, valuesByParameter.getOrElse((gameId, parameterId), Nil))
      }
      gameRows.map { case (id, name, description, url, active, externalId, minPlayers, maxPlayers) =>
        Game(
          id,
          name,
          description,
          url,
          active,
          rolesByGame.getOrElse(id, Nil),
          parametersByGame.getOrElse(id, Nil),
          externalId,
          minPlayers,
          maxPlayers
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
