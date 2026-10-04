package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model.{EloRating, GameId, MatchId, Participant, ParticipantId, Player, PlayerId}
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

    /** Records on each of a match's seats what its player was rated when it began (V43) — the starting rating for a
      * player who has none yet. For every match, friendly or not: it is a fact about the seat either way.
      *
      * For the start's own transaction, the one that writes the seats. The ratings are read FOR SHARE, which a
      * contended table read on the way to a write is owed: a match of the same player completing at the same moment
      * either lands first and is in the number recorded, or waits until this start has committed and is not.
      */
    def recordStart(session: skunk.Session[IO], gameId: GameId, seats: Seq[Participant]): IO[Unit] = {
        val participantRepo = new ParticipantRepo(session)
        new EloRatingRepo(session).readForShare(gameId, seats.map(_.playerId)).flatMap { ratings =>
            seats.traverse_(seat =>
                participantRepo.setEloStart(
                  gameId,
                  seat.participantId,
                  ratings.getOrElse(seat.playerId, EloRating.initial)
                )
            )
        }
    }

    /** Rates a match that is not friendly and has just completed: each seat's delta, from the ratings its seats began
      * it at, recorded on the seat (V43), and each player's rating moved by their seats' deltas. `ranks` is where each
      * seat with a result finished; a seat with none has nothing to be rated by, and is left without a delta.
      *
      * For the caller's transaction, the one that completes the match: the ratings move exactly when the match becomes
      * completed, and a retried completion that finds it already so does not call this again. The caller decides that
      * the match is not friendly, and holds the match's lock, which keeps the two from disagreeing — and keeps its
      * seats, which only a holder of that lock writes, as they were read here.
      *
      * The deltas come from `elo_start`, not from the ratings as they stand now: another of a player's matches
      * finishing while this one was played does not change what this one was played at. A seat made before V43 has no
      * start, and takes its player's rating now, which is the nearest thing there is. The ratings themselves are locked
      * FOR UPDATE and moved by adding to them, so two matches finishing at once each add their own delta.
      */
    def rate(session: skunk.Session[IO], gameId: GameId, matchId: MatchId, ranks: Map[ParticipantId, Int]): IO[Unit] = {
        val participantRepo = new ParticipantRepo(session)
        val ratingRepo = new EloRatingRepo(session)
        participantRepo.eloSeatsForMatch(gameId, matchId).flatMap { rows =>
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
                )
            else if (ranked.size < 2) IO.unit
            else
                for {
                    current <- ratingRepo.lockForPlay(gameId, ranked.map(_.playerId))
                    deltas = EloRating.deltas(ranked.map { row =>
                        EloRating.Seat(
                          row.participantId,
                          row.playerId,
                          row.eloStart.getOrElse(current(row.playerId)),
                          ranks(row.participantId)
                        )
                    })
                    // In player order, as the locks were taken. One seat per player, so a seat's delta
                    // is its player's.
                    _ <- ranked.sortBy(_.playerId.value).traverse_ { row =>
                        deltas.get(row.participantId).traverse_ { delta =>
                            participantRepo.setEloDelta(gameId, row.participantId, delta) *>
                                ratingRepo.played(gameId, row.playerId, delta)
                        }
                    }
                } yield ()
        }
    }
}
