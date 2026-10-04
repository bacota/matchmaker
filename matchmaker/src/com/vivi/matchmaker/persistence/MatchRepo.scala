package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import skunk.data.Arr
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._
import MatchRepo.{CompletedSpan, GameParameterRow, MatchClockRow, MatchSeatRow}

class MatchRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId
    private val challengeId = SkunkIdCodecs.challengeId
    private val instant = SkunkCodecs.instant
    private val settings: Codec[String] = SkunkCodecs.jsonb
    private val timeLimitKind = SkunkCodecs.timeLimitKind
    private val timeLimitUnit = SkunkCodecs.timeLimitUnit

    // time_limit is bound/read as a second count rather than via a custom INTERVAL codec.
    private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    private val insertMatch: Command[
      (
          GameId,
          MatchId,
          ChallengeId,
          String,
          Option[Instant],
          Boolean,
          Instant,
          Option[Double],
          String,
          Boolean,
          Option[String],
          Option[String],
          Option[String],
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean
      )
    ] =
        sql"""INSERT INTO match (game_id, match_id, challenge_id, description, completed, cancelled, start, time_limit,
                             settings, public, status_url, play_url, public_url, time_limit_kind, time_limit_unit, live,
                             friendly)
          VALUES ($gameId, $matchId, $challengeId, $text, ${instant.opt}, $bool, $instant, ${float8.opt} * INTERVAL '1 second',
                  $settings, $bool, ${text.opt}, ${text.opt}, ${text.opt}, $timeLimitKind, $timeLimitUnit, $bool,
                  $bool)""".command

    private type MatchRow =
        (
            ChallengeId,
            String,
            Option[Instant],
            Boolean,
            Instant,
            Option[Double],
            String,
            Boolean,
            Option[String],
            Option[String],
            Option[String],
            TimeLimitKind,
            TimeLimitUnit,
            Boolean,
            Boolean,
            Option[Instant],
            Boolean
        )

    // The last two are the archive's (V38), read here and never written: `ArchiveRepo` owns them.
    private val matchRow: Codec[MatchRow] =
        challengeId *: text *: instant.opt *: bool *: instant *: float8.opt *: settings *: bool *: text.opt *: text.opt *:
            text.opt *: timeLimitKind *: timeLimitUnit *: bool *: bool *: instant.opt *: bool

    private val selectMatch: Query[(GameId, MatchId), MatchRow] =
        sql"""SELECT challenge_id, description, completed, cancelled, start,
                 EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url, time_limit_kind, time_limit_unit, live, friendly,
                 archived_at, archive_expired_at IS NOT NULL
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId"""
            .query(matchRow)

    /* As selectMatch, but holding the row until the transaction ends. Used by the game-engine
     * callbacks, which read a match, decide from it, and write it back — a concurrent callback for
     * the same match would otherwise be able to interleave between the two. */
    private val selectMatchForUpdate: Query[(GameId, MatchId), MatchRow] =
        sql"""SELECT challenge_id, description, completed, cancelled, start,
                 EXTRACT(EPOCH FROM time_limit)::float8, settings,
                 public, status_url, play_url, public_url, time_limit_kind, time_limit_unit, live, friendly,
                 archived_at, archive_expired_at IS NOT NULL
          FROM match
          WHERE game_id = $gameId AND match_id = $matchId FOR UPDATE"""
            .query(matchRow)

    // challenge_id is not updatable: a match is started from one challenge and stays that
    // challenge's match. Changing it would rewrite who created the match.
    private val updateMatch: Command[
      (
          String,
          Option[Instant],
          Boolean,
          Instant,
          Option[Double],
          String,
          Boolean,
          Option[String],
          Option[String],
          Option[String],
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          GameId,
          MatchId
      )
    ] =
        sql"""UPDATE match SET description = $text, completed = ${instant.opt}, cancelled = $bool, start = $instant,
          time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool, status_url = ${text.opt}, play_url = ${text.opt}, public_url = ${text.opt},
          time_limit_kind = $timeLimitKind, time_limit_unit = $timeLimitUnit, friendly = $bool
          WHERE game_id = $gameId AND match_id = $matchId""".command

    def create(m: Match): IO[Match] =
        session
            .execute(insertMatch)(
              (
                m.gameId,
                m.matchId,
                m.challengeId,
                m.description,
                m.completedAt,
                m.cancelled,
                m.start,
                toSeconds(m.timeLimit),
                m.settings,
                m.isPublic,
                m.statusUrl,
                m.playUrl,
                m.publicUrl,
                m.timeLimitKind,
                m.timeLimitUnit,
                m.live,
                m.friendly
              )
            )
            .as(m)

    private def toMatch(gameId: GameId, matchId: MatchId, row: MatchRow): Match = {
        val (
          challengeId,
          description,
          completedAt,
          cancelled,
          start,
          timeLimitSeconds,
          settings,
          isPublic,
          statusUrl,
          playUrl,
          publicUrl,
          timeLimitKind,
          timeLimitUnit,
          live,
          friendly,
          archivedAt,
          archiveExpired
        ) = row
        Match(
          gameId,
          matchId,
          challengeId,
          description,
          completedAt,
          start,
          fromSeconds(timeLimitSeconds),
          settings,
          isPublic,
          cancelled,
          statusUrl,
          playUrl,
          publicUrl,
          timeLimitKind,
          timeLimitUnit,
          live,
          friendly,
          archivedAt,
          archiveExpired
        )
    }

    def read(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
        session.option(selectMatch)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

    /** As `read`, but locking the row for the rest of the transaction. */
    def readForUpdate(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
        session.option(selectMatchForUpdate)((gameId, matchId)).map(_.map(toMatch(gameId, matchId, _)))

    def update(m: Match): IO[Unit] =
        session
            .execute(updateMatch)(
              (
                m.description,
                m.completedAt,
                m.cancelled,
                m.start,
                toSeconds(m.timeLimit),
                m.settings,
                m.isPublic,
                m.statusUrl,
                m.playUrl,
                m.publicUrl,
                m.timeLimitKind,
                m.timeLimitUnit,
                m.friendly,
                m.gameId,
                m.matchId
              )
            )
            .void

    private val updateUrls: Command[(Option[String], Option[String], Option[String], Option[String], GameId, MatchId)] =
        sql"""UPDATE match SET status_url = ${text.opt}, play_url = ${text.opt}, public_url = ${text.opt},
                 cancel_url = ${text.opt}
          WHERE game_id = $gameId AND match_id = $matchId""".command

    /** Sets the three urls the engine answered a create with, and nothing else.
      *
      * Not [[update]], which writes the whole row from the `Match` it is given: the start that calls this holds no lock
      * across the engine call, and its `Match` is the one it wrote before making it. Anything changed meanwhile — a
      * game's admin saying the match is not friendly, its creator cancelling it — would be written back over.
      */
    def setUrls(m: Match, cancelUrl: Option[String] = None): IO[Unit] =
        session.execute(updateUrls)((m.statusUrl, m.playUrl, m.publicUrl, cancelUrl, m.gameId, m.matchId)).void

    /* now() rather than a time bound from Scala: the completion time is a fact about when the
     * database recorded the match as over, and the application's clock is not the same clock. It
     * returns what was stored so the caller does not have to read the row back to find out. */
    private val completeMatch: Query[(GameId, MatchId), Instant] =
        sql"""UPDATE match SET completed = now()
          WHERE game_id = $gameId AND match_id = $matchId
          RETURNING completed"""
            .query(instant)

    private val updateResultSummary: Command[(String, GameId, MatchId)] =
        sql"UPDATE match SET result_summary = $text WHERE game_id = $gameId AND match_id = $matchId".command

    /** Records how the match came out, as its engine said it: HTML already cleaned by `SummaryHtml.accept`. */
    def setResultSummary(gameId: GameId, matchId: MatchId, summary: String): IO[Unit] =
        session.execute(updateResultSummary)((summary, gameId, matchId)).void

    /** Marks a match completed, as of the database's clock, and returns when that was.
      *
      * Overwrites an existing completion time, so callers that mean "complete it if it is not already" must check first
      * — `GameEngineService` does, under the row lock `readForUpdate` takes, which is also what makes the
      * read-then-write here safe.
      */
    private val moveSequenceForUpdateQuery: Query[(GameId, MatchId), Option[Long]] =
        sql"""SELECT move_sequence FROM match WHERE game_id = $gameId AND match_id = $matchId FOR UPDATE"""
            .query(int8.opt)

    /** The latest move sequence applied to this match's seats (V26), locked with the row. `None` when no numbered move
      * or status has been applied, and also when there is no such match — the callers have already required it.
      */
    def moveSequenceForUpdate(gameId: GameId, matchId: MatchId): IO[Option[Long]] =
        session.option(moveSequenceForUpdateQuery)((gameId, matchId)).map(_.flatten)

    private val advanceMoveSequenceCommand: Command[(Long, Long, GameId, MatchId)] =
        sql"""UPDATE match SET move_sequence = GREATEST(COALESCE(move_sequence, $int8), $int8)
          WHERE game_id = $gameId AND match_id = $matchId""".command

    /** Raises the match's move sequence to `sequence`, never lowering it: a number that arrives late is already
      * accounted for by the one above it.
      */
    def advanceMoveSequence(gameId: GameId, matchId: MatchId, sequence: Long): IO[Unit] =
        session.execute(advanceMoveSequenceCommand)((sequence, sequence, gameId, matchId)).void

    def complete(gameId: GameId, matchId: MatchId): IO[Instant] =
        session.unique(completeMatch)((gameId, matchId))

    private val deleteMatch: Command[(GameId, MatchId)] =
        sql"DELETE FROM match WHERE game_id = $gameId AND match_id = $matchId".command

    /** Removes a match. Only used to undo a match whose game the engine failed to create — a played match is completed,
      * never deleted, and its participants would block this anyway.
      */
    def delete(gameId: GameId, matchId: MatchId): IO[Unit] =
        session.execute(deleteMatch)((gameId, matchId)).void

    private val playerId = SkunkIdCodecs.playerId

    // participant_id and character_id are decoded as raw int8 and wrapped below, because a
    // trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from outside
    // Ids.scala -- and the decoded tuple has to reduce to a plain tuple for the mapping to work.
    // character_id is nullable: a 'P'-type game's participant has no character_participant row.
    private val seatRow =
        gameId *: matchId *: text *: text *: instant.opt *: bool *: bool *: instant *: float8.opt *: timeLimitKind *:
            timeLimitUnit *: bool *: int8 *: int8.opt *: bool *: instant.opt *: text *: bool *: bool *: instant.opt *:
            text.opt *: bool *: instant.opt *: bool *: settings *: text.opt

    private def toSeatRow(
        row: (
            GameId,
            MatchId,
            String,
            String,
            Option[Instant],
            Boolean,
            Boolean,
            Instant,
            Option[Double],
            TimeLimitKind,
            TimeLimitUnit,
            Boolean,
            Long,
            Option[Long],
            Boolean,
            Option[Instant],
            String,
            Boolean,
            Boolean,
            Option[Instant],
            Option[String],
            Boolean,
            Option[Instant],
            Boolean,
            String,
            Option[String]
        )
    ): MatchSeatRow = {
        val (
          gameId,
          matchId,
          gameName,
          description,
          completedAt,
          cancelled,
          isCreator,
          start,
          timeLimitSeconds,
          timeLimitKind,
          timeLimitUnit,
          live,
          callerParticipantId,
          callerCharacterId,
          callerPending,
          callerDue,
          seatNickname,
          seatPending,
          seatCompleted,
          seatDue,
          publicUrl,
          friendly,
          archivedAt,
          archiveExpired,
          matchSettings,
          resultSummary
        ) = row
        MatchSeatRow(
          gameId,
          matchId,
          gameName,
          description,
          completedAt,
          cancelled,
          isCreator,
          start,
          fromSeconds(timeLimitSeconds),
          timeLimitKind,
          timeLimitUnit,
          live,
          ParticipantId(callerParticipantId),
          callerCharacterId.map(CharacterId.apply),
          callerPending,
          callerDue,
          seatNickname,
          seatPending,
          seatCompleted,
          seatDue,
          publicUrl,
          friendly,
          archivedAt,
          archiveExpired,
          matchSettings,
          resultSummary
        )
    }

    /* The columns and joins every match list shares. Only the WHERE and the ORDER BY differ
     * between the three below, and they are written out in each rather than assembled from
     * fragments: a query that has to be pieced together to be read is harder to check against the
     * plan the database actually runs. */
    private val selectActiveForPlayer =
        sql"""SELECT m.game_id, m.match_id, g.display_name, m.description, m.completed, m.cancelled,
                 ch.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind, m.time_limit_unit, m.live,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed_at IS NOT NULL, seat.due,
                 -- Where anyone may watch, for a match created public: the engine issues one only
                 -- then, so it is null for every private match and is the field a Watch link
                 -- needs. The same column on every list, because who may watch does not depend on
                 -- which list the match is being read for.
                 m.public_url, m.friendly, m.archived_at, m.archive_expired_at IS NOT NULL, m.settings,
                 m.result_summary
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          -- The challenge the match was started from, which is never deleted: its challenger is
          -- the match's creator, and comparing them here is what tells this player whether the
          -- match is theirs to cancel.
          JOIN challenge ch ON ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          -- Everyone in the match, the caller included. This is what multiplies the rows, and
          -- what lets a caller say who is playing and who is waited on without asking again.
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND m.completed IS NULL AND NOT m.cancelled
          -- Ordered by the caller's own deadline, most urgent first, with NULLS LAST so matches
          -- with no deadline do not crowd out ones that have one; then by seat, so a match's
          -- rows arrive together and in a stable order.
          ORDER BY p.due ASC NULLS LAST, m.start DESC, m.match_id, seat.participant_id"""
            .query(seatRow)

    /* One window of the player's finished matches: the seats they finished with from `from`, up to
     * but not including `until` -- by the seat's own
     * `completed_at` (V41), and in one game where `game` names one. Served by the index on
     * participant(player_id, completed_at).
     *
     * Not a cancelled match: one called off was never finished, and is not listed as though it had
     * been. Its seats are retired, so it is not among the running matches either.
     *
     * Most recently finished first -- a history read from the top. */
    private val selectOverForPlayer =
        sql"""SELECT m.game_id, m.match_id, g.display_name, m.description, m.completed, m.cancelled,
                 ch.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind, m.time_limit_unit, m.live,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed_at IS NOT NULL, seat.due,
                 -- Where anyone may watch, for a match created public: the engine issues one only
                 -- then, so it is null for every private match and is the field a Watch link
                 -- needs. The same column on every list, because who may watch does not depend on
                 -- which list the match is being read for.
                 m.public_url, m.friendly, m.archived_at, m.archive_expired_at IS NOT NULL, m.settings,
                 m.result_summary
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN challenge ch ON ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND p.game_id = COALESCE(${gameId.opt}, p.game_id)
            AND p.completed_at >= $instant AND p.completed_at < $instant
            AND NOT m.cancelled
          ORDER BY p.completed_at DESC, m.start DESC, m.match_id, seat.participant_id"""
            .query(seatRow)

    /* The same two lists, for a player who is not the caller: only the matches marked public.
     *
     * `m.public` is set when the match is started, from the challenge it was started from -- the
     * "Public" box on the challenge form -- and it is the player's own statement that this match may
     * be looked at by anybody. So it is the whole of the visibility rule here, and it is applied in
     * the WHERE rather than by the caller: a filter in Scala would mean the private matches were
     * fetched, and a list that is filtered after it is read is one refactor away from being
     * returned unfiltered.
     *
     * Written out rather than folded into the two queries above with an `AND ($bool OR m.public)`.
     * The columns are the same and the duplication is real, but the alternative is a flag that
     * decides who may see the rows, sitting in the middle of a query that is read for its columns --
     * and a caller that passes the wrong one has a leak rather than a wrong list. Two queries cannot
     * be called with the wrong argument.
     *
     * The caller-relative columns are still relative to the player being asked about: `p.pending` is
     * whether it is their turn, and `ch.challenger = p.player_id` whether the match is theirs. */
    private val selectPublicActiveForPlayer =
        sql"""SELECT m.game_id, m.match_id, g.display_name, m.description, m.completed, m.cancelled,
                 ch.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind, m.time_limit_unit, m.live,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed_at IS NOT NULL, seat.due,
                 -- Where anyone may watch, for a match created public: the engine issues one only
                 -- then, so it is null for every private match and is the field a Watch link
                 -- needs. The same column on every list, because who may watch does not depend on
                 -- which list the match is being read for.
                 m.public_url, m.friendly, m.archived_at, m.archive_expired_at IS NOT NULL, m.settings,
                 m.result_summary
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN challenge ch ON ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          -- The seat, and only the seat: `p` is the player being asked about, and `p.completed` is
          -- whether *their* part is over. A player who is out of a match that is still running has
          -- finished with it, and it belongs among what they have played rather than among what they
          -- are playing.
          --
          -- Nothing about the match is consulted, so a seat is in at most one of these two lists and
          -- `completed_at` alone decides which. That puts the weight on it being kept: a match ending
          -- retires its seats through the engine's status (`applyEngineStatus`) and a cancel retires
          -- them outright (V15), which is what makes it the whole answer -- and keeps a cancelled match
          -- out of this list, as the next one leaves it out on purpose.
          -- Supported by the index on participant(player_id, completed_at) -- V41.
          WHERE p.player_id = $playerId AND m.public AND p.completed_at IS NULL
          -- Not by the caller's deadline, which is nothing to a reader who is not in the match:
          -- most recently started first, which is the order a stranger reads a list of games in.
          ORDER BY m.start DESC, m.match_id, seat.participant_id"""
            .query(seatRow)

    private val selectPublicOverForPlayer =
        sql"""SELECT m.game_id, m.match_id, g.display_name, m.description, m.completed, m.cancelled,
                 ch.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind, m.time_limit_unit, m.live,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed_at IS NOT NULL, seat.due,
                 -- Where anyone may watch, for a match created public: the engine issues one only
                 -- then, so it is null for every private match and is the field a Watch link
                 -- needs. The same column on every list, because who may watch does not depend on
                 -- which list the match is being read for.
                 m.public_url, m.friendly, m.archived_at, m.archive_expired_at IS NOT NULL, m.settings,
                 m.result_summary
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN challenge ch ON ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          -- The other half of that: their seat is done, whatever the match is doing -- in one window
          -- of time, as the caller's own list above, and never a match called off.
          WHERE p.player_id = $playerId AND m.public AND p.game_id = COALESCE(${gameId.opt}, p.game_id)
            AND p.completed_at >= $instant AND p.completed_at < $instant
            AND NOT m.cancelled
          ORDER BY p.completed_at DESC, m.start DESC, m.match_id, seat.participant_id"""
            .query(seatRow)

    private val selectDueForPlayer =
        sql"""SELECT m.game_id, m.match_id, g.display_name, m.description, m.completed, m.cancelled,
                 ch.challenger = p.player_id, m.start,
                 EXTRACT(EPOCH FROM m.time_limit)::float8, m.time_limit_kind, m.time_limit_unit, m.live,
                 p.participant_id, cp.character_id, p.pending, p.due,
                 seat_player.nickname, seat.pending, seat.completed_at IS NOT NULL, seat.due,
                 -- Where anyone may watch, for a match created public: the engine issues one only
                 -- then, so it is null for every private match and is the field a Watch link
                 -- needs. The same column on every list, because who may watch does not depend on
                 -- which list the match is being read for.
                 m.public_url, m.friendly, m.archived_at, m.archive_expired_at IS NOT NULL, m.settings,
                 m.result_summary
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN game g ON g.game_id = m.game_id
          JOIN challenge ch ON ch.game_id = m.game_id AND ch.challenge_id = m.challenge_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          JOIN participant seat ON seat.game_id = m.game_id AND seat.match_id = m.match_id
          JOIN player seat_player ON seat_player.player_id = seat.player_id
          WHERE p.player_id = $playerId AND p.pending = true AND m.completed IS NULL AND m.cancelled = false
          ORDER BY p.due ASC NULLS LAST, m.start DESC, m.match_id, seat.participant_id"""
            .query(seatRow)

    /** Every match the player is in that is still running, most urgent first, one row per seat. */
    def listActiveForPlayer(playerId: PlayerId): IO[List[MatchSeatRow]] =
        session.execute(selectActiveForPlayer)(playerId).map(_.map(toSeatRow))

    /** One window of the matches the player has finished, most recent first, one row per seat: see [[CompletedSpan]].
      */
    def listCompletedForPlayer(playerId: PlayerId, span: CompletedSpan): IO[List[MatchSeatRow]] =
        session
            .execute(selectOverForPlayer)((playerId, span.gameId, span.from, span.until))
            .map(_.map(toSeatRow))

    /** The public matches one player is in that are still running, one row per seat.
      *
      * For somebody else's page: the lists above answer about the caller and show everything, this one and the next
      * answer about anybody and show only what that player marked public.
      */
    def listPublicActiveForPlayer(playerId: PlayerId): IO[List[MatchSeatRow]] =
        session.execute(selectPublicActiveForPlayer)(playerId).map(_.map(toSeatRow))

    /** One window of the public matches the player has finished, most recent first, one row per seat. */
    def listPublicCompletedForPlayer(playerId: PlayerId, span: CompletedSpan): IO[List[MatchSeatRow]] =
        session
            .execute(selectPublicOverForPlayer)((playerId, span.gameId, span.from, span.until))
            .map(_.map(toSeatRow))

    /* Whether anything the same list could show was finished before a window began: what decides
     * whether there is an older window to go to. The same rules as the two lists -- the player's
     * seats, the one game where there is one, never a cancelled match, and only public ones for
     * somebody else's page -- and the same reason for writing the two out rather than flagging one. */
    private val selectCompletedBefore: Query[(PlayerId, Option[GameId], Instant), Boolean] =
        sql"""SELECT EXISTS (
            SELECT 1 FROM participant p
            JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
            WHERE p.player_id = $playerId AND p.game_id = COALESCE(${gameId.opt}, p.game_id)
              AND p.completed_at < $instant AND NOT m.cancelled
          )""".query(bool)

    private val selectPublicCompletedBefore: Query[(PlayerId, Option[GameId], Instant), Boolean] =
        sql"""SELECT EXISTS (
            SELECT 1 FROM participant p
            JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
            WHERE p.player_id = $playerId AND m.public AND p.game_id = COALESCE(${gameId.opt}, p.game_id)
              AND p.completed_at < $instant AND NOT m.cancelled
          )""".query(bool)

    /** Whether the player finished anything before `before`, in the one game where there is one, that their own
      * completed list would show.
      */
    def hasCompletedBefore(playerId: PlayerId, gameId: Option[GameId], before: Instant): IO[Boolean] =
        session.unique(selectCompletedBefore)((playerId, gameId, before))

    /** The same, for the public list on the player's page. */
    def hasPublicCompletedBefore(playerId: PlayerId, gameId: Option[GameId], before: Instant): IO[Boolean] =
        session.unique(selectPublicCompletedBefore)((playerId, gameId, before))

    /** The database's clock, which a first window is measured back from, so that the windows and the completion times
      * they hold are read off one clock.
      */
    def now: IO[Instant] = session.unique(sql"SELECT now()".query(instant))

    /** The running matches in which it is this player's turn, one row per seat. */
    def listDueForPlayer(playerId: PlayerId): IO[List[MatchSeatRow]] =
        session.execute(selectDueForPlayer)(playerId).map(_.map(toSeatRow))

    /* Still its own query rather than more joined columns, because unlike whose turn it is this
     * really is an aggregate: a seat's balance is a sum over every turn it has taken, and joining
     * `turn` into the list above would multiply each match's rows by its every move for a caller
     * to add up again. One query for the whole list either way -- not one per match.
     *
     * The balance is the match's limit less the turns that seat has finished, over a LEFT JOIN so
     * a player who has not moved yet has their whole budget rather than no row. Restricted to
     * matches under a total limit: there is no balance to run down under a per-turn one.
     *
     * `due` comes along so the caller can tell the player on the clock from the rest -- theirs is
     * the balance that is being spent as it is read. */
    private val selectClocksForPlayer: Query[PlayerId, (GameId, MatchId, String, Double, Option[Instant])] =
        sql"""SELECT p.game_id, p.match_id, pl.nickname,
                 EXTRACT(EPOCH FROM (m.time_limit - coalesce(sum(GREATEST(t.taken_at - t.started_at, INTERVAL '0')), INTERVAL '0')))::float8,
                 p.due
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN turn t ON t.game_id = p.game_id AND t.participant_id = p.participant_id
          WHERE m.completed IS NULL AND NOT m.cancelled
            AND m.time_limit IS NOT NULL AND m.time_limit_kind = 'TOTAL'
            AND EXISTS (SELECT 1 FROM participant mine
                         WHERE mine.game_id = p.game_id AND mine.match_id = p.match_id
                           AND mine.player_id = $playerId)
          GROUP BY p.game_id, p.match_id, p.participant_id, pl.nickname, p.due, m.time_limit
          ORDER BY p.participant_id"""
            .query(gameId *: matchId *: text *: float8 *: instant.opt)

    /* The parameters of the games a list of matches is in, each with the values it may take, in the
     * order each game defines them -- which is the order they are shown in. One query for the whole
     * list, as with the clocks above, not one per match or per game.
     *
     * Read plainly: this is a listing, which writes nothing. */
    private def selectParametersForGames(
        n: Int
    ): Query[List[GameId], (GameId, String, String, Option[String], Arr[String])] =
        sql"""SELECT gp.game_id, gp.name, gp.display_name, gp.default_value,
                 coalesce(array_agg(v.value ORDER BY v.value) FILTER (WHERE v.value IS NOT NULL), '{}')
          FROM game_parameter gp
          LEFT JOIN game_parameter_value v
            ON v.game_id = gp.game_id AND v.game_parameter_id = gp.game_parameter_id
          WHERE gp.game_id IN (${gameId.list(n)})
          GROUP BY gp.game_id, gp.game_parameter_id
          ORDER BY gp.game_id, gp.game_parameter_id""".query(gameId *: text *: text *: text.opt *: _text)

    /** The parameters of each of these games, in the order each game defines them. */
    def parametersForGames(gameIds: Set[GameId]): IO[List[GameParameterRow]] =
        if (gameIds.isEmpty) IO.pure(Nil)
        else {
            val ids = gameIds.toList
            session
                .execute(selectParametersForGames(ids.size))(ids)
                .map(
                  _.map((game, name, displayName, defaultValue, values) =>
                      GameParameterRow(game, name, displayName, defaultValue, values.flattenTo(List))
                  )
                )
        }

    /* A game's matches for its admins: the ones still being played first, then the rest, most
     * recently started first within each. Who is playing comes as one array per match, so a match
     * is one row however many seats it has; a match with no seats yet (one whose engine is still
     * being asked to create it) has an empty one. */
    private val selectForGame
        : Query[(GameId, Int), (MatchId, String, Instant, Option[Instant], Boolean, Boolean, Arr[String])] =
        sql"""SELECT m.match_id, m.description, m.start, m.completed, m.cancelled, m.friendly,
                 coalesce(array_agg(pl.nickname ORDER BY p.participant_id) FILTER (WHERE pl.nickname IS NOT NULL),
                          '{}')
          FROM match m
          LEFT JOIN participant p ON p.game_id = m.game_id AND p.match_id = m.match_id
          LEFT JOIN player pl ON pl.player_id = p.player_id
          WHERE m.game_id = $gameId
          GROUP BY m.game_id, m.match_id
          ORDER BY (m.completed IS NULL AND NOT m.cancelled) DESC, m.start DESC, m.match_id
          LIMIT $int4""".query(matchId *: text *: instant *: instant.opt *: bool *: bool *: _text)

    /** At most `limit` of the game's matches, running ones first, each with its players' nicknames. */
    def listForGame(gameId: GameId, limit: Int): IO[List[GameMatch]] =
        session
            .execute(selectForGame)((gameId, limit))
            .map(
              _.map((id, description, start, completedAt, cancelled, friendly, players) =>
                  GameMatch(id, description, start, completedAt, cancelled, friendly, players.flattenTo(List))
              )
            )

    /** Every seat's remaining budget, across the caller's running chess-clock matches. */
    def clocksForPlayer(playerId: PlayerId): IO[List[MatchClockRow]] =
        session
            .execute(selectClocksForPlayer)(playerId)
            .map(_.map { case (gameId, matchId, nickname, seconds, due) =>
                MatchClockRow(gameId, matchId, nickname, Duration.ofMillis((seconds * 1000).toLong), due)
            })
}

object MatchRepo {

    /* One (match, seat) pair, which is what a list of matches comes back as.
     *
     * The queries below join `participant` twice: once as `p`, the caller's own seat, which is
     * what scopes the list and carries the facts that are theirs alone (their deadline, whether
     * they are pending, which character they are playing); and once as `seat`, which is every
     * player in the match including them. So a two-player match is two rows, both carrying the
     * same match and the same caller.
     *
     * That is deliberately more rows than a caller wants, and the caller is expected to fold them
     * into one summary per match -- MatchService.summarise does it. The alternative, and what
     * this replaced, was a scalar subquery per derived column: one to aggregate the nicknames of
     * whoever is pending, another to take the earliest of their deadlines. Each new question about
     * the other seats wanted another subquery, each was a rule about what a match means written in
     * SQL, and the nicknames had to be joined into one string and split apart again because a
     * column cannot be a list. A join says only what the rows are; what they mean is Scala's to
     * decide, where it can be read and tested as ordinary code.
     *
     * The rows arrive in the order the summaries want, and every row of one match is adjacent to
     * its siblings -- each query orders by its own ordering columns first and by `seat` within
     * them -- so the fold preserves that order without having to sort again. */
    case class MatchSeatRow(
        gameId: GameId,
        matchId: MatchId,
        gameName: String,
        description: String,
        completedAt: Option[Instant],
        cancelled: Boolean,
        isCreator: Boolean,
        start: Instant,
        timeLimit: Option[Duration],
        timeLimitKind: TimeLimitKind,
        timeLimitUnit: TimeLimitUnit,
        live: Boolean,
        // The caller's own seat, repeated on every row of the match.
        callerParticipantId: ParticipantId,
        callerCharacterId: Option[CharacterId],
        callerPending: Boolean,
        callerDue: Option[Instant],
        // The seat this row is about, which may be the caller's own or anyone else's.
        seatNickname: String,
        seatPending: Boolean,
        seatCompleted: Boolean,
        seatDue: Option[Instant],
        // Where anyone may watch this match, and `None` for one that is not public. A fact about the
        // match rather than about this seat, and so the same on every row of it.
        publicUrl: Option[String],
        // Whether the match is friendly (V36), likewise the match's.
        friendly: Boolean,
        // The match's archive (V38): when it was confirmed, and whether a friendly one has expired.
        archivedAt: Option[Instant] = None,
        archiveExpired: Boolean = false,
        // The match's settings, which hold the challenger's choice for each of the game's
        // parameters (see `ChallengeSettings`); likewise the match's.
        settings: String = "{}",
        // How the match came out, in the engine's words (V40); likewise the match's.
        resultSummary: Option[String] = None
    )

    /** One of a game's parameters, with every value it may take: what a match's settings are resolved against. */
    case class GameParameterRow(
        gameId: GameId,
        name: String,
        displayName: String,
        defaultValue: Option[String],
        values: List[String]
    )

    /** The stretch of time one window of a completed list covers: finished from `from`, up to but not including
      * `until`; and in one game where `gameId` names one.
      */
    case class CompletedSpan(from: Instant, until: Instant, gameId: Option[GameId])

    /** What one seat has left of a chess-clock budget. */
    case class MatchClockRow(
        gameId: GameId,
        matchId: MatchId,
        nickname: String,
        remaining: Duration,
        due: Option[Instant]
    )
}
