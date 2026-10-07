package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** Who has signed up for a tournament (V53): a player, and in a character game the character they entered. */
class EntryRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val entryId = SkunkIdCodecs.entryId
    private val playerId = SkunkIdCodecs.playerId
    private val characterId = SkunkIdCodecs.characterId

    private val insertEntry: Query[(GameId, TournamentId, PlayerId), EntryId] =
        sql"""INSERT INTO tournament_entry (game_id, tournament_id, player_id) VALUES ($gameId, $tournamentId, $playerId)
          RETURNING entry_id""".query(entryId)

    private val insertCharacterEntry: Command[(GameId, TournamentId, EntryId, CharacterId)] =
        sql"""INSERT INTO character_tournament_entry (game_id, tournament_id, entry_id, character_id)
          VALUES ($gameId, $tournamentId, $entryId, $characterId)""".command

    private val columns: Fragment[Void] =
        sql"""e.game_id, e.tournament_id, e.entry_id, e.player_id, ce.character_id
          FROM tournament_entry e
          LEFT JOIN character_tournament_entry ce
                 ON ce.game_id = e.game_id AND ce.tournament_id = e.tournament_id AND ce.entry_id = e.entry_id"""

    private val row = gameId *: tournamentId *: entryId *: playerId *: characterId.opt

    private val selectForTournament: Query[(GameId, TournamentId), TournamentEntry] =
        sql"""SELECT $columns WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId
          ORDER BY e.entry_id""".query(row).to[TournamentEntry]

    private val selectOne: Query[(GameId, TournamentId, EntryId), TournamentEntry] =
        sql"""SELECT $columns WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId AND e.entry_id = $entryId"""
            .query(row)
            .to[TournamentEntry]

    private val selectForPlayer: Query[(GameId, TournamentId, PlayerId), TournamentEntry] =
        sql"""SELECT $columns WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId AND e.player_id = $playerId
          ORDER BY e.entry_id""".query(row).to[TournamentEntry]

    /** Signs up `entry.playerId`, entering `entry.characterId` in a character game. */
    def create(entry: TournamentEntry): IO[TournamentEntry] =
        for {
            id <- session.unique(insertEntry)((entry.gameId, entry.tournamentId, entry.playerId))
            _ <- entry.characterId.traverse_(c =>
                session.execute(insertCharacterEntry)((entry.gameId, entry.tournamentId, id, c))
            )
        } yield entry.copy(entryId = id)

    /** Every entry, in the order they were made. */
    def listForTournament(game: GameId, tournament: TournamentId): IO[List[TournamentEntry]] =
        session.execute(selectForTournament)((game, tournament))

    def read(game: GameId, tournament: TournamentId, entry: EntryId): IO[Option[TournamentEntry]] =
        session.option(selectOne)((game, tournament, entry))

    /** The entries one player has made in a tournament: at most one, while a player may enter only once. */
    def listForPlayer(game: GameId, tournament: TournamentId, player: PlayerId): IO[List[TournamentEntry]] =
        session.execute(selectForPlayer)((game, tournament, player))

    private val deleteCharacterEntry: Command[(GameId, TournamentId, EntryId)] =
        sql"""DELETE FROM character_tournament_entry
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND entry_id = $entryId""".command

    private val deleteEntry: Command[(GameId, TournamentId, EntryId)] =
        sql"""DELETE FROM tournament_entry
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND entry_id = $entryId""".command

    /** Removes an entry before the tournament starts, when nothing has been seeded from it yet. After the start an
      * entry stays, and withdrawing is its participant's `withdrawn`.
      */
    def delete(game: GameId, tournament: TournamentId, entry: EntryId): IO[Unit] =
        session.execute(deleteCharacterEntry)((game, tournament, entry)) *>
            session.execute(deleteEntry)((game, tournament, entry)).void
}
