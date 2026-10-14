package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign}
import com.vivi.matchmaker.service.TournamentModelService

/** Matches and seats written straight into the tables, for specs about matches rather than about how they come to be.
  *
  * Every match is a tournament's, so each one written here is given a tournament of its own -- running, owned by the
  * owner given, made from an unclassified model of one round-robin stage that no spec's sync removes -- and the pool
  * and round it is played in. A seat is given an entry in that tournament, with its character in a character game, and
  * is stamped with what its player wants to hear about it, as a round's seat is.
  */
object TestMatches {

    private val holder = TournamentModelId("test-match-holder")

    private val holderModel = TournamentModel(
      holder,
      1,
      "Match holder",
      "",
      None,
      None,
      isLateEntries = false,
      TournamentDesign(List(StageDesign(isBrackets = false, demote = None)))
    )

    @volatile private var isHolderReady = false

    /* The holder model, written once, under the sync's lock so that specs running at once do not both write it. */
    private def ensureHolder(session: Session[IO]): IO[Unit] =
        if (isHolderReady) IO.unit
        else
            session.transaction
                .use { _ =>
                    val repo = new TournamentModelRepo(session)
                    TournamentModelService.lockSync(session) *> repo.read(holder).flatMap {
                        case Some(_) => IO.unit
                        case None    => repo.create(holderModel)
                    }
                }
                .flatTap(_ => IO { isHolderReady = true })

    private def long(session: Session[IO], q: String): IO[Long] = session.unique(sql"#$q".query(int8))
    private def exec(session: Session[IO], q: String): IO[Unit] = session.execute(sql"#$q".command).void

    private val insertMatch: Command[
      (
          GameId,
          MatchId,
          String,
          TournamentId,
          Long,
          Long,
          String,
          Option[java.time.Instant],
          Option[java.time.Instant],
          Boolean,
          Option[Double],
          TimeLimitKind,
          TimeLimitUnit,
          String,
          Boolean,
          Boolean,
          Boolean,
          Option[String],
          Option[String],
          Option[String]
      )
    ] =
        sql"""INSERT INTO match (game_id, match_id, status, tournament_id, pool_id, round, stage_id,
                                 description, start, completed_at, is_cancelled, time_limit, time_limit_kind,
                                 time_limit_unit, settings, is_public, is_live, is_friendly, status_url, play_url, public_url)
              VALUES (${SkunkIdCodecs.gameId}, ${SkunkIdCodecs.matchId}, $text, ${SkunkIdCodecs.tournamentId}, $int8, 1, $int8, $text, ${SkunkCodecs.instant.opt},
                      ${SkunkCodecs.instant.opt}, $bool, ${float8.opt} * INTERVAL '1 second',
                      ${SkunkCodecs.timeLimitKind}, ${SkunkCodecs.timeLimitUnit}, ${SkunkCodecs.jsonb}, $bool, $bool,
                      $bool, ${text.opt}, ${text.opt}, ${text.opt})""".command

    /** Writes `m` as a match of a tournament of its own, and answers it with that tournament's id. Its status is what
      * it says, unless it is over, which `completedAt` and `isCancelled` say -- as `MatchRepo` writes a status.
      */
    def writeMatch(session: Session[IO], m: Match, owner: PlayerId): IO[Match] =
        ensureHolder(session) *> session.transaction.use { _ =>
            val status =
                if (m.completedAt.isDefined || m.isCancelled) MatchStatus.Finished else m.status
            for {
                t <- new TournamentRepo(session).create(
                  Tournament(
                    m.gameId,
                    TournamentId.unassigned,
                    "match",
                    "",
                    owner,
                    holder,
                    2,
                    TournamentSettings(),
                    TournamentStatus.Running,
                    startedAt = Some(java.time.Instant.now())
                  )
                )
                _ <- new StageRepo(session).copy(m.gameId, t.tournamentId, holderModel.design)
                root <- new PoolRepo(session).createRoot(m.gameId, t.tournamentId)
                g = m.gameId.value
                id = t.tournamentId.value
                stage <- long(session, s"SELECT stage_id FROM atomic_stage WHERE game_id = $g AND tournament_id = $id")
                _ <- exec(
                  session,
                  s"INSERT INTO round (game_id, tournament_id, round, stage_id) VALUES ($g, $id, 1, $stage)"
                )
                pool <- long(
                  session,
                  s"""INSERT INTO pool (game_id, tournament_id, kind, parent_pool_id, round)
                      VALUES ($g, $id, 'A', $root, 1) RETURNING pool_id"""
                )
                _ <- exec(
                  session,
                  s"INSERT INTO atomic_pool (game_id, tournament_id, pool_id, stage_id) VALUES ($g, $id, $pool, $stage)"
                )
                _ <- session.execute(insertMatch)(
                  (
                    m.gameId,
                    m.matchId,
                    status.code,
                    t.tournamentId,
                    pool,
                    stage,
                    m.description,
                    m.start,
                    m.completedAt,
                    m.isCancelled,
                    m.timeLimit.map(_.getSeconds.toDouble),
                    m.timeLimitKind,
                    m.timeLimitUnit,
                    m.settings,
                    m.isPublic,
                    m.isLive,
                    m.isFriendly,
                    m.statusUrl,
                    m.playUrl,
                    m.publicUrl
                  )
                )
            } yield m.copy(tournamentId = t.tournamentId, status = status)
        }

