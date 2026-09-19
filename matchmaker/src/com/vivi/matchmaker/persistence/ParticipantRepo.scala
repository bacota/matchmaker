package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.Instant
import com.vivi.matchmaker.model._

class ParticipantRepo(session: Session[IO]) {
    private val participantId = SkunkIdCodecs.participantId
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId
    private val playerId = SkunkIdCodecs.playerId
    private val characterId = SkunkIdCodecs.characterId
    private val gameType = SkunkCodecs.gameType
    private val gameRoleId = SkunkIdCodecs.gameRoleId
    private val instant = SkunkCodecs.instant

    /* A seat, stamped on creation with what its player wants to hear about it.
     *
     * INSERT ... SELECT rather than VALUES, because the seat's four `notify_*` columns are NOT NULL (V14)
     * and what goes in them is the chain resolved at this moment: what the player has said about this
     * game, else what they have said in general, else what the game asks for. That is the same rule as
     * `NotificationLevels.resolve`, done here instead of read and passed in, so that a seat cannot be
     * created unstamped and the resolution cannot happen a query earlier than the row it describes.
     *
     * The LEFT JOIN is the "else what they have said in general": a player who has never opened this
     * game's settings has no `player_game` row, and NULLs from the outer join fall through the
     * COALESCE exactly as an unanswered question does.
     *
     * The player is named twice -- once as the seat's own column, once to resolve the chain -- so the
     * value is bound twice; `game` likewise. */
    private val insertParticipant
        : Query[(GameId, MatchId, GameType, PlayerId, Boolean, Boolean, Option[Instant], GameRoleId), ParticipantId] =
        sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, pending, completed, due, game_role_id,
              notify_match_started, notify_turn_taken, notify_your_turn, notify_match_ended)
          SELECT $gameId, $matchId, $gameType, $playerId, $bool, $bool, ${instant.opt}, $gameRoleId,
                 COALESCE(pg.notify_match_started, pl.notify_match_started,
                          g.notify_match_started),
                 COALESCE(pg.notify_turn_taken, pl.notify_turn_taken,
                          g.notify_turn_taken),
                 COALESCE(pg.notify_your_turn, pl.notify_your_turn,
                          g.notify_your_turn),
                 COALESCE(pg.notify_match_ended, pl.notify_match_ended,
                          g.notify_match_ended)
          FROM player pl
              CROSS JOIN game g
              LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = g.game_id
          WHERE pl.player_id = $playerId AND g.game_id = $gameId
          RETURNING participant_id"""
            .query(participantId)
            .contramap { case t @ (game, _, _, player, _, _, _, _) => t ++ (player, game) }

    private val insertCharacterParticipant: Command[(GameId, ParticipantId, CharacterId)] =
        sql"""INSERT INTO character_participant (game_id, participant_id, game_type, character_id)
          VALUES ($gameId, $participantId, 'C', $characterId)""".command

    // participant_id is only unique within its game_id — the table's primary key is the composite
    // (game_id, participant_id), with no separate UNIQUE(participant_id) the way character has —
    // so both columns are required in the WHERE clause here, not participant_id alone.
    private val participantRow
        : Codec[(GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], GameRoleId, Option[Long])] =
        gameType *: matchId *: playerId *: bool *: bool *: instant.opt *: gameRoleId *: int8.opt

    private val selectParticipant: Query[
      (GameId, ParticipantId),
      (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], GameRoleId, Option[Long])
    ] =
        sql"""SELECT p.game_type, p.match_id, p.player_id, p.pending, p.completed, p.due, p.game_role_id,
                 cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.participant_id = $participantId"""
            .query(participantRow)

    // Everyone in one match, with the player's external (Cognito) id, which is what the game engine
    // knows a player by — it authenticates them itself and never sees matchmaker's player ids.
    private val selectParticipantsForMatch: Query[
      (GameId, MatchId),
      (ParticipantId, GameType, PlayerId, String, Boolean, Boolean, Option[Instant], GameRoleId, String, Option[Long])
    ] =
        sql"""SELECT p.participant_id, p.game_type, p.player_id, pl.external_id, p.pending, p.completed,
                 p.due, p.game_role_id, r.name, cp.character_id
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          JOIN game_role r ON r.game_id = p.game_id AND r.game_role_id = p.game_role_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
            .query(
              participantId *: gameType *: playerId *: text *: bool *: bool *: instant.opt *: gameRoleId *: text *: int8.opt
            )

    /* Whose clock has run out, decided by the database's own now().
     *
     * The comparison is here rather than in Scala so that one clock settles it. `due` was written
     * from times that came out of the database, the completion it leads to is stamped by now(),
     * and the API runs in lambdas whose clocks are not the database's and need not agree with each
     * other — a player's turn must not end early or late because of which instance they reached.
     *
     * `pending AND NOT completed` is what "it is still their turn" means; `due` is only set while
     * that is true and the match has a time limit, so a row with a past `due` is exactly a run-out
     * turn. */
    private val selectOverdueForMatch: Query[
      (GameId, MatchId),
      (ParticipantId, GameType, PlayerId, Boolean, Boolean, Option[Instant], GameRoleId, Option[Long])
    ] =
        sql"""SELECT p.participant_id, p.game_type, p.player_id, p.pending, p.completed,
                 p.due, p.game_role_id, cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
            AND p.pending AND NOT p.completed AND p.due < now()
          ORDER BY p.participant_id"""
            .query(participantId *: gameType *: playerId *: bool *: bool *: instant.opt *: gameRoleId *: int8.opt)

    /** The participants of a match whose turn it is and whose deadline has passed, as of the database's clock.
      */
    def listOverdueForMatch(gameId: GameId, matchId: MatchId): IO[List[Participant]] =
        session
            .execute(selectOverdueForMatch)((gameId, matchId))
            .map(_.map { case (id, gt, playerId, pending, completed, due, roleId, characterIdValue) =>
                toParticipant(id, gameId, (gt, matchId, playerId, pending, completed, due, roleId, characterIdValue))
            })

    private val updateParticipant
        : Command[(PlayerId, Boolean, Boolean, Option[Instant], GameRoleId, GameId, ParticipantId)] =
        sql"""UPDATE participant SET player_id = $playerId, pending = $bool, completed = $bool,
          due = ${instant.opt}, game_role_id = $gameRoleId
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    private def toParticipant(
        id: ParticipantId,
        gameId: GameId,
        row: (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], GameRoleId, Option[Long])
    ): Participant = {
        val (gameType, matchId, playerId, pending, completed, due, roleId, characterIdValue) = row
        gameType match {
            case GameType.Character =>
                val cid = characterIdValue.getOrElse(
                  throw new IllegalStateException(
                    s"participant ${id.value} is game_type 'C' but has no character_participant row"
                  )
                )
                CharacterParticipant(id, gameId, matchId, playerId, pending, completed, due, CharacterId(cid), roleId)
            case GameType.Plain =>
                PlainParticipant(id, gameId, matchId, playerId, pending, completed, due, roleId)
        }
    }

    def create(p: Participant): IO[Participant] = {
        val gt = p match {
            case _: CharacterParticipant => GameType.Character
            case _: PlainParticipant     => GameType.Plain
        }
        for {
            id <- session.unique(insertParticipant)(
              (p.gameId, p.matchId, gt, p.playerId, p.pending, p.completed, p.due, p.gameRoleId)
            )
            _ <- p match {
                case cp: CharacterParticipant =>
                    session.execute(insertCharacterParticipant)((p.gameId, id, cp.characterId)).void
                case _: PlainParticipant => IO.unit
            }
        } yield p match {
            case cp: CharacterParticipant => cp.copy(participantId = id)
            case pp: PlainParticipant     => pp.copy(participantId = id)
        }
    }

    def read(gameId: GameId, id: ParticipantId): IO[Option[Participant]] =
        session.option(selectParticipant)((gameId, id)).map(_.map(row => toParticipant(id, gameId, row)))

    def update(p: Participant): IO[Unit] =
        session
            .execute(updateParticipant)(
              (p.playerId, p.pending, p.completed, p.due, p.gameRoleId, p.gameId, p.participantId)
            )
            .void

    /* Every seat in a match, retired at once.
     *
     * The same three columns the results and forfeit paths write per seat -- turn given up, no
     * deadline, finished -- because a cancelled match is over in exactly the way those are: nobody is
     * waiting on anybody, and no clock is running. One statement rather than a read and a write per
     * seat, since there is nothing to decide per seat.
     *
     * `NOT completed` keeps a repeat harmless and keeps the row count honest: the flag is sticky
     * everywhere it is written, and a seat already retired has nothing to retire.
     *
     * update_date is left to `trg_participant_update_date`, as in `updateParticipant` above. */
    private val completeParticipantsForMatch: Command[(GameId, MatchId)] =
        sql"""UPDATE participant SET pending = false, completed = true, due = NULL
          WHERE game_id = $gameId AND match_id = $matchId AND NOT completed""".command

    /** Retires every seat in a match: nobody's turn, no deadline, finished.
      *
      * For cancelling, which ends a match without a result. Completion marks its seats seat by seat as it records what
      * each of them scored; a cancellation has nothing to record, so it says the one thing that is true of all of them.
      *
      * Matters beyond tidiness: a seat that still reads `pending` in a called-off match is a seat that other queries
      * have to remember to exclude by joining `match`, and `NotificationRepo.restampParticipants` is the one that
      * stopped being able to.
      */
    def completeForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
        session.execute(completeParticipantsForMatch)((gameId, matchId)).void

    // character_participant has a FK to participant, so its rows go first.
    private val deleteCharacterParticipantsForMatch: Command[(GameId, MatchId)] =
        sql"""DELETE FROM character_participant cp
          USING participant p
          WHERE p.game_id = cp.game_id AND p.participant_id = cp.participant_id
            AND p.game_id = $gameId AND p.match_id = $matchId""".command

    private val deleteParticipantsForMatch: Command[(GameId, MatchId)] =
        sql"DELETE FROM participant WHERE game_id = $gameId AND match_id = $matchId".command

    /** Removes every participant in a match. Only used to undo a match whose game the engine failed to create;
      * participants of a match that is actually being played are completed, never deleted.
      */
    def deleteForMatch(gameId: GameId, matchId: MatchId): IO[Unit] =
        for {
            _ <- session.execute(deleteCharacterParticipantsForMatch)((gameId, matchId))
            _ <- session.execute(deleteParticipantsForMatch)((gameId, matchId))
        } yield ()

    /** Everyone playing one match, together with the player's external id and role name.
      *
      * The two extra columns are there for the game-engine calls: the engine is told which Cognito identity plays which
      * role, and knows nothing of matchmaker's own player or role ids. Both are inner joins -- a participant always has
      * a player and, since V4, always has a role.
      */
    def listForMatch(gameId: GameId, matchId: MatchId): IO[List[(Participant, String, String)]] =
        session
            .execute(selectParticipantsForMatch)((gameId, matchId))
            .map(_.map {
                case (id, gt, playerId, externalId, pending, completed, due, roleId, roleName, characterIdValue) =>
                    val participant = toParticipant(
                      id,
                      gameId,
                      (gt, matchId, playerId, pending, completed, due, roleId, characterIdValue)
                    )
                    (participant, externalId, roleName)
            })
}
