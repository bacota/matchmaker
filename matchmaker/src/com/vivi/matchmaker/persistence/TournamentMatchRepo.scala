package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._

/** Reads and writes `tournament_match` and `tournament_seat` (V52): each match of a tournament's round, from the moment
  * the round starts until its result is recorded, and its seats.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class TournamentMatchRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val entrantId = SkunkIdCodecs.entrantId
    private val gameRoleId = SkunkIdCodecs.gameRoleId
    private val participantId = SkunkIdCodecs.participantId
    private val matchId = SkunkIdCodecs.matchId
    private val instant = SkunkCodecs.instant

    private val status: Codec[TournamentMatchStatus] = text.imap(TournamentMatchStatus.fromCode)(_.code)

    private type Key = (GameId, TournamentId, Int, Int)

    private val insertMatch: Command[(GameId, TournamentId, Int, Int, Int, TournamentMatchStatus, Boolean)] =
        sql"""INSERT INTO tournament_match (game_id, tournament_id, pool_id, match_no, round, status, ties_allowed)
          VALUES ($gameId, $tournamentId, $int4, $int4, $int4, $status, $bool)""".command

    private val insertSeat: Command[
      (GameId, TournamentId, Int, Int, EntrantId, Option[GameRoleId], Option[Int], Option[Instant], Boolean)
    ] =
        sql"""INSERT INTO tournament_seat (game_id, tournament_id, pool_id, match_no, entrant_id, game_role_id,
                                           choose_order, choose_started_at, forfeit)
          VALUES ($gameId, $tournamentId, $int4, $int4, $entrantId, ${gameRoleId.opt}, ${int4.opt}, ${instant.opt},
                  $bool)""".command

    /** Writes a round's matches and their seats. */
    def insert(matches: List[TournamentMatch]): IO[Unit] =
        matches.traverse_ { m =>
            session.execute(insertMatch)(
              (m.gameId, m.tournamentId, m.poolId, m.matchNo, m.round, m.status, m.tiesAllowed)
            ) *>
                m.seats.traverse_(s =>
                    session.execute(insertSeat)(
                      (
                        m.gameId,
                        m.tournamentId,
                        m.poolId,
                        m.matchNo,
                        s.entrantId,
                        s.gameRoleId,
                        s.chooseOrder,
                        s.chooseStartedAt,
                        s.forfeit
                      )
                    )
                )
        }

    private val matchColumns = sql"pool_id, match_no, round, status, ties_allowed, match_id"
    private val matchRow = int4 *: int4 *: int4 *: status *: bool *: matchId.opt

    private val selectOne: Query[Key, (Int, Int, Int, TournamentMatchStatus, Boolean, Option[MatchId])] =
        sql"""SELECT $matchColumns FROM tournament_match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4"""
            .query(matchRow)

    private val selectOneForUpdate: Query[Key, (Int, Int, Int, TournamentMatchStatus, Boolean, Option[MatchId])] =
        sql"""SELECT $matchColumns FROM tournament_match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4
          FOR UPDATE""".query(matchRow)

    private val selectRound
        : Query[(GameId, TournamentId, Int), (Int, Int, Int, TournamentMatchStatus, Boolean, Option[MatchId])] =
        sql"""SELECT $matchColumns FROM tournament_match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          ORDER BY pool_id, match_no""".query(matchRow)

    private val selectUnfinished
        : Query[(GameId, TournamentId), (Int, Int, Int, TournamentMatchStatus, Boolean, Option[MatchId])] =
        sql"""SELECT $matchColumns FROM tournament_match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND status <> 'done'
          ORDER BY pool_id, match_no""".query(matchRow)

    private val selectSeats: Query[
      Key,
      (EntrantId, Option[GameRoleId], Option[Int], Option[Instant], Option[Instant], Boolean, Option[ParticipantId])
    ] =
        sql"""SELECT entrant_id, game_role_id, choose_order, choose_started_at, chosen_at, forfeit, participant_id
          FROM tournament_seat
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4
          ORDER BY choose_order NULLS LAST, entrant_id""".query(
          entrantId *: gameRoleId.opt *: int4.opt *: instant.opt *: instant.opt *: bool *: participantId.opt
        )

    private def withSeats(game: GameId, tournament: TournamentId)(
        row: (Int, Int, Int, TournamentMatchStatus, Boolean, Option[MatchId])
    ): IO[TournamentMatch] = {
        val (pool, no, round, st, ties, id) = row
        session
            .execute(selectSeats)((game, tournament, pool, no))
            .map(_.map(TournamentSeat.apply.tupled))
            .map(TournamentMatch(game, tournament, pool, no, round, st, ties, id, _))
    }

    def read(game: GameId, tournament: TournamentId, pool: Int, no: Int): IO[Option[TournamentMatch]] =
        session.option(selectOne)((game, tournament, pool, no)).flatMap(_.traverse(withSeats(game, tournament)))

    /** As [[read]], locking the match row for the rest of the transaction: the claim on it. */
    def readForUpdate(game: GameId, tournament: TournamentId, pool: Int, no: Int): IO[Option[TournamentMatch]] =
        session
            .option(selectOneForUpdate)((game, tournament, pool, no))
            .flatMap(_.traverse(withSeats(game, tournament)))

    /** Every match of a round, with its seats. */
    def listRound(game: GameId, tournament: TournamentId, round: Int): IO[List[TournamentMatch]] =
        session.execute(selectRound)((game, tournament, round)).flatMap(_.traverse(withSeats(game, tournament)))

    /** Every match whose result is not yet recorded. */
    def listUnfinished(game: GameId, tournament: TournamentId): IO[List[TournamentMatch]] =
        session.execute(selectUnfinished)((game, tournament)).flatMap(_.traverse(withSeats(game, tournament)))

    private val updateStatus: Command[(TournamentMatchStatus, Option[MatchId], GameId, TournamentId, Int, Int)] =
        sql"""UPDATE tournament_match SET status = $status, match_id = ${matchId.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4""".command

    /** Moves a match on, recording the match it became once there is one. */
    def setStatus(
        game: GameId,
        tournament: TournamentId,
        pool: Int,
        no: Int,
        st: TournamentMatchStatus,
        id: Option[MatchId]
    ): IO[Unit] =
        session.execute(updateStatus)((st, id, game, tournament, pool, no)).void

    private val updateDone: Command[(GameId, TournamentId, Int, Int)] =
        sql"""UPDATE tournament_match SET status = 'done'
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4""".command

    /** Marks a match's result recorded, keeping the match it became. */
    def markDone(game: GameId, tournament: TournamentId, pool: Int, no: Int): IO[Unit] =
        session.execute(updateDone)((game, tournament, pool, no)).void

    private val updateSeat: Command[
      (
          Option[GameRoleId],
          Option[Instant],
          Option[Instant],
          Boolean,
          Option[ParticipantId],
          GameId,
          TournamentId,
          Int,
          Int,
          EntrantId
      )
    ] =
        sql"""UPDATE tournament_seat SET game_role_id = ${gameRoleId.opt}, choose_started_at = ${instant.opt},
                 chosen_at = ${instant.opt}, forfeit = $bool, participant_id = ${participantId.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND pool_id = $int4 AND match_no = $int4
            AND entrant_id = $entrantId""".command

    /** Rewrites a seat: its role, its choosing, its forfeit, and the match seat it became. */
    def updateSeat(game: GameId, tournament: TournamentId, pool: Int, no: Int, seat: TournamentSeat): IO[Unit] =
        session
            .execute(updateSeat)(
              (
                seat.gameRoleId,
                seat.chooseStartedAt,
                seat.chosenAt,
                seat.forfeit,
                seat.participantId,
                game,
                tournament,
                pool,
                no,
                seat.entrantId
              )
            )
            .void

    private val selectByMatch: Query[(GameId, MatchId), (TournamentId, Int, Int)] =
        sql"""SELECT tournament_id, pool_id, match_no FROM tournament_match
          WHERE game_id = $gameId AND match_id = $matchId""".query(tournamentId *: int4 *: int4)

    /** The tournament match a match is, if it is one. */
    def findByMatch(game: GameId, id: MatchId): IO[Option[(TournamentId, Int, Int)]] =
        session.option(selectByMatch)((game, id))

    // Seats whose turn it is to choose, for a player: the earliest unchosen seat of each choosing
    // match, played by the player now (a character entry by its character's current owner).
    private val selectChoosingFor: Query[PlayerId, (GameId, TournamentId, Int, Int)] =
        sql"""SELECT m.game_id, m.tournament_id, m.pool_id, m.match_no
          FROM tournament_match m
          JOIN tournament_seat s ON s.game_id = m.game_id AND s.tournament_id = m.tournament_id
                                AND s.pool_id = m.pool_id AND s.match_no = m.match_no
          JOIN entrant e ON e.game_id = s.game_id AND e.entrant_id = s.entrant_id
          LEFT JOIN character_entrant ce ON ce.game_id = e.game_id AND ce.entrant_id = e.entrant_id
          LEFT JOIN character c ON c.game_id = ce.game_id AND c.character_id = ce.character_id
          WHERE m.status = 'choosing' AND s.game_role_id IS NULL AND NOT s.forfeit
            AND COALESCE(c.player_id, CASE WHEN ce.entrant_id IS NULL THEN e.player_id END) = ${SkunkIdCodecs.playerId}
            AND s.choose_order = (SELECT min(o.choose_order) FROM tournament_seat o
                                   WHERE o.game_id = s.game_id AND o.tournament_id = s.tournament_id
                                     AND o.pool_id = s.pool_id AND o.match_no = s.match_no
                                     AND o.game_role_id IS NULL AND NOT o.forfeit)
          ORDER BY m.create_date""".query(gameId *: tournamentId *: int4 *: int4)

    /** The tournament matches waiting on this player to choose a role. */
    def choosingFor(player: PlayerId): IO[List[(GameId, TournamentId, Int, Int)]] =
        session.execute(selectChoosingFor)(player)
}
