package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import TournamentMatchRepo.SeatRow

/** A tournament's matches as a round sees them (V53): every seat of every match made for the round's pools, with the
  * slot's occupant, the seat's result and whether its turn has run out.
  */
class TournamentMatchRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val fixtureId = SkunkIdCodecs.fixtureId
    private val matchId = SkunkIdCodecs.matchId
    private val scores = SkunkCodecs.jsonObject

    // Ids decoded raw and wrapped below: a trailing opaque-typed codec defeats skunk's twiddles outside Ids.scala.
    // `overdue` by the database's clock, as every other deadline here is judged.
    private val selectSeats: Query[(GameId, TournamentId, Int), SeatRow] =
        sql"""SELECT m.fixture_id, m.match_no, m.match_id, m.completed IS NOT NULL, m.cancelled, m.live,
                 p.participant_id, s.tournament_participant_id, r.rank, r.scores,
                 p.pending AND p.completed_at IS NULL AND p.due < now(), COALESCE(r.manual, false)
          FROM match m
          JOIN fixture f ON f.game_id = m.game_id AND f.tournament_id = m.tournament_id AND f.fixture_id = m.fixture_id
          JOIN participant p ON p.game_id = m.game_id AND p.match_id = m.match_id
          JOIN fixture_slot s ON s.game_id = p.game_id AND s.tournament_id = p.tournament_id
                             AND s.fixture_id = p.fixture_id AND s.slot_id = p.slot_id
          LEFT JOIN result r ON r.game_id = p.game_id AND r.participant_id = p.participant_id
          WHERE m.game_id = $gameId AND m.tournament_id = $tournamentId AND f.round = $int4
          ORDER BY m.fixture_id, m.match_no, p.participant_id"""
            .query(
              int8 *: int4 *: matchId *: bool *: bool *: bool *: int8 *: int8.opt *: int4.opt *: scores.opt *: bool *:
                  bool
            )
            .map {
                case (
                      fixture,
                      no,
                      id,
                      completed,
                      cancelled,
                      live,
                      participant,
                      occupant,
                      rank,
                      scored,
                      overdue,
                      manual
                    ) =>
                    SeatRow(
                      FixtureId(fixture),
                      no,
                      id,
                      completed,
                      cancelled,
                      live,
                      ParticipantId(participant),
                      occupant.map(TournamentParticipantId(_)),
                      rank,
                      scored.getOrElse(Map.empty),
                      overdue,
                      manual
                    )
            }

    /** Every seat of every match made for a round's pools, by pool, match and seat. */
    def seatsOfRound(game: GameId, tournament: TournamentId, round: Int): IO[List[SeatRow]] =
        session.execute(selectSeats)((game, tournament, round))

    private val selectExisting: Query[(GameId, TournamentId, FixtureId, Int), MatchId] =
        sql"""SELECT match_id FROM match
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND fixture_id = $fixtureId AND match_no = $int4"""
            .query(matchId)

    /** The match made as a pool's `matchNo`th, if it has been. */
    def existing(game: GameId, tournament: TournamentId, fixture: FixtureId, matchNo: Int): IO[Option[MatchId]] =
        session.option(selectExisting)((game, tournament, fixture, matchNo))

    private val selectMatchNumbers: Query[(GameId, TournamentId, Int), (Long, Int)] =
        sql"""SELECT m.fixture_id, m.match_no FROM match m
          JOIN fixture f ON f.game_id = m.game_id AND f.tournament_id = m.tournament_id AND f.fixture_id = m.fixture_id
          WHERE m.game_id = $gameId AND m.tournament_id = $tournamentId AND f.round = $int4""".query(int8 *: int4)

    /** Which of a round's matches have been made, by pool and number — seats or none. */
    def madeInRound(game: GameId, tournament: TournamentId, round: Int): IO[Set[(FixtureId, Int)]] =
        session.execute(selectMatchNumbers)((game, tournament, round)).map(_.map((f, n) => FixtureId(f) -> n).toSet)
}

object TournamentMatchRepo {

    /** One seat of one tournament match. `occupant` is the slot's, which is who the seat is. */
    case class SeatRow(
        fixtureId: FixtureId,
        matchNo: Int,
        matchId: MatchId,
        completed: Boolean,
        cancelled: Boolean,
        live: Boolean,
        participantId: ParticipantId,
        occupant: Option[TournamentParticipantId],
        rank: Option[Int],
        scores: Map[String, Any],
        overdue: Boolean,
        manual: Boolean = false
    )
}
