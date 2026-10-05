package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.ending.MatchEndings
import com.vivi.matchmaker.model.{EloRating, GameId, Leaderboard, MatchId, ParticipantId, Player, PlayerId}
import com.vivi.matchmaker.persistence.{
    EloRatingRepo,
    GameAdminRepo,
    GameRepo,
    ParticipantRepo,
    PlayerRepo,
    ResultRepo,
    TextCodec
}

/** Players' Elo ratings in each game (V42): the leaderboard anybody may read, and the setting of one that a game's
  * admin may do. What moves them is a match that is not friendly completing, which is [[EloRatingService.rate]], called
  * from inside the transaction that completes it.
  */
class EloRatingService[T](sessionPool: SessionPool, endings: MatchEndings = MatchEndings.disabled)(using
    TextCodec[T]
) {

    /** A page of the game's leaderboard (V45), from `page` 0, best first, for any registered player: a rating is there
      * to be compared. A page is a range of places — page 0 everybody placed 1 to 20, page 1 21 to 40 — so a tie is
      * never split between two pages, and a page can hold more than 20 players, or none at all after a long one.
      */
    def leaderboard(gameId: GameId, page: Int, callerExternalId: String): IO[Leaderboard] =
        sessionPool.use { session =>
            for {
                // Capped so that the last place a page covers is still a number.
                _ <- IO.raiseWhen(page < 0 || page >= Int.MaxValue / Leaderboard.pageSize)(
                  ValidationError(s"there is no page $page of the leaderboard")
                )
                _ <- requireCaller(new PlayerRepo(session).readByExternalId(callerExternalId), callerExternalId)
                // Read plainly: nothing is written.
                board <- new EloRatingRepo(session)
                    .leaderboard(gameId, page * Leaderboard.pageSize + 1, (page + 1) * Leaderboard.pageSize)
                    .flatMap {
                        case Some(board) => IO.pure(board)
                        case None        => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
                    }
            } yield board
        }

    /** One player's rating in the game — their place, the rating it was worked out from, and the rating as it stands —
      * for any registered player, as the leaderboard is. Not found if there is no such game, or the player has no
      * rating in it.
      */
    def standing(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[EloRating] =
        sessionPool.use { session =>
            for {
                _ <- requireCaller(new PlayerRepo(session).readByExternalId(callerExternalId), callerExternalId)
                // Read plainly: nothing is written.
                _ <- new GameRepo[T](session).read(gameId).flatMap {
                    case Some(_) => IO.unit
                    case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
                }
                rating <- new EloRatingRepo(session).readRated(gameId, playerId).flatMap {
                    case Some(rating) => IO.pure(rating)
                    case None =>
                        IO.raiseError(NotFoundError(s"player ${playerId.value} has no rating in game ${gameId.value}"))
                }
            } yield rating
        }

    /** Sets `playerId`'s rating in the game outright, if the caller is an overall admin or an admin of that game — for
      * a player bringing a rating from elsewhere, or a correction. A player with no rating yet is given one.
      *
      * The rating is what the admin said, not an adjustment of what was there, so nothing read decides it and there is
      * no row to lock before writing it; the upsert takes its own. What does decide it is that the caller may, and that
      * the game and the player exist, which are held FOR SHARE until it lands, as in `GameAdminService.grant`.
      */
    def set(gameId: GameId, playerId: PlayerId, rating: Int, callerExternalId: String): IO[EloRating] =
        sessionPool
            .use { session =>
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
            // Placed by the listener, as a match's players are, once this has committed and released its session.
            .flatTap(_ => endings.ratingsChanged(gameId))

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
      * For the start's own transaction, the one that writes the seats. Every seat's number has to come from the same
      * moment: a match of some of these players completing alongside the start is either in all of them or in none. So
      * the ratings are read FOR SHARE, which keeps a completion from moving one until this has committed — and a player
      * with no rating yet is given a row at the starting rating first, since FOR SHARE locks only rows it finds. Read
      * as "none" with nothing held, a completion could make and move that player's rating, and another's, between this
      * reading the one and the other.
      *
      * All the rows made, then all of them locked, each in player order: the order a completion makes its rows and then
      * moves them in, so that the two queue rather than deadlock. Making a row counts as holding it — a second
      * transaction making the same one waits for the first to commit — so a start that made and locked one player's row
      * before making the next could hold a row a completion was waiting for while waiting for one the completion held.
      */
    def startingRatings(session: skunk.Session[IO], gameId: GameId, players: Seq[PlayerId]): IO[Map[PlayerId, Int]] = {
        val repo = new EloRatingRepo(session)
        repo.ensureRated(gameId, players) *> repo.readForShare(gameId, players)
    }

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
                    s"match ${matchId.value} of game ${gameId.value} is rated but has a player in two seats; " +
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

    /** Re-rates a completed match a game's admin has just reclassified: `friendly` is what it now is. Made friendly,
      * what it did to each rating is taken back; made not friendly, it is rated now, from the ratings its seats began
      * it at, as if it had been when it finished. Either way each result's `elo_delta` says what it now did.
      *
      * Not allowed to cascade. A player who has begun *and* finished another match of the game since this one finished
      * began that one at a rating that took in this one, and its result was worked out from it: changing this one would
      * mean re-rating that one, and everything after it. So that is refused. A player still playing such a match began
      * it at a rating that is about to be wrong, but nothing has been worked out from it yet — its seat's `elo_start`
      * is moved by the same change, and the match will be rated from that when it ends.
      *
      * For the caller's transaction, which holds the match's lock. The locks, in the order every other writer of them
      * takes them so that none of this can deadlock against a match completing:
      *
      *   1. The seats of those players' matches that began after this one and are still being played, FOR UPDATE. A
      *      completion writes its seats before its ratings, so one that has its seats is let finish first — and is then
      *      seen as finished below, and refused — and one that has not waits until this is done, and is rated from the
      *      moved `elo_start`.
      *   1. The ratings, by updating them, in player order.
      *   1. The same seats again, for any match that began while (2) waited — its start read the rating before it
      *      moved, and wrote the old one on its seats. NOWAIT, because a match holding its seats here may be completing
      *      and waiting on the ratings (2) holds: that is refused, to be tried again, rather than deadlocked.
      *
      * Whether a later match has finished is asked after all three, when nothing that would change the answer can
      * commit until this does, and refuses the whole of it if so.
      */
    def reclassify(session: skunk.Session[IO], gameId: GameId, matchId: MatchId, friendly: Boolean): IO[Unit] = {
        val participantRepo = new ParticipantRepo(session)
        val resultRepo = new ResultRepo(session)
        val ratingRepo = new EloRatingRepo(session)

        def refuseIfCascading(players: List[PlayerId]): IO[Unit] =
            participantRepo.laterCompleted(gameId, matchId, players).flatMap { later =>
                IO.raiseWhen(later)(
                  ConflictError(
                    "a player in this match has played another of this game's matches since it finished, so whether " +
                        "it was friendly can no longer change"
                  )
                )
            }

        for {
            seats <- participantRepo.eloSeatsForMatch(gameId, matchId)
            players = seats.map(_.playerId).distinct
            _ <- participantRepo.lockLaterSeats(gameId, matchId, players)
            // Early, so that the common refusal costs no writes; and again at the end, which is the one that counts.
            _ <- refuseIfCascading(players)
            results <- resultRepo.forMatch(gameId, matchId)
            deltas <-
                if (friendly) IO.pure(Map.empty[ParticipantId, Int])
                else
                    IO.raiseUnless(EloRating.playersOnce(seats.map(_.playerId)))(
                      ConflictError("a player holds more than one seat in this match, so it can only be friendly")
                    ) *> IO.pure(
                      EloRating.deltas(
                        seats.flatMap(seat =>
                            results
                                .find(_.participantId == seat.participantId)
                                .map(r => EloRating.Seat(seat.participantId, seat.playerId, seat.eloStart, r.rank))
                        )
                      )
                    )
            byPlayer = seats.map(seat => seat.participantId -> seat.playerId).toMap
            // What each player's rating moves by: the new delta less the old one, seat by seat.
            change = results
                .groupMapReduce(r => byPlayer(r.participantId))(r =>
                    deltas.getOrElse(r.participantId, 0) - r.eloDelta.getOrElse(0)
                )(_ + _)
            _ <- ratingRepo.ensureRated(gameId, deltas.keys.toList.map(byPlayer))
            _ <- results.sortBy(r => byPlayer(r.participantId).value).traverse_ { r =>
                val player = byPlayer(r.participantId)
                r.eloDelta.traverse_(ratingRepo.unplayed(gameId, player, _)) *>
                    deltas.get(r.participantId).traverse_(ratingRepo.played(gameId, player, _)) *>
                    resultRepo.setEloDelta(gameId, r.participantId, deltas.get(r.participantId))
            }
            later <- participantRepo
                .lockLaterSeats(gameId, matchId, players, noWait = true)
                .adaptError {
                    case e: skunk.exception.PostgresErrorException if e.code == "55P03" =>
                        ConflictError("another match of a player in this one is finishing right now; try again")
                }
            _ <- refuseIfCascading(players)
            _ <- later.filter(_.afterCompletion).traverse_ { seat =>
                change
                    .get(seat.playerId)
                    .filter(_ != 0)
                    .traverse_(participantRepo.adjustEloStart(gameId, seat.participantId, _))
            }
        } yield ()
    }
}
