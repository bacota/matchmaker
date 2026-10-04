package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{EloRating, GameId, PlayerId, PublicPlayer}

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

    private val select: Query[(GameId, PlayerId), (String, Int, Int)] =
        sql"""SELECT p.nickname, r.rating, r.matches
          FROM elo_rating r JOIN player p ON p.player_id = r.player_id
          WHERE r.game_id = $gameId AND r.player_id = $playerId""".query(text *: int4 *: int4)

    /* From the game outward, as GameAdminRepo's list is: no row at all is no game, and a game nobody
     * is rated in is one row of nulls. Highest first, and by nickname among equals so that the order
     * does not shuffle between two reads. */
    private val selectForGame: Query[GameId, Option[(PlayerId, String, Int, Int)]] =
        sql"""SELECT p.player_id, p.nickname, r.rating, r.matches
          FROM game g
          LEFT JOIN elo_rating r ON r.game_id = g.game_id
          LEFT JOIN player p ON p.player_id = r.player_id
          WHERE g.game_id = $gameId
          ORDER BY r.rating DESC, p.nickname""".query((playerId *: text *: int4 *: int4).opt)

    /** Gives each player a rating in the game at [[EloRating.initial]] if they have none yet, for a rated match about
      * to move them all.
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

    /** Each player's rating in the game, for those who have one, held FOR SHARE until the transaction ends: what a
      * match's start records as the rating its seats began at. In player order, as a completion takes its locks, so
      * that a start and a completion with players in common queue rather than deadlock.
      */
    def readForShare(game: GameId, players: Seq[PlayerId]): IO[Map[PlayerId, Int]] =
        players.distinct
            .sortBy(_.value)
            .traverse(player => session.option(selectForShare)((game, player)).map(_.map(player -> _)))
            .map(_.flatten.toMap)

    /** Records a rated match's effect on one player: their rating moved by `delta`, and one more match behind it. An
      * addition in the update itself rather than a value worked out from a read, so it needs no read to be locked: two
      * matches finishing at once each add their own.
      */
    def played(game: GameId, player: PlayerId, delta: Int): IO[Unit] =
        session.execute(updatePlayed)((delta, game, player)).void

    /** Sets the player's rating outright, on `setBy`'s say, making the row if there is none. How many matches stand
      * behind it is left as it was.
      */
    def set(game: GameId, player: PlayerId, rating: Int, setBy: PlayerId): IO[Unit] =
        session.execute(upsertSet)((game, player, rating, setBy)).void

    /** The player's rating in the game, as anybody may see it, or nothing if they have none. */
    def read(game: GameId, player: PlayerId): IO[Option[EloRating]] =
        session
            .option(select)((game, player))
            .map(_.map((nickname, rating, matches) => EloRating(PublicPlayer(player, nickname), rating, matches)))

    /** Every rated player of the game, highest first — or nothing, if there is no such game. */
    def listForGame(game: GameId): IO[Option[List[EloRating]]] =
        session
            .execute(selectForGame)(game)
            .map(rows =>
                Option.when(rows.nonEmpty)(
                  rows.flatten
                      .map((id, nickname, rating, matches) => EloRating(PublicPlayer(id, nickname), rating, matches))
                )
            )
}
