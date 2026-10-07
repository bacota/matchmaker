package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** A tournament's rounds, and the pools and slots each round is played in (V53). */
class FixtureRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val fixtureId = SkunkIdCodecs.fixtureId
    private val slotId = SkunkIdCodecs.slotId
    private val participantId = SkunkIdCodecs.tournamentParticipantId
    private val instant = SkunkCodecs.instant
    private val tiebreaker = SkunkCodecs.tiebreaker

    // Intervals as a count of seconds, as everywhere else here.
    private def seconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def duration(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    // ---- rounds -------------------------------------------------------------------------------------

    private val insertRound: Command[
      (
          GameId,
          TournamentId,
          Int,
          Int,
          Boolean,
          Boolean,
          Option[Double],
          Option[Int],
          Option[Int],
          Option[Int],
          Option[Tiebreaker]
      )
    ] =
        sql"""INSERT INTO tournament_round (game_id, tournament_id, round, cycle, reseed, live, duration, rotations,
                                            pool_size, min_pool_advance, tiebreaker)
          VALUES ($gameId, $tournamentId, $int4, $int4, $bool, $bool, ${float8.opt} * INTERVAL '1 second',
                  ${int4.opt}, ${int4.opt}, ${int4.opt}, ${tiebreaker.opt})""".command

    private type RoundRow = (
        GameId,
        TournamentId,
        Int,
        Int,
        Boolean,
        Boolean,
        Option[Double],
        Option[Int],
        Option[Int],
        Option[Int],
        Option[Tiebreaker],
        Option[Instant],
        Option[Instant],
        Option[Instant]
    )

    private val roundRow: Codec[RoundRow] =
        gameId *: tournamentId *: int4 *: int4 *: bool *: bool *: float8.opt *: int4.opt *: int4.opt *: int4.opt *:
            tiebreaker.opt *: instant.opt *: instant.opt *: instant.opt

    private val roundColumns: Fragment[Void] =
        sql"""game_id, tournament_id, round, cycle, reseed, live, EXTRACT(EPOCH FROM duration)::float8, rotations,
              pool_size, min_pool_advance, tiebreaker, started_at, completed_at, checked_at
          FROM tournament_round"""

    private val selectRounds: Query[(GameId, TournamentId), RoundRow] =
        sql"SELECT $roundColumns WHERE game_id = $gameId AND tournament_id = $tournamentId ORDER BY round".query(
          roundRow
        )

    private val selectRoundForUpdate: Query[(GameId, TournamentId, Int), RoundRow] =
        sql"""SELECT $roundColumns WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          FOR UPDATE""".query(roundRow)

    private def toRound(r: RoundRow): TournamentRound = {
        val (
          game,
          tournament,
          round,
          cycle,
          reseed,
          live,
          duration_,
          rotations,
          poolSize,
          minPoolAdvance,
          tiebreaker,
          startedAt,
          completedAt,
          checkedAt
        ) = r
        TournamentRound(
          game,
          tournament,
          round,
          cycle,
          reseed,
          live,
          duration(duration_),
          rotations,
          poolSize,
          minPoolAdvance,
          tiebreaker,
          startedAt,
          completedAt,
          checkedAt
        )
    }

    /** Writes a round, not yet started. */
    def createRound(r: TournamentRound): IO[Unit] =
        session
            .execute(insertRound)(
              (
                r.gameId,
                r.tournamentId,
                r.round,
                r.cycle,
                r.reseed,
                r.live,
                seconds(r.duration),
                r.rotations,
                r.poolSize,
                r.minPoolAdvance,
                r.tiebreaker
              )
            )
            .void

    /** Every round, in order. */
    def listRounds(game: GameId, tournament: TournamentId): IO[List[TournamentRound]] =
        session.execute(selectRounds)((game, tournament)).map(_.map(toRound))

    /** A round, locked for the rest of the transaction: what starting, checking and completing it each decide from. */
    def readRoundForUpdate(game: GameId, tournament: TournamentId, round: Int): IO[Option[TournamentRound]] =
        session.option(selectRoundForUpdate)((game, tournament, round)).map(_.map(toRound))

    // Held against the round's completion, which takes FOR UPDATE, by a match being made for it.
    private val selectRoundForShare: Query[(GameId, TournamentId, Int), RoundRow] =
        sql"""SELECT $roundColumns WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          FOR SHARE""".query(roundRow)

    /** A round, held against its completion until the transaction ends. */
    def readRoundForShare(game: GameId, tournament: TournamentId, round: Int): IO[Option[TournamentRound]] =
        session.option(selectRoundForShare)((game, tournament, round)).map(_.map(toRound))

    private val deleteSlots: Command[(GameId, TournamentId)] =
        sql"DELETE FROM fixture_slot WHERE game_id = $gameId AND tournament_id = $tournamentId".command
    private val deleteFixtures: Command[(GameId, TournamentId)] =
        sql"DELETE FROM fixture WHERE game_id = $gameId AND tournament_id = $tournamentId".command
    private val deleteRounds: Command[(GameId, TournamentId)] =
        sql"DELETE FROM tournament_round WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    /** Removes every round, pool and slot, to lay the tournament out again before its first round starts. */
    def deleteLayout(game: GameId, tournament: TournamentId): IO[Unit] =
        session.execute(deleteSlots)((game, tournament)) *> session.execute(deleteFixtures)((game, tournament)) *>
            session.execute(deleteRounds)((game, tournament)).void

    private val updateRoundSettings: Command[
      (Boolean, Option[Double], Option[Int], Option[Int], Option[Int], Option[Tiebreaker], GameId, TournamentId, Int)
    ] =
        sql"""UPDATE tournament_round SET reseed = $bool, duration = ${float8.opt} * INTERVAL '1 second',
                 rotations = ${int4.opt}, pool_size = ${int4.opt}, min_pool_advance = ${int4.opt},
                 tiebreaker = ${tiebreaker.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4""".command

    /** Rewrites a round's own settings — its overrides and whether it reseeds — before it starts. */
    def setRoundSettings(r: TournamentRound): IO[Unit] =
        session
            .execute(updateRoundSettings)(
              (
                r.reseed,
                seconds(r.duration),
                r.rotations,
                r.poolSize,
                r.minPoolAdvance,
                r.tiebreaker,
                r.gameId,
                r.tournamentId,
                r.round
              )
            )
            .void

    // Each stamped by the database's clock, and answered with what it stored.
    private val markRoundStarted: Query[(Boolean, GameId, TournamentId, Int), Instant] =
        sql"""UPDATE tournament_round SET started_at = now(), live = $bool
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          RETURNING started_at""".query(instant)

    private val markRoundCompleted: Query[(GameId, TournamentId, Int), Instant] =
        sql"""UPDATE tournament_round SET completed_at = now()
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          RETURNING completed_at""".query(instant)

    private val markRoundChecked: Query[(GameId, TournamentId, Int), Instant] =
        sql"""UPDATE tournament_round SET checked_at = now()
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          RETURNING checked_at""".query(instant)

    /** Stamps a round started, with `live` — the tournament's setting as it stands now — copied onto it. */
    def startRound(game: GameId, tournament: TournamentId, round: Int, live: Boolean): IO[Instant] =
        session.unique(markRoundStarted)((live, game, tournament, round))

    def completeRound(game: GameId, tournament: TournamentId, round: Int): IO[Instant] =
        session.unique(markRoundCompleted)((game, tournament, round))

    def checkRound(game: GameId, tournament: TournamentId, round: Int): IO[Instant] =
        session.unique(markRoundChecked)((game, tournament, round))

    // ---- fixtures -----------------------------------------------------------------------------------

    private val insertFixture: Query[(GameId, TournamentId, Int, Int), FixtureId] =
        sql"""INSERT INTO fixture (game_id, tournament_id, round, position) VALUES ($gameId, $tournamentId, $int4, $int4)
          RETURNING fixture_id""".query(fixtureId)

    private val fixtureRow = gameId *: tournamentId *: fixtureId *: int4 *: int4

    private val selectFixtures: Query[(GameId, TournamentId), Fixture] =
        sql"""SELECT game_id, tournament_id, fixture_id, round, position FROM fixture
          WHERE game_id = $gameId AND tournament_id = $tournamentId
          ORDER BY round, position""".query(fixtureRow).to[Fixture]

    private val selectFixturesOfRound: Query[(GameId, TournamentId, Int), Fixture] =
        sql"""SELECT game_id, tournament_id, fixture_id, round, position FROM fixture
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND round = $int4
          ORDER BY position""".query(fixtureRow).to[Fixture]

    def createFixture(f: Fixture): IO[Fixture] =
        session.unique(insertFixture)((f.gameId, f.tournamentId, f.round, f.position)).map(id => f.copy(fixtureId = id))

    /** Every pool of the tournament, by round and then by place. */
    def listFixtures(game: GameId, tournament: TournamentId): IO[List[Fixture]] =
        session.execute(selectFixtures)((game, tournament))

    /** A round's pools, in their places. */
    def listFixtures(game: GameId, tournament: TournamentId, round: Int): IO[List[Fixture]] =
        session.execute(selectFixturesOfRound)((game, tournament, round))

    // ---- slots --------------------------------------------------------------------------------------

    private val insertSlot: Query[
      (GameId, TournamentId, FixtureId, Boolean, Option[Int], Option[FixtureId], Option[Int]),
      SlotId
    ] =
        sql"""INSERT INTO fixture_slot (game_id, tournament_id, fixture_id, bye, seed, prev_fixture_id, rank)
          VALUES ($gameId, $tournamentId, $fixtureId, $bool, ${int4.opt}, ${fixtureId.opt}, ${int4.opt})
          RETURNING slot_id""".query(slotId)

    private val slotRow =
        gameId *: tournamentId *: fixtureId *: slotId *: bool *: int4.opt *: fixtureId.opt *: int4.opt *:
            participantId.opt

    private def toSlot(
        r: (
            GameId,
            TournamentId,
            FixtureId,
            SlotId,
            Boolean,
            Option[Int],
            Option[FixtureId],
            Option[Int],
            Option[
              TournamentParticipantId
            ]
        )
    ): FixtureSlot = {
        val (game, tournament, fixture, slot, bye, seed, prev, rank, occupant) = r
        // The table's CHECK makes exactly one of these hold.
        val source = (bye, seed, prev, rank) match {
            case (true, _, _, _)          => SlotSource.Bye
            case (_, Some(s), _, _)       => SlotSource.Seed(s)
            case (_, _, Some(p), Some(r)) => SlotSource.Winner(p, r)
            case _                        => throw IllegalStateException(s"slot ${slot.value} has no source")
        }
        FixtureSlot(game, tournament, fixture, slot, source, occupant)
    }

    private val selectSlotsOfRound: Query[(GameId, TournamentId, Int), FixtureSlot] =
        sql"""SELECT s.game_id, s.tournament_id, s.fixture_id, s.slot_id, s.bye, s.seed, s.prev_fixture_id, s.rank,
                 s.tournament_participant_id
          FROM fixture_slot s
          JOIN fixture f ON f.game_id = s.game_id AND f.tournament_id = s.tournament_id AND f.fixture_id = s.fixture_id
          WHERE s.game_id = $gameId AND s.tournament_id = $tournamentId AND f.round = $int4
          ORDER BY f.position, s.slot_id""".query(slotRow).map(toSlot)

    private val selectSlotsOfTournament: Query[(GameId, TournamentId), FixtureSlot] =
        sql"""SELECT s.game_id, s.tournament_id, s.fixture_id, s.slot_id, s.bye, s.seed, s.prev_fixture_id, s.rank,
                 s.tournament_participant_id
          FROM fixture_slot s
          JOIN fixture f ON f.game_id = s.game_id AND f.tournament_id = s.tournament_id AND f.fixture_id = s.fixture_id
          WHERE s.game_id = $gameId AND s.tournament_id = $tournamentId
          ORDER BY f.round, f.position, s.slot_id""".query(slotRow).map(toSlot)

    def createSlot(s: FixtureSlot): IO[FixtureSlot] = {
        val (bye, seed, prev, rank) = s.source match {
            case SlotSource.Bye              => (true, None, None, None)
            case SlotSource.Seed(n)          => (false, Some(n), None, None)
            case SlotSource.Winner(p, place) => (false, None, Some(p), Some(place))
        }
        session
            .unique(insertSlot)((s.gameId, s.tournamentId, s.fixtureId, bye, seed, prev, rank))
            .map(id => s.copy(slotId = id))
    }

    /** A round's slots, pool by pool in their places, and within a pool in the order they were made. */
    def listSlots(game: GameId, tournament: TournamentId, round: Int): IO[List[FixtureSlot]] =
        session.execute(selectSlotsOfRound)((game, tournament, round))

    /** Every slot of the tournament, round by round. */
    def listSlots(game: GameId, tournament: TournamentId): IO[List[FixtureSlot]] =
        session.execute(selectSlotsOfTournament)((game, tournament))

    // Only a slot nobody fills yet: once settled, a slot's occupant is never changed (V53).
    private val updateOccupant: Command[(TournamentParticipantId, GameId, TournamentId, FixtureId, SlotId)] =
        sql"""UPDATE fixture_slot SET tournament_participant_id = $participantId
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND fixture_id = $fixtureId AND slot_id = $slotId
            AND tournament_participant_id IS NULL AND NOT bye""".command

    /** Settles who fills a slot, as its round starts; a slot already filled, or a bye, is left as it is. */
    def fill(
        game: GameId,
        tournament: TournamentId,
        fixture: FixtureId,
        slot: SlotId,
        occupant: TournamentParticipantId
    ): IO[Unit] =
        session.execute(updateOccupant)((occupant, game, tournament, fixture, slot)).void
}
