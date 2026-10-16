package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model.{GameId, MatchId, ParticipantId, PlayerId, Result}
import ResultRepo.{ParticipantResultRow, TimeTakenRow}
import MatchRepo.CompletedSpan

class ResultRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val participantId = SkunkIdCodecs.participantId
    private val instant = SkunkCodecs.instant

    // result.scores is jsonb holding an object; SkunkCodecs.jsonObject presents it as a Map.
    private val scores: Codec[Map[String, Any]] = SkunkCodecs.jsonObject

    private val insertResult: Command[
      (GameId, ParticipantId, Int, Map[String, Any], Boolean, Boolean, Option[Int], Option[Int])
    ] =
        sql"""INSERT INTO result (game_id, participant_id, rank, scores, is_winner, is_forfeit, elo_delta, elo_role_delta)
          VALUES ($gameId, $participantId, $int4, $scores, $bool, $bool, ${int4.opt}, ${int4.opt})""".command

    // result's primary key is the composite (game_id, participant_id) — participant_id alone is
    // not declared unique (unlike character_id, which has its own explicit UNIQUE constraint), so
    // both columns are required in the WHERE clause here, not participant_id alone.
    private val selectResult: Query[
      (GameId, ParticipantId),
      (Int, Map[String, Any], Boolean, Boolean, Option[Int], Option[Int])
    ] =
        sql"""SELECT rank, scores, is_winner, is_forfeit, elo_delta, elo_role_delta FROM result
          WHERE game_id = $gameId AND participant_id = $participantId""".query(
          int4 *: scores *: bool *: bool *: int4.opt *: int4.opt
        )

    private val updateResult: Command[
      (Int, Map[String, Any], Boolean, Boolean, Option[Int], Option[Int], GameId, ParticipantId)
    ] =
        sql"""UPDATE result SET rank = $int4, scores = $scores, is_winner = $bool, is_forfeit = $bool,
          elo_delta = ${int4.opt}, elo_role_delta = ${int4.opt}
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    // Every seat of every finished match this player is in within one window of their completed
    // list, with its outcome.
    //
    // One query for the window rather than one per match: the UI shows the table on each finished
    // row, and asking per row would be a request per row. `mine` is the caller's own seat, which is
    // what scopes the list; `p` is everyone's, which is what fills the table.
    //
    // Windowed on `mine.completed_at` exactly as `MatchRepo.selectOverForPlayer` is, so the rows are
    // the ones that list shows and `participant_player_completed_at` drives the read. Unwindowed, this
    // was the player's whole history on every load of the home page.
    //
    // A LEFT JOIN onto result, so a participant the engine reported no result for is still a row
    // — see ParticipantResult. Ordered by rank within a match, unreported seats last, so the
    // winner comes first without the caller having to sort.
    private val selectResultsForPlayer: Query[
      (PlayerId, Option[GameId], Instant, Instant),
      (
          GameId,
          MatchId,
          ParticipantId,
          String,
          Option[String],
          Option[Int],
          Option[Map[String, Any]],
          Option[Boolean],
          Option[Boolean],
          Int,
          Option[Int]
      )
    ] =
        sql"""SELECT p.game_id, p.match_id, p.participant_id, COALESCE(pl.nickname, ch.name), gr.display_name, r.rank, r.scores, r.is_winner, r.is_forfeit,
                 p.elo_start, r.elo_delta
          FROM participant mine
          JOIN match m ON m.game_id = mine.game_id AND m.match_id = mine.match_id
          JOIN participant p ON p.game_id = m.game_id AND p.match_id = m.match_id
          -- Outer: a seat whose character had no owner is nobody's, and goes by its character's name instead.
          LEFT JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          LEFT JOIN character ch ON ch.game_id = cp.game_id AND ch.character_id = cp.character_id
          -- Outer: a seat of a game without roles has none.
          LEFT JOIN game_role gr ON gr.game_id = p.game_id AND gr.game_role_id = p.game_role_id
          LEFT JOIN result r ON r.game_id = p.game_id AND r.participant_id = p.participant_id
          -- Begun: a tournament's match is_cancelled before it began has seats with no ratings and no results.
          WHERE mine.player_id = ${SkunkIdCodecs.playerId} AND mine.game_id = COALESCE(${gameId.opt}, mine.game_id)
            AND mine.completed_at >= $instant AND mine.completed_at < $instant
            AND m.start IS NOT NULL AND ((m.completed_at IS NOT NULL) OR m.is_cancelled)
          ORDER BY p.match_id, r.rank ASC NULLS LAST, p.participant_id"""
            .query(
              gameId *: SkunkIdCodecs.matchId *: participantId *: text *: text.opt *: int4.opt *: scores.opt *:
                  bool.opt *: bool.opt *: int4 *: int4.opt
            )

    /** Every seat of every finished match this player is in within `span`, with its outcome — one row per seat, the
      * winner of each match first. `span` is a window of the completed list, and these are the result tables of the
      * matches that window shows.
      *
      * What a seat *spent* is not here: see [[timeTakenForPlayer]], which is the other half of a result row and is
      * asked for separately.
      */
    def listForPlayer(playerId: PlayerId, span: CompletedSpan): IO[List[ParticipantResultRow]] =
        session
            .execute(selectResultsForPlayer)((playerId, span.gameId, span.from, span.until))
            .map(_.map {
                case (game, match_, id, nickname, roleName, rank, scores, isWinner, forfeit, eloStart, eloDelta) =>
                    ParticipantResultRow(
                      gameId = game,
                      matchId = match_,
                      participantId = id,
                      nickname = nickname,
                      roleName = roleName,
                      rank = rank,
                      scores = scores.getOrElse(Map.empty),
                      isWinner = isWinner.getOrElse(false),
                      isForfeit = forfeit.getOrElse(false),
                      eloStart = eloStart,
                      eloDelta = eloDelta
                    )
            })

    /* How long each seat spent over its turns, across the caller's finished matches in one window
     * of their completed list: the same matches, chosen the same way, as `selectResultsForPlayer`.
     *
     * Its own query rather than a column of the one above, and an aggregate rather than a join,
     * for the same reason `MatchRepo.clocksFor` is: this is a sum over every turn a seat has
     * taken, and joining `turn` into the result list would multiply each seat's row by its every
     * move for the caller to add up again — a long match would cross the wire once per move, per
     * seat, per time anybody opened their history.
     *
     * Seats with no turns recorded are simply absent; the caller reads it through
     * `getOrElse(Duration.ZERO)`, which is the right answer both for a player who never moved and
     * for a match played before turns were recorded. GREATEST clamps a turn the engine reported as
     * taken before it started, matching `Turn.elapsed`: two clocks disagreeing is not a refund. */
    private val selectTimeTakenForPlayer
        : Query[(PlayerId, Option[GameId], Instant, Instant), (GameId, ParticipantId, Double)] =
        sql"""SELECT t.game_id, t.participant_id,
                 EXTRACT(EPOCH FROM sum(GREATEST(t.taken_at - t.started_at, INTERVAL '0')))::float8
          FROM participant p
          JOIN turn t ON t.game_id = p.game_id AND t.participant_id = p.participant_id
          -- A set of matches rather than a join onto the caller's seat, so that a match they sat in
          -- twice is still summed once.
          WHERE (p.game_id, p.match_id) IN (
                  SELECT mine.game_id, mine.match_id FROM participant mine
                  JOIN match m ON m.game_id = mine.game_id AND m.match_id = mine.match_id
                  WHERE mine.player_id = ${SkunkIdCodecs.playerId}
                    AND mine.game_id = COALESCE(${gameId.opt}, mine.game_id)
                    AND mine.completed_at >= $instant AND mine.completed_at < $instant
                    AND m.start IS NOT NULL AND ((m.completed_at IS NOT NULL) OR m.is_cancelled))
          GROUP BY t.game_id, t.participant_id"""
            .query(gameId *: participantId *: float8)

    /** What each seat of the caller's finished matches within `span` spent, by seat. */
    def timeTakenForPlayer(playerId: PlayerId, span: CompletedSpan): IO[List[TimeTakenRow]] =
        session
            .execute(selectTimeTakenForPlayer)((playerId, span.gameId, span.from, span.until))
            .map(_.map { case (gameId, participantId, seconds) =>
                TimeTakenRow(
                  gameId = gameId,
                  participantId = participantId,
                  timeTaken = Duration.ofMillis((seconds * 1000).toLong)
                )
            })

    private val selectExistsForMatch: Query[(GameId, MatchId), Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM result r
                              JOIN participant p ON p.game_id = r.game_id AND p.participant_id = r.participant_id
                             WHERE p.game_id = $gameId AND p.match_id = ${SkunkIdCodecs.matchId})""".query(bool)

    /** Whether any seat of the match has a result recorded: the engine's, or a forfeit's. */
    def existsForMatch(gameId: GameId, matchId: MatchId): IO[Boolean] =
        session.unique(selectExistsForMatch)((gameId, matchId))

    private val selectForMatch: Query[(GameId, MatchId), ResultRepo.RatedResultRow] =
        sql"""SELECT r.participant_id, r.rank, r.elo_delta, r.is_forfeit, r.elo_role_delta FROM result r
          JOIN participant p ON p.game_id = r.game_id AND p.participant_id = r.participant_id
          WHERE p.game_id = $gameId AND p.match_id = ${SkunkIdCodecs.matchId}
          ORDER BY r.participant_id"""
            .query(participantId *: int4 *: int4.opt *: bool *: int4.opt)
            .to[ResultRepo.RatedResultRow]

    /** Every result of the match, as rating sees it: the seat, where it finished, what it did to the rating overall and
      * in the seat's role, and whether a turn running out decided it.
      */
    def forMatch(gameId: GameId, matchId: MatchId): IO[List[ResultRepo.RatedResultRow]] =
        session.execute(selectForMatch)((gameId, matchId))

    private val updateEloDelta: Command[(Option[Int], Option[Int], GameId, ParticipantId)] =
        sql"""UPDATE result SET elo_delta = ${int4.opt}, elo_role_delta = ${int4.opt}
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    /** Says what the match did to the seat's player's rating, overall and in the seat's role: for a completed match
      * reclassified as friendly (none) or not (the deltas worked out now).
      */
    def setEloDelta(gameId: GameId, id: ParticipantId, delta: Option[Int], roleDelta: Option[Int] = None): IO[Unit] =
        session.execute(updateEloDelta)((delta, roleDelta, gameId, id)).void

    private val insertManual: Command[(GameId, ParticipantId, Int, Boolean)] =
        sql"""INSERT INTO result (game_id, participant_id, rank, scores, is_winner, is_forfeit, is_manual)
          VALUES ($gameId, $participantId, $int4, '{}'::jsonb, $bool, false, true)""".command

    /** A rank a tournament's owner gave a seat of a match they called off: no scores, no rating change, marked as given
      * by hand. Only ever on a cancelled tournament match, which nothing that moves a rating reads.
      */
    def createManual(gameId: GameId, id: ParticipantId, rank: Int, isWinner: Boolean): IO[Unit] =
        session.execute(insertManual)((gameId, id, rank, isWinner)).void

    private val deleteManualForMatch: Command[(GameId, MatchId)] =
        sql"""DELETE FROM result r USING participant p
          WHERE r.game_id = p.game_id AND r.participant_id = p.participant_id AND r.is_manual
            AND p.game_id = $gameId AND p.match_id = ${SkunkIdCodecs.matchId}""".command

    /** Removes the ranks given by hand to the seats of a match: for a launch the engine refused, taking back the last
      * places it recorded for its forfeited seats, so that the next attempt can record them again.
      */
    def deleteManualForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
        session.execute(deleteManualForMatch)((gameId, matchId)).void

    private val updateToManual: Command[(Int, Boolean, GameId, ParticipantId)] =
        sql"""UPDATE result SET rank = $int4, scores = '{}'::jsonb, is_winner = $bool, is_forfeit = false, is_manual = true,
                 elo_delta = NULL, elo_role_delta = NULL
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    /** As [[createManual]], over the forfeit a cancel gave the seat: the owner ranking by hand a match called off. */
    def replaceWithManual(gameId: GameId, id: ParticipantId, rank: Int, isWinner: Boolean): IO[Unit] =
        session.execute(updateToManual)((rank, isWinner, gameId, id)).void

    def create(result: Result): IO[Result] =
        session
            .execute(insertResult)(
              (
                result.gameId,
                result.participantId,
                result.rank,
                result.scores,
                result.isWinner,
                result.isForfeit,
                result.eloDelta,
                result.eloRoleDelta
              )
            )
            .as(result)

    def read(gameId: GameId, id: ParticipantId): IO[Option[Result]] =
        session
            .option(selectResult)((gameId, id))
            .map(_.map { case (rank, scores, isWinner, forfeit, eloDelta, eloRoleDelta) =>
                Result(
                  gameId = gameId,
                  participantId = id,
                  rank = rank,
                  scores = scores,
                  isWinner = isWinner,
                  isForfeit = forfeit,
                  eloDelta = eloDelta,
                  eloRoleDelta = eloRoleDelta
                )
            })

    def update(result: Result): IO[Unit] =
        session
            .execute(updateResult)(
              (
                result.rank,
                result.scores,
                result.isWinner,
                result.isForfeit,
                result.eloDelta,
                result.eloRoleDelta,
                result.gameId,
                result.participantId
              )
            )
            .void
}

object ResultRepo {

    /** One line of a finished match's result table as the join returns it: who played, in which role, and how they did.
      *
      * Everything here is a column of a row that exists — the reading of them, and the time each seat spent, are put
      * together into a [[com.vivi.matchmaker.model.ParticipantResult]] by the service.
      */
    case class ParticipantResultRow(
        gameId: GameId,
        matchId: MatchId,
        participantId: ParticipantId,
        nickname: String,
        roleName: Option[String],
        rank: Option[Int],
        scores: Map[String, Any],
        isWinner: Boolean,
        isForfeit: Boolean,
        // Its player's rating as the match began, from the seat, and the match's change to it, from the
        // result.
        eloStart: Int,
        eloDelta: Option[Int]
    )

    /** A result as rating sees it: where the seat finished, and what the match did to its player's rating, overall and
      * in the seat's role.
      */
    case class RatedResultRow(
        participantId: ParticipantId,
        rank: Int,
        eloDelta: Option[Int],
        isForfeit: Boolean = false,
        eloRoleDelta: Option[Int] = None
    )

    /** What one seat spent over its turns, all told. */
    case class TimeTakenRow(gameId: GameId, participantId: ParticipantId, timeTaken: Duration)
}
