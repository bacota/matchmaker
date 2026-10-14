package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scala.concurrent.duration._
import skunk._
import skunk.implicits._
import skunk.codec.all.{int4, int8}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.{QuietTests, TestMigration}
import com.vivi.matchmaker.model.GameType

/** The tournament schema as the database enforces it, written row by row in plain SQL rather than through any
  * repository: a model copied into a tournament, and the rows its constraints refuse.
  *
  * Everything happens in one transaction that is rolled back, so nothing is left behind. Each refusal is tried inside a
  * savepoint and rolled back to it, and the deferred keys -- between a model or tournament and its first stage, and
  * between a stage or pool and its subtype row -- are made to answer at once with `SET CONSTRAINTS ALL IMMEDIATE`
  * rather than at a commit that never comes.
  */
class TournamentSchemaSpec extends FunSuite with QuietTests {
    TestMigration.ensure()

    private case class Ids(game: Int, gameType: String, player: Long, role: Int, tournament: Long, character: Long)

    private def exec(session: Session[IO], statement: String): IO[Unit] =
        session.execute(sql"#$statement".command).void

    private def long(session: Session[IO], query: String): IO[Long] = session.unique(sql"#$query".query(int8))

    /** A game, a player in it, and a tournament copied from a one-stage model: its atomic stage, root pool, first round
      * and one atomic pool, and the player entered.
      */
    private def tournament(session: Session[IO]): IO[Ids] =
        for {
            made <- Generators.gameWithOwner(session)
            (game, owner) = made
            g = game.gameId.value
            gameType = if (game.gameType == GameType.Character) "C" else "P"
            player = owner.value
            role = game.roles.head.gameRoleId.value
            // A model of its own, unclassified so that no spec's sync takes it for one the code dropped.
            model = s"'test-schema-${java.util.UUID.randomUUID()}'"
            _ <- exec(
              session,
              s"INSERT INTO tournament_model (tournament_model_id, name, first_stage_model_id) VALUES ($model, 'model', 'only')"
            )
            _ <- exec(
              session,
              s"""INSERT INTO stage_model (tournament_model_id, stage_model_id, kind, name, seed_policy)
                  VALUES ($model, 'only', 'A', 'only', 'ELO')"""
            )
            _ <- exec(
              session,
              s"INSERT INTO atomic_stage_model (tournament_model_id, stage_model_id) VALUES ($model, 'only')"
            )
            t <- long(
              session,
              s"""INSERT INTO tournament (game_id, name, owner, tournament_model_id, default_pool_size, first_stage_id)
                  VALUES ($g, 'tournament', $player, $model, 2, 0) RETURNING tournament_id"""
            )
            stage <- long(
              session,
              s"INSERT INTO stage (game_id, tournament_id, kind, name, seed_policy) VALUES ($g, $t, 'A', 'only', 'ELO') RETURNING stage_id"
            )
            _ <- exec(
              session,
              s"INSERT INTO atomic_stage (game_id, tournament_id, stage_id) VALUES ($g, $t, $stage)"
            )
            _ <- exec(
              session,
              s"UPDATE tournament SET first_stage_id = $stage WHERE game_id = $g AND tournament_id = $t"
            )
            root <- long(
              session,
              s"INSERT INTO pool (game_id, tournament_id, kind) VALUES ($g, $t, 'R') RETURNING pool_id"
            )
            _ <- exec(
              session,
              s"INSERT INTO round (game_id, tournament_id, round, stage_id) VALUES ($g, $t, 1, $stage)"
            )
            pool <- long(
              session,
              s"""INSERT INTO pool (game_id, tournament_id, kind, parent_pool_id, round)
                  VALUES ($g, $t, 'A', $root, 1) RETURNING pool_id"""
            )
            _ <- exec(
              session,
              s"INSERT INTO atomic_pool (game_id, tournament_id, pool_id, stage_id) VALUES ($g, $t, $pool, $stage)"
            )
            // The player's character, which a character game's entry names.
            character <- long(session, s"SELECT character_id FROM character WHERE game_id = $g AND player_id = $player")
            entrant <- long(
              session,
              s"""INSERT INTO entrant (game_id, tournament_id, game_type, rating_at_entry)
                  VALUES ($g, $t, '$gameType', 1500) RETURNING entrant_id"""
            )
            _ <- exec(
              session,
              if (gameType == "C")
                  s"""INSERT INTO character_entrant (game_id, tournament_id, entrant_id, character_id)
                      VALUES ($g, $t, $entrant, $character)"""
              else
                  s"""INSERT INTO player_entrant (game_id, tournament_id, entrant_id, player_id)
                      VALUES ($g, $t, $entrant, $player)"""
            )
            _ <- exec(session, "SET CONSTRAINTS ALL IMMEDIATE")
        } yield Ids(g, gameType, player, role, t, character)

