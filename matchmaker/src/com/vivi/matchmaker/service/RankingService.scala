package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all._
import skunk.exception.PostgresErrorException
import com.vivi.matchmaker.model.{GameId, PlayerId}
import com.vivi.matchmaker.persistence.EloRatingRepo
import com.vivi.matchmaker.persistence.EloRatingRepo.Placed

/** Keeps each game's leaderboard (V45) in order: run by the listener that settles the ends of matches, after a match's
  * completion has moved its players' ratings, and after an admin has set one.
  *
  * Places are one more than how many are rated higher, so ties share a place and the places after them are skipped (1,
  * 2, 2, 4).
  *
  * It places whoever is waiting to be placed — not only the players of the match that ended, so that one whose placing
  * failed, or whose message was lost, is placed by the next run for their game. Each player in a transaction of their
  * own, which holds the game's ranking lock and every row it writes; a lock it cannot have at once fails that
  * transaction, which is tried again after a random pause. It never waits on a lock, so it can never be in a deadlock,
  * and a completion that waits on a row it holds waits only for one player's placing.
  */
class RankingService(
    sessionPool: SessionPool,
    /** How many times a player's placing is tried before it is left for the next run. */
    attempts: Int = 8,
    /** The pause before try `n` (from 1), after a lock could not be had. */
    pause: Int => IO[Unit] = RankingService.randomPause
) {

    /** Places everybody in the game who is waiting to be placed: Settled once there is nobody, Owed if somebody's
      * placing could not get its locks — and the run that settles the next message for the game places them.
      */
    def rank(gameId: GameId): IO[Settlement] = {
        def pass: IO[Settlement] =
            sessionPool.use(session => new EloRatingRepo(session).unplaced(gameId, RankingService.batch)).flatMap {
                players =>
                    players.traverse(player => placeRetrying(gameId, player).attempt.map(player -> _)).flatMap {
                        results =>
                            results.collect { case (player, Left(e)) => player -> e } match {
                                case Nil if players.size == RankingService.batch => pass
                                case Nil                                         => IO.pure(Settlement.Settled)
                                case failed =>
                                    IO.blocking(
                                      failed.foreach((player, e) =>
                                          System.err.println(
                                            s"placing player ${player.value} in game ${gameId.value} failed: $e"
                                          )
                                      )
                                    ).as(Settlement.Owed(s"${failed.size} of the game's players could not be placed"))
                            }
                    }
            }
        pass
    }

    private def placeRetrying(gameId: GameId, player: PlayerId): IO[Unit] = {
        def attempt(n: Int): IO[Unit] =
            placeOnce(gameId, player).handleErrorWith {
                case e if RankingService.lockFailure(e) && n < attempts => pause(n) *> attempt(n + 1)
                case e                                                  => IO.raiseError(e)
            }
        attempt(1)
    }

    private def placeOnce(gameId: GameId, player: PlayerId): IO[Unit] =
        sessionPool.use { session =>
            val repo = new EloRatingRepo(session)
            session.transaction.use { _ =>
                repo.tryLockRanking(gameId).flatMap {
                    case false => IO.raiseError(RankingService.Busy)
                    case true  => RankingService.place(repo, gameId, player)
                }
            }
        }
}

object RankingService {

    /** How many waiting players one pass reads. */
    val batch: Int = 500

    /** Another placing of the same game holds its ranking lock. */
    case object Busy extends Exception("another placing of this game is running")

    /** Whether `e` is a lock that could not be had — and so worth trying again — rather than a failure. */
    def lockFailure(e: Throwable): Boolean = e match {
        case Busy                                                                => true
        case p: PostgresErrorException if p.code == "55P03" || p.code == "40P01" => true
        case _                                                                   => false
    }

    /** Somewhere up to 50ms doubled for each try already made, at most two seconds: random, so that two placings that
      * collided once are unlikely to collide again.
      */
    def randomPause(n: Int): IO[Unit] =
        Random
            .scalaUtilRandom[IO]
            .flatMap(_.betweenInt(10, math.min(2000, 50 << math.min(n, 6)) + 1))
            .flatMap(ms => IO.sleep(ms.millis))

