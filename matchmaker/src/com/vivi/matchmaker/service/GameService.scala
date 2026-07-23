package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, PlayerRepo, TextCodec}

/** Creates or updates a Game, together with all of its roles, parameters, and parameter
  * values. Only an admin may do this.
  */
class GameService[T](config: DbConfig)(using codec: TextCodec[T]) {

  /** Creates `game` if it has no id yet (gameId == GameId.unassigned), otherwise updates the
    * existing game with that id. Returns the persisted state, including any
    * database-generated role/parameter ids.
    *
    * @param externalUserId identifies the caller; must belong to an existing admin player
    */
  def createOrUpdate(externalUserId: String, game: Game): IO[Game] =
    DbSession.resource(config).use { session =>
      val playerRepo = new PlayerRepo(session)
      val gameRepo = new GameRepo[T](session)
      for {
        _ <- authorize(playerRepo, externalUserId)
        result <-
          if (game.gameId == GameId.unassigned) gameRepo.create(game)
          else
            gameRepo.read(game.gameId).flatMap {
              case None    => IO.raiseError(NotFoundError(s"no game with id ${game.gameId.value}"))
              case Some(_) => gameRepo.update(game) *> gameRepo.read(game.gameId).map(_.get)
            }
      } yield result
    }

  private def authorize(playerRepo: PlayerRepo, externalUserId: String): IO[Player] =
    playerRepo.readByExternalId(externalUserId).flatMap {
      case None                       => IO.raiseError(UnauthorizedError(s"no such user '$externalUserId'"))
      case Some(player) if !player.isAdmin => IO.raiseError(UnauthorizedError(s"user '$externalUserId' is not an admin"))
      case Some(player)               => IO.pure(player)
    }
}
