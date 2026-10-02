package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Game, its roles, its parameters, and its parameter values are always read, written, and updated together, so this
  * repo persists the whole aggregate in one call.
  */
class GameRepo[T](session: Session[IO])(using codec: TextCodec[T]) {
    private val gameId = SkunkIdCodecs.gameId
    private val gameRoleId = SkunkIdCodecs.gameRoleId
    private val gameParameterId = SkunkIdCodecs.gameParameterId
    private val gameType = SkunkCodecs.gameType
    private val timeoutAction = SkunkCodecs.timeoutAction
    private val value: Codec[T] = SkunkCodecs.plainText[T]

    private val insertGameRow: Query[
      (GameType, String, String, String, String, Option[String], Boolean, String, TimeoutAction),
      GameId
    ] =
        sql"""INSERT INTO game (game_type, name, display_name, description, url, character_url, active, external_id,
                                timeout_action)
          VALUES ($gameType, $text, $text, $text, $text, ${text.opt}, $bool, $text, $timeoutAction)
          RETURNING game_id""".query(gameId)

    private val updateGameRow: Command[
      (GameType, String, String, String, String, Option[String], Boolean, String, TimeoutAction, GameId)
    ] =
        sql"""UPDATE game SET game_type = $gameType, name = $text, display_name = $text, description = $text, url = $text,
          character_url = ${text.opt}, active = $bool,
          external_id = $text, timeout_action = $timeoutAction
          WHERE game_id = $gameId""".command

    private val selectGameRow: Query[
      GameId,
      (GameType, String, String, String, String, Option[String], Boolean, String, TimeoutAction, Boolean)
    ] =
        // Whether a key is stored, never the key: no read of a game carries it (V34).
        sql"""SELECT game_type, name, display_name, description, url, character_url, active, external_id, timeout_action,
                 EXISTS (SELECT 1 FROM game_api_key k WHERE k.game_id = game.game_id)
          FROM game
          WHERE game_id = $gameId"""
            .query(gameType *: text *: text *: text *: text *: text.opt *: bool *: text *: timeoutAction *: bool)

    /* Confirms a game exists and holds it that way for the rest of the transaction.
     *
     * FOR SHARE rather than FOR UPDATE: the caller is about to insert a row that references this
     * game, not modify the game. FOR SHARE blocks a concurrent delete or update of it, while still
     * letting other transactions take the same lock — so two players creating characters in the
     * same game do not queue behind each other. */
    private val lockGameRow: Query[GameId, GameId] =
        sql"SELECT game_id FROM game WHERE game_id = $gameId FOR SHARE".query(gameId)

    /* The games an engine's identity names, read plainly, and held as `lockGameRow` holds one. */
    private val selectGamesByExternalId: Query[String, GameId] =
        sql"SELECT game_id FROM game WHERE external_id = $text ORDER BY game_id".query(gameId)

    private val lockGamesByExternalId: Query[String, GameId] =
        sql"SELECT game_id FROM game WHERE external_id = $text ORDER BY game_id FOR SHARE".query(gameId)

    /* And the exclusive form, for the caller that is about to rewrite the game itself. A game is
     * its row plus its roles, parameters and values, and an edit reads all of that to decide what
     * to write -- so two admins saving the same game at once must queue here rather than both
     * diffing against the state before either of them wrote. */
    private val lockGameRowForUpdate: Query[GameId, GameId] =
        sql"SELECT game_id FROM game WHERE game_id = $gameId FOR UPDATE".query(gameId)

    private val insertRoleStmt: Query[(GameId, String, Boolean, String), GameRoleId] =
        sql"""INSERT INTO game_role (game_id, name, optional, display_name) VALUES ($gameId, $text, $bool, $text)
          RETURNING game_role_id""".query(gameRoleId)

    private val selectRoles: Query[GameId, (GameRoleId, String, Boolean, String)] =
        sql"SELECT game_role_id, name, optional, display_name FROM game_role WHERE game_id = $gameId"
            .query(gameRoleId *: text *: bool *: text)

    private val updateRoleStmt: Command[(String, Boolean, String, GameId, GameRoleId)] =
        sql"""UPDATE game_role SET name = $text, optional = $bool, display_name = $text
          WHERE game_id = $gameId AND game_role_id = $gameRoleId""".command

