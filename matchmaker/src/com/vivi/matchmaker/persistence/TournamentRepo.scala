package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** A tournament and the settings an elimination one adds (V53), read and written together; and who has been invited to
  * one.
  *
  * Like every repository here it opens no transaction: the service that calls it holds one, and takes [[readForUpdate]]
  * when what it reads decides what it writes.
  */
class TournamentRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val playerId = SkunkIdCodecs.playerId
    private val characterId = SkunkIdCodecs.characterId
    private val instant = SkunkCodecs.instant
    private val tournamentClass = SkunkCodecs.tournamentClass
    private val tournamentType = SkunkCodecs.tournamentType
    private val tiebreaker = SkunkCodecs.tiebreaker

    // Intervals are bound and read as a count of seconds, as `match.time_limit` is.
    private def seconds(d: Duration): Double = d.getSeconds.toDouble
    private def duration(s: Double): Duration = Duration.ofSeconds(s.toLong)

    private val insertTournament: Query[
      (
          GameId,
          TournamentClass,
          String,
          PlayerId,
          Boolean,
          Boolean,
          Boolean,
          Boolean,
          Short,
          Double,
          Option[Int],
          Option[Int],
          Int
      ),
      TournamentId
    ] =
        sql"""INSERT INTO tournament (game_id, tournament_class, name, owner, invitational, public, friendly, live,
                                      max_entries_per_player, round_duration, min_rating, max_rating, rotations)
          VALUES ($gameId, $tournamentClass, $text, $playerId, $bool, $bool, $bool, $bool, $int2,
                  $float8 * INTERVAL '1 second', ${int4.opt}, ${int4.opt}, $int4)
          RETURNING tournament_id""".query(tournamentId)

    private val insertElimination: Command[(GameId, TournamentId, TournamentType, Int, Int, Int, Tiebreaker)] =
        sql"""INSERT INTO elimination_tournament (game_id, tournament_id, tournament_type, pool_size, min_pool_advance,
                                                  elimination_rotations, tiebreaker)
          VALUES ($gameId, $tournamentId, $tournamentType, $int4, $int4, $int4, $tiebreaker)""".command

    private val updateTournament: Command[
      (String, Boolean, Boolean, Boolean, Boolean, Double, Option[Int], Option[Int], Int, GameId, TournamentId)
    ] =
        sql"""UPDATE tournament SET name = $text, invitational = $bool, public = $bool, friendly = $bool, live = $bool,
                 round_duration = $float8 * INTERVAL '1 second', min_rating = ${int4.opt}, max_rating = ${int4.opt},
                 rotations = $int4
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    private val updateElimination: Command[(TournamentType, Int, Int, Int, Tiebreaker, GameId, TournamentId)] =
        sql"""UPDATE elimination_tournament SET tournament_type = $tournamentType, pool_size = $int4,
                 min_pool_advance = $int4, elimination_rotations = $int4, tiebreaker = $tiebreaker
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    private type Row = (
        GameId,
        TournamentId,
        TournamentClass,
        String,
        PlayerId,
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Short,
        Double,
        Option[Int],
        Option[Int],
        Int,
        Option[Instant],
        Option[Instant],
        Option[TournamentType],
        Option[Int],
        Option[Int],
        Option[Int],
        Option[Tiebreaker]
    )

    private val row: Codec[Row] =
        gameId *: tournamentId *: tournamentClass *: text *: playerId *: bool *: bool *: bool *: bool *: int2 *:
            float8 *: int4.opt *: int4.opt *: int4 *: instant.opt *: instant.opt *: tournamentType.opt *: int4.opt *:
            int4.opt *: int4.opt *: tiebreaker.opt

    private val columns: Fragment[Void] =
        sql"""t.game_id, t.tournament_id, t.tournament_class, t.name, t.owner, t.invitational, t.public, t.friendly,
              t.live, t.max_entries_per_player, EXTRACT(EPOCH FROM t.round_duration)::float8, t.min_rating,
              t.max_rating, t.rotations, t.started_at, t.ended_at, e.tournament_type, e.pool_size, e.min_pool_advance,
              e.elimination_rotations, e.tiebreaker
          FROM tournament t
          LEFT JOIN elimination_tournament e ON e.game_id = t.game_id AND e.tournament_id = t.tournament_id"""

    private val selectOne: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId".query(row)

    // The elimination row is locked with it: the two are one tournament, and an edit writes both.
    private val selectOneForUpdate: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId FOR UPDATE OF t".query(row)

    // Held against a start, which takes FOR UPDATE, by an entry that relies on the tournament not having started.
    private val selectOneForShare: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId FOR SHARE OF t".query(row)

    private val selectForGame: Query[GameId, Row] =
        sql"SELECT $columns WHERE t.game_id = $gameId ORDER BY t.create_date DESC, t.tournament_id DESC".query(row)

    /* The tournaments a player has anything to do with: owns, has an entry in, or is invited to -- by
     * name or by a character they own now. One list, each tournament once. */
    private val selectForPlayer: Query[(PlayerId, PlayerId, PlayerId, PlayerId), Row] =
        sql"""SELECT $columns
          WHERE t.owner = $playerId
             OR EXISTS (SELECT 1 FROM tournament_entry en
                         WHERE en.game_id = t.game_id AND en.tournament_id = t.tournament_id
                           AND en.player_id = $playerId)
             OR EXISTS (SELECT 1 FROM tournament_invitation i
                         WHERE i.game_id = t.game_id AND i.tournament_id = t.tournament_id
                           AND i.player_id = $playerId)
             OR EXISTS (SELECT 1 FROM character_tournament_invitation ci
                         JOIN character c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
                         WHERE ci.game_id = t.game_id AND ci.tournament_id = t.tournament_id
                           AND c.player_id = $playerId)
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(row)

    // The tournaments a player has an entry in, and those they are invited to, by name or by a character they own.
    // Decoded as raw ids and wrapped: a trailing opaque-typed codec defeats skunk's twiddles outside Ids.scala.
    private val selectEntered: Query[PlayerId, (GameId, TournamentId)] =
        sql"""SELECT DISTINCT game_id, tournament_id FROM tournament_entry WHERE player_id = $playerId"""
            .query(int4 *: int8)
            .map((g, t) => (GameId(g), TournamentId(t)))

    private val selectInvitedTo: Query[(PlayerId, PlayerId), (GameId, TournamentId)] =
        sql"""SELECT game_id, tournament_id FROM tournament_invitation WHERE player_id = $playerId
          UNION
          SELECT ci.game_id, ci.tournament_id FROM character_tournament_invitation ci
          JOIN character c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
          WHERE c.player_id = $playerId""".query(int4 *: int8).map((g, t) => (GameId(g), TournamentId(t)))

    /** The tournaments `player` has entered. */
    def enteredBy(player: PlayerId): IO[Set[(GameId, TournamentId)]] =
        session.execute(selectEntered)(player).map(_.toSet)

    /** The tournaments `player` is invited to, by name or through a character they own. */
    def invitationsOf(player: PlayerId): IO[Set[(GameId, TournamentId)]] =
        session.execute(selectInvitedTo)((player, player)).map(_.toSet)

    private def toTournament(r: Row): Tournament = {
        val (
          game,
          id,
          kind,
          name,
          owner,
          invitational,
          isPublic,
          friendly,
          live,
          maxEntries,
          roundSeconds,
          minRating,
          maxRating,
          rotations,
          startedAt,
          endedAt,
          tournamentType,
          poolSize,
          minPoolAdvance,
          eliminationRotations,
          tiebreaker
        ) = r
        Tournament(
          gameId = game,
          tournamentId = id,
          tournamentClass = kind,
          name = name,
          owner = owner,
          invitational = invitational,
          roundDuration = duration(roundSeconds),
          elimination = (tournamentType, poolSize, minPoolAdvance, eliminationRotations, tiebreaker).mapN(
            EliminationSettings.apply
          ),
          isPublic = isPublic,
          friendly = friendly,
          live = live,
          maxEntriesPerPlayer = maxEntries.toInt,
          minRating = minRating,
          maxRating = maxRating,
          rotations = rotations,
          startedAt = startedAt,
          endedAt = endedAt
        )
    }

    /** Inserts the tournament, and its elimination settings if it has them. */
    def create(t: Tournament): IO[Tournament] =
        for {
            id <- session.unique(insertTournament)(
              (
                t.gameId,
                t.tournamentClass,
                t.name,
                t.owner,
                t.invitational,
                t.isPublic,
                t.friendly,
                t.live,
                t.maxEntriesPerPlayer.toShort,
                seconds(t.roundDuration),
                t.minRating,
                t.maxRating,
                t.rotations
              )
            )
            _ <- t.elimination.traverse_(e =>
                session.execute(insertElimination)(
                  (t.gameId, id, e.tournamentType, e.poolSize, e.minPoolAdvance, e.eliminationRotations, e.tiebreaker)
                )
            )
        } yield t.copy(tournamentId = id)

    def read(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(selectOne)((game, id)).map(_.map(toTournament))

    /** As [[read]], locking the tournament for the rest of the transaction. */
    def readForUpdate(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(selectOneForUpdate)((game, id)).map(_.map(toTournament))

    /** As [[read]], holding the tournament against a start or an edit until the transaction ends. */
    def readForShare(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(selectOneForShare)((game, id)).map(_.map(toTournament))

    /** Every tournament of a game, newest first. */
    def listForGame(game: GameId): IO[List[Tournament]] = session.execute(selectForGame)(game).map(_.map(toTournament))

    /** The tournaments a player owns, has entered, or has been invited to, by name or through a character they own. */
    def listForPlayer(player: PlayerId): IO[List[Tournament]] =
        session.execute(selectForPlayer)((player, player, player, player)).map(_.map(toTournament))

    /** Rewrites the settings an owner may edit, and the elimination settings with them. Not the owner, the class or the
      * start and end, which have calls of their own.
      */
    def update(t: Tournament): IO[Unit] =
        for {
            _ <- session.execute(updateTournament)(
              (
                t.name,
                t.invitational,
                t.isPublic,
                t.friendly,
                t.live,
                seconds(t.roundDuration),
                t.minRating,
                t.maxRating,
                t.rotations,
                t.gameId,
                t.tournamentId
              )
            )
            _ <- t.elimination.traverse_(e =>
                session.execute(updateElimination)(
                  (
                    e.tournamentType,
                    e.poolSize,
                    e.minPoolAdvance,
                    e.eliminationRotations,
                    e.tiebreaker,
                    t.gameId,
                    t.tournamentId
                  )
                )
            )
        } yield ()

    private val updateOwner: Command[(PlayerId, GameId, TournamentId)] =
        sql"UPDATE tournament SET owner = $playerId WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    def setOwner(game: GameId, id: TournamentId, owner: PlayerId): IO[Unit] =
        session.execute(updateOwner)((owner, game, id)).void

    // now(), the database's clock, as every other start and end here is stamped.
    private val markStarted: Query[(GameId, TournamentId), Instant] =
        sql"""UPDATE tournament SET started_at = now() WHERE game_id = $gameId AND tournament_id = $tournamentId
          RETURNING started_at""".query(instant)

    /** Stamps the tournament started, and answers when. */
    def start(game: GameId, id: TournamentId): IO[Instant] = session.unique(markStarted)((game, id))

    private val markEnded: Query[(GameId, TournamentId), Instant] =
        sql"""UPDATE tournament SET ended_at = now() WHERE game_id = $gameId AND tournament_id = $tournamentId
          RETURNING ended_at""".query(instant)

    /** Stamps the tournament ended, and answers when. */
    def end(game: GameId, id: TournamentId): IO[Instant] = session.unique(markEnded)((game, id))

    // ---- invitations ------------------------------------------------------------------------------

    private val insertInvitation: Command[(GameId, TournamentId, PlayerId)] =
        sql"""INSERT INTO tournament_invitation (game_id, tournament_id, player_id) VALUES ($gameId, $tournamentId, $playerId)
          ON CONFLICT DO NOTHING""".command

    private val insertCharacterInvitation: Command[(GameId, TournamentId, CharacterId)] =
        sql"""INSERT INTO character_tournament_invitation (game_id, tournament_id, character_id)
          VALUES ($gameId, $tournamentId, $characterId)
          ON CONFLICT DO NOTHING""".command

    private val deleteInvitation: Command[(GameId, TournamentId, PlayerId)] =
        sql"""DELETE FROM tournament_invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId""".command

    private val deleteCharacterInvitation: Command[(GameId, TournamentId, CharacterId)] =
        sql"""DELETE FROM character_tournament_invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId AND character_id = $characterId""".command

    private val selectInvitations: Query[(GameId, TournamentId), PlayerId] =
        sql"""SELECT player_id FROM tournament_invitation WHERE game_id = $gameId AND tournament_id = $tournamentId
          ORDER BY create_date, player_id""".query(playerId)

    private val selectCharacterInvitations: Query[(GameId, TournamentId), CharacterId] =
        sql"""SELECT character_id FROM character_tournament_invitation
          WHERE game_id = $gameId AND tournament_id = $tournamentId
          ORDER BY create_date, character_id""".query(characterId)

    /* Whether a player may enter an invitational tournament: invited by name, or owning -- now -- a
     * character that was invited. FOR SHARE, so the invitation cannot be withdrawn under an entry that
     * relies on it. */
    private val selectInvited: Query[(GameId, TournamentId, PlayerId, GameId, TournamentId, PlayerId), Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM tournament_invitation
                              WHERE game_id = $gameId AND tournament_id = $tournamentId AND player_id = $playerId
                              FOR SHARE)
                  OR EXISTS (SELECT 1 FROM character_tournament_invitation ci
                              JOIN character c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
                              WHERE ci.game_id = $gameId AND ci.tournament_id = $tournamentId
                                AND c.player_id = $playerId
                              FOR SHARE OF ci)""".query(bool)

    /** Invites a player; inviting one already invited changes nothing. */
    def invite(game: GameId, id: TournamentId, player: PlayerId): IO[Unit] =
        session.execute(insertInvitation)((game, id, player)).void

    /** Invites a character, whose owner when an entry is made is the one invited. */
    def inviteCharacter(game: GameId, id: TournamentId, character: CharacterId): IO[Unit] =
        session.execute(insertCharacterInvitation)((game, id, character)).void

    /** Withdraws a player's invitation, answering whether there was one. */
    def uninvite(game: GameId, id: TournamentId, player: PlayerId): IO[Boolean] =
        session.execute(deleteInvitation)((game, id, player)).map(completionCount(_) > 0)

    /** Withdraws a character's invitation, answering whether there was one. */
    def uninviteCharacter(game: GameId, id: TournamentId, character: CharacterId): IO[Boolean] =
        session.execute(deleteCharacterInvitation)((game, id, character)).map(completionCount(_) > 0)

    def invitations(game: GameId, id: TournamentId): IO[List[PlayerId]] =
        session.execute(selectInvitations)((game, id))

    def characterInvitations(game: GameId, id: TournamentId): IO[List[CharacterId]] =
        session.execute(selectCharacterInvitations)((game, id))

    /** Whether `player` is invited, by name or through a character they own; the invitation held for the transaction.
      */
    def isInvitedForShare(game: GameId, id: TournamentId, player: PlayerId): IO[Boolean] =
        session.unique(selectInvited)((game, id, player, game, id, player))

    private def completionCount(c: skunk.data.Completion): Int =
        c match {
            case skunk.data.Completion.Delete(n) => n
            case _                               => 0
        }
}
