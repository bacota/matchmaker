package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import skunk.implicits._
import skunk.codec.all.{int8, text}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StageDesign, TournamentDesign, TournamentGame, Validation}
import com.vivi.matchmaker.model.tournament.standard.StandardModels
import com.vivi.matchmaker.persistence.{PoolRepo, StageRepo, TournamentModelRepo, TournamentRepo}

/** Tournaments for specs: the model of a standard format, offered in a game, and a tournament made from one. */
object TournamentFixtures {

    /** The model of the standard format `key`, offered in `game`: added first if it is not stored yet, as a sync adds
      * it, under the sync's lock so that specs running at once do not both add it. A game that cannot play the format
      * -- one of a single role, say -- is given a plain round robin instead, a model of its own with no classification,
      * so that no spec's sync removes it. In one transaction, as the services write a model: it and its stages name
      * each other.
      */
    def model(session: Session[IO], game: Game, key: String): IO[TournamentModel] =
        session.transaction.use(_ => found(session, game, key))

    private def found(session: Session[IO], game: Game, key: String): IO[TournamentModel] = {
        val repo = new TournamentModelRepo(session)
        val standard = StandardModels.find(key).getOrElse(throw new IllegalArgumentException(s"no standard model $key"))
        val playable =
            Validation.designProblems(standard.design, TournamentGame.of(game), standard.poolSize).forall(_.isWarning)
        if (playable)
            for {
                _ <- TournamentModelService.lockSync(session)
                stored <- repo.read(TournamentModelId(key))
                _ <- IO.whenA(stored.isEmpty)(repo.create(standard.model))
                _ <- repo.offer(game.gameId, TournamentModelId(key))
                model <- repo.read(TournamentModelId(key)).map(_.get)
            } yield model
        else {
            val roundRobin = TournamentModel(
              TournamentModelId(s"test-round-robin-${java.util.UUID.randomUUID()}"),
              1,
              "Round robin",
              "",
              None,
              None,
              isLateEntries = false,
              TournamentDesign(List(StageDesign(isBrackets = false, demote = None)))
            )
            repo.create(roundRobin) *> repo.read(roundRobin.tournamentModelId).map(_.get)
        }
    }

    /** A draft tournament of `game`, owned by `owner`, made from its model of `key` as `TournamentService.create` makes
      * one: the row, its stages and its root pool.
      */
    def tournament(
        session: Session[IO],
        game: Game,
        owner: PlayerId,
        key: String = "single-elimination"
    ): IO[Tournament] =
        // In one transaction, for the deferred keys between a tournament and its stages.
        session.transaction.use(_ => made(session, game, owner, key))

    private def made(session: Session[IO], game: Game, owner: PlayerId, key: String): IO[Tournament] =
        for {
            m <- found(session, game, key)
            t <- new TournamentRepo(session).create(
              Tournament(
                game.gameId,
                TournamentId.unassigned,
                "tournament",
                "",
                owner,
                m.tournamentModelId,
                m.poolSize.getOrElse(game.poolSeats),
                TournamentSettings()
              )
            )
            _ <- new StageRepo(session).copy(game.gameId, t.tournamentId, m.design)
            _ <- new PoolRepo(session).createRoot(game.gameId, t.tournamentId)
        } yield t

    /** A tournament match of `t` that has begun, written from `m` in the first round of an atomic pool of its own, as a
      * tournament's match is once its engine game is made. For specs about matches, which care that it is a
      * tournament's and not how it got there.
      */
    def begunMatch(session: Session[IO], t: Tournament, m: Match): IO[Match] =
        session.transaction.use { _ =>
            val g = t.gameId.value
            val id = t.tournamentId.value
            def long(q: String): IO[Long] = session.unique(sql"#$q".query(int8))
            def exec(q: String): IO[Unit] = session.execute(sql"#$q".command).void
            for {
                stage <- long(s"SELECT stage_id FROM atomic_stage WHERE game_id = $g AND tournament_id = $id LIMIT 1")
                root <- long(s"SELECT pool_id FROM pool WHERE game_id = $g AND tournament_id = $id AND kind = 'R'")
                _ <- exec(
                  s"""INSERT INTO round (game_id, tournament_id, round, stage_id) VALUES ($g, $id, 1, $stage)
                      ON CONFLICT DO NOTHING"""
                )
                pool <- long(
                  s"""INSERT INTO pool (game_id, tournament_id, kind, parent_pool_id, round)
                      VALUES ($g, $id, 'A', $root, 1) RETURNING pool_id"""
                )
                _ <- exec(
                  s"INSERT INTO atomic_pool (game_id, tournament_id, pool_id, stage_id) VALUES ($g, $id, $pool, $stage)"
                )
                _ <- session.execute(
                  sql"""INSERT INTO match (game_id, match_id, description, completed_at, start, settings, is_public,
                                           public_url, is_friendly, status, tournament_id, pool_id, round, stage_id)
                        VALUES (#${g.toString}, $int8, $text,
                                ${com.vivi.matchmaker.persistence.SkunkCodecs.instant.opt}, now(), '{}', #${m.isPublic.toString},
                                ${text.opt}, #${m.isFriendly.toString}, #${
                          if (m.completed) "'F'" else "'P'"
                      }, #${id.toString},
                                #${pool.toString}, 1, #${stage.toString})""".command
                )((m.matchId.value, m.description, m.completedAt, m.publicUrl))
            } yield m.copy(tournamentId = t.tournamentId)
        }

    /** A seat of a begun tournament match ([[begunMatch]]), as a tournament's seat is once it is played: entered by
      * `player` with `character`, in `role`, and rated.
      */
    def begunSeat(
        session: Session[IO],
        t: Tournament,
        matchId: MatchId,
        player: PlayerId,
        character: CharacterId,
        role: GameRoleId,
        pending: Boolean,
        completed: Boolean,
        due: Option[java.time.Instant]
    ): IO[Unit] =
        session.transaction.use { _ =>
            for {
                entrant <- new com.vivi.matchmaker.persistence.EntrantRepo(session)
                    .create(t.gameId, GameType.Character, t.tournamentId, player, Some(character), EloRating.initial)
                participant <- session.unique(
                  sql"""INSERT INTO participant (game_id, match_id, game_type, player_id, is_pending, completed_at, due,
                            game_role_id, elo_start, tournament_id, entrant_id, is_notify_match_started, is_notify_turn_taken,
                            is_notify_your_turn, is_notify_match_ended)
                        VALUES (#${t.gameId.value.toString}, $int8, 'C', #${player.value.toString},
                                #${pending.toString}, #${if (completed) "now()" else "NULL"},
                                ${com.vivi.matchmaker.persistence.SkunkCodecs.instant.opt}, #${role.value.toString},
                                #${EloRating.initial.toString}, #${t.tournamentId.value.toString},
                                #${entrant.entrantId.value.toString}, true, true, true, true)
                        RETURNING participant_id""".query(int8)
                )((matchId.value, due))
                _ <- session.execute(
                  sql"""INSERT INTO character_participant (game_id, participant_id, character_id, tournament_id,
                                                           entrant_id)
                        VALUES (#${t.gameId.value.toString}, #${participant.toString},
                                #${character.value.toString}, #${t.tournamentId.value.toString},
                                #${entrant.entrantId.value.toString})""".command
                )
            } yield ()
        }
}