    /** Whether `statement` is refused, leaving the transaction as it was either way. */
    private def refused(session: Session[IO], xa: Transaction[IO], statement: String): IO[Boolean] =
        xa.savepoint.flatMap { sp =>
            (exec(session, statement) *> exec(session, "SET CONSTRAINTS ALL IMMEDIATE")).attempt.flatMap { outcome =>
                xa.rollback(sp).as(outcome.isLeft)
            }
        }

    private def inRolledBack[A](f: (Session[IO], Transaction[IO]) => IO[A]): A =
        TestSession.resource
            .use(session => session.transaction.use(xa => f(session, xa).flatTap(_ => xa.rollback)))
            .timeout(60.seconds)
            .unsafeRunSync()

    /** A tournament match of the one atomic pool in round 1, with `extra` columns set. */
    private def tournamentMatch(ids: Ids, id: String, status: String, extra: String = ""): String = {
        val (columns, values) = extra.split(";").filter(_.nonEmpty).map(_.split("=", 2)).map(a => (a(0), a(1))).unzip
        val more = columns.map(", " + _).mkString
        val moreValues = values.map(", " + _).mkString
        s"""INSERT INTO match (game_id, match_id, description, settings, tournament_id, pool_id, round, stage_id,
                               status$more)
            SELECT ${ids.game}, '$id', '', '{}', ${ids.tournament}, p.pool_id, 1, p.stage_id, '$status'$moreValues
            FROM atomic_pool p WHERE p.game_id = ${ids.game} AND p.tournament_id = ${ids.tournament}"""
    }

    test("a model copied into a tournament, with its root pool, round and atomic pool, satisfies every constraint") {
        val ids = inRolledBack((session, _) => tournament(session))
        assert(ids.tournament > 0)
    }

    test("a tournament match may be scheduled, with no start and no urls, in a pool and round of one stage") {
        val ok = inRolledBack { (session, xa) =>
            tournament(session).flatMap(ids =>
                refused(session, xa, tournamentMatch(ids, Generators.newMatchId().value.toString, "S"))
            )
        }
        assert(!ok, "a scheduled tournament match was refused")
    }

    test("a match outside any tournament is refused") {
        assert(inRolledBack { (session, xa) =>
            tournament(session).flatMap { ids =>
                refused(
                  session,
                  xa,
                  s"""INSERT INTO match (game_id, match_id, description, settings, start, status)
                      VALUES (${ids.game}, ${Generators.newMatchId().value}, '', '{}', now(), 'P')"""
                )
            }
        })
    }

    test("a tournament match still choosing roles may not have urls") {
        assert(inRolledBack { (session, xa) =>
            tournament(session).flatMap { ids =>
                refused(
                  session,
                  xa,
                  tournamentMatch(
                    ids,
                    Generators.newMatchId().value.toString,
                    "C",
                    "status_url='https://engine.test/s'"
                  )
                )
            }
        })
    }

    test("a finished match must be completed or cancelled") {
        assert(inRolledBack { (session, xa) =>
            tournament(session).flatMap { ids =>
                refused(session, xa, tournamentMatch(ids, Generators.newMatchId().value.toString, "F", "start=now()"))
            }
        })
    }

    test("a rubber match may not allow ties") {
        assert(inRolledBack { (session, xa) =>
            tournament(session).flatMap { ids =>
                refused(
                  session,
                  xa,
                  tournamentMatch(
                    ids,
                    Generators.newMatchId().value.toString,
                    "S",
                    "is_rubber=true;can_tie=true"
                  )
                )
            }
        })
    }

