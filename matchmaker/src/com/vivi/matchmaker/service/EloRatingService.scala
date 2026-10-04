package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model.{EloRating, GameId, Player, PlayerId}
import com.vivi.matchmaker.persistence.{EloRatingRepo, GameAdminRepo, GameRepo, PlayerRepo, TextCodec}

/** Players' Elo ratings in each game (V42): the list anybody may read, and the setting of one that a game's admin may
  * do. What moves them is a match that is not friendly completing, which is [[EloRatingService.rate]], called from
  * inside the transaction that completes it.
  */
class EloRatingService[T](sessionPool: SessionPool)(using TextCodec[T]) {

    /** The game's rated players, highest first, for any registered player: a rating is there to be compared. */
    def list(gameId: GameId, callerExternalId: String): IO[List[EloRating]] =
        sessionPool.use { session =>
            for {
                _ <- requireCaller(new PlayerRepo(session).readByExternalId(callerExternalId), callerExternalId)
                // Read plainly, and the game's existence in the same query: nothing is written.
                ratings <- new EloRatingRepo(session).listForGame(gameId).flatMap {
                    case Some(ratings) => IO.pure(ratings)
                    case None          => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
                }
            } yield ratings
        }

    /** Sets `playerId`'s rating in the game outright, if the caller is an overall admin or an admin of that game — for
      * a player bringing a rating from elsewhere, or a correction. A player with no rating yet is given one.
      *
      * The rating is what the admin said, not an adjustment of what was there, so nothing read decides it and there is
      * no row to lock before writing it; the upsert takes its own. What does decide it is that the caller may, and that
      * the game and the player exist, which are held FOR SHARE until it lands, as in `GameAdminService.grant`.
      */
    def set(gameId: GameId, playerId: PlayerId, rating: Int, callerExternalId: String): IO[EloRating] =
        sessionPool.use { session =>
            val playerRepo = new PlayerRepo(session)
            val ratingRepo = new EloRatingRepo(session)
            session.transaction.use { _ =>
                for {
                    _ <- IO.raiseUnless(rating >= EloRating.minimum && rating <= EloRating.maximum)(
                      ValidationError(
                        s"an Elo rating is between ${EloRating.minimum} and ${EloRating.maximum}, not $rating"
                      )
                    )
                    caller <- requireCaller(playerRepo.readByExternalIdForShare(callerExternalId), callerExternalId)
                    _ <- new GameRepo[T](session).lockForShare(gameId).flatMap {
                        case Some(_) => IO.unit
                        case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
                    }
                    allowed <-
                        if (caller.isAdmin) IO.pure(true)
                        else new GameAdminRepo(session).isAdminForShare(caller.playerId, gameId)
                    _ <- IO.raiseUnless(allowed)(
                      UnauthorizedError("only an admin of this game may set a player's Elo rating in it")
                    )
                    _ <- playerRepo.readForShare(playerId).flatMap {
                        case Some(_) => IO.unit
                        case None    => IO.raiseError(NotFoundError(s"no player with id ${playerId.value}"))
                    }
                    _ <- ratingRepo.set(gameId, playerId, rating, caller.playerId)
                    saved <- ratingRepo.read(gameId, playerId).map(_.get)
                } yield saved
            }
        }

    private def requireCaller(read: IO[Option[Player]], callerExternalId: String): IO[Player] =
        read.flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }
}

object EloRatingService {

    /** Moves the ratings of everybody seated in a match that has just completed, by where each seat finished.
      *
      * For the caller's transaction, the one that completes the match: a rating moves exactly when the match it is for
      * becomes completed, and a retried completion that finds the match already so does not call this again. The caller
      * decides whether the match is rated at all — that it is not friendly — and holds the match's lock, which is what
      * keeps the two from disagreeing.
      *
      * Each player's rating is read FOR UPDATE and the new one is worked out from what was read under the lock: two of
      * a player's matches finishing at once both move it, one after the other, rather than the second overwriting the
      * first. A seat with no result has no rank to be rated by and is left out.
      */
    def rate(session: skunk.Session[IO], gameId: GameId, seats: Seq[EloRating.Seat]): IO[Unit] =
        if (seats.map(_.player).distinct.size < 2) IO.unit
        else {
            val repo = new EloRatingRepo(session)
            for {
                before <- repo.lockForPlay(gameId, seats.map(_.player))
                after = EloRating.adjusted(seats, before)
                _ <- after.toList.sortBy(_._1.value).traverse_((player, rating) => repo.played(gameId, player, rating))
            } yield ()
        }
}
