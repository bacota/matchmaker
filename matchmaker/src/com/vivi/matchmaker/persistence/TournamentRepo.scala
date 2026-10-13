package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** Reads and writes `tournament` (V52): a tournament's settings, the model it was made from, and where it is.
  *
  * Its design is its `stage` rows ([[StageRepo]]) and its progress is rows of their own; the round being played, when
  * it began and when it must be over, is read from `round` through `current_round`, and written there by whoever starts
  * a round. Every change to a tournament reads it first under [[readForUpdate]]'s lock.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class TournamentRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val modelId = SkunkIdCodecs.tournamentModelId
    private val playerId = SkunkIdCodecs.playerId
    private val instant = SkunkCodecs.instant

    private val status: Codec[TournamentStatus] = text.imap(TournamentStatus.fromCode)(_.code)

    // round_duration is bound and read as a number of seconds, as MatchRepo does time_limit.
    private def seconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def duration(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    // What is written of a tournament, in the order of `insert` and `updateRow`.
    private type Written = (
        String,
        String,
        PlayerId,
        TournamentStatus,
        TournamentModelId,
        Int,
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Option[Int],
        Option[Int],
        Boolean,
        Int,
        Option[Double],
        Option[Int],
        Boolean,
        Option[Instant],
        Option[Instant],
        Option[Instant]
    )

    private val written: Codec[Written] =
        text *: text *: playerId *: status *: modelId *: int4 *: bool *: bool *: bool *: bool *: int4.opt *: int4.opt *:
            bool *: int4 *: float8.opt *: int4.opt *: bool *: instant.opt *: instant.opt *: instant.opt

    // What is read: the written columns, then the round being played's start and deadline.
    private type Row = (Written, Option[Instant], Option[Instant])
    private val row: Codec[Row] = written *: instant.opt *: instant.opt

    private def columnsOf(t: String): Fragment[Void] =
        sql"""#$t.name, #$t.description, #$t.owner, #$t.status, #$t.tournament_model_id, #$t.default_pool_size,
              #$t.automatic_rounds, #$t.friendly, #$t.invitational, #$t.late_entries, #$t.min_rating, #$t.max_rating,
              #$t.public, #$t.entries_per_player, EXTRACT(EPOCH FROM #$t.round_duration)::float8, #$t.max_turns,
              #$t.concurrent_rounds, #$t.started_at, #$t.ended_at, #$t.checked_at,
              (SELECT r.start_date FROM round r
                WHERE r.game_id = #$t.game_id AND r.tournament_id = #$t.tournament_id AND r.round = #$t.current_round),
              (SELECT r.deadline FROM round r
                WHERE r.game_id = #$t.game_id AND r.tournament_id = #$t.tournament_id AND r.round = #$t.current_round)"""

    private val columns = columnsOf("tournament")

    private def writtenOf(t: Tournament): Written =
        (
          t.name,
          t.description,
          t.owner,
          t.status,
          t.tournamentModelId,
          t.defaultPoolSize,
          t.settings.autoRounds,
          t.settings.friendly,
          t.settings.invitational,
          t.settings.lateEntries,
          t.settings.minRating,
          t.settings.maxRating,
          t.settings.isPublic,
          t.settings.entriesPerPlayer,
          seconds(t.settings.roundDuration),
          t.settings.maxTurns,
          t.settings.concurrentRounds,
          t.startedAt,
          t.endedAt,
          t.checkedAt
        )

    private def tournamentOf(game: GameId, id: TournamentId, r: Row): Tournament = {
        val (w, roundStartedAt, roundDeadline) = r
        val (
          name,
          description,
          owner,
          st,
          model,
          defaultPoolSize,
          auto,
          friendly,
          invitational,
          late,
          minRating,
          maxRating,
          isPublic,
          entries,
          roundSeconds,
          maxTurns,
          concurrentRounds,
          startedAt,
          endedAt,
          checkedAt
        ) = w
        Tournament(
          game,
          id,
          name,
          description,
          owner,
          model,
          defaultPoolSize,
          TournamentSettings(
            auto,
            friendly,
            invitational,
            late,
            minRating,
            maxRating,
            entries,
            duration(roundSeconds),
            maxTurns,
            concurrentRounds,
            isPublic
          ),
          st,
          startedAt,
          endedAt,
          roundStartedAt,
          roundDeadline,
          checkedAt
        )
    }

    // first_stage_id is written as a placeholder, and set by StageRepo.copy before the transaction commits: the two
    // reference each other, and the key between them is deferred.
    private val insert: Query[GameId *: Written, TournamentId] =
        sql"""INSERT INTO tournament (game_id, name, description, owner, status, tournament_model_id, default_pool_size,
                                      automatic_rounds, friendly, invitational, late_entries, min_rating, max_rating,
                                      public, entries_per_player, round_duration, max_turns, concurrent_rounds,
                                      started_at, ended_at, checked_at, first_stage_id)
          VALUES ($gameId, $text, $text, $playerId, $status, $modelId, $int4, $bool, $bool, $bool, $bool, ${int4.opt},
                  ${int4.opt}, $bool, $int4, ${float8.opt} * INTERVAL '1 second', ${int4.opt}, $bool, ${instant.opt},
                  ${instant.opt}, ${instant.opt}, 0)
          RETURNING tournament_id""".query(tournamentId)

    /** Writes a new tournament. Its stages are written next, by [[StageRepo.copy]], in the same transaction. */
    def create(t: Tournament): IO[Tournament] =
        session.unique(insert)(t.gameId *: writtenOf(t)).map(id => t.copy(tournamentId = id))

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

    private val updateRow: Command[Tuple.Concat[Written, (GameId, TournamentId)]] =
        sql"""UPDATE tournament SET name = $text, description = $text, owner = $playerId, status = $status,
                 tournament_model_id = $modelId, default_pool_size = $int4, automatic_rounds = $bool, friendly = $bool,
                 invitational = $bool, late_entries = $bool, min_rating = ${int4.opt}, max_rating = ${int4.opt},
                 public = $bool, entries_per_player = $int4, round_duration = ${float8.opt} * INTERVAL '1 second',
                 max_turns = ${int4.opt}, concurrent_rounds = $bool, started_at = ${instant.opt},
                 ended_at = ${instant.opt}, checked_at = ${instant.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    /** Rewrites the tournament's own columns. Not the round being played, which is the round's to say. */
    def update(t: Tournament): IO[Unit] =
        session.execute(updateRow)(writtenOf(t) ++ (t.gameId, t.tournamentId)).void

    /** The state as a whole, as the rules hold it: read and written from its tables from Phase 5 of
      * design/new-tournament-plan.md. Until then there is none to read.
      */
    def readState(game: GameId, id: TournamentId): IO[Option[com.vivi.matchmaker.model.tournament.TournamentState]] =
        IO.raiseError(new UnsupportedOperationException("a tournament's state is read from its tables (Phase 5)"))

    def writeState(
        game: GameId,
        id: TournamentId,
        state: com.vivi.matchmaker.model.tournament.TournamentState
    ): IO[Unit] =
        IO.raiseError(new UnsupportedOperationException("a tournament's state is written to its tables (Phase 5)"))

    private val selectForGame: Query[GameId, TournamentId *: Row] =
        sql"""SELECT tournament_id, $columns FROM tournament WHERE game_id = $gameId
          ORDER BY create_date DESC, tournament_id DESC""".query(tournamentId *: row)

    /** Every tournament of a game, newest first. */
    def listForGame(game: GameId): IO[List[Tournament]] =
        session.execute(selectForGame)(game).map(_.map { case id *: r => tournamentOf(game, id, r) })

    // A player's tournaments: owned, entered (with any entry, withdrawn or not), or invited to.
    private val selectForPlayer: Query[(PlayerId, PlayerId, PlayerId), GameId *: TournamentId *: Row] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")} FROM tournament t
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

    // What a list shows beside each tournament, for a viewer: its game's name, its owner's nickname, how many
    // entries are still in it, whether the viewer has an entry or an invitation, and whether it is under way
    // with every match of its round over -- waiting, unless its rounds start themselves, on its owner.
    private val summaryColumns =
        sql"""g.display_name, o.nickname,
              (SELECT count(*) FROM entrant e
                WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.withdrawn_at IS NULL),
              EXISTS (SELECT 1 FROM entrant e
                       WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.player_id = $playerId),
              EXISTS (SELECT 1 FROM tournament_invitation i
                       WHERE i.game_id = t.game_id AND i.tournament_id = t.tournament_id AND i.player_id = $playerId),
              t.status = 'running' AND t.current_round IS NOT NULL AND NOT EXISTS (SELECT 1 FROM match m
                       WHERE m.game_id = t.game_id AND m.tournament_id = t.tournament_id
                         AND m.round = t.current_round AND m.status <> 'F')"""

    private val summaryRow = text *: text *: int8 *: bool *: bool *: bool

    // The row decodes as one element, its summary as six more: (game, id, row, game name, owner, entries,
    // entered, invited, round over).
    private type SummaryRow = (GameId, TournamentId, Row, String, String, Long, Boolean, Boolean, Boolean)
    private val summaryCodec: Codec[SummaryRow] = gameId *: tournamentId *: row *: summaryRow

    private def summaryOf(viewer: PlayerId)(s: SummaryRow): TournamentSummary = {
        val (game, id, r, gameName, owner, entrants, entered, invited, roundOver) = s
        val t = tournamentOf(game, id, r)
        TournamentSummary(t, gameName, owner, entrants.toInt, t.owner == viewer, entered, invited, roundOver)
    }

    private val selectSummariesForGame: Query[((PlayerId, PlayerId), GameId), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM tournament t JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE t.game_id = $gameId
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(summaryCodec)

    private val selectSummariesForPlayer: Query[((PlayerId, PlayerId), PlayerId, PlayerId, PlayerId), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM tournament t JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE t.owner = $playerId
             OR EXISTS (SELECT 1 FROM entrant e
                         WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.player_id = $playerId)
             OR EXISTS (SELECT 1 FROM tournament_invitation i
                         WHERE i.game_id = t.game_id AND i.tournament_id = t.tournament_id AND i.player_id = $playerId)
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(summaryCodec)

    private val selectSummary: Query[((PlayerId, PlayerId), GameId, TournamentId), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM tournament t JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId""".query(summaryCodec)

    /** A game's tournaments as `viewer` sees them in a list, newest first. */
    def summariesForGame(game: GameId, viewer: PlayerId): IO[List[TournamentSummary]] =
        session.execute(selectSummariesForGame)(((viewer, viewer), game)).map(_.map(summaryOf(viewer)))

    /** The tournaments `viewer` owns, has entered or is invited to, in every game, newest first. */
    def summariesForPlayer(viewer: PlayerId): IO[List[TournamentSummary]] =
        session
            .execute(selectSummariesForPlayer)(((viewer, viewer), viewer, viewer, viewer))
            .map(_.map(summaryOf(viewer)))

    /** One tournament as `viewer` sees it in a list. */
    def summary(game: GameId, id: TournamentId, viewer: PlayerId): IO[Option[TournamentSummary]] =
        session.option(selectSummary)(((viewer, viewer), game, id)).map(_.map(summaryOf(viewer)))
}
