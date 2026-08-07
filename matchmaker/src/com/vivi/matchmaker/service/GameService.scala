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
      for {
        _ <- authorize(playerRepo, externalUserId)
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

  private def authorize(playerRepo: PlayerRepo, externalUserId: String): IO[Player] =
    playerRepo.readByExternalId(externalUserId).flatMap {
      case None                       => IO.raiseError(UnauthorizedError(s"no such user '$externalUserId'"))
      case Some(player) if !player.isAdmin => IO.raiseError(UnauthorizedError(s"user '$externalUserId' is not an admin"))
      case Some(player)               => IO.pure(player)
    }
}
