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
          _ <- validate(game)
          result <-
            if (game.gameId == GameId.unassigned) gameRepo.create(game)
            else
              gameRepo.read(game.gameId).flatMap {
                case None => IO.raiseError(NotFoundError(s"no game with id ${game.gameId.value}"))
                case Some(_) =>
                  gameRepo.update(game) *> gameRepo.read(game.gameId).flatMap {
                    case Some(updated) => IO.pure(updated)
                    case None          => IO.raiseError(NotFoundError(s"no game with id ${game.gameId.value}"))
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