    private val insertParameterStmt: Query[(GameId, String, String), GameParameterId] =
        sql"""INSERT INTO game_parameter (game_id, name, display_name) VALUES ($gameId, $text, $text)
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

    private val selectParameters: Query[GameId, (GameParameterId, String, Option[T], String)] =
        sql"""SELECT game_parameter_id, name, default_value, display_name
          FROM game_parameter WHERE game_id = $gameId"""
            .query(gameParameterId *: text *: value.opt *: text)

    private val selectParameterValues: Query[(GameId, GameParameterId), T] =
        sql"SELECT value FROM game_parameter_value WHERE game_id = $gameId AND game_parameter_id = $gameParameterId"
            .query(value)

    def create(game: Game): IO[Game] =
        for {
            gameId <- session.unique(insertGameRow)(
              (
                game.gameType,
                game.name,
                game.displayName,
                game.description,
                game.url,
                game.characterUrl,
                game.active,
                game.externalId,
                game.timeoutAction
              )
            )
            roles <- game.roles.toList.traverse(insertRole(gameId, _))
            parameters <- game.parameters.toList.traverse(p =>
                insertParameter(gameId, p.asInstanceOf[GameParameter[T]])
            )
        } yield game.copy(gameId = gameId, roles = roles, parameters = parameters)

    /** Whether the game exists, locking it against modification until the transaction ends. A full `read` would fetch
      * roles and parameters that a caller checking existence never looks at.
      */
    def lockForShare(id: GameId): IO[Option[GameId]] = session.option(lockGameRow)(id)

    /** The ids of every game whose `external_id` is this, each locked as [[lockForShare]] locks one.
      *
      * For a call made on behalf of a game rather than a player, where the caller's identity is the game's
      * `external_id` and the game is not otherwise named — an engine reporting a character it made. A list, because
      * nothing in the schema makes the column unique; one game is what a caller should find.
      */
    def lockForShareByExternalId(externalId: String): IO[List[GameId]] =
        session.execute(lockGamesByExternalId)(externalId)

    /** As [[lockForShareByExternalId]], for a read that writes nothing and so needs no lock. */
    def readIdsByExternalId(externalId: String): IO[List[GameId]] =
        session.execute(selectGamesByExternalId)(externalId)

    /** As [[lockForShare]], but exclusively: for a caller that is about to modify the game, and whose decision about
      * what to write comes from reading it. Nothing else may read it for modification until the transaction ends.
      */
    def lockForUpdate(id: GameId): IO[Option[GameId]] = session.option(lockGameRowForUpdate)(id)

    def read(id: GameId): IO[Option[Game]] =
        session.option(selectGameRow)(id).flatMap {
            case None => IO.pure(None)
            case Some(
                  (
                    gameType,
                    name,
                    displayName,
                    description,
                    url,
                    characterUrl,
                    active,
                    externalId,
                    timeoutAction,
                    hasKey
                  )
                ) =>
                for {
                    roles <- readRoles(id)
                    parameters <- readParameters(id)
                } yield Some(
                  Game(
                    id,
                    gameType,
                    name,
                    displayName,
                    description,
                    url,
                    active,
                    roles,
                    parameters,
                    externalId,
                    timeoutAction,
                    characterUrl,
                    hasKey
                  )
                )
        }

    def update(game: Game): IO[Unit] =
        for {
            _ <- session.execute(updateGameRow)(
              (
                game.gameType,
                game.name,
                game.displayName,
                game.description,
                game.url,
                game.characterUrl,
                game.active,
                game.externalId,
                game.timeoutAction,
                game.gameId
              )
            )
            _ <- upsertRoles(game.gameId, game.roles)
            _ <- replaceParameters(game.gameId, game.parameters)
        } yield ()

    private def insertRole(gameId: GameId, role: GameRole): IO[GameRole] =
        session
            .unique(insertRoleStmt)((gameId, role.name, role.optional, role.displayName))
            .map(id => role.copy(gameRoleId = id, gameId = gameId))

    private def readRoles(gameId: GameId): IO[Seq[GameRole]] =
        session
            .execute(selectRoles)(gameId)
            .map(_.map { case (id, name, optional, displayName) => GameRole(id, gameId, name, optional, displayName) })

    /** Writes a game's roles without ever deleting one.
      *
      * A role that carries a real id already exists and is updated in place; one carrying [[GameRoleId.unassigned]] is
      * new and is inserted. Nothing is removed, and that is the point: `acceptance` and `participant` both point at
      * `game_role`, so a deleted role is either a foreign-key violation or, worse, a match whose players have no seats.
      * Refusing the deletion is `GameService`'s job — this simply has no way to do one.
      */
    private def upsertRoles(gameId: GameId, roles: Seq[GameRole]): IO[Unit] =
        roles.toList.traverse_ { role =>
            if (role.gameRoleId == GameRoleId.unassigned) insertRole(gameId, role).void
            else
                session
                    .execute(updateRoleStmt)((role.name, role.optional, role.displayName, gameId, role.gameRoleId))
                    .void
        }

    // game_parameter.default_value has a composite FK to game_parameter_value(game_id,
    // game_parameter_id, value), so the parameter row must be inserted before its values
    // exist, and default_value can only be set once a matching value row is present.
    private def insertParameter(gameId: GameId, parameter: GameParameter[T]): IO[GameParameter[T]] =
        for {
            parameterId <- session.unique(insertParameterStmt)((gameId, parameter.name, parameter.displayName))
            values <- parameter.values.toList.traverse(v => insertParameterValue(gameId, parameterId, v))
            _ <- parameter.defaultValue match {
                case Some(v) => session.execute(setDefaultValueStmt)((v, gameId, parameterId)).void
                case None    => IO.unit
            }
        } yield parameter.copy(gameId = gameId, gameParameterId = parameterId, values = values)

    private def insertParameterValue(
        gameId: GameId,
        parameterId: GameParameterId,
        value: GameParameterValue[T]
    ): IO[GameParameterValue[T]] =
        session
            .execute(insertParameterValueStmt)((gameId, parameterId, value.value))
            .as(value.copy(gameId = gameId, gameParameterId = parameterId))

    private def readParameters(gameId: GameId): IO[Seq[GameParameter[T]]] =
        session
            .execute(selectParameters)(gameId)
            .flatMap(_.traverse { case (parameterId, name, defaultValue, displayName) =>
                readParameterValues(gameId, parameterId)
                    .map(values => GameParameter(gameId, parameterId, name, defaultValue, values, displayName))
            })

    private def readParameterValues(gameId: GameId, parameterId: GameParameterId): IO[Seq[GameParameterValue[T]]] =
        session
            .execute(selectParameterValues)((gameId, parameterId))
            .map(_.map(v => GameParameterValue(gameId, parameterId, v)))

    /** One row of the listing join: a game, optionally one of its roles, and optionally one of its parameters together
      * with one of that parameter's values.
      *
      * The role and parameter columns are nullable because the joins are outer — a game with no roles still has to
      * appear — and the ids are decoded as raw ints because a twiddle ending in an opaque id does not reduce to a tuple
      * (an opaque type cannot be shown to be disjoint from Tuple outside the scope that defines it).
      */
    private case class GameListRow(
        gameId: GameId,
        gameType: GameType,
        name: String,
        displayName: String,
        description: String,
        url: String,
        characterUrl: Option[String],
        active: Boolean,
        externalId: String,
        timeoutAction: TimeoutAction,
        hasApiKey: Boolean,
        roleId: Option[Int],
        roleName: Option[String],
        roleOptional: Option[Boolean],
        roleDisplayName: Option[String],
        parameterId: Option[Int],
        parameterName: Option[String],
        parameterDefault: Option[T],
        parameterDisplayName: Option[String],
        parameterValue: Option[T]
    )

    // Roles and parameter values are independent one-to-many branches off game, so joining both in
    // one statement yields their cross product: a game with 2 roles and 3 parameter values comes
    // back as 6 rows. That is what `list` de-duplicates below. It is the right trade while these
    // collections stay small — which the schema encourages, since both describe a game's rules
    // rather than its activity — but it is the reason to revisit this if they ever grow.
    private val selectGameAggregate =
        sql"""SELECT g.game_id, g.game_type, g.name, g.display_name, g.description, g.url, g.character_url, g.active, g.external_id,
                 g.timeout_action, EXISTS (SELECT 1 FROM game_api_key k WHERE k.game_id = g.game_id),
                 r.game_role_id, r.name, r.optional, r.display_name,
                 p.game_parameter_id, p.name, p.default_value, p.display_name,
                 v.value
          FROM game g
          LEFT JOIN game_role r ON r.game_id = g.game_id
          LEFT JOIN game_parameter p ON p.game_id = g.game_id
          LEFT JOIN game_parameter_value v
                 ON v.game_id = p.game_id AND v.game_parameter_id = p.game_parameter_id
          WHERE (NOT $bool OR g.active)
          ORDER BY g.game_id"""
            .query(
              gameId *: gameType *: text *: text *: text *: text *: text.opt *: bool *: text *: timeoutAction *: bool *:
                  int4.opt *: text.opt *: bool.opt *: text.opt *:
                  int4.opt *: text.opt *: value.opt *: text.opt *: value.opt
            )

    /** All games with their roles and parameters, sorted by the name players see, in a single query regardless of how
      * many games there are.
      *
      * The sort is applied here rather than in the query for two reasons. The database would be sorting the join's
      * cross product, where this sorts one entry per game, after the rows have been collapsed. And `ORDER BY name`
      * would use the server's collation, which need not match between a local Postgres and Aurora, whereas this
      * ordering is the same wherever it runs. The query still orders by game_id, which only has to be stable, not
      * meaningful.
      *
      * @param activeOnly
      *   restrict to games flagged active
      */
    def list(activeOnly: Boolean): IO[List[Game]] =
        session.execute(selectGameAggregate)(activeOnly).map { rows =>
            val listRows = rows.map(GameListRow.apply.tupled)

            listRows
                .groupBy(_.gameId)
                .toList
                .map { case (id, gameRows) =>
                    val head = gameRows.head

                    val roles = gameRows
                        .flatMap { row =>
                            for {
                                roleId <- row.roleId
                                name <- row.roleName
                                optional <- row.roleOptional
                                displayName <- row.roleDisplayName
                            } yield GameRole(GameRoleId(roleId), id, name, optional, displayName)
                        }
                        .distinctBy(_.gameRoleId)

                    val parameters = gameRows
                        .flatMap(row =>
                            row.parameterId.zip(row.parameterName).map { case (pid, name) => (pid, name, row) }
                        )
                        .groupBy(_._1)
                        .toList
                        .map { case (parameterId, parameterRows) =>
                            val (_, name, first) = parameterRows.head
                            GameParameter(
                              id,
                              GameParameterId(parameterId),
                              name,
                              first.parameterDefault,
                              parameterRows
                                  .flatMap(_._3.parameterValue)
                                  .distinct
                                  .map(GameParameterValue(id, GameParameterId(parameterId), _)),
                              // NOT NULL in the table; only the outer join makes it optional here.
                              first.parameterDisplayName.getOrElse(name)
                            )
                        }
                        .sortBy(_.gameParameterId.value)

                    Game(
                      id,
                      head.gameType,
                      head.name,
                      head.displayName,
                      head.description,
                      head.url,
                      head.active,
                      roles,
                      parameters,
                      head.externalId,
                      head.timeoutAction,
                      head.characterUrl,
                      head.hasApiKey
                    )
                }
                // game_id breaks ties, so games sharing a name still come back in a stable order.
                .sortBy(game => (game.displayName, game.gameId.value))
        }

    /** Writes a game's parameters by replacing them wholesale, which — unlike [[upsertRoles]] — may delete. Nothing
      * outside `game_parameter_value` points at a parameter, so a parameter that is gone is simply gone, and an admin
      * removing one is a thing they are allowed to do.
      */
    private def replaceParameters(gameId: GameId, parameters: Seq[GameParameter[_]]): IO[Unit] =
        for {
            _ <- session.execute(clearDefaultValues)(gameId)
            _ <- session.execute(deleteParameterValues)(gameId)
            _ <- session.execute(deleteParameters)(gameId)
            _ <- parameters.toList.traverse(p => insertParameter(gameId, p.asInstanceOf[GameParameter[T]]))
        } yield ()
}
