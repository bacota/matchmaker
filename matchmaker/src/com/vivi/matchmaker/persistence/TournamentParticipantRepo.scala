package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** A tournament's seeded field (V53): each entry as the tournament plays it, with its seed, whether it has withdrawn,
  * and where it finished.
  *
  * Seeds are overwritten in place as the tournament goes, and two may be swapped within one transaction: the unique
  * constraint on `(game_id, tournament_id, seed)` is checked at the commit, not row by row.
  */
class TournamentParticipantRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val entryId = SkunkIdCodecs.entryId
    private val participantId = SkunkIdCodecs.tournamentParticipantId

    private val insertParticipant
        : Query[(GameId, TournamentId, EntryId, Int, Int, Option[Int]), TournamentParticipantId] =
        sql"""INSERT INTO tournament_participant (game_id, tournament_id, entry_id, seed, initial_seed, ladder_rank)
          VALUES ($gameId, $tournamentId, $entryId, $int4, $int4, ${int4.opt})
          RETURNING tournament_participant_id""".query(participantId)

    private val row =
        gameId *: tournamentId *: participantId *: entryId *: int4 *: int4 *: bool *: int4.opt *: int4.opt

    private val columns: Fragment[Void] =
        sql"""game_id, tournament_id, tournament_participant_id, entry_id, seed, initial_seed, withdrawn, final_rank,
              ladder_rank
          FROM tournament_participant"""

    private val selectAll: Query[(GameId, TournamentId), TournamentParticipant] =
        sql"""SELECT $columns WHERE game_id = $gameId AND tournament_id = $tournamentId ORDER BY seed"""
            .query(row)
            .to[TournamentParticipant]

    private val selectAllForUpdate: Query[(GameId, TournamentId), TournamentParticipant] =
        sql"""SELECT $columns WHERE game_id = $gameId AND tournament_id = $tournamentId ORDER BY seed FOR UPDATE"""
            .query(row)
            .to[TournamentParticipant]

    private val selectByEntry: Query[(GameId, TournamentId, EntryId), TournamentParticipant] =
        sql"""SELECT $columns WHERE game_id = $gameId AND tournament_id = $tournamentId AND entry_id = $entryId FOR UPDATE"""
            .query(row)
            .to[TournamentParticipant]

    /** Seeds an entry. `ladderRank` is 0 for a ladder's entrant, and `None` in any other class. */
    def create(p: TournamentParticipant): IO[TournamentParticipant] =
        session
            .unique(insertParticipant)((p.gameId, p.tournamentId, p.entryId, p.seed, p.initialSeed, p.ladderRank))
            .map(id => p.copy(tournamentParticipantId = id))

    /** The field, best seed first. */
    def list(game: GameId, tournament: TournamentId): IO[List[TournamentParticipant]] =
        session.execute(selectAll)((game, tournament))

    /** As [[list]], locking every row for the rest of the transaction: for reseeding, and for anything else that writes
      * from what it reads here.
      */
    def listForUpdate(game: GameId, tournament: TournamentId): IO[List[TournamentParticipant]] =
        session.execute(selectAllForUpdate)((game, tournament))

    /** An entry's participant, locked: for withdrawing it. */
    def readByEntryForUpdate(
        game: GameId,
        tournament: TournamentId,
        entry: EntryId
    ): IO[Option[TournamentParticipant]] =
        session.option(selectByEntry)((game, tournament, entry))

    private val updateSeed: Command[(Int, GameId, TournamentId, TournamentParticipantId)] =
        sql"""UPDATE tournament_participant SET seed = $int4
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND tournament_participant_id = $participantId""".command

    /** Writes a new seed over the old one. */
    def setSeed(game: GameId, tournament: TournamentId, id: TournamentParticipantId, seed: Int): IO[Unit] =
        session.execute(updateSeed)((seed, game, tournament, id)).void

    private val updateWithdrawn: Command[(Boolean, GameId, TournamentId, TournamentParticipantId)] =
        sql"""UPDATE tournament_participant SET withdrawn = $bool
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND tournament_participant_id = $participantId""".command

    /** Withdraws a participant, or — a ladder's re-entry — brings one back. */
    def setWithdrawn(
        game: GameId,
        tournament: TournamentId,
        id: TournamentParticipantId,
        withdrawn: Boolean
    ): IO[Unit] =
        session.execute(updateWithdrawn)((withdrawn, game, tournament, id)).void

    private val updateFinalRank: Command[(Option[Int], GameId, TournamentId, TournamentParticipantId)] =
        sql"""UPDATE tournament_participant SET final_rank = ${int4.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND tournament_participant_id = $participantId""".command

    def setFinalRank(game: GameId, tournament: TournamentId, id: TournamentParticipantId, rank: Option[Int]): IO[Unit] =
        session.execute(updateFinalRank)((rank, game, tournament, id)).void

    private val updateLadderRank: Command[(Int, GameId, TournamentId, TournamentParticipantId)] =
        sql"""UPDATE tournament_participant SET ladder_rank = $int4
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND tournament_participant_id = $participantId""".command

    def setLadderRank(game: GameId, tournament: TournamentId, id: TournamentParticipantId, rank: Int): IO[Unit] =
        session.execute(updateLadderRank)((rank, game, tournament, id)).void
}
