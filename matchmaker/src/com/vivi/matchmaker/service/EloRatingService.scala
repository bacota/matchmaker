package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model.{EloRating, GameId, MatchId, ParticipantId, Player, PlayerId}
import com.vivi.matchmaker.persistence.{EloRatingRepo, GameAdminRepo, GameRepo, ParticipantRepo, PlayerRepo, TextCodec}

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

    /** What each of a match's players is rated as it begins (V43) — the starting rating for a player who has none yet —
      * for the start to write on their seats. For every match, friendly or not: it is a fact about the seat either way.
      *
      * For the start's own transaction, the one that writes the seats. The ratings are read FOR SHARE, which a
      * contended table read on the way to a write is owed: a match of the same player completing at the same moment
      * either lands first and is in the number recorded, or waits until this start has committed and is not.
      */
    def startingRatings(session: skunk.Session[IO], gameId: GameId, players: Seq[PlayerId]): IO[Map[PlayerId, Int]] =
        new EloRatingRepo(session)
            .readForShare(gameId, players)
            .map(rated => players.map(player => player -> rated.getOrElse(player, EloRating.initial)).toMap)

    /** Rates a match that is not friendly and has just completed: works out each seat's delta from the ratings its
      * seats began it at, moves each player's rating by it, and answers with the deltas by seat, for the caller to
      * write on the result rows it is about to insert (V43). `ranks` is where each seat with a result finished; a seat
      * with none has nothing to be rated by, and has no delta.
      *
      * For the caller's transaction, the one that records the results: the ratings move exactly when the results are
      * recorded, and a repeated callback that finds them recorded does not call this again. The caller decides that the
      * match is not friendly, and holds the match's lock, which keeps the two from disagreeing — and keeps its seats,
      * which only a holder of that lock writes, as they were read here.
      *
      * The deltas come from `elo_start`, not from the ratings as they stand now: another of a player's matches
      * finishing while this one was played does not change what this one was played at. The ratings are locked by the
      * update that moves them, which adds to them, so two matches finishing at once each add their own delta.
      */
    def rate(
        session: skunk.Session[IO],
        gameId: GameId,
        matchId: MatchId,
        ranks: Map[ParticipantId, Int]
    ): IO[Map[ParticipantId, Int]] = {
        val ratingRepo = new EloRatingRepo(session)
        new ParticipantRepo(session).eloSeatsForMatch(gameId, matchId).flatMap { rows =>
            val ranked = rows.filter(row => ranks.contains(row.participantId))
            /* Checked over every seat, ranked or not, before anything is touched. Accepting, starting and
             * `setFriendly` all refuse a match that is not friendly and has a player in two seats, so
             * this is the last word rather than the only one: a match like that is left unrated, and
             * said so, rather than refused -- its results are still its results, and failing the
             * callback that brought them would leave it unfinished as well. */
            if (!EloRating.playersOnce(rows.map(_.playerId)))
                IO(
                  System.err.println(
                    s"match ${matchId.value} of game ${gameId.value} is not friendly but has a player in two seats; " +
                        "it is not rated"
                  )
                ).as(Map.empty)
            else {
                val deltas = EloRating.deltas(
                  ranked.map(row =>
                      EloRating.Seat(row.participantId, row.playerId, row.eloStart, ranks(row.participantId))
                  )
                )
                for {
                    _ <- ratingRepo
                        .ensureRated(gameId, ranked.filter(row => deltas.contains(row.participantId)).map(_.playerId))
                    // In player order, as `ensureRated` went, which is the order the row locks are taken in.
                    // One seat per player, so a seat's delta is its player's.
                    _ <- ranked.sortBy(_.playerId.value).traverse_ { row =>
                        deltas.get(row.participantId).traverse_(ratingRepo.played(gameId, row.playerId, _))
                    }
                } yield deltas
            }
        }
    }
}