    private val selectTournament: Query[(GameId, MatchId), (TournamentId, GameType)] =
        sql"""SELECT m.tournament_id, g.game_type FROM match m JOIN game g ON g.game_id = m.game_id
              WHERE m.game_id = ${SkunkIdCodecs.gameId} AND m.match_id = ${SkunkIdCodecs.matchId}"""
            .query(SkunkIdCodecs.tournamentId *: SkunkCodecs.gameType)

    // The entry a seat plays for, if there is one already: the character's in a character game, the player's in a plain
    // one.
    private val selectCharacterEntrant: Query[(GameId, TournamentId, Long), Long] =
        sql"""SELECT entrant_id FROM character_entrant
              WHERE game_id = ${SkunkIdCodecs.gameId} AND tournament_id = ${SkunkIdCodecs.tournamentId}
                AND character_id = $int8""".query(int8)

    private val selectPlayerEntrant: Query[(GameId, TournamentId, PlayerId), Long] =
        sql"""SELECT entrant_id FROM player_entrant
              WHERE game_id = ${SkunkIdCodecs.gameId} AND tournament_id = ${SkunkIdCodecs.tournamentId}
                AND player_id = ${SkunkIdCodecs.playerId}
              ORDER BY entrant_id LIMIT 1""".query(int8)

    private val insertParticipant: Query[
      (
          GameId,
          MatchId,
          GameType,
          PlayerId,
          Boolean,
          Boolean,
          Option[java.time.Instant],
          GameRoleId,
          Int,
          Option[Int],
          TournamentId,
          Long,
          GameId,
          PlayerId
      ),
      ParticipantId
    ] =
        sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, is_pending, completed_at, due, game_role_id,
                  elo_start, elo_role_start, tournament_id, entrant_id, is_notify_match_started, is_notify_turn_taken,
                  is_notify_your_turn, is_notify_match_ended)
              SELECT ${SkunkIdCodecs.gameId}, ${SkunkIdCodecs.matchId}, ${SkunkCodecs.gameType},
                     ${SkunkIdCodecs.playerId}, $bool, CASE WHEN $bool THEN now() END, ${SkunkCodecs.instant.opt},
                     ${SkunkIdCodecs.gameRoleId}, $int4, ${int4.opt}, ${SkunkIdCodecs.tournamentId}, $int8,
                     COALESCE(pg.is_notify_match_started, pl.is_notify_match_started, TRUE),
                     COALESCE(pg.is_notify_turn_taken, pl.is_notify_turn_taken, TRUE),
                     COALESCE(pg.is_notify_your_turn, pl.is_notify_your_turn, TRUE),
                     COALESCE(pg.is_notify_match_ended, pl.is_notify_match_ended, TRUE)
              FROM player pl
              LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = ${SkunkIdCodecs.gameId}
              WHERE pl.player_id = ${SkunkIdCodecs.playerId}
              RETURNING participant_id""".query(SkunkIdCodecs.participantId)

    /** Seats `p` in its match, rated `eloStart` overall and `eloRoleStart` in its role as the match began: with an
      * entry in the match's tournament -- the one its character, or in a plain game its player, already has, if any --
      * and its subtype, and stamped with what its player wants to hear about it.
      */
    def writeSeat(
        session: Session[IO],
        p: Participant,
        eloStart: Int = EloRating.initial,
        eloRoleStart: Option[Int] = None
    ): IO[Participant] =
        session.transaction.use { _ =>
            val character = p match {
                case cp: CharacterParticipant => Some(cp.characterId)
                case _: PlainParticipant      => None
            }
            for {
                found <- session.unique(selectTournament)((p.gameId, p.matchId))
                (tournament, gameType) = found
                existing <- character match {
                    case Some(c) => session.option(selectCharacterEntrant)((p.gameId, tournament, c.value))
                    case None    => session.option(selectPlayerEntrant)((p.gameId, tournament, p.playerId))
                }
                entrant <- existing match {
                    case Some(e) => IO.pure(e)
                    case None =>
                        new EntrantRepo(session)
                            .create(p.gameId, gameType, tournament, p.playerId, character, eloStart)
                            .map(_.entrantId.value)
                }
                id <- session.unique(insertParticipant)(
                  (
                    p.gameId,
                    p.matchId,
                    gameType,
                    p.playerId,
                    p.isPending,
                    p.isCompleted,
                    p.due,
                    p.gameRoleId,
                    eloStart,
                    eloRoleStart,
                    tournament,
                    entrant,
                    p.gameId,
                    p.playerId
                  )
                )
                _ <- exec(
                  session,
                  character match {
                      case Some(c) =>
                          s"""INSERT INTO character_participant (game_id, participant_id, character_id, tournament_id,
                                                                entrant_id)
                              VALUES (${p.gameId.value}, ${id.value}, ${c.value}, ${tournament.value}, $entrant)"""
                      case None =>
                          s"""INSERT INTO player_participant (game_id, participant_id, tournament_id, entrant_id)
                              VALUES (${p.gameId.value}, ${id.value}, ${tournament.value}, $entrant)"""
                  }
                )
            } yield p match {
                case cp: CharacterParticipant => cp.copy(participantId = id)
                case pp: PlainParticipant     => pp.copy(participantId = id)
            }
        }
}
