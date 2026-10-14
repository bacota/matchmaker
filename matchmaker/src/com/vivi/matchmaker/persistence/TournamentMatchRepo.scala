package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import java.time.Instant
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** A tournament's matches as its rounds play them: `match` rows of its atomic pools, and their seats, which are
  * `participant` rows from the moment the pool is formed. A scheduled match (`S`) is not yet in a round, and none of
  * these lists it.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class TournamentMatchRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val matchId = SkunkIdCodecs.matchId
    private val instant = SkunkCodecs.instant

    private val status: Codec[TournamentMatchStatus] = text.imap {
        case "C" => TournamentMatchStatus.Choosing
        case "D" => TournamentMatchStatus.Due
        case "P" => TournamentMatchStatus.Started
        case _   => TournamentMatchStatus.Done
    } {
        case TournamentMatchStatus.Choosing => "C"
        case TournamentMatchStatus.Due      => "D"
        case TournamentMatchStatus.Started  => "P"
        case TournamentMatchStatus.Done     => "F"
    }

    private type MatchRow = (GameId, TournamentId, MatchId, Long, Int, TournamentMatchStatus, Boolean)
    private val matchRow = gameId *: tournamentId *: matchId *: int8 *: int4 *: status *: bool

    private type SeatRow = (MatchId, Long, Long, Option[Int], Option[Int], Option[Instant], Option[Instant], Boolean)
    private val seatRow =
        matchId *: int8 *: int8 *: int4.opt *: int4.opt *: instant.opt *: instant.opt *: bool

    private val seatColumns =
        "p.match_id, p.participant_id, p.entrant_id, p.game_role_id, p.choose_order, p.choose_started_at, p.chosen_at, p.is_forfeit"

    private def seatOf(r: SeatRow): TournamentSeat = {
        val (_, participant, entrant, role, order, started, chosen, forfeit) = r
        TournamentSeat(
          participantId = ParticipantId(participant),
          entrantId = EntrantId(entrant),
          gameRoleId = role.map(GameRoleId(_)),
          chooseOrder = order,
          chooseStartedAt = started,
          chosenAt = chosen,
          isForfeit = forfeit
        )
    }

    private def assemble(matches: List[MatchRow], seats: List[SeatRow]): List[TournamentMatch] = {
        val byMatch = seats.groupBy(_._1)
        matches.map { case (g, t, id, pool, round, st, ties) =>
            TournamentMatch(
              gameId = g,
              tournamentId = t,
              matchId = id,
              poolId = pool.toInt,
              round = round,
              status = st,
              canTie = ties,
              seats = byMatch.getOrElse(id, Nil).map(seatOf)
            )
        }
    }

    private val selectRound: Query[(GameId, TournamentId, Int), MatchRow] =
        sql"""SELECT game_id, tournament_id, match_id, pool_id, round, status, can_tie FROM match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4 AND status <> 'S'
          ORDER BY pool_id, create_date, match_id""".query(matchRow)

    private val selectOpen: Query[(GameId, TournamentId), MatchRow] =
        sql"""SELECT game_id, tournament_id, match_id, pool_id, round, status, can_tie FROM match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND status IN ('C', 'D', 'P')
          ORDER BY pool_id, create_date, match_id""".query(matchRow)

    private val selectSeatsOfTournament: Query[(GameId, TournamentId), SeatRow] =
        sql"""SELECT #$seatColumns FROM participant p
          WHERE p.game_id = $gameId AND p.tournament_id = $tournamentId
          ORDER BY p.participant_id""".query(seatRow)

    /** The matches of round `round`, with their seats. */
    def listRound(game: GameId, tournament: TournamentId, round: Int): IO[List[TournamentMatch]] =
        (
          session.execute(selectRound)((game, tournament, round)),
          session.execute(selectSeatsOfTournament)((game, tournament))
        )
            .mapN(assemble)

    /** The matches still to be played to an end: choosing, due, or being played. */
    def listUnfinished(game: GameId, tournament: TournamentId): IO[List[TournamentMatch]] =
        (session.execute(selectOpen)((game, tournament)), session.execute(selectSeatsOfTournament)((game, tournament)))
            .mapN(assemble)

    private val selectOne: Query[(GameId, MatchId), MatchRow] =
        sql"""SELECT game_id, tournament_id, match_id, pool_id, round, status, can_tie FROM match
          WHERE game_id = $gameId AND match_id = $matchId AND tournament_id IS NOT NULL AND status <> 'S'"""
            .query(matchRow)

    private val selectOneForUpdate: Query[(GameId, MatchId), MatchRow] =
        sql"""SELECT game_id, tournament_id, match_id, pool_id, round, status, can_tie FROM match
          WHERE game_id = $gameId AND match_id = $matchId AND tournament_id IS NOT NULL AND status <> 'S'
          FOR UPDATE""".query(matchRow)

    private val selectSeatsOfMatch: Query[(GameId, MatchId), SeatRow] =
        sql"""SELECT #$seatColumns FROM participant p
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id""".query(seatRow)

    private val selectSeatsOfMatchForUpdate: Query[(GameId, MatchId), SeatRow] =
        sql"""SELECT #$seatColumns FROM participant p
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id FOR UPDATE""".query(seatRow)

    /** A tournament match of a round, with its seats; `None` for one that is not a tournament's, or is not yet in a
      * round.
      */
    def read(game: GameId, id: MatchId): IO[Option[TournamentMatch]] =
        session
            .option(selectOne)((game, id))
            .flatMap(
              _.traverse(m =>
                  session.execute(selectSeatsOfMatch)((game, id)).map(seats => assemble(List(m), seats).head)
              )
            )

    /** As [[read]], locking the match and then its seats for the rest of the transaction. */
    def readForUpdate(game: GameId, id: MatchId): IO[Option[TournamentMatch]] =
        session
            .option(selectOneForUpdate)((game, id))
            .flatMap(
              _.traverse(m =>
                  session.execute(selectSeatsOfMatchForUpdate)((game, id)).map(seats => assemble(List(m), seats).head)
              )
            )

    private val updateSeatCommand: Command[
      (Option[Int], Option[Int], Option[Instant], Option[Instant], Boolean, GameId, ParticipantId)
    ] =
        sql"""UPDATE participant SET game_role_id = ${int4.opt}, choose_order = ${int4.opt},
                 choose_started_at = ${instant.opt}, chosen_at = ${instant.opt}, is_forfeit = $bool
          WHERE game_id = $gameId AND participant_id = ${SkunkIdCodecs.participantId}""".command

    /** Rewrites what a seat has chosen, and when, and whether it forfeits. */
    def updateSeat(game: GameId, seat: TournamentSeat): IO[Unit] =
        session
            .execute(updateSeatCommand)(
              (
                seat.gameRoleId.map(_.value),
                seat.chooseOrder,
                seat.chooseStartedAt,
                seat.chosenAt,
                seat.isForfeit,
                game,
                seat.participantId
              )
            )
            .void

    private val updateStatus: Command[(TournamentMatchStatus, GameId, MatchId)] =
        sql"UPDATE match SET status = $status WHERE game_id = $gameId AND match_id = $matchId".command

    /** Moves a match between choosing and due. Begun and over are the match's own to say, with its start and its end.
      */
    def setStatus(game: GameId, id: MatchId, to: TournamentMatchStatus): IO[Unit] =
        session.execute(updateStatus)((to, game, id)).void

    private val launchMatch: Command[
      (Instant, String, Option[Double], TimeLimitKind, TimeLimitUnit, Boolean, String, Boolean, GameId, MatchId)
    ] =
        sql"""UPDATE match SET status = 'P', start = $instant, description = $text,
                 time_limit = ${float8.opt} * INTERVAL '1 second', time_limit_kind = ${SkunkCodecs.timeLimitKind},
                 time_limit_unit = ${SkunkCodecs.timeLimitUnit}, is_friendly = $bool, settings = ${SkunkCodecs.jsonb},
                 is_live = $bool
          WHERE game_id = $gameId AND match_id = $matchId AND status = 'D'""".command

    /** A due match beginning: in progress from `at`, played under `clock`. Its engine's game is made next, after the
      * transaction that wrote this commits.
      */
    def launch(
        game: GameId,
        id: MatchId,
        at: Instant,
        description: String,
        limit: Option[java.time.Duration],
        kind: TimeLimitKind,
        unit: TimeLimitUnit,
        friendly: Boolean,
        settings: String,
        live: Boolean
    ): IO[Unit] =
        session
            .execute(launchMatch)(
              (at, description, limit.map(_.getSeconds.toDouble), kind, unit, friendly, settings, live, game, id)
            )
            .void

    private val relaunchable: Command[(GameId, MatchId)] =
        sql"""UPDATE match SET status = 'D', start = NULL, status_url = NULL, play_url = NULL, public_url = NULL
          WHERE game_id = $gameId AND match_id = $matchId AND status = 'P'""".command

    /** A match whose engine would not make its game, due again under the same id: the engine answers a create it has
      * already made with that game, so trying again is safe.
      */
    def backToDue(game: GameId, id: MatchId): IO[Unit] = session.execute(relaunchable)((game, id)).void

    private val launchSeatCommand: Command[(Long, Option[Int], Option[Int], GameId, ParticipantId)] =
        sql"""UPDATE participant SET player_id = $int8, elo_start = ${int4.opt}, elo_role_start = ${int4.opt}
          WHERE game_id = $gameId AND participant_id = ${SkunkIdCodecs.participantId}""".command

    /** A seat as its match begins: played by `player` -- a character entry's owner now -- rated as they are now. */
    def launchSeat(
        game: GameId,
        seat: ParticipantId,
        player: PlayerId,
        eloStart: Option[Int],
        eloRoleStart: Option[Int]
    ): IO[Unit] =
        session.execute(launchSeatCommand)((player.value, eloStart, eloRoleStart, game, seat)).void

    private val retireSeatCommand: Command[(Option[Int], GameId, ParticipantId)] =
        sql"""UPDATE participant SET game_role_id = COALESCE(game_role_id, ${int4.opt}), completed_at = now(),
                 is_pending = false, due = NULL
          WHERE game_id = $gameId AND participant_id = ${SkunkIdCodecs.participantId}""".command

    /** A seat that forfeits as its match begins: given a role nobody plays, if it had none, so that no seat of a match
      * being played lacks one, and finished with.
      */
    def retireSeat(game: GameId, seat: ParticipantId, role: Option[GameRoleId]): IO[Unit] =
        session.execute(retireSeatCommand)((role.map(_.value), game, seat)).void

    private val selectTournamentOf: Query[(GameId, MatchId), TournamentId] =
        sql"SELECT tournament_id FROM match WHERE game_id = $gameId AND match_id = $matchId AND tournament_id IS NOT NULL"
            .query(tournamentId)

    /** The tournament a match is played in, or `None` for a match that is not there. */
    def tournamentOf(game: GameId, id: MatchId): IO[Option[TournamentId]] =
        session.option(selectTournamentOf)((game, id))

    // Seats whose turn it is to choose, for a player: the earliest unchosen seat of each choosing match, whose
    // participant row names who plays it now.
    private val selectChoosingFor: Query[PlayerId, (GameId, TournamentId, Long)] =
        sql"""SELECT m.game_id, m.tournament_id, m.match_id
          FROM match m
          JOIN participant s ON s.game_id = m.game_id AND s.match_id = m.match_id
          WHERE m.status = 'C' AND s.game_role_id IS NULL AND NOT s.is_forfeit AND s.player_id = ${SkunkIdCodecs.playerId}
            AND s.choose_order = (SELECT min(o.choose_order) FROM participant o
                                   WHERE o.game_id = s.game_id AND o.match_id = s.match_id
                                     AND o.game_role_id IS NULL AND NOT o.is_forfeit)
          ORDER BY m.create_date""".query(gameId *: tournamentId *: int8)

    /** The tournament matches waiting on this player to choose a role. */
    def choosingFor(player: PlayerId): IO[List[(GameId, TournamentId, MatchId)]] =
        // The match id decoded as a number and wrapped: a trailing opaque-typed codec defeats the tuple's types.
        session.execute(selectChoosingFor)(player).map(_.map((g, t, id) => (g, t, MatchId(id))))
}
