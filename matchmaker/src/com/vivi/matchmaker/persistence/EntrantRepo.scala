package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._

/** Reads and writes `entrant` and `character_entrant` (V52): who has entered a tournament, and with what.
  *
  * A character entry is read with its character's owner as it stands now — `character.player_id` — beside the player
  * who entered it: the owner is who plays it, and may have changed since.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class EntrantRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val entrantId = SkunkIdCodecs.entrantId
    private val playerId = SkunkIdCodecs.playerId
    private val characterId = SkunkIdCodecs.characterId
    private val gameType = SkunkCodecs.gameType
    private val instant = SkunkCodecs.instant

    private val insertEntrant
        : Query[(GameId, TournamentId, GameType, PlayerId, Int), EntrantId *: Instant *: EmptyTuple] =
        sql"""INSERT INTO entrant (game_id, tournament_id, game_type, player_id, rating_at_entry)
          VALUES ($gameId, $tournamentId, $gameType, $playerId, $int4)
          RETURNING entrant_id, entered_at""".query(entrantId *: instant)

    private val insertCharacter: Command[(GameId, EntrantId, TournamentId, CharacterId)] =
        sql"""INSERT INTO character_entrant (game_id, entrant_id, tournament_id, game_type, character_id)
          VALUES ($gameId, $entrantId, $tournamentId, 'C', $characterId)""".command

    /** Enters a player, or in a character game a player's character, at the rating they have now. */
    def create(
        game: GameId,
        gt: GameType,
        tournament: TournamentId,
        player: PlayerId,
        character: Option[CharacterId],
        rating: Int
    ): IO[Entrant] =
        for {
            made <- session.unique(insertEntrant)((game, tournament, gt, player, rating))
            (id, at) = (made.head, made.tail.head)
            _ <- character.traverse_(c => session.execute(insertCharacter)((game, id, tournament, c)))
        } yield Entrant(game, tournament, id, player, character, rating, at)

    // The entering player's nickname and, for a character entry, the character's name and its owner now.
    private val selectAll: Query[
      (GameId, TournamentId),
      (EntrantId, PlayerId, Option[Long], Int, Instant, Option[Instant], String, Option[String], Option[Long])
    ] =
        sql"""SELECT e.entrant_id, e.player_id, ce.character_id, e.rating_at_entry, e.entered_at, e.withdrawn_at,
                 p.nickname, c.name, c.player_id
          FROM entrant e
          JOIN player p ON p.player_id = e.player_id
          LEFT JOIN character_entrant ce ON ce.game_id = e.game_id AND ce.entrant_id = e.entrant_id
          LEFT JOIN character c ON c.game_id = ce.game_id AND c.character_id = ce.character_id
          WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId
          ORDER BY e.entered_at, e.entrant_id""".query(
          entrantId *: playerId *: int8.opt *: int4 *: instant *: instant.opt *: text *: text.opt *: int8.opt
        )

    /** Every entry in a tournament, withdrawn ones included, in the order they were made. */
    def list(game: GameId, tournament: TournamentId): IO[List[Entrant]] =
        session
            .execute(selectAll)((game, tournament))
            .map(_.map { case (id, player, character, rating, at, withdrawn, nickname, characterName, _) =>
                Entrant(
                  game,
                  tournament,
                  id,
                  player,
                  character.map(CharacterId(_)),
                  rating,
                  at,
                  withdrawn,
                  nickname,
                  characterName
                )
            })

    /** Who plays each entry now: the player who entered it, or for a character entry the character's current owner — or
      * nobody, for a character that has none.
      */
    def players(game: GameId, tournament: TournamentId): IO[Map[EntrantId, Option[PlayerId]]] =
        session
            .execute(selectAll)((game, tournament))
            .map(_.map { case (id, player, character, _, _, _, _, _, owner) =>
                id -> (if (character.isDefined) owner.map(PlayerId(_)) else Some(player))
            }.toMap)

    private val lockOne: Query[(GameId, EntrantId), (TournamentId, PlayerId, Option[Instant])] =
        sql"""SELECT tournament_id, player_id, withdrawn_at FROM entrant
          WHERE game_id = $gameId AND entrant_id = $entrantId FOR UPDATE""".query(
          tournamentId *: playerId *: instant.opt
        )

    /** An entry's tournament, who entered it, and when it was withdrawn, locked for the rest of the transaction. */
    def readForUpdate(game: GameId, id: EntrantId): IO[Option[(TournamentId, PlayerId, Option[Instant])]] =
        session.option(lockOne)((game, id))

    private val setWithdrawn: Command[(Option[Instant], GameId, EntrantId)] =
        sql"UPDATE entrant SET withdrawn_at = ${instant.opt} WHERE game_id = $gameId AND entrant_id = $entrantId".command

    /** Withdraws an entry, or with `None` takes it back. */
    def setWithdrawnAt(game: GameId, id: EntrantId, at: Option[Instant]): IO[Unit] =
        session.execute(setWithdrawn)((at, game, id)).void

    private val countForPlayer: Query[(GameId, TournamentId, PlayerId), Long] =
        sql"""SELECT count(*) FROM entrant
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId""".query(int8)

    /** How many entries a player has made in a tournament, withdrawn ones included. */
    def count(game: GameId, tournament: TournamentId, player: PlayerId): IO[Long] =
        session.unique(countForPlayer)((game, tournament, player))

    private val deleteCharacter: Command[(GameId, EntrantId)] =
        sql"DELETE FROM character_entrant WHERE game_id = $gameId AND entrant_id = $entrantId".command

    private val deleteOne: Command[(GameId, EntrantId)] =
        sql"DELETE FROM entrant WHERE game_id = $gameId AND entrant_id = $entrantId".command

    /** Removes an entry outright: only before the tournament starts, when nothing refers to it. */
    def delete(game: GameId, id: EntrantId): IO[Unit] =
        session.execute(deleteCharacter)((game, id)) *> session.execute(deleteOne)((game, id)).void
}
