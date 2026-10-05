package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{EloRating, GameId, Leaderboard, PlayerId, PublicPlayer}
import EloRatingRepo.{placed, standing, Placed, Placing, RankingLockSpace, Standing}
import scala.math.Ordering.Implicits._

/** Each player's Elo rating in each game (V42). How it moves, and who may set it, is
  * [[com.vivi.matchmaker.service.EloRatingService]]'s and [[EloRating]]'s business, not this one's.
  */
class EloRatingRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId

    private val insertInitial: Command[(GameId, PlayerId, Int)] =
        sql"""INSERT INTO elo_rating (game_id, player_id, rating) VALUES ($gameId, $playerId, $int4)
          ON CONFLICT (game_id, player_id) DO NOTHING""".command

    private val selectForShare: Query[(GameId, PlayerId), Int] =
        sql"""SELECT rating FROM elo_rating WHERE game_id = $gameId AND player_id = $playerId FOR SHARE"""
            .query(int4)

    private val updatePlayed: Command[(Int, GameId, PlayerId)] =
        sql"""UPDATE elo_rating SET rating = rating + $int4, matches = matches + 1
          WHERE game_id = $gameId AND player_id = $playerId""".command

    private val upsertSet: Command[(GameId, PlayerId, Int, PlayerId)] =
        sql"""INSERT INTO elo_rating (game_id, player_id, rating, set_by) VALUES ($gameId, $playerId, $int4, $playerId)
          ON CONFLICT (game_id, player_id) DO UPDATE SET rating = EXCLUDED.rating, set_by = EXCLUDED.set_by""".command

    private val select: Query[(GameId, PlayerId), (String, Int, Int, Option[Int], Option[Int], Option[Int], Boolean)] =
        sql"""SELECT p.nickname, r.rating, r.matches, r.rank, r.ranked_rating, r.ranked_matches,
            r.matches > 0 OR r.set_by IS NOT NULL
          FROM elo_rating r JOIN player p ON p.player_id = r.player_id
          WHERE r.game_id = $gameId AND r.player_id = $playerId"""
            .query(text *: int4 *: int4 *: int4.opt *: int4.opt *: int4.opt *: bool)

    private val gameExists: Query[GameId, Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM game WHERE game_id = $gameId)""".query(bool)

    /* A range of places, by the (game_id, rank) index: everybody placed from `first` to `last`
     * inclusive, however many share them -- equal in rating and matches both -- and by nickname
     * among those so that the order does not
     * shuffle between two reads. A player not placed yet -- rated a moment ago, and waiting for the
     * listener -- is on no page until they are.
     *
     * Only players who have a rating to show: one a rated match has moved, or an admin has set. A
     * player taken off the leaderboard keeps their place until the listener takes it away. */
    private val selectPlaces
        : Query[(GameId, Int, Int), (PlayerId, String, Int, Int, Option[Int], Option[Int], Option[Int])] =
        sql"""SELECT p.player_id, p.nickname, r.rating, r.matches, r.rank, r.ranked_rating, r.ranked_matches
          FROM elo_rating r JOIN player p ON p.player_id = r.player_id
          WHERE r.game_id = $gameId AND r.rank BETWEEN $int4 AND $int4 AND (r.matches > 0 OR r.set_by IS NOT NULL)
          ORDER BY r.rank, p.nickname""".query(playerId *: text *: int4 *: int4 *: int4.opt *: int4.opt *: int4.opt)

    private val selectPlacedAfter: Query[(GameId, Int), Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM elo_rating WHERE game_id = $gameId AND rank > $int4)""".query(bool)

    /** Gives each player a rating in the game at [[EloRating.initial]] if they have none yet: for a rated match about
      * to move them all, and for a match about to begin, which needs a row to lock for each of its players.
      *
      * In player order, one at a time — the order [[played]] is called in after it — so that two matches finishing at
      * once with players in common take their row locks in the same order, and one waits for the other rather than each
      * holding a row the other needs. A row two transactions both make waits on the first to commit as a locked one
      * does.
      */
    def ensureRated(game: GameId, players: Seq[PlayerId]): IO[Unit] =
        players.distinct
            .sortBy(_.value)
            .traverse_(player => session.execute(insertInitial)((game, player, EloRating.initial)))

    /** Each player's rating in the game, held FOR SHARE until the transaction ends: what a match's start records as the
      * rating its seats began at. Every player must have a row already — [[ensureRated]] first — since FOR SHARE locks
      * only the rows it finds, and a player read as "no row yet" is held by nothing. In player order, as a completion
      * takes its locks.
      */
    def readForShare(game: GameId, players: Seq[PlayerId]): IO[Map[PlayerId, Int]] =
        players.distinct
            .sortBy(_.value)
            .traverse(player => session.unique(selectForShare)((game, player)).map(player -> _))
            .map(_.toMap)

    /** Records a rated match's effect on one player: their rating moved by `delta`, and one more match behind it. An
      * addition in the update itself rather than a value worked out from a read, so it needs no read to be locked: two
      * matches finishing at once each add their own.
      */
    def played(game: GameId, player: PlayerId, delta: Int): IO[Unit] =
        session.execute(updatePlayed)((delta, game, player)).void

    private val updateUnplayed: Command[(Int, GameId, PlayerId)] =
        sql"""UPDATE elo_rating SET rating = rating - $int4, matches = matches - 1
          WHERE game_id = $gameId AND player_id = $playerId""".command

    /** Takes back a rated match's effect on one player, [[played]] in reverse: for a completed match a game's admin has
      * since said was friendly.
      */
    def unplayed(game: GameId, player: PlayerId, delta: Int): IO[Unit] =
        session.execute(updateUnplayed)((delta, game, player)).void

    /** Sets the player's rating outright, on `setBy`'s say, making the row if there is none. How many matches stand
      * behind it is left as it was.
      */
    def set(game: GameId, player: PlayerId, rating: Int, setBy: PlayerId): IO[Unit] =
        session.execute(upsertSet)((game, player, rating, setBy)).void

    private def readWhere(game: GameId, player: PlayerId)(keep: Boolean => Boolean): IO[Option[EloRating]] =
        session
            .option(select)((game, player))
            .map(_.filter((_, _, _, _, _, _, rated) => keep(rated)).map {
                (nickname, rating, matches, rank, ranked, behind, _) =>
                    EloRating(PublicPlayer(player, nickname), rating, matches, rank, ranked, behind)
            })

    /** The player's row in the game, rated or not: nothing only if there is no row at all. */
    def read(game: GameId, player: PlayerId): IO[Option[EloRating]] = readWhere(game, player)(_ => true)

    /** The player's rating in the game as the leaderboard counts it, or nothing if they have none: a row a match's
      * start made, which nothing has rated yet, is none.
      */
    def readRated(game: GameId, player: PlayerId): IO[Option[EloRating]] = readWhere(game, player)(identity)

    /** The game's leaderboard from place `first` to place `last`, and whether anybody is placed after it — or nothing,
      * if there is no such game.
      */
    def leaderboard(game: GameId, first: Int, last: Int): IO[Option[Leaderboard]] =
        session.unique(gameExists)(game).flatMap {
            case false => IO.pure(None)
            case true =>
                (session.execute(selectPlaces)((game, first, last)), session.unique(selectPlacedAfter)((game, last)))
                    .mapN((rows, more) =>
                        Some(
                          Leaderboard(
                            rows.map((id, nickname, rating, matches, rank, ranked, behind) =>
                                EloRating(PublicPlayer(id, nickname), rating, matches, rank, ranked, behind)
                            ),
                            more
                          )
                        )
                    )
        }

    // ---------------------------------------------------------------------------------------------
    // Places (V45): the primitives `RankingService` places a player with. Every one is for its
    // transaction, which holds the game's ranking lock (`tryLockRanking`), so nothing else moves a
    // place while it runs. What it does not keep out is play, which moves ratings: so every row a
    // place is written to is locked first, NOWAIT, and a lock held elsewhere fails the transaction
    // (55P03) to be tried again, rather than waiting where it could be waited on.
    // ---------------------------------------------------------------------------------------------

    /* The two keys of an advisory lock: a space of this repo's own, and the game. */
    private val tryLock: Query[GameId, Boolean] =
        sql"""SELECT pg_try_advisory_xact_lock(${int4}, $gameId)""".query(bool).contramap(g => (RankingLockSpace, g))

    /** Takes the game's ranking lock until the transaction ends, if nobody else holds it: false, rather than waiting,
      * if somebody does. Places are a structure over the whole game — one player's move shifts the others' — so two
      * placings of one game cannot run at once.
      */
    def tryLockRanking(game: GameId): IO[Boolean] = session.unique(tryLock)(game)

    private val unplacedWhere =
        """((matches > 0 OR set_by IS NOT NULL)
            AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
          OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL)"""

    // The predicate is V45's `elo_rating_unplaced` word for word, so that the index serves it.
    private val selectUnplaced: Query[(GameId, Int), PlayerId] =
        sql"""SELECT player_id FROM elo_rating WHERE game_id = $gameId AND (#$unplacedWhere)
          ORDER BY player_id LIMIT $int4""".query(playerId)

    /** Up to `limit` of the game's players waiting to be placed, or taken off: rated and not placed, placed on a rating
      * or a number of matches that has moved since, or placed and no longer rated.
      */
    def unplaced(game: GameId, limit: Int): IO[List[PlayerId]] = session.execute(selectUnplaced)((game, limit))

    private val selectForPlacing: Query[(GameId, PlayerId), Placing] =
        sql"""SELECT rating, matches, matches > 0 OR set_by IS NOT NULL, rank, ranked_rating, ranked_matches
          FROM elo_rating WHERE game_id = $gameId AND player_id = $playerId
          FOR NO KEY UPDATE NOWAIT""".query(int4 *: int4 *: bool *: int4.opt *: int4.opt *: int4.opt).map {
            (rating, matches, rated, rank, rankedRating, rankedMatches) =>
                val placed = for {
                    r <- rank
                    by <- rankedRating
                    behind <- rankedMatches
                } yield Placed(r, Standing(by, behind))
                Placing(Standing(rating, matches), rated, placed)
        }

    /** The player's row, locked NOWAIT — the rating and matches it holds are what they are placed by, and play cannot
      * move them until the transaction ends — or nothing if they have none.
      */
    def lockForPlacing(game: GameId, player: PlayerId): IO[Option[Placing]] =
        session.option(selectForPlacing)((game, player))

    /* The probes placing walks the leaderboard with: each one row, found by the (game_id, rank)
     * index, and never `except`, the player being placed. Read plainly: only placings change a
     * place, and the ranking lock keeps every other placing out. Everybody in one place was placed by
     * the same rating and matches, so any one of them says what the place was placed by. */
    private val selectAbove: Query[(GameId, Int, PlayerId), Placed] =
        sql"""SELECT rank, ranked_rating, ranked_matches FROM elo_rating
          WHERE game_id = $gameId AND rank < $int4 AND player_id <> $playerId
          ORDER BY rank DESC LIMIT 1""".query(placed)

    private val selectFrom: Query[(GameId, Int, PlayerId), Placed] =
        sql"""SELECT rank, ranked_rating, ranked_matches FROM elo_rating
          WHERE game_id = $gameId AND rank >= $int4 AND player_id <> $playerId
          ORDER BY rank LIMIT 1""".query(placed)

    /** The place just above `rank`, or nothing at the top. */
    def placeAbove(game: GameId, rank: Int, except: PlayerId): IO[Option[Placed]] =
        session.option(selectAbove)((game, rank, except))

    /** The first place at `rank` or below it, or nothing past the end. */
    def placeFrom(game: GameId, rank: Int, except: PlayerId): IO[Option[Placed]] =
        session.option(selectFrom)((game, rank, except))

    private val selectLast: Query[(GameId, PlayerId, GameId, PlayerId), (Int, Long)] =
        sql"""SELECT rank, count(*) FROM elo_rating
          WHERE game_id = $gameId AND player_id <> $playerId AND rank = (
            SELECT max(rank) FROM elo_rating WHERE game_id = $gameId AND player_id <> $playerId
          )
          GROUP BY rank""".query(int4 *: int8)

    /** The place after everybody but `except`: 1 on an empty leaderboard, and otherwise the last place plus how many
      * share it.
      */
    def placeAfterLast(game: GameId, except: PlayerId): IO[Int] =
        session
            .option(selectLast)((game, except, game, except))
            .map(_.fold(1)((rank, sharing) => rank + sharing.toInt))

    /* A run of places, by the index, and of what they were placed by: rating and matches compared
     * together, rating first, as row values compare. */
    private val runWhere: Fragment[(GameId, Int, Int, Standing, Standing, PlayerId)] =
        sql"""game_id = $gameId AND rank BETWEEN $int4 AND $int4
            AND (ranked_rating, ranked_matches) >= ($standing) AND (ranked_rating, ranked_matches) < ($standing)
            AND player_id <> $playerId"""

    private val lockRun: Query[(GameId, Int, Int, Standing, Standing, PlayerId), PlayerId] =
        sql"""SELECT player_id FROM elo_rating WHERE $runWhere FOR NO KEY UPDATE NOWAIT""".query(playerId)

    private val updateRun: Command[(Int, (GameId, Int, Int, Standing, Standing, PlayerId))] =
        sql"""UPDATE elo_rating SET rank = rank + $int4 WHERE $runWhere""".command

    /** Moves `by` places everybody but `except` who was placed by a standing from `atLeast` up to but not including
      * `below`: down one when a player arrives above them, up one when a player leaves from above them. `from` and `to`
      * are the places those players are between, inclusive — what keeps the update to the index's range, and not the
      * game's whole leaderboard. Locked NOWAIT first, so that the update has nothing to wait for.
      */
    def shift(
        game: GameId,
        from: Int,
        to: Int,
        atLeast: Standing,
        below: Standing,
        by: Int,
        except: PlayerId
    ): IO[Unit] = {
        val run = (game, from, to, atLeast, below, except)
        IO.whenA(from <= to && atLeast < below)(
          session.execute(lockRun)(run) *> session.execute(updateRun)((by, run)).void
        )
    }

    private val updatePlace: Command[(Option[Int], Option[Int], Option[Int], GameId, PlayerId)] =
        sql"""UPDATE elo_rating SET rank = ${int4.opt}, ranked_rating = ${int4.opt}, ranked_matches = ${int4.opt}
          WHERE game_id = $gameId AND player_id = $playerId""".command

    /** Puts the player at `place`, placed by what it holds: or takes them off the leaderboard, given nothing. The row
      * is the one [[lockForPlacing]] locked.
      */
    def place(game: GameId, player: PlayerId, place: Option[Placed]): IO[Unit] =
        session
            .execute(updatePlace)(
              (place.map(_.rank), place.map(_.by.rating), place.map(_.by.matches), game, player)
            )
            .void
}

object EloRatingRepo {

    /** The first key of the ranking lock's advisory locks: anything, so long as nothing else takes locks under it. */
    val RankingLockSpace: Int = 0x52414e4b // "RANK"

    /** What a player is placed by: their rating, and of two rated the same, the one more rated matches stand behind is
      * the higher.
      */
    case class Standing(rating: Int, matches: Int)

    object Standing {
        given Ordering[Standing] = Ordering.by(s => (s.rating, s.matches))

        /** Below and above every standing there is. */
        val lowest: Standing = Standing(Int.MinValue, Int.MinValue)
        val highest: Standing = Standing(Int.MaxValue, Int.MaxValue)
    }

    /** Where a player is on the leaderboard, and the standing they were placed by. */
    case class Placed(rank: Int, by: Standing)

    /** A player's row as placing them reads it: their standing, whether it is one the leaderboard shows, and their
      * place.
      */
    case class Placing(standing: Standing, rated: Boolean, placed: Option[Placed])

    private val standing: Codec[Standing] = (int4 *: int4).to[Standing]
    private val placed: Decoder[Placed] = (int4 *: standing).to[Placed]
}