    test("a seat may wait for its role, and must name the entrant it plays for") {
        val (withoutEntrant, withoutRole) = inRolledBack { (session, xa) =>
            for {
                ids <- tournament(session)
                matchId = Generators.newMatchId().value.toString
                _ <- exec(session, tournamentMatch(ids, matchId, "C"))
                entrant <- long(
                  session,
                  s"SELECT entrant_id FROM entrant WHERE game_id = ${ids.game} AND tournament_id = ${ids.tournament}"
                )
                // The seat and its subtype in one statement, so that the deferred key between them is satisfied when
                // `refused` checks every constraint.
                subtype =
                    if (ids.gameType == "C")
                        s"""INSERT INTO character_participant (game_id, participant_id, character_id, tournament_id,
                                                               entrant_id)
                            SELECT game_id, participant_id, ${ids.character}, tournament_id, entrant_id FROM seat"""
                    else
                        """INSERT INTO player_participant (game_id, participant_id, tournament_id, entrant_id)
                            SELECT game_id, participant_id, tournament_id, entrant_id FROM seat"""
                seat = (more: String, values: String) => s"""WITH seat AS (
                          INSERT INTO participant (game_id, game_type, match_id, player_id, tournament_id,
                                                   is_notified_of_match_started, is_notified_of_turn_taken, is_notified_of_your_turn,
                                                   is_notified_of_match_ended$more)
                          VALUES (${ids.game}, '${ids.gameType}', '$matchId', ${ids.player}, ${ids.tournament}, true,
                                  true, true, true$values)
                          RETURNING game_id, participant_id, tournament_id, entrant_id)
                        $subtype"""
                withoutEntrant <- refused(session, xa, seat("", ""))
                withoutRole <- refused(session, xa, seat(", entrant_id", s", $entrant"))
            } yield (withoutEntrant, withoutRole)
        }
        assert(withoutEntrant, "a seat with no entrant was accepted")
        assert(!withoutRole, "a seat waiting for its role was refused")
    }

    test("an entry may not lack the subtype its game's type says, nor have the other kind's") {
        val (missing, wrong) = inRolledBack { (session, xa) =>
            for {
                ids <- tournament(session)
                // A character game's entry with no character_entrant row.
                missing <- refused(
                  session,
                  xa,
                  s"""INSERT INTO entrant (game_id, tournament_id, game_type, rating_at_entry)
                      VALUES (${ids.game}, ${ids.tournament}, '${ids.gameType}', 1500)"""
                )
                // And one given a player's subtype instead.
                wrong <- refused(
                  session,
                  xa,
                  s"""WITH e AS (INSERT INTO entrant (game_id, tournament_id, game_type, rating_at_entry)
                                 VALUES (${ids.game}, ${ids.tournament}, '${ids.gameType}', 1500)
                                 RETURNING game_id, tournament_id, entrant_id)
                      INSERT INTO player_entrant (game_id, tournament_id, entrant_id, player_id)
                      SELECT game_id, tournament_id, entrant_id, ${ids.player} FROM e"""
                )
            } yield (missing, wrong)
        }
        assert(missing, "an entry with no subtype row was accepted")
        assert(wrong, "an entry with the other kind's subtype was accepted")
    }

    test("a stage of one kind may not lack its subtype row, nor have the other kind's") {
        val (missing, wrong) = inRolledBack { (session, xa) =>
            for {
                ids <- tournament(session)
                missing <- refused(
                  session,
                  xa,
                  s"INSERT INTO stage (game_id, tournament_id, kind, name, seed_policy) VALUES (${ids.game}, ${ids.tournament}, 'N', 'bare', 'ELO')"
                )
                stage <- long(
                  session,
                  s"SELECT first_stage_id FROM tournament WHERE game_id = ${ids.game} AND tournament_id = ${ids.tournament}"
                )
                wrong <- refused(
                  session,
                  xa,
                  s"""INSERT INTO nesting_stage (game_id, tournament_id, stage_id, inner_first_stage_id)
                      VALUES (${ids.game}, ${ids.tournament}, $stage, $stage)"""
                )
            } yield (missing, wrong)
        }
        assert(missing, "a nesting stage with no nesting_stage row was accepted")
        assert(wrong, "an atomic stage was given a nesting_stage row")
    }
}
