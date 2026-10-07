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
     * game, else what they have said in general, else send it. That is the same rule as
     * `NotificationLevels.apply`, done here instead of read and passed in, so that a seat cannot be
     * created unstamped and the resolution cannot happen a query earlier than the row it describes.
     *
     * The LEFT JOIN is the "else what they have said in general": a player who has never opened this
     * game's settings has no `player_game` row, and NULLs from the outer join fall through the
     * COALESCE exactly as an unanswered question does.
     *
     * The player and the game are each named twice -- once as the seat's own column, once to resolve
     * the chain -- so each value is bound twice. */
    private val insertParticipant: Query[
      (GameId, MatchId, GameType, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Int, Option[Int]),
      ParticipantId
    ] =
        // `completed_at` (V41) is the database's now() for a seat created already finished, as
        // `updateParticipant` stamps one below; Scala says only whether it is.
        sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, pending, completed_at, due, game_role_id,
              elo_start, elo_role_start, notify_match_started, notify_turn_taken, notify_your_turn, notify_match_ended)
          SELECT $gameId, $matchId, $gameType, $playerId, $bool, CASE WHEN $bool THEN now() END, ${instant.opt},
                 ${gameRoleId.opt}, $int4, ${int4.opt},
                 COALESCE(pg.notify_match_started, pl.notify_match_started, TRUE),
                 COALESCE(pg.notify_turn_taken, pl.notify_turn_taken, TRUE),
                 COALESCE(pg.notify_your_turn, pl.notify_your_turn, TRUE),
                 COALESCE(pg.notify_match_ended, pl.notify_match_ended, TRUE)
          FROM player pl
              LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = $gameId
          WHERE pl.player_id = $playerId
          RETURNING participant_id"""
            .query(participantId)
            .contramap { case t @ (game, _, _, player, _, _, _, _, _, _) => t ++ (game, player) }

    private val insertCharacterParticipant: Command[(GameId, ParticipantId, CharacterId)] =
        sql"""INSERT INTO character_participant (game_id, participant_id, game_type, character_id)
          VALUES ($gameId, $participantId, 'C', $characterId)""".command

    // participant_id is only unique within its game_id — the table's primary key is the composite
    // (game_id, participant_id), with no separate UNIQUE(participant_id) the way character has —
    // so both columns are required in the WHERE clause here, not participant_id alone.
    private val participantRow
        : Codec[(GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])] =
        gameType *: matchId *: playerId *: bool *: bool *: instant.opt *: gameRoleId.opt *: int8.opt

    private val selectParticipant: Query[
      (GameId, ParticipantId),
      (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])
    ] =
        sql"""SELECT p.game_type, p.match_id, p.player_id, p.pending, p.completed_at IS NOT NULL, p.due, p.game_role_id,
                 cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.participant_id = $participantId"""
            .query(participantRow)

    // Everyone in one match, with the player's external (Cognito) id, which is what the game engine
    // knows a player by — it authenticates them itself and never sees matchmaker's player ids.
    private val selectParticipantsForMatch: Query[
      (GameId, MatchId),
      (
          ParticipantId,
          GameType,
          PlayerId,
          String,
          Boolean,
          Boolean,
          Option[Instant],
          Option[GameRoleId],
          Option[String],
          Option[Long]
      )
    ] =
        sql"""SELECT p.participant_id, p.game_type, p.player_id, pl.external_id, p.pending,
                 p.completed_at IS NOT NULL, p.due, p.game_role_id, r.name, cp.character_id
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          -- LEFT: a seat whose role is still being chosen in its engine (V52) has none yet.
          LEFT JOIN game_role r ON r.game_id = p.game_id AND r.game_role_id = p.game_role_id
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
            .query(
              participantId *: gameType *: playerId *: text *: bool *: bool *: instant.opt *: gameRoleId.opt *: text.opt *:
                  int8.opt
            )

    /* Whose clock has run out, decided by the database's own now().
     *
     * The comparison is here rather than in Scala so that one clock settles it. `due` was written
     * from times that came out of the database, the completion it leads to is stamped by now(),
     * and the API runs in lambdas whose clocks are not the database's and need not agree with each
     * other — a player's turn must not end early or late because of which instance they reached.
     *
     * `pending` on a seat not yet completed is what "it is still their turn" means; `due` is only set while
     * that is true and the match has a time limit, so a row with a past `due` is exactly a run-out
     * turn. */
    private val selectOverdueForMatch: Query[
      (GameId, MatchId),
      (ParticipantId, GameType, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])
    ] =
        sql"""SELECT p.participant_id, p.game_type, p.player_id, p.pending, p.completed_at IS NOT NULL,
                 p.due, p.game_role_id, cp.character_id
          FROM participant p
          LEFT JOIN character_participant cp ON cp.game_id = p.game_id AND cp.participant_id = p.participant_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
            AND p.pending AND p.completed_at IS NULL AND p.due < now()
          ORDER BY p.participant_id"""
            .query(participantId *: gameType *: playerId *: bool *: bool *: instant.opt *: gameRoleId.opt *: int8.opt)

    /** The participants of a match whose turn it is and whose deadline has passed, as of the database's clock.
      */
    def listOverdueForMatch(gameId: GameId, matchId: MatchId): IO[List[Participant]] =
        session
            .execute(selectOverdueForMatch)((gameId, matchId))
            .map(_.map { case (id, gt, playerId, pending, completed, due, roleId, characterIdValue) =>
                toParticipant(id, gameId, (gt, matchId, playerId, pending, completed, due, roleId, characterIdValue))
            })

    private val updateParticipant
        : Command[(PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], GameId, ParticipantId)] =
        // A seat finished keeps the time it was first finished at (V41): completing is sticky, and a
        // later write to a finished seat -- a re-stamp of its settings, a repeated callback -- is not
        // a second ending. One not finished has none.
        //
        // A role is never taken off a seat: one chosen in the engine (V52) may have been written since `p` was read.
        sql"""UPDATE participant SET player_id = $playerId, pending = $bool,
          completed_at = CASE WHEN $bool THEN COALESCE(completed_at, now()) END,
          due = ${instant.opt}, game_role_id = COALESCE(${gameRoleId.opt}, game_role_id)
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    private def toParticipant(
        id: ParticipantId,
        gameId: GameId,
        row: (GameType, MatchId, PlayerId, Boolean, Boolean, Option[Instant], Option[GameRoleId], Option[Long])
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

    /** Seats `p`, whose player was rated `eloStart` in the game as the match began (V43), and `eloRoleStart` in the
      * seat's role (V49) — none for a game whose roles are unimportant.
      */
    def create(p: Participant, eloStart: Int, eloRoleStart: Option[Int] = None): IO[Participant] = {
        val gt = p match {
            case _: CharacterParticipant => GameType.Character
            case _: PlainParticipant     => GameType.Plain
        }
        for {
            id <- session.unique(insertParticipant)(
              (p.gameId, p.matchId, gt, p.playerId, p.pending, p.completed, p.due, p.gameRoleId, eloStart, eloRoleStart)
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

    /* Only a seat with no role yet: a role, once a seat has one, is the seat's for good. */
    private val updateRole: Command[(GameRoleId, Option[Int], GameId, ParticipantId)] =
        sql"""UPDATE participant SET game_role_id = $gameRoleId, elo_role_start = ${int4.opt}
          WHERE game_id = $gameId AND participant_id = $participantId AND game_role_id IS NULL""".command

    /** Gives a seat that has no role yet the role its player chose in the engine (V52), with what they were rated in it
      * as they chose it. A seat that already has a role keeps it.
      */
    def setRole(gameId: GameId, id: ParticipantId, role: GameRoleId, eloRoleStart: Option[Int]): IO[Unit] =
        session.execute(updateRole)((role, eloRoleStart, gameId, id)).void

    /* Every seat in a match, retired at once.
     *
     * The same three columns the results and forfeit paths write per seat -- turn given up, no
     * deadline, finished -- because a cancelled match is over in exactly the way those are: nobody is
     * waiting on anybody, and no clock is running. One statement rather than a read and a write per
     * seat, since there is nothing to decide per seat.
     *
     * `completed_at IS NULL` keeps a repeat harmless and keeps the row count honest: the flag is sticky
     * everywhere it is written, and a seat already retired has nothing to retire.
     *
     * update_date is left to `trg_participant_update_date`, as in `updateParticipant` above. */
    private val completeParticipantsForMatch: Command[(GameId, MatchId)] =
        sql"""UPDATE participant SET pending = false, completed_at = now(), due = NULL
          WHERE game_id = $gameId AND match_id = $matchId AND completed_at IS NULL""".command

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
      * role, and knows nothing of matchmaker's own player or role ids. A participant always has a player; its role is
      * `None` while the engine is still having it chosen (V52).
      */
    def listForMatch(gameId: GameId, matchId: MatchId): IO[List[(Participant, String, Option[String])]] =
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

    private val selectEloSeats: Query[(GameId, MatchId), ParticipantRepo.EloSeatRow] =
        sql"""SELECT participant_id, player_id, elo_start, game_role_id, elo_role_start FROM participant
          WHERE game_id = $gameId AND match_id = $matchId
          ORDER BY participant_id"""
            .query(participantId *: playerId *: int4 *: gameRoleId.opt *: int4.opt)
            .to[ParticipantRepo.EloSeatRow]

    /** Every seat in a match as rating sees it: whose it is, its role, and what they were rated as it began, overall
      * (V43) and in the role (V49).
      */
    def eloSeatsForMatch(gameId: GameId, matchId: MatchId): IO[List[ParticipantRepo.EloSeatRow]] =
        session.execute(selectEloSeats)((gameId, matchId))

    private val updateEloStartBy: Command[(Int, GameId, ParticipantId)] =
        sql"""UPDATE participant SET elo_start = elo_start + $int4
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    /** Moves a seat's starting rating by `change`: for a seat whose match began after another match of its player's was
      * rated differently than it is now (MatchService.setFriendly). The one change made to `elo_start` after the seat
      * is written.
      */
    def adjustEloStart(gameId: GameId, id: ParticipantId, change: Int): IO[Unit] =
        session.execute(updateEloStartBy)((change, gameId, id)).void

    private val updateEloRoleStartBy: Command[(Int, GameId, ParticipantId)] =
        sql"""UPDATE participant SET elo_role_start = elo_role_start + $int4
          WHERE game_id = $gameId AND participant_id = $participantId""".command

    /** As [[adjustEloStart]], for the seat's starting rating in its role (V49). A seat with none keeps none. */
    def adjustEloRoleStart(gameId: GameId, id: ParticipantId, change: Int): IO[Unit] =
        session.execute(updateEloRoleStartBy)((change, gameId, id)).void

    /* `t` is the match being reclassified. Seats of its players in the same game's matches that
     * began after it and are still being played, locked in seat order -- every caller takes them in
     * the same order. Built per call, since the players are a list of their own length. */
    private def selectLaterSeats(
        players: Int,
        noWait: Boolean
    ): Query[(GameId, MatchId, GameId, MatchId, List[PlayerId]), ParticipantRepo.LaterSeatRow] =
        sql"""SELECT p.participant_id, p.player_id, p.game_role_id, p.create_date > t.completed
          FROM participant p
          JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
          JOIN match t ON t.game_id = $gameId AND t.match_id = $matchId
          WHERE p.game_id = $gameId AND p.match_id <> $matchId
            AND p.player_id IN (${playerId.list(players)})
            AND m.completed IS NULL AND NOT m.cancelled
            AND m.create_date > t.create_date
          ORDER BY p.participant_id
          FOR UPDATE OF p #${if (noWait) "NOWAIT" else ""}"""
            .query(participantId *: playerId *: gameRoleId.opt *: bool)
            .to[ParticipantRepo.LaterSeatRow]

    /** The seats `players` hold in matches of the game that began after match `matchId` and are not over yet, each
      * locked FOR UPDATE, with whether it was made after that match completed — and so with a starting rating that took
      * in what that match did to its player. For reclassifying a completed match, which changes what it did.
      *
      * `noWait` refuses rather than waits for a seat somebody else holds: for a second look taken while holding rating
      * rows, where waiting on a match that is completing — which holds its seats and wants those rows — would deadlock.
      */
    def lockLaterSeats(
        gameId: GameId,
        matchId: MatchId,
        players: List[PlayerId],
        noWait: Boolean = false
    ): IO[List[ParticipantRepo.LaterSeatRow]] =
        if (players.isEmpty) IO.pure(Nil)
        else
            session
                .execute(selectLaterSeats(players.size, noWait))((gameId, matchId, gameId, matchId, players))

    private def selectLaterCompleted(players: Int): Query[(GameId, MatchId, GameId, MatchId, List[PlayerId]), Boolean] =
        sql"""SELECT EXISTS (
            SELECT 1 FROM participant p
            JOIN match m ON m.game_id = p.game_id AND m.match_id = p.match_id
            JOIN match t ON t.game_id = $gameId AND t.match_id = $matchId
            WHERE p.game_id = $gameId AND p.match_id <> $matchId
              AND p.player_id IN (${playerId.list(players)})
              AND m.completed IS NOT NULL
              AND p.create_date > t.completed)""".query(bool)

    /** Whether any of `players` has begun and finished another match of the game since match `matchId` finished: a
      * match whose starting ratings took in what that one did, and whose own result has been worked out from them.
      */
    def laterCompleted(gameId: GameId, matchId: MatchId, players: List[PlayerId]): IO[Boolean] =
        if (players.isEmpty) IO.pure(false)
        else session.unique(selectLaterCompleted(players.size))((gameId, matchId, gameId, matchId, players))
}

object ParticipantRepo {

    /** A seat's player and role, and what they were rated as the match began: overall (V43), and in the role (V49) —
      * none for a seat of a game whose roles are unimportant, or from before ratings were kept by role.
      */
    case class EloSeatRow(
        participantId: ParticipantId,
        playerId: PlayerId,
        eloStart: Int,
        // None for a seat that never got a role: a match forfeited while roles were still being chosen (V52).
        gameRoleId: Option[GameRoleId] = None,
        eloRoleStart: Option[Int] = None
    )

    /** A seat in a match still being played, which began after another of its player's matches: whether it began after
      * that match had finished, and so took in its rating's change.
      */
    case class LaterSeatRow(
        participantId: ParticipantId,
        playerId: PlayerId,
        gameRoleId: Option[GameRoleId],
        afterCompletion: Boolean
    )
}
