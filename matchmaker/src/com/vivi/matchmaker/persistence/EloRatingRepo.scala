package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{EloRating, GameId, GameRoleId, Leaderboard, MatchRecord, PlayerId, PublicPlayer}
import EloRatingRepo.{placed, standing, Board, Placed, Placing, RankingLockSpace, RatingKey, Standing}
import scala.math.Ordering.Implicits._

/** Each player's Elo rating in each game (V42), and for a game whose roles matter, in each role (V49). How it moves,
  * and who may set it, is [[com.vivi.matchmaker.service.EloRatingService]]'s and [[EloRating]]'s business, not this
  * one's.
  *
  * The overall ratings are `elo_rating`, and a role's are `elo_role_rating`, whose key has the role in it. The two
  * tables hold the same columns but for `set_by` — an admin sets only the overall rating — and are kept, ranked and
  * counted alike, so each query is written once, in [[BoardSql]], and run against the table a [[Board]] names. A `role`
  * of `None` is the overall board, which is what every caller that does not say otherwise is asking about.
  */
class EloRatingRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val gameRoleId = SkunkIdCodecs.gameRoleId

    /* A rating as it is shown, from `<table> r JOIN player p`: what every query that answers with
     * one selects, and how it is read. */
    private val ratingColumns =
        """p.player_id, p.nickname, r.rating, r.matches, r.rank, r.ranked_rating, r.ranked_matches,
           r.wins, r.losses, r.draws, r.forfeit_wins, r.forfeit_losses"""

    private val ratingRow: Decoder[EloRating] =
        (playerId *: text *: int4 *: int4 *: int4.opt *: int4.opt *: int4.opt *:
            int4 *: int4 *: int4 *: int4 *: int4).map {
            case (
                  id,
                  nickname,
                  rating,
                  matches,
                  rank,
                  ranked,
                  behind,
                  wins,
                  losses,
                  draws,
                  forfeitWins,
                  forfeitLosses
                ) =>
                EloRating(
                  PublicPlayer(id, nickname),
                  rating,
                  matches,
                  rank,
                  ranked,
                  behind,
                  MatchRecord(wins, losses, draws, forfeitWins, forfeitLosses)
                )
        }

    /** Every query about one board, against its table: `elo_rating` for the overall board, `elo_role_rating` for a
      * role's.
      *
      * @param keyColumns
      *   the columns that name the board, ahead of `player_id` in the table's key
      * @param key
      *   the board's values for them
      * @param where
      *   the board's rows, given the alias to qualify its columns with
      * @param rated
      *   whether a row is one the leaderboard shows, given the same: a rated match has moved it, or — overall only — an
      *   admin has set it
      */
    private final class BoardSql(
        table: String,
        keyColumns: String,
        key: Encoder[Board],
        where: String => Fragment[Board],
        rated: String => String
    ) {
        val insertInitial: Command[(Board, PlayerId, Int)] =
            sql"""INSERT INTO #$table (#$keyColumns, player_id, rating) VALUES ($key, $playerId, $int4)
              ON CONFLICT (#$keyColumns, player_id) DO NOTHING""".command

        val selectForShare: Query[(Board, PlayerId), Int] =
            sql"""SELECT rating FROM #$table WHERE ${where("")} AND player_id = $playerId FOR SHARE""".query(int4)

        /* What one match adds to a row, or takes back: its rating, its match, and its record, each an
         * addition in the update itself. */
        val updateMoved: Command[(Int, Int, MatchRecord, Board, PlayerId)] =
            sql"""UPDATE #$table
              SET rating = rating + $int4, matches = matches + $int4,
                  wins = wins + $int4, losses = losses + $int4, draws = draws + $int4,
                  forfeit_wins = forfeit_wins + $int4, forfeit_losses = forfeit_losses + $int4
              WHERE ${where("")} AND player_id = $playerId""".command
                .contramap { case (delta, matches, r, board, player) =>
                    (delta, matches, r.wins, r.losses, r.draws, r.forfeitWins, r.forfeitLosses, board, player)
                }

        val select: Query[(Board, PlayerId), (EloRating, Boolean)] =
            sql"""SELECT #$ratingColumns, #${rated("r.")}
              FROM #$table r JOIN player p ON p.player_id = r.player_id
              WHERE ${where("r.")} AND r.player_id = $playerId""".query(ratingRow *: bool)

        /* A range of places, by the rank index: everybody placed from `first` to `last` inclusive,
         * however many share them -- equal in rating and matches both -- and by nickname among those
         * so that the order does not shuffle between two reads. A player not placed yet -- rated a
         * moment ago, and waiting for the listener -- is on no page until they are.
         *
         * Only players who have a rating to show. A player taken off the leaderboard keeps their place
         * until the listener takes it away. */
        val selectPlaces: Query[(Board, Int, Int), EloRating] =
            sql"""SELECT #$ratingColumns
              FROM #$table r JOIN player p ON p.player_id = r.player_id
              WHERE ${where("r.")} AND r.rank BETWEEN $int4 AND $int4 AND (#${rated("r.")})
              ORDER BY r.rank, p.nickname""".query(ratingRow)

        /* The board's rated players whose nickname begins with a prefix, compared as the player search
         * compares (PlayerRepo.nicknamePrefixPattern), in nickname order: who "Find a Player" under the
         * rankings shows. */
        val selectByPrefix: Query[(Board, String, Int), EloRating] =
            sql"""SELECT #$ratingColumns
              FROM #$table r JOIN player p ON p.player_id = r.player_id
              WHERE ${where("r.")} AND (#${rated("r.")})
                AND #${PlayerRepo.nicknameKey("p")} LIKE $text
              ORDER BY p.nickname
              LIMIT $int4""".query(ratingRow)

        val selectPlacedAfter: Query[(Board, Int), Boolean] =
            sql"""SELECT EXISTS (SELECT 1 FROM #$table WHERE ${where("")} AND rank > $int4)""".query(bool)

        val selectForPlacing: Query[(Board, PlayerId), Placing] =
            sql"""SELECT rating, matches, #${rated("")}, rank, ranked_rating, ranked_matches
              FROM #$table WHERE ${where("")} AND player_id = $playerId
              FOR NO KEY UPDATE NOWAIT""".query(int4 *: int4 *: bool *: int4.opt *: int4.opt *: int4.opt).map {
                (rating, matches, rated, rank, rankedRating, rankedMatches) =>
                    val placed = for {
                        r <- rank
                        by <- rankedRating
                        behind <- rankedMatches
                    } yield Placed(r, Standing(by, behind))
                    Placing(Standing(rating, matches), rated, placed)
            }

        /* The probes placing walks the leaderboard with: each one row, found by the rank index, and
         * never `except`, the player being placed. Read plainly: only placings change a place, and the
         * ranking lock keeps every other placing out. Everybody in one place was placed by the same
         * rating and matches, so any one of them says what the place was placed by. */
        val selectAbove: Query[(Board, Int, PlayerId), Placed] =
            sql"""SELECT rank, ranked_rating, ranked_matches FROM #$table
              WHERE ${where("")} AND rank < $int4 AND player_id <> $playerId
              ORDER BY rank DESC LIMIT 1""".query(placed)

        val selectFrom: Query[(Board, Int, PlayerId), Placed] =
            sql"""SELECT rank, ranked_rating, ranked_matches FROM #$table
              WHERE ${where("")} AND rank >= $int4 AND player_id <> $playerId
              ORDER BY rank LIMIT 1""".query(placed)

        val selectLast: Query[(Board, PlayerId, Board, PlayerId), (Int, Long)] =
            sql"""SELECT rank, count(*) FROM #$table
              WHERE ${where("")} AND player_id <> $playerId AND rank = (
                SELECT max(rank) FROM #$table WHERE ${where("")} AND player_id <> $playerId
              )
              GROUP BY rank""".query(int4 *: int8)

        /* A run of places, by the rank index, and of what they were placed by: rating and matches
         * compared together, rating first, as row values compare. */
        private val runWhere: Fragment[(Board, Int, Int, Standing, Standing, PlayerId)] =
            sql"""${where("")} AND rank BETWEEN $int4 AND $int4
                AND (ranked_rating, ranked_matches) >= ($standing) AND (ranked_rating, ranked_matches) < ($standing)
                AND player_id <> $playerId"""

        val lockRun: Query[(Board, Int, Int, Standing, Standing, PlayerId), PlayerId] =
            sql"""SELECT player_id FROM #$table WHERE $runWhere FOR NO KEY UPDATE NOWAIT""".query(playerId)

        val updateRun: Command[(Int, (Board, Int, Int, Standing, Standing, PlayerId))] =
            sql"""UPDATE #$table SET rank = rank + $int4 WHERE $runWhere""".command

        val updatePlace: Command[(Option[Int], Option[Int], Option[Int], Board, PlayerId)] =
            sql"""UPDATE #$table SET rank = ${int4.opt}, ranked_rating = ${int4.opt}, ranked_matches = ${int4.opt}
              WHERE ${where("")} AND player_id = $playerId""".command
    }

    private val overallSql = new BoardSql(
      "elo_rating",
      "game_id",
      gameId.contramap[Board](_.game),
      a => sql"#${a}game_id = $gameId".contramap[Board](_.game),
      a => s"${a}matches > 0 OR ${a}set_by IS NOT NULL"
    )

    /* Only ever asked about a board with a role: `on` sends the overall board elsewhere. */
    private val roleSql = new BoardSql(
      "elo_role_rating",
      "game_id, game_role_id",
      // Raw numbers: a twiddle ending in an opaque id does not reduce to a tuple (see GameRepo).
      (int4 *: int4).contramap[Board](b => (b.game.value, b.role.get.value)),
      a => sql"#${a}game_id = $gameId AND #${a}game_role_id = $gameRoleId".contramap[Board](b => (b.game, b.role.get)),
      a => s"${a}matches > 0"
    )

    private def on(b: Board): BoardSql = if (b.role.isEmpty) overallSql else roleSql

    // The overall rating only: an admin sets that, and a role's is only ever moved by play (V49).
    private val upsertSet: Command[(GameId, PlayerId, Int, PlayerId)] =
        sql"""INSERT INTO elo_rating (game_id, player_id, rating, set_by) VALUES ($gameId, $playerId, $int4, $playerId)
          ON CONFLICT (game_id, player_id) DO UPDATE SET rating = EXCLUDED.rating, set_by = EXCLUDED.set_by""".command

    private val gameExists: Query[GameId, Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM game WHERE game_id = $gameId)""".query(bool)

    private val roleExists: Query[(GameId, GameRoleId), Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM game_role WHERE game_id = $gameId AND game_role_id = $gameRoleId)"""
            .query(bool)

    /* Whether there is such a board: the game, or the role in it when one is named. */
    private def exists(b: Board): IO[Boolean] =
        b.role.fold(session.unique(gameExists)(b.game))(role => session.unique(roleExists)((b.game, role)))

    /** Up to `limit` of the game's rated players, overall or in `role`, whose nickname begins with `prefix`, with
      * whether there were more — or nothing, if there is no such game, or no such role in it. One more than `limit` is
      * asked for, which is how it knows.
      */
    def findByNicknamePrefix(
        game: GameId,
        prefix: String,
        limit: Int,
        role: Option[GameRoleId] = None
    ): IO[Option[Leaderboard]] = {
        val b = Board(game, role)
        exists(b).flatMap {
            case false => IO.pure(None)
            case true =>
                session
                    .execute(on(b).selectByPrefix)((b, PlayerRepo.nicknamePrefixPattern(prefix), limit + 1))
                    .map(rows =>
                        Some(
                          Leaderboard(rows.take(limit), more = rows.sizeIs > limit)
                        )
                    )
        }
    }

    /** Gives each player an overall rating in the game at [[EloRating.initial]] if they have none yet:
      * [[ensureRatedIn]] for the overall board alone.
      */
    def ensureRated(game: GameId, players: Seq[PlayerId]): IO[Unit] =
        ensureRatedIn(game, players.map(RatingKey(None, _)))

    /** Gives each key — a player, overall or in a role — a rating in the game at [[EloRating.initial]] if it has none
      * yet: for a rated match about to move them all, and for a match about to begin, which needs a row to lock for
      * each of its players.
      *
      * In key order, one at a time — every overall row, then each role's, each by player — the order [[played]] is
      * called in after it, so that two matches finishing at once with players in common take their row locks in the
      * same order, and one waits for the other rather than each holding a row the other needs. A row two transactions
      * both make waits on the first to commit as a locked one does.
      */
    def ensureRatedIn(game: GameId, keys: Seq[RatingKey]): IO[Unit] =
        keys.distinct.sorted
            .traverse_ { key =>
                val b = Board(game, key.role)
                session.execute(on(b).insertInitial)((b, key.player, EloRating.initial))
            }

    /** Each key's rating in the game, held FOR SHARE until the transaction ends: what a match's start records as the
      * ratings its seats began at. Every key must have a row already — [[ensureRatedIn]] first — since FOR SHARE locks
      * only the rows it finds, and a player read as "no row yet" is held by nothing. In key order, as a completion
      * takes its locks.
      */
    def readForShare(game: GameId, keys: Seq[RatingKey]): IO[Map[RatingKey, Int]] =
        keys.distinct.sorted
            .traverse { key =>
                val b = Board(game, key.role)
                session.unique(on(b).selectForShare)((b, key.player)).map(key -> _)
            }
            .map(_.toMap)

    /** Records a rated match's effect on one player's rating, overall or in `role`: moved by `delta`, one more match
      * behind it, and `record` — what the match was to them (V46) — added to its record. An addition in the update
      * itself rather than a value worked out from a read, so it needs no read to be locked: two matches finishing at
      * once each add their own.
      */
    def played(
        game: GameId,
        player: PlayerId,
        delta: Int,
        record: MatchRecord,
        role: Option[GameRoleId] = None
    ): IO[Unit] = {
        val b = Board(game, role)
        session.execute(on(b).updateMoved)((delta, 1, record, b, player)).void
    }

    /** Takes back a rated match's effect on one player's rating, [[played]] in reverse: for a completed match a game's
      * admin has since said was friendly.
      */
    def unplayed(
        game: GameId,
        player: PlayerId,
        delta: Int,
        record: MatchRecord,
        role: Option[GameRoleId] = None
    ): IO[Unit] = {
        val b = Board(game, role)
        session.execute(on(b).updateMoved)((-delta, -1, -record, b, player)).void
    }

    /** Sets the player's overall rating outright, on `setBy`'s say, making the row if there is none. How many matches
      * stand behind it is left as it was.
      */
    def set(game: GameId, player: PlayerId, rating: Int, setBy: PlayerId): IO[Unit] =
        session.execute(upsertSet)((game, player, rating, setBy)).void

    private def readWhere(game: GameId, player: PlayerId, role: Option[GameRoleId])(
        keep: Boolean => Boolean
    ): IO[Option[EloRating]] = {
        val b = Board(game, role)
        session
            .option(on(b).select)((b, player))
            .map(_.collect { case (rating, rated) if keep(rated) => rating })
    }

    /** The player's row in the game, overall or in `role`, rated or not: nothing only if there is no row at all. */
    def read(game: GameId, player: PlayerId, role: Option[GameRoleId] = None): IO[Option[EloRating]] =
        readWhere(game, player, role)(_ => true)

    /** The player's rating in the game, overall or in `role`, as the leaderboard counts it, or nothing if they have
      * none: a row a match's start made, which nothing has rated yet, is none.
      */
    def readRated(game: GameId, player: PlayerId, role: Option[GameRoleId] = None): IO[Option[EloRating]] =
        readWhere(game, player, role)(identity)

    /** The game's leaderboard, overall or `role`'s, from place `first` to place `last`, and whether anybody is placed
      * after it — or nothing, if there is no such game, or no such role in it.
      */
    def leaderboard(game: GameId, first: Int, last: Int, role: Option[GameRoleId] = None): IO[Option[Leaderboard]] = {
        val b = Board(game, role)
        exists(b).flatMap {
            case false => IO.pure(None)
            case true =>
                (
                  session.execute(on(b).selectPlaces)((b, first, last)),
                  session.unique(on(b).selectPlacedAfter)((b, last))
                )
                    .mapN((rows, more) =>
                        Some(
                          Leaderboard(rows, more)
                        )
                    )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Places (V45): the primitives `RankingService` places a player with, each on one board -- the
    // game's overall leaderboard, or one role's (V49). Every one is for its transaction, which holds
    // the game's ranking lock (`tryLockRanking`) -- one for all of its boards -- so nothing else
    // moves a place while it runs. What it does not keep out is play, which moves ratings: so every
    // row a place is written to is locked first, NOWAIT, and a lock held elsewhere fails the
    // transaction (55P03) to be tried again, rather than waiting where it could be waited on.
    // ---------------------------------------------------------------------------------------------

    /* The two keys of an advisory lock: a space of this repo's own, and the game. */
    private val tryLock: Query[GameId, Boolean] =
        sql"""SELECT pg_try_advisory_xact_lock(${int4}, $gameId)""".query(bool).contramap(g => (RankingLockSpace, g))

    /** Takes the game's ranking lock until the transaction ends, if nobody else holds it: false, rather than waiting,
      * if somebody does. Places are a structure over the whole of a board — one player's move shifts the others' — so
      * two placings of one game cannot run at once.
      */
    def tryLockRanking(game: GameId): IO[Boolean] = session.unique(tryLock)(game)

    // The predicates are V45's `elo_rating_unplaced` and V49's `elo_role_rating_unplaced` word for
    // word, so that the indexes serve them.
    private val selectUnplaced: Query[(GameId, Int), PlayerId] =
        sql"""SELECT player_id FROM elo_rating WHERE game_id = $gameId AND (
            ((matches > 0 OR set_by IS NOT NULL)
              AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
            OR (NOT (matches > 0 OR set_by IS NOT NULL) AND rank IS NOT NULL))
          ORDER BY player_id LIMIT $int4""".query(playerId)

    private val selectRoleUnplaced: Query[(GameId, Int), RatingKey] =
        sql"""SELECT game_role_id, player_id FROM elo_role_rating WHERE game_id = $gameId AND (
            (matches > 0
              AND (rank IS NULL OR ranked_rating IS DISTINCT FROM rating OR ranked_matches IS DISTINCT FROM matches))
            OR (NOT matches > 0 AND rank IS NOT NULL))
          ORDER BY game_role_id, player_id LIMIT $int4"""
            // Raw numbers: a twiddle ending in an opaque id does not reduce to a tuple (see GameRepo).
            .query(int4 *: int8)
            .map((role, player) => RatingKey(Some(GameRoleId(role)), PlayerId(player)))

    /** Up to `limit` of the game's players waiting to be placed, or taken off, on any of its boards — the overall one's
      * first: rated and not placed, placed on a rating or a number of matches that has moved since, or placed and no
      * longer rated.
      */
    def unplaced(game: GameId, limit: Int): IO[List[RatingKey]] =
        session.execute(selectUnplaced)((game, limit)).flatMap { overall =>
            val keys = overall.map(RatingKey(None, _))
            if (keys.sizeIs >= limit) IO.pure(keys)
            else session.execute(selectRoleUnplaced)((game, limit - keys.size)).map(keys ++ _)
        }

    /** The player's row on the board, locked NOWAIT — the rating and matches it holds are what they are placed by, and
      * play cannot move them until the transaction ends — or nothing if they have none.
      */
    def lockForPlacing(b: Board, player: PlayerId): IO[Option[Placing]] =
        session.option(on(b).selectForPlacing)((b, player))

    /** The place just above `rank`, or nothing at the top. */
    def placeAbove(b: Board, rank: Int, except: PlayerId): IO[Option[Placed]] =
        session.option(on(b).selectAbove)((b, rank, except))

    /** The first place at `rank` or below it, or nothing past the end. */
    def placeFrom(b: Board, rank: Int, except: PlayerId): IO[Option[Placed]] =
        session.option(on(b).selectFrom)((b, rank, except))

    /** The place after everybody but `except`: 1 on an empty leaderboard, and otherwise the last place plus how many
      * share it.
      */
    def placeAfterLast(b: Board, except: PlayerId): IO[Int] =
        session
            .option(on(b).selectLast)((b, except, b, except))
            .map(_.fold(1)((rank, sharing) => rank + sharing.toInt))

    /** Moves `by` places everybody but `except` who was placed by a standing from `atLeast` up to but not including
      * `below`: down one when a player arrives above them, up one when a player leaves from above them. `from` and `to`
      * are the places those players are between, inclusive — what keeps the update to the index's range, and not the
      * board's whole leaderboard. Locked NOWAIT first, so that the update has nothing to wait for.
      */
    def shift(
        b: Board,
        from: Int,
        to: Int,
        atLeast: Standing,
        below: Standing,
        by: Int,
        except: PlayerId
    ): IO[Unit] = {
        val run = (b, from, to, atLeast, below, except)
        IO.whenA(from <= to && atLeast < below)(
          session.execute(on(b).lockRun)(run) *> session.execute(on(b).updateRun)((by, run)).void
        )
    }

    /** Puts the player at `place`, placed by what it holds: or takes them off the leaderboard, given nothing. The row
      * is the one [[lockForPlacing]] locked.
      */
    def place(b: Board, player: PlayerId, place: Option[Placed]): IO[Unit] =
        session
            .execute(on(b).updatePlace)(
              (place.map(_.rank), place.map(_.by.rating), place.map(_.by.matches), b, player)
            )
            .void
}

object EloRatingRepo {

    /** One leaderboard of a game: its overall one, or one role's (V49). */
    case class Board(game: GameId, role: Option[GameRoleId] = None)

    /** A player's rating row on one of a game's boards: overall, or in `role`. Ordered as every writer takes their row
      * locks — the overall rows first, then each role's, each by player — so that none of them can deadlock another.
      */
    case class RatingKey(role: Option[GameRoleId], player: PlayerId)

    object RatingKey {
        given Ordering[RatingKey] = Ordering.by(k => (k.role.fold(0)(_.value), k.player.value))
    }

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
