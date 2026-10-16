package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.data.Arr
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** Reads and writes `tournament`: a tournament's settings, the model it was made from, and where it is.
  *
  * Its design is its `stage` rows ([[StageRepo]]) and its progress is rows of their own; the round being played, when
  * it began and when it must be over, is read from `round` through `current_round`, and written there by whoever starts
  * a round. Every change to a tournament reads it first under [[readForUpdate]]'s lock.
  *
  * Opens no transaction of its own; that is the calling service's job.
  */
class TournamentRepo(session: Session[IO]) {
    private val gameId = SkunkIdCodecs.gameId
    private val tournamentId = SkunkIdCodecs.tournamentId
    private val modelId = SkunkIdCodecs.tournamentModelId
    private val playerId = SkunkIdCodecs.playerId
    private val instant = SkunkCodecs.instant
    private val settings = SkunkCodecs.jsonb

    private val status: Codec[TournamentStatus] = text.imap(TournamentStatus.fromCode)(_.code)

    // round_duration is bound and read as a number of seconds, as MatchRepo does time_limit.
    private def seconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def duration(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    // What is written of a tournament, in the order of `insert` and `updateRow`.
    private type Written = (
        String,
        String,
        PlayerId,
        TournamentStatus,
        TournamentModelId,
        Int,
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Option[Int],
        Option[Int],
        Boolean,
        Int,
        Option[Double],
        Option[Int],
        Boolean,
        Option[Instant],
        Option[Instant],
        Option[Instant],
        String,
        Boolean,
        Option[Instant],
        Boolean,
        Option[Int],
        Boolean,
        Option[Int]
    )

    private val written: Codec[Written] =
        text *: text *: playerId *: status *: modelId *: int4 *: bool *: bool *: bool *: bool *: int4.opt *: int4.opt *:
            bool *: int4 *: float8.opt *: int4.opt *: bool *: instant.opt *: instant.opt *: instant.opt *: settings *:
            bool *: instant.opt *: bool *: int4.opt *: bool *: int4.opt

    // What is read: the written columns, then the round being played's start and deadline, and whether it is stopped,
    // which only [[stop]] writes.
    private type Row = (Written, Option[Instant], Option[Instant], Boolean)
    private val row: Codec[Row] = written *: instant.opt *: instant.opt *: bool

    private def columnsOf(t: String): Fragment[Void] =
        sql"""#$t.name, #$t.description, #$t.owner, #$t.status, #$t.tournament_model_id, #$t.default_pool_size,
              #$t.has_automatic_rounds, #$t.is_friendly, #$t.is_invitational, #$t.has_late_entries, #$t.min_rating, #$t.max_rating,
              #$t.is_public, #$t.entries_per_player, EXTRACT(EPOCH FROM #$t.round_duration)::float8, #$t.max_turns,
              #$t.has_concurrent_rounds, #$t.started_at, #$t.ended_at, #$t.checked_at, #$t.settings, #$t.is_live, #$t.start,
              #$t.has_role_choice, #$t.max_entrants, #$t.is_auto_started, #$t.players_per_match,
              (SELECT r.start_date FROM round r
                WHERE r.game_id = #$t.game_id AND r.tournament_id = #$t.tournament_id AND r.round = #$t.current_round),
              (SELECT r.deadline FROM round r
                WHERE r.game_id = #$t.game_id AND r.tournament_id = #$t.tournament_id AND r.round = #$t.current_round),
              #$t.is_stopped"""

    private val columns = columnsOf("tournament")

    private def writtenOf(t: Tournament): Written =
        (
          t.name,
          t.description,
          t.owner,
          t.status,
          t.tournamentModelId,
          t.defaultPoolSize,
          t.settings.hasAutomaticRounds,
          t.settings.isFriendly,
          t.settings.isInvitational,
          t.settings.hasLateEntries,
          t.settings.minRating,
          t.settings.maxRating,
          t.settings.isPublic,
          t.settings.entriesPerPlayer,
          seconds(t.settings.roundDuration),
          t.settings.maxTurns,
          t.settings.hasConcurrentRounds,
          t.startedAt,
          t.endedAt,
          t.checkedAt,
          t.settings.gameSettings,
          t.settings.isLive,
          t.settings.start,
          t.settings.hasRoleChoice,
          t.settings.maxEntrants,
          t.settings.isAutoStarted,
          t.settings.playersPerMatch
        )

    private def tournamentOf(game: GameId, id: TournamentId, r: Row): Tournament = {
        val (w, roundStartedAt, roundDeadline, stopped) = r
        val (
          name,
          description,
          owner,
          st,
          model,
          defaultPoolSize,
          auto,
          friendly,
          invitational,
          late,
          minRating,
          maxRating,
          isPublic,
          entries,
          roundSeconds,
          maxTurns,
          concurrentRounds,
          startedAt,
          endedAt,
          checkedAt,
          gameSettings,
          live,
          start,
          chooseRoles,
          maxEntrants,
          autoStart,
          playersPerMatch
        ) = w
        Tournament(
          gameId = game,
          tournamentId = id,
          name = name,
          description = description,
          owner = owner,
          tournamentModelId = model,
          defaultPoolSize = defaultPoolSize,
          settings = TournamentSettings(
            hasAutomaticRounds = auto,
            isFriendly = friendly,
            isInvitational = invitational,
            hasLateEntries = late,
            minRating = minRating,
            maxRating = maxRating,
            entriesPerPlayer = entries,
            roundDuration = duration(roundSeconds),
            maxTurns = maxTurns,
            hasConcurrentRounds = concurrentRounds,
            isPublic = isPublic,
            gameSettings = gameSettings,
            isLive = live,
            start = start,
            hasRoleChoice = chooseRoles,
            maxEntrants = maxEntrants,
            isAutoStarted = autoStart,
            playersPerMatch = playersPerMatch
          ),
          status = st,
          startedAt = startedAt,
          endedAt = endedAt,
          roundStartedAt = roundStartedAt,
          roundDeadline = roundDeadline,
          checkedAt = checkedAt,
          isStopped = stopped
        )
    }

    // first_stage_id is written as a placeholder, and set by StageRepo.copy before the transaction commits: the two
    // reference each other, and the key between them is deferred.
    private val insert: Query[GameId *: Written, TournamentId] =
        sql"""INSERT INTO tournament (game_id, name, description, owner, status, tournament_model_id, default_pool_size,
                                      has_automatic_rounds, is_friendly, is_invitational, has_late_entries, min_rating, max_rating,
                                      is_public, entries_per_player, round_duration, max_turns, has_concurrent_rounds,
                                      started_at, ended_at, checked_at, settings, is_live, start, has_role_choice,
                                      max_entrants, is_auto_started, players_per_match, first_stage_id)
          VALUES ($gameId, $text, $text, $playerId, $status, $modelId, $int4, $bool, $bool, $bool, $bool, ${int4.opt},
                  ${int4.opt}, $bool, $int4, ${float8.opt} * INTERVAL '1 second', ${int4.opt}, $bool, ${instant.opt},
                  ${instant.opt}, ${instant.opt}, $settings, $bool, ${instant.opt}, $bool, ${int4.opt}, $bool, ${int4.opt}, 0)
          RETURNING tournament_id""".query(tournamentId)

    /** Writes a new tournament. Its stages are written next, by [[StageRepo.copy]], in the same transaction. */
    def create(t: Tournament): IO[Tournament] =
        session.unique(insert)(t.gameId *: writtenOf(t)).map(id => t.copy(tournamentId = id))

    private val select: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId".query(row)

    private val selectForUpdate: Query[(GameId, TournamentId), Row] =
        sql"SELECT $columns FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId FOR UPDATE"
            .query(row)

    def read(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(select)((game, id)).map(_.map(tournamentOf(game, id, _)))

    // Of each tournament named, unnested as (game, tournament) pairs so that each is a lookup on its key.
    private val selectFor: Query[(Arr[Int], Arr[Long]), GameId *: TournamentId *: Row] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}
          FROM unnest(${_int4}, ${_int8}) AS k (game_id, tournament_id)
          JOIN tournament t ON t.game_id = k.game_id AND t.tournament_id = k.tournament_id"""
            .query(gameId *: tournamentId *: row)

    /** As [[read]], for each of several tournaments in one query, by key: those not there are left out. */
    def readFor(tournaments: List[TournamentKey]): IO[Map[TournamentKey, Tournament]] =
        if (tournaments.isEmpty) IO.pure(Map.empty)
        else
            session
                .execute(selectFor)(TournamentKey.arrays(tournaments))
                .map(_.map { case game *: id *: r =>
                    TournamentKey(game, id) -> tournamentOf(game = game, id = id, r = r)
                }.toMap)

    /** As [[read]], locking the tournament for the rest of the transaction: what every change to it reads first. */
    def readForUpdate(game: GameId, id: TournamentId): IO[Option[Tournament]] =
        session.option(selectForUpdate)((game, id)).map(_.map(tournamentOf(game, id, _)))

    private val updateRow: Command[Tuple.Concat[Written, (GameId, TournamentId)]] =
        sql"""UPDATE tournament SET name = $text, description = $text, owner = $playerId, status = $status,
                 tournament_model_id = $modelId, default_pool_size = $int4, has_automatic_rounds = $bool, is_friendly = $bool,
                 is_invitational = $bool, has_late_entries = $bool, min_rating = ${int4.opt}, max_rating = ${int4.opt},
                 is_public = $bool, entries_per_player = $int4, round_duration = ${float8.opt} * INTERVAL '1 second',
                 max_turns = ${int4.opt}, has_concurrent_rounds = $bool, started_at = ${instant.opt},
                 ended_at = ${instant.opt}, checked_at = ${instant.opt}, settings = $settings, is_live = $bool,
                 start = ${instant.opt}, has_role_choice = $bool, max_entrants = ${int4.opt}, is_auto_started = $bool,
                 players_per_match = ${int4.opt}
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    /** Rewrites the tournament's own columns. Not the round being played, which is the round's to say. */
    def update(t: Tournament): IO[Unit] =
        session.execute(updateRow)(writtenOf(t) ++ (t.gameId, t.tournamentId)).void

    private val updateStopped: Command[(GameId, TournamentId)] =
        sql"""UPDATE tournament SET is_stopped = true
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    /** Marks a running tournament stopped: no further round of it is begun. Apart from [[update]], which leaves it
      * alone, so that nothing written back from a tournament read before the stop can undo it.
      */
    def stop(game: GameId, id: TournamentId): IO[Unit] =
        session.execute(updateStopped)((game, id)).void

    private val selectStarted: Query[(GameId, TournamentId), Boolean] =
        sql"SELECT started_at IS NOT NULL FROM tournament WHERE game_id = $gameId AND tournament_id = $tournamentId"
            .query(bool)

    /** The tournament's progress as the rules hold it, read from its rows ([[TournamentStateRepo]]), or `None` before
      * it has started.
      */
    def readState(game: GameId, id: TournamentId): IO[Option[com.vivi.matchmaker.model.tournament.TournamentState]] =
        session.option(selectStarted)((game, id)).flatMap {
            case Some(true) => new TournamentStateRepo(session).load(game, id).map(_.map(_.state))
            case _          => IO.pure(None)
        }

    /** Writes the rules' next state, as what changed from the one its rows hold now ([[TournamentStateRepo.write]]). */
    def writeState(
        game: GameId,
        id: TournamentId,
        state: com.vivi.matchmaker.model.tournament.TournamentState
    ): IO[Unit] =
        new TournamentStateRepo(session).write(game = game, tournament = id, after = state).void

    private val selectForGame: Query[GameId, TournamentId *: Row] =
        sql"""SELECT tournament_id, $columns FROM tournament WHERE game_id = $gameId
          ORDER BY create_date DESC, tournament_id DESC""".query(tournamentId *: row)

    /** Every tournament of a game, newest first. */
    def listForGame(game: GameId): IO[List[Tournament]] =
        session.execute(selectForGame)(game).map(_.map { case id *: r => tournamentOf(game = game, id = id, r = r) })

    // A player's tournaments, by key: owned, entered (with any entry, withdrawn or not), or invited to -- themselves,
    // or a character they own now. A union of four lookups, each served by an index on the player, where an OR of
    // them across `tournament` would read every tournament there is.
    private val playersTournaments: Fragment[(PlayerId, PlayerId, PlayerId, PlayerId)] =
        sql"""SELECT game_id, tournament_id FROM tournament WHERE owner = $playerId
          UNION SELECT game_id, tournament_id FROM entrant_player WHERE player_id = $playerId
          UNION SELECT game_id, tournament_id FROM player_invitation WHERE player_id = $playerId
          UNION SELECT ci.game_id, ci.tournament_id FROM character_invitation ci
                JOIN "character" c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
                WHERE c.player_id = $playerId"""

    // A challenge that is over is a match played, which the completed matches list: the tournament lists leave it
    // out, so that they grow with the tournaments being played and not with every challenge ever played. Written
    // with literals, as the partial indexes `tournament_listed` and `tournament_open_challenge` are, so that the
    // planner can match them.
    private val challenge = com.vivi.matchmaker.model.tournament.standard.Challenge.modelId.value
    private val isListed: Fragment[Void] =
        sql"(t.tournament_model_id <> '#$challenge' OR t.status IN ('draft', 'open', 'running'))"
    private val isOpenChallenge: Fragment[Void] =
        sql"t.tournament_model_id = '#$challenge' AND t.status = 'open'"

    // What a list shows beside each tournament, for a viewer: its game's name, its owner's nickname, how many
    // entries are still in it, whether the viewer has an entry or an invitation, and whether it is under way
    // with every match of its round over -- waiting, unless its rounds start themselves, on its owner.
    private val summaryColumns =
        sql"""g.display_name, o.nickname,
              (SELECT count(*) FROM entrant e
                WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.withdrawn_at IS NULL),
              EXISTS (SELECT 1 FROM entrant_player e
                       WHERE e.game_id = t.game_id AND e.tournament_id = t.tournament_id AND e.player_id = $playerId),
              EXISTS (SELECT 1 FROM player me WHERE me.player_id = $playerId AND (
                       EXISTS (SELECT 1 FROM player_invitation i
                                WHERE i.game_id = t.game_id AND i.tournament_id = t.tournament_id AND i.player_id = me.player_id)
                       OR EXISTS (SELECT 1 FROM character_invitation ci
                                  JOIN "character" c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
                                  WHERE ci.game_id = t.game_id AND ci.tournament_id = t.tournament_id
                                    AND c.player_id = me.player_id))),
              t.status = 'running' AND t.current_round IS NOT NULL AND NOT EXISTS (SELECT 1 FROM match m
                       WHERE m.game_id = t.game_id AND m.tournament_id = t.tournament_id
                         AND m.round = t.current_round AND m.status <> 'F')"""

    private val summaryRow = text *: text *: int8 *: bool *: bool *: bool

    // The row decodes as one element, its summary as six more: (game, id, row, game name, owner, entries,
    // entered, invited, round over).
    private type SummaryRow = (GameId, TournamentId, Row, String, String, Long, Boolean, Boolean, Boolean)
    private val summaryCodec: Codec[SummaryRow] = gameId *: tournamentId *: row *: summaryRow

    private def summaryOf(viewer: PlayerId)(s: SummaryRow): TournamentSummary = {
        val (game, id, r, gameName, owner, entrants, entered, invited, roundOver) = s
        val t = tournamentOf(game = game, id = id, r = r)
        TournamentSummary(
          tournament = t,
          gameName = gameName,
          ownerNickname = owner,
          entrants = entrants.toInt,
          isOwner = t.owner == viewer,
          isEntered = entered,
          isInvited = invited,
          isRoundOver = roundOver
        )
    }

    private def selectSummariesForGame(where: Fragment[Void]): Query[((PlayerId, PlayerId), GameId), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM tournament t JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE t.game_id = $gameId AND $where
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(summaryCodec)

    private val selectListedForGame = selectSummariesForGame(isListed)
    private val selectOpenChallengesForGame = selectSummariesForGame(isOpenChallenge)

    private def selectSummariesForPlayer(
        where: Fragment[Void]
    ): Query[((PlayerId, PlayerId), (PlayerId, PlayerId, PlayerId, PlayerId)), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM ($playersTournaments) mine
          JOIN tournament t ON t.game_id = mine.game_id AND t.tournament_id = mine.tournament_id
          JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE $where
          ORDER BY t.create_date DESC, t.tournament_id DESC""".query(summaryCodec)

    private val selectListedForPlayer = selectSummariesForPlayer(isListed)
    private val selectOpenChallengesForPlayer = selectSummariesForPlayer(isOpenChallenge)

    private val selectSummary: Query[((PlayerId, PlayerId), GameId, TournamentId), SummaryRow] =
        sql"""SELECT t.game_id, t.tournament_id, ${columnsOf("t")}, $summaryColumns
          FROM tournament t JOIN game g ON g.game_id = t.game_id JOIN player o ON o.player_id = t.owner
          WHERE t.game_id = $gameId AND t.tournament_id = $tournamentId""".query(summaryCodec)

    /** A game's tournaments as `viewer` sees them in a list, newest first: all but the challenges that are over. */
    def summariesForGame(game: GameId, viewer: PlayerId): IO[List[TournamentSummary]] =
        session.execute(selectListedForGame)(((viewer, viewer), game)).map(_.map(summaryOf(viewer)))

    /** A game's challenges still taking entries, as `viewer` sees them, newest first: every one, invitational or not.
      */
    def openChallengesForGame(game: GameId, viewer: PlayerId): IO[List[TournamentSummary]] =
        session.execute(selectOpenChallengesForGame)(((viewer, viewer), game)).map(_.map(summaryOf(viewer)))

    /** The tournaments `viewer` owns, has entered or is invited to, in every game, newest first: all but the challenges
      * that are over.
      */
    def summariesForPlayer(viewer: PlayerId): IO[List[TournamentSummary]] =
        session
            .execute(selectListedForPlayer)(((viewer, viewer), (viewer, viewer, viewer, viewer)))
            .map(_.map(summaryOf(viewer)))

    /** The challenges still taking entries that `viewer` owns, has entered or is invited to, in every game, newest
      * first.
      */
    def openChallengesForPlayer(viewer: PlayerId): IO[List[TournamentSummary]] =
        session
            .execute(selectOpenChallengesForPlayer)(((viewer, viewer), (viewer, viewer, viewer, viewer)))
            .map(_.map(summaryOf(viewer)))

    /** One tournament as `viewer` sees it in a list. */
    def summary(game: GameId, id: TournamentId, viewer: PlayerId): IO[Option[TournamentSummary]] =
        session.option(selectSummary)(((viewer, viewer), game, id)).map(_.map(summaryOf(viewer)))

    // Every entry a player plays now -- theirs, or a character's they own -- with its seed and stratum as its latest
    // SEED row in the tournament's root pool says, and how many are still in.
    private val selectStandings: Query[PlayerId, TournamentStandingRow] =
        sql"""SELECT t.game_id, t.tournament_id, t.name, g.display_name, t.status, e.entrant_id, ch.name,
                 s.seed, s.stratum, s.eliminated IS NOT NULL,
                 (SELECT count(*) FROM entrant o
                   WHERE o.game_id = t.game_id AND o.tournament_id = t.tournament_id AND o.withdrawn_at IS NULL),
                 e.final_rank
          FROM entrant e
          JOIN tournament t ON t.game_id = e.game_id AND t.tournament_id = e.tournament_id
          JOIN game g ON g.game_id = t.game_id
          LEFT JOIN character_entrant ce
                 ON ce.game_id = e.game_id AND ce.tournament_id = e.tournament_id AND ce.entrant_id = e.entrant_id
          LEFT JOIN character ch ON ch.game_id = ce.game_id AND ch.character_id = ce.character_id
          JOIN entrant_player ep
            ON ep.game_id = e.game_id AND ep.tournament_id = e.tournament_id AND ep.entrant_id = e.entrant_id
          LEFT JOIN LATERAL (
              SELECT sd.seed, sd.stratum, sd.eliminated FROM seed sd
              JOIN pool p ON p.game_id = sd.game_id AND p.tournament_id = sd.tournament_id AND p.pool_id = sd.pool_id
              WHERE sd.game_id = e.game_id AND sd.tournament_id = e.tournament_id AND sd.entrant_id = e.entrant_id
                AND p.kind = 'R'
              ORDER BY sd.round DESC LIMIT 1
          ) s ON true
          WHERE ep.player_id = $playerId AND $isListed
          ORDER BY t.status = 'running' DESC, t.create_date DESC, e.entrant_id"""
            .query(
              gameId *: tournamentId *: text *: text *: status *: int8 *: text.opt *: int4.opt *: int4.opt *: bool *:
                  int8 *: int4.opt
            )

    private type TournamentStandingRow =
        (
            GameId,
            TournamentId,
            String,
            String,
            TournamentStatus,
            Long,
            Option[String],
            Option[Int],
            Option[Int],
            Boolean,
            Long,
            Option[Int]
        )

    /** Where a player stands in every tournament they have entered, bar the challenges that are over: those under way
      * first and then the newest.
      */
    def standingsForPlayer(player: PlayerId): IO[List[TournamentStanding]] =
        session
            .execute(selectStandings)(player)
            .map(_.map { case (g, t, name, gameName, st, entrant, character, seed, stratum, out, entrants, rank) =>
                TournamentStanding(
                  gameId = g,
                  tournamentId = t,
                  tournamentName = name,
                  gameName = gameName,
                  status = st,
                  entrantId = EntrantId(entrant),
                  characterName = character,
                  seed = seed,
                  stratum = stratum,
                  isEliminated = out,
                  entrants = entrants.toInt,
                  finalRank = rank
                )
            })

    // ---- the tournaments made from a model, for a sync removing it ----

    private val selectMadeFromForUpdate: Query[TournamentModelId, (GameId, TournamentId)] =
        sql"""SELECT game_id, tournament_id FROM tournament WHERE tournament_model_id = $modelId
          ORDER BY game_id, tournament_id FOR UPDATE"""
            // Decoded as numbers and wrapped: a pair of opaque codecs does not reduce to a pair.
            .query(int4 *: int8)
            .map((g, t) => (GameId(g), TournamentId(t)))

    /** Locks every tournament made from the model, in any game, for the rest of the transaction, and lists them: what
      * removing the model takes first, so that nobody enters one -- entering takes the same lock -- while it is
      * decided.
      */
    def lockMadeFrom(model: TournamentModelId): IO[List[(GameId, TournamentId)]] =
        session.execute(selectMadeFromForUpdate)(model)

    private val selectAnyEntrantMadeFrom: Query[TournamentModelId, Boolean] =
        sql"""SELECT EXISTS (SELECT 1 FROM entrant e JOIN tournament t USING (game_id, tournament_id)
                              WHERE t.tournament_model_id = $modelId)""".query(bool)

    /** Whether any tournament made from the model has an entrant, withdrawn or not. One that has a match has some. */
    def anyEntrantMadeFrom(model: TournamentModelId): IO[Boolean] =
        session.unique(selectAnyEntrantMadeFrom)(model)

    private def deleting(table: String): Command[(GameId, TournamentId)] =
        sql"DELETE FROM #$table WHERE game_id = $gameId AND tournament_id = $tournamentId".command

    private val clearCurrentRound: Command[(GameId, TournamentId)] =
        sql"""UPDATE tournament SET current_round = NULL
          WHERE game_id = $gameId AND tournament_id = $tournamentId""".command

    // Each table after every one that names it. pool names itself and is emptied in one statement; tournament names
    // round through current_round, cleared first, and stage through the deferred first_stage_id.
    private val deletes: List[Command[(GameId, TournamentId)]] =
        List(
          "seed",
          "pool_member",
          "atomic_pool",
          "pool_group",
          "pool",
          "round",
          "character_entrant",
          "player_entrant",
          "entrant",
          "character_invitation",
          "player_invitation",
          "invitation",
          "nesting_stage",
          "atomic_stage",
          "stage",
          "tournament"
        ).map(deleting)

    /** Removes a tournament that has no match, with its entries, invitations, stages and root pool. Refused by the
      * database, as a foreign key violation, if it has a match.
      */
    def delete(game: GameId, id: TournamentId): IO[Unit] =
        session.execute(clearCurrentRound)((game, id)) *> deletes.traverse_(session.execute(_)((game, id)))
}
