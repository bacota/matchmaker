package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._

/** Reads and writes `entrant` and its subtypes: `player_entrant` in a plain game, `character_entrant` in a character
  * game.
  *
  * A character entry names no player. It is read with its character's owner as it stands now -- the `entrant_player`
  * view -- since the owner is who plays it, and may have changed since it entered.
  *
  * Opens no transaction of its own; that is the calling service's job. An entry and its subtype name each other, and
  * each is written or removed with the other in one statement.
  */
class EntrantRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val entrantId = SkunkIdCodecs.entrantId
    private val playerId = SkunkIdCodecs.playerId
    private val characterId = SkunkIdCodecs.characterId
    private val gameType = SkunkCodecs.gameType
    private val instant = SkunkCodecs.instant

    // The entry and its subtype in one statement, so the deferred key between them holds however the caller holds its
    // transaction.
    private val insertPlayer: Query[(GameId, TournamentId, Int, PlayerId), EntrantId *: Instant *: EmptyTuple] =
        sql"""WITH e AS (
                 INSERT INTO entrant (game_id, tournament_id, game_type, rating_at_entry)
                 VALUES ($gameId, $tournamentId, 'P', $int4)
                 RETURNING game_id, tournament_id, entrant_id, entered_at),
               p AS (
                 INSERT INTO player_entrant (game_id, tournament_id, entrant_id, player_id)
                 SELECT game_id, tournament_id, entrant_id, $playerId FROM e)
          SELECT entrant_id, entered_at FROM e""".query(entrantId *: instant)

    private val insertCharacter: Query[(GameId, TournamentId, Int, CharacterId), EntrantId *: Instant *: EmptyTuple] =
        sql"""WITH e AS (
                 INSERT INTO entrant (game_id, tournament_id, game_type, rating_at_entry)
                 VALUES ($gameId, $tournamentId, 'C', $int4)
                 RETURNING game_id, tournament_id, entrant_id, entered_at),
               c AS (
                 INSERT INTO character_entrant (game_id, tournament_id, entrant_id, character_id)
                 SELECT game_id, tournament_id, entrant_id, $characterId FROM e)
          SELECT entrant_id, entered_at FROM e""".query(entrantId *: instant)

    /** Enters `player` in a plain game, or in a character game their `character`, at the rating `player` has now. */
    def create(
        game: GameId,
        gt: GameType,
        tournament: TournamentId,
        player: PlayerId,
        character: Option[CharacterId],
        rating: Int
    ): IO[Entrant] =
        ((gt, character) match {
            case (GameType.Character, Some(c)) => session.unique(insertCharacter)((game, tournament, rating, c))
            case (GameType.Plain, None)        => session.unique(insertPlayer)((game, tournament, rating, player))
            case _ => IO.raiseError(IllegalArgumentException(s"a ${gt} game's entry with character $character"))
        }).map(made =>
            Entrant(
              gameId = game,
              tournamentId = tournament,
              entrantId = made.head,
              playerId = Some(player),
              characterId = character,
              ratingAtEntry = rating,
              enteredAt = made.tail.head
            )
        )

    // Who plays each entry now, with their nickname, and for a character entry the character and its name.
    private val selectAll: Query[
      (GameId, TournamentId),
      (EntrantId, Option[Long], Option[Long], Int, Instant, Option[Instant], Option[String], Option[String])
    ] =
        sql"""SELECT e.entrant_id, ep.player_id, ce.character_id, e.rating_at_entry, e.entered_at, e.withdrawn_at,
                 p.nickname, c.name
          FROM entrant e
          LEFT JOIN entrant_player ep
                 ON ep.game_id = e.game_id AND ep.tournament_id = e.tournament_id AND ep.entrant_id = e.entrant_id
          LEFT JOIN player p ON p.player_id = ep.player_id
          LEFT JOIN character_entrant ce
                 ON ce.game_id = e.game_id AND ce.tournament_id = e.tournament_id AND ce.entrant_id = e.entrant_id
          LEFT JOIN "character" c ON c.game_id = ce.game_id AND c.character_id = ce.character_id
          WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId
          ORDER BY e.entered_at, e.entrant_id""".query(
          entrantId *: int8.opt *: int8.opt *: int4 *: instant *: instant.opt *: text.opt *: text.opt
        )

    /** Every entry in a tournament, withdrawn ones included, in the order they were made. */
    def list(game: GameId, tournament: TournamentId): IO[List[Entrant]] =
        session
            .execute(selectAll)((game, tournament))
            .map(_.map { case (id, player, character, rating, at, withdrawn, nickname, characterName) =>
                Entrant(
                  gameId = game,
                  tournamentId = tournament,
                  entrantId = id,
                  playerId = player.map(PlayerId(_)),
                  characterId = character.map(CharacterId(_)),
                  ratingAtEntry = rating,
                  enteredAt = at,
                  withdrawnAt = withdrawn,
                  nickname = nickname.getOrElse(""),
                  characterName = characterName
                )
            })

    /** Who plays each entry now: the player who entered it, or for a character entry the character's current owner — or
      * nobody, for a character that has none.
      */
    def players(game: GameId, tournament: TournamentId): IO[Map[EntrantId, Option[PlayerId]]] =
        list(game, tournament).map(_.map(e => e.entrantId -> e.playerId).toMap)

    // Each entry with whether anybody plays it now, that player's rating in the game if they have one, and the rating
    // it entered with. entrant_player holds a row only for an entry somebody plays.
    private val selectRatings: Query[(GameId, TournamentId), (EntrantId, Boolean, Option[Int], Int)] =
        sql"""SELECT e.entrant_id, ep.player_id IS NOT NULL, r.rating, e.rating_at_entry
          FROM entrant e
          LEFT JOIN entrant_player ep
                 ON ep.game_id = e.game_id AND ep.tournament_id = e.tournament_id AND ep.entrant_id = e.entrant_id
          LEFT JOIN elo_rating r ON r.game_id = ep.game_id AND r.player_id = ep.player_id
          WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId""".query(entrantId *: bool *: int4.opt *: int4)

    /** Each entry's rating now, withdrawn ones included, for seeding: its player's overall rating in the game -- a
      * character entry's owner's -- or the starting rating for a player with none, and the rating it entered with for a
      * character nobody owns.
      */
    def ratingsNow(game: GameId, tournament: TournamentId): IO[Map[EntrantId, Int]] =
        session
            .execute(selectRatings)((game, tournament))
            .map(_.map { (id, isPlayed, rating, atEntry) =>
                id -> (if (isPlayed) rating.getOrElse(EloRating.initial) else atEntry)
            }.toMap)

    // Every statement below names the entry by its whole key, tournament included: every key on `entrant` and its two
    // subtypes is (game_id, tournament_id, entrant_id), and the game alone would read the entries of every tournament
    // and challenge the game has had.
    private val lockOne: Query[(GameId, TournamentId, EntrantId), (Option[Long], Option[Instant])] =
        sql"""SELECT ep.player_id, e.withdrawn_at FROM entrant e
          LEFT JOIN entrant_player ep
                 ON ep.game_id = e.game_id AND ep.tournament_id = e.tournament_id AND ep.entrant_id = e.entrant_id
          WHERE e.game_id = $gameId AND e.tournament_id = $tournamentId AND e.entrant_id = $entrantId
          FOR UPDATE OF e""".query(int8.opt *: instant.opt)

    /** Who plays an entry of this tournament now, and when it was withdrawn, locked for the rest of the transaction;
      * `None` for an entry that is not this tournament's.
      */
    def readForUpdate(
        game: GameId,
        tournament: TournamentId,
        id: EntrantId
    ): IO[Option[(Option[PlayerId], Option[Instant])]] =
        session.option(lockOne)((game, tournament, id)).map(_.map((p, w) => (p.map(PlayerId(_)), w)))

    private val setWithdrawn: Command[(Option[Instant], GameId, TournamentId, EntrantId)] =
        sql"""UPDATE entrant SET withdrawn_at = ${instant.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND entrant_id = $entrantId""".command

    /** Withdraws an entry, or with `None` takes it back. */
    def setWithdrawnAt(game: GameId, tournament: TournamentId, id: EntrantId, at: Option[Instant]): IO[Unit] =
        session.execute(setWithdrawn)((at, game, tournament, id)).void

    private val deleteOne
        : Command[(GameId, TournamentId, EntrantId, GameId, TournamentId, EntrantId, GameId, TournamentId, EntrantId)] =
        sql"""WITH c AS (DELETE FROM character_entrant
                        WHERE game_id = $gameId AND tournament_id = $tournamentId AND entrant_id = $entrantId),
               p AS (DELETE FROM player_entrant
                        WHERE game_id = $gameId AND tournament_id = $tournamentId AND entrant_id = $entrantId)
          DELETE FROM entrant WHERE game_id = $gameId AND tournament_id = $tournamentId AND entrant_id = $entrantId""".command

    /** Removes an entry outright, with its subtype: only before the tournament starts, when nothing refers to it. */
    def delete(game: GameId, tournament: TournamentId, id: EntrantId): IO[Unit] =
        session.execute(deleteOne)((game, tournament, id, game, tournament, id, game, tournament, id)).void
}