    /** Places one player, in the caller's transaction, which holds the game's ranking lock. A player's place is one
      * more than how many are rated above them, so equal ratings share a place and the places after them are skipped
      * (1, 2, 2, 4). Everybody else is in order by what they were placed by, and this player is the one out of place.
      *
      * However the player moves, the players whose place changes are exactly those rated between where they were and
      * where they are now — one more of them above, or one fewer — and they move by one in a single update:
      *
      *   - Not rated any more (a match made friendly took back the only one that had): taken off, and everybody rated
      *     below them moves up one.
      *   - Not placed yet: their place is found by binary search over the places, each step one read of one place by
      *     the (game_id, rank) index, and everybody rated below them moves down one.
      *   - Placed, on a rating that has moved: compared with the place above (or below), and past it if they now belong
      *     there, until they do not — a bubble sort of one — and the places passed move by one.
      */
    def place(repo: EloRatingRepo, game: GameId, player: PlayerId): IO[Unit] =
        repo.lockForPlacing(game, player).flatMap {
            case None                    => IO.unit
            case Some(row) if !row.rated => row.placed.traverse_(takeOff(repo, game, player, _))
            case Some(row) =>
                row.placed match {
                    case None                                => placeNew(repo, game, player, row.rating)
                    case Some(at) if at.rating == row.rating => IO.unit
                    case Some(at) if row.rating > at.rating  => climb(repo, game, player, at, row.rating)
                    case Some(at)                            => sink(repo, game, player, at, row.rating)
                }
        }

    private val (top, bottom) = (Int.MinValue, Int.MaxValue)

    private def takeOff(repo: EloRatingRepo, game: GameId, player: PlayerId, from: Placed): IO[Unit] =
        repo.shift(game, from.rank + 1, bottom, top, from.rating, -1, player) *> repo.place(game, player, None)

    private def placeNew(repo: EloRatingRepo, game: GameId, player: PlayerId, rating: Int): IO[Unit] = {
        // The first place at or after `low` rated no higher than this player: theirs, since everybody above it is
        // rated higher. Past the end, there is none, and theirs is the place after the last.
        def search(low: Int, high: Int): IO[Int] =
            if (low >= high) IO.pure(low)
            else {
                val middle = low + (high - low) / 2
                repo.placeFrom(game, middle, player).flatMap { found =>
                    if (found.forall(_.rating <= rating)) search(low, middle) else search(middle + 1, high)
                }
            }
        for {
            end <- repo.placeAfterLast(game, player)
            from <- search(1, end)
            k <- repo.placeFrom(game, from, player).map(_.fold(end)(_.rank))
            _ <- repo.shift(game, k, bottom, top, rating, 1, player)
            _ <- repo.place(game, player, Some(Placed(k, rating)))
        } yield ()
    }

    /* Up past every place rated no higher than the new rating -- a place rated the same is shared,
     * so it is passed too, and is the one taken. The players rated from the old rating up to the new
     * one, the player's old place-mates among them, have one more above them. */
    private def climb(repo: EloRatingRepo, game: GameId, player: PlayerId, from: Placed, rating: Int): IO[Unit] = {
        def walk(x: Int): IO[Int] =
            repo.placeAbove(game, x, player).flatMap {
                case Some(above) if above.rating <= rating => walk(above.rank)
                case _                                     => IO.pure(x)
            }
        walk(from.rank).flatMap { k =>
            repo.shift(game, k, from.rank, from.rating, rating, 1, player) *>
                repo.place(game, player, Some(Placed(k, rating)))
        }
    }

    /* Down past every place rated higher than the new rating. The players rated from the new rating
     * up to the old one, a place rated the same included, have one fewer above them. The player's
     * place is the one before the first place not passed, or, having passed them all, the one after
     * the last. */
    private def sink(repo: EloRatingRepo, game: GameId, player: PlayerId, from: Placed, rating: Int): IO[Unit] = {
        def walk(x: Int): IO[(Int, Option[Placed])] =
            repo.placeFrom(game, x + 1, player).flatMap {
                case Some(below) if below.rating > rating => walk(below.rank)
                case stopped                              => IO.pure((x, stopped))
            }
        walk(from.rank).flatMap { (passed, stopped) =>
            val last = stopped.filter(_.rating == rating).fold(passed)(_.rank)
            for {
                _ <- repo.shift(game, from.rank + 1, last, rating, from.rating, -1, player)
                k <- stopped.fold(repo.placeAfterLast(game, player))(next => IO.pure(next.rank - 1))
                _ <- repo.place(game, player, Some(Placed(k, rating)))
            } yield ()
        }
    }
}
