package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import upickle.default.{read => readJson, write => writeJson}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{TournamentCodecs, TournamentDesign, TournamentState}

/** Reads and writes `tournament` (V52): a tournament's settings and design, and, separately, its state.
  *
  * The state is read and written whole, as the JSON `TournamentCodecs` makes of it, and only ever under
  * [[readForUpdate]]'s lock: every change to a tournament's progress is one transaction that reads the state, works out
  * the next one, and writes it back.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class TournamentRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val playerId = SkunkIdCodecs.playerId
    private val instant = SkunkCodecs.instant
    private val jsonb = SkunkCodecs.jsonb

    private val status: Codec[TournamentStatus] = text.imap(TournamentStatus.fromCode)(_.code)

    private def designJson(d: TournamentDesign): String = writeJson(d)(using TournamentCodecs.designRW)
    private def designOf(json: String): TournamentDesign = readJson(json)(using TournamentCodecs.designRW)

    // round_duration is bound and read as a number of seconds, as MatchRepo does time_limit.
    private def seconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def duration(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    private type Row = (
        String,
        String,
        PlayerId,
        String,
        Option[String],
        TournamentStatus,
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Option[Int],
        Option[Int],
        Int,
        Option[Double],
        Option[Int],
        Option[Instant],
        Option[Instant],
        Option[Instant],
        Option[Instant],
        Option[Instant]
    )

    private val row: Codec[Row] =
        text *: text *: playerId *: jsonb *: text.opt *: status *: bool *: bool *: bool *: bool *: int4.opt *: int4.opt *:
            int4 *: float8.opt *: int4.opt *: instant.opt *: instant.opt *: instant.opt *: instant.opt *: instant.opt

    private val columns =
        sql"""name, description, owner, design, standard_design, status, auto_rounds, friendly, invitational,
              late_entries, min_rating, max_rating, entries_per_player,
              EXTRACT(EPOCH FROM round_duration)::float8, max_turns, started_at, ended_at, round_started_at,
              round_deadline, checked_at"""

    private def rowOf(t: Tournament): Row =
        (
          t.name,
          t.description,
          t.owner,
          designJson(t.design),
          t.standardDesign,
          t.status,
          t.settings.autoRounds,
          t.settings.friendly,
          t.settings.invitational,
          t.settings.lateEntries,
          t.settings.minRating,
          t.settings.maxRating,
          t.settings.entriesPerPlayer,
          seconds(t.settings.roundDuration),
          t.settings.maxTurns,
          t.startedAt,
          t.endedAt,
          t.roundStartedAt,
          t.roundDeadline,
          t.checkedAt
        )

    private def tournamentOf(game: GameId, id: TournamentId, r: Row): Tournament = {
        val (
          name,
          description,
          owner,
          design,
          standard,
          st,
          auto,
          friendly,
          invitational,
          late,
          minRating,
          maxRating,
          entries,
          roundSeconds,
          maxTurns,
          startedAt,
          endedAt,
          roundStartedAt,
          roundDeadline,
          checkedAt
        ) = r
        Tournament(
          game,
          id,
          name,
          description,
          owner,
          designOf(design),
          standard,
          TournamentSettings(
            auto,
            friendly,
            invitational,
            late,
            minRating,
            maxRating,
            entries,
            duration(roundSeconds),
            maxTurns
          ),
          st,
          startedAt,
          endedAt,
          roundStartedAt,
          roundDeadline,
          checkedAt
        )
    }

    private val insert: Query[GameId *: Row, TournamentId] =
        sql"""INSERT INTO tournament (game_id, name, description, owner, design, standard_design, status, auto_rounds,
                                      friendly, invitational, late_entries, min_rating, max_rating, entries_per_player,
                                      round_duration, max_turns, started_at, ended_at, round_started_at, round_deadline,
                                      checked_at)
          VALUES ($gameId, $text, $text, $playerId, $jsonb, ${text.opt}, $status, $bool, $bool, $bool, $bool,
                  ${int4.opt}, ${int4.opt}, $int4, ${float8.opt} * INTERVAL '1 second', ${int4.opt}, ${instant.opt},
                  ${instant.opt}, ${instant.opt}, ${instant.opt}, ${instant.opt})
          RETURNING tournament_id""".query(tournamentId)

    def create(t: Tournament): IO[Tournament] =
        session.unique(insert)(t.gameId *: rowOf(t)).map(id => t.copy(tournamentId = id))

    private val select: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId".query(row)

    private val selectForUpdate: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId FOR UPDATE"
            .query(row)

    def read(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(select)((game, id)).map(_.map(tournamentOf(game, id, _)))

    /** As [[read]], locking the tournament for the rest of the transaction: what every change to it reads first. */
    def readForUpdate(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(selectForUpdate)((game, id)).map(_.map(tournamentOf(game, id, _)))

    private val updateRow: Command[Tuple.Concat[Row, (GameId, TournamentId)]] =
        sql"""UPDATE tournament SET name = $text, description = $text, owner = $playerId, design = $jsonb,
                 standard_design = ${text.opt}, status = $status, auto_rounds = $bool, friendly = $bool,
                 invitational = $bool, late_entries = $bool, min_rating = ${int4.opt}, max_rating = ${int4.opt},
                 entries_per_player = $int4, round_duration = ${float8.opt} * INTERVAL '1 second',
                 max_turns = ${int4.opt}, started_at = ${instant.opt}, ended_at = ${instant.opt},
                 round_started_at = ${instant.opt}, round_deadline = ${instant.opt}, checked_at = ${instant.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    def update(t: Tournament): IO[Unit] =
        session.execute(updateRow)(rowOf(t) ++ (t.gameId, t.tournamentId)).void

    private val selectState: Query[(GameId, TournamentId), Option[String]] =
        sql"SELECT state FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId".query(jsonb.opt)

    /** The tournament's state, or `None` before it has started. */
    def readState(game: GameId, id: TournamentId): IO[Option[TournamentState]] =
        session
            .option(selectState)((game, id))
            .map(_.flatten.map(json => readJson[TournamentState](json)(using TournamentCodecs.stateRW)))

    private val updateState: Command[(String, GameId, TournamentId)] =
        sql"UPDATE tournament SET state = $jsonb WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    def writeState(game: GameId, id: TournamentId, state: TournamentState): IO[Unit] =
        session.execute(updateState)((writeJson(state)(using TournamentCodecs.stateRW), game, id)).void

    private val selectForGame: Query[GameId, TournamentId *: Row] =
        sql"""SELECT tournament_id, $columns FROM tournament WHERE game_id = $gameId
          ORDER BY create_date DESC, tournament_id DESC""".query(tournamentId *: row)

    /** Every tournament of a game, newest first. */
    def listForGame(game: GameId): IO[List[Tournament]] =
        session.execute(selectForGame)(game).map(_.map { case id *: r => tournamentOf(game, id, r) })

    // A player's tournaments: owned, entered (with any entry, withdrawn or not), or invited to.
    private val selectForPlayer: Query[(PlayerId, PlayerId, PlayerId), GameId *: TournamentId *: Row] =
        sql"""SELECT t.game_id, t.tournament_id, $columns FROM tournament t
          WHERE t.owner = $playerId
             OR EXISTS (SELECT 1 FROM entrant e
                         WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.player_id = $playerId)
             OR EXISTS (SELECT 1 FROM tournament_invitation i
                         WHERE i.game_id = t.game_id AND i.tournament_id = t.tournament_id AND i.player_id = $playerId)
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(gameId *: tournamentId *: row)

    /** The tournaments a player owns, has entered or is invited to, in any game, newest first. */
    def listForPlayer(player: PlayerId): IO[List[Tournament]] =
        session
            .execute(selectForPlayer)((player, player, player))
            .map(_.map { case g *: id *: r => tournamentOf(g, id, r) })
}
