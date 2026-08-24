package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, PlayerRepo, TextCodec}

/** Creates or updates a Game, together with all of its roles, parameters, and parameter
  * values. Only an admin may do this.
  */
class GameService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

  /** Creates `game` if it has no id yet (gameId == GameId.unassigned), otherwise updates the
    * existing game with that id. Returns the persisted state, including any
    * database-generated role/parameter ids.
    *
    * @param externalUserId identifies the caller; must belong to an existing admin player
    */
  def createOrUpdate(externalUserId: String, game: Game): IO[Game] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val gameRepo = new GameRepo[T](session)
      // A game is its rows plus its roles, parameters and parameter values; the repo writes
      // them as separate statements, so the transaction that makes them one change lives here.
      session.transaction.use { _ =>
        for {
          _ <- authorize(playerRepo, externalUserId)
          // Trimmed before it is checked, so that "  attacker  " and "attacker" are refused as the
          // duplicate they are rather than stored as two roles that read identically.
          trimmed = normalize(game)
          _ <- validate(trimmed)
          result <-
            if (trimmed.gameId == GameId.unassigned) gameRepo.create(trimmed)
            else
              gameRepo.read(trimmed.gameId).flatMap {
                case None => IO.raiseError(NotFoundError(s"no game with id ${trimmed.gameId.value}"))
                case Some(_) =>
                  gameRepo.update(trimmed) *> gameRepo.read(trimmed.gameId).flatMap {
                    case Some(updated) => IO.pure(updated)
                    case None          => IO.raiseError(NotFoundError(s"no game with id ${trimmed.gameId.value}"))
                  }
              }
        } yield result
      }
    }

  /** Lists games for any registered caller. Unlike `createOrUpdate` this needs no admin rights —
    * the game catalogue is what every player browses — but the caller must still be a known
    * player.
    *
    * @param activeOnly hide games flagged inactive
    */
  def list(callerExternalId: String, activeOnly: Boolean = false): IO[List[Game]] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      for {
        _ <- playerRepo.readByExternalId(callerExternalId).flatMap {
          case Some(player) => IO.pure(player)
          case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }
        games <- new GameRepo[T](session).list(activeOnly)
      } yield games
    }

  /** Strips the whitespace around every role name, parameter name, parameter value and default.
    *
    * All four are things an admin types and something else matches on, so the spaces around them
    * are typing and not content: " X " and "X" name the same role, and storing both would show a
    * player two roles that look the same and let a game define two parameters it cannot tell
    * apart. For a value it is worse than cosmetic — `game_parameter.default_value` is a foreign
    * key to `game_parameter_value`, so a default of "3x3" against a value stored as "3x3 " is not
    * a default at all. Done here rather than in the form, because the form is not the only thing
    * that can post a game.
    */
  private def normalize(game: Game): Game =
    game.copy(
      roles = game.roles.map(role => role.copy(name = role.name.trim)),
      parameters = game.parameters.map { p =>
        // The same cast GameRepo makes: `parameters` is existential in its value type, and
        // TextCodec[String] is the only instance there is.
        val parameter = p.asInstanceOf[GameParameter[T]]
        parameter.copy(
          name = parameter.name.trim,
          defaultValue = parameter.defaultValue.map(trimValue),
          values = parameter.values.map(value => value.copy(value = trimValue(value.value)))
        )
      }
    )

  /* A parameter value is generic, so it is trimmed through the codec that decides what its text
   * form is — which for the only instance there is, TextCodec[String], is the string itself. */
  private def trimValue(value: T): T = codec.decode(codec.encode(value).trim)

  /** What the schema would refuse anyway, refused here first so that it is a 400 explaining
    * itself rather than a constraint violation surfacing as a 500.
    *
    * The one rule that is not the schema's: a game must define at least one role. Since V4 every
    * acceptance names a role, so a game with none is a game nothing can be offered or accepted
    * for — it would take challenges no further than the form that tried to create one.
    */
  private def validate(game: Game): IO[Unit] =
    for {
      _ <- IO.raiseWhen(game.roles.isEmpty)(
        ValidationError(s"game '${game.name}' defines no roles; every acceptance names one, so a game needs at least one")
      )
      _ <- IO.raiseWhen(game.roles.exists(_.name.trim.isEmpty))(
        ValidationError(s"game '${game.name}' has a role with no name")
      )
      _ <- IO.raiseWhen(game.roles.map(_.name).distinct.sizeIs != game.roles.size)(
        ValidationError(s"game '${game.name}' has two roles with the same name")
      )
      _ <- IO.raiseWhen(game.parameters.exists(_.name.trim.isEmpty))(
        ValidationError(s"game '${game.name}' has a parameter with no name")
      )
      _ <- IO.raiseWhen(game.parameters.map(_.name).distinct.sizeIs != game.parameters.size)(
        ValidationError(s"game '${game.name}' has two parameters with the same name")
      )
      _ <- game.parameters.toList.traverse_ { parameter =>
        val values = parameter.values.map(_.value)
        for {
          _ <- IO.raiseWhen(values.distinct.sizeIs != values.size)(
            ValidationError(s"parameter '${parameter.name}' lists the same value twice")
          )
          // game_parameter.default_value is a foreign key to game_parameter_value, so a default
          // that is not one of the parameter's values cannot be stored at all.
          _ <- parameter.defaultValue.traverse_ { default =>
            IO.raiseUnless(values.contains(default))(
              ValidationError(s"parameter '${parameter.name}' has default '$default', which is not one of its values")
            )
          }
        } yield ()
      }
    } yield ()

  private def authorize(playerRepo: PlayerRepo, externalUserId: String): IO[Player] =
    playerRepo.readByExternalId(externalUserId).flatMap {
      case None                       => IO.raiseError(UnauthorizedError(s"no such user '$externalUserId'"))
      case Some(player) if !player.isAdmin => IO.raiseError(UnauthorizedError(s"user '$externalUserId' is not an admin"))
      case Some(player)               => IO.pure(player)
    }
}
