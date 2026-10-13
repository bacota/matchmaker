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

    private case class Ids(game: Int, gameType: String, player: Long, challenge: Long, role: Int, tournament: Long)

    private def exec(session: Session[IO], statement: String): IO[Unit] =
        session.execute(sql"#$statement".command).void

    private def long(session: Session[IO], query: String): IO[Long] = session.unique(sql"#$query".query(int8))

    /** A game, a player in it with a challenge, and a tournament copied from a one-stage model: its atomic stage, root
      * pool, first round and one atomic pool, and the player entered.
      */
    private def tournament(session: Session[IO]): IO[Ids] =
        for {
            made <- Generators.gameWithChallenge(session)
            (game, challengeId) = made
            g = game.gameId.value
            gameType = if (game.gameType == GameType.Character) "C" else "P"
            player <- long(
              session,
              s"SELECT challenger FROM challenge WHERE game_id = $g AND challenge_id = ${challengeId.value}"
            )
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
            _ <- exec(
              session,
              s"""INSERT INTO entrant (game_id, tournament_id, game_type, player_id, rating_at_entry)
                  VALUES ($g, $t, '$gameType', $player, 1500)"""
            )
            _ <- exec(session, "SET CONSTRAINTS ALL IMMEDIATE")
        } yield Ids(g, gameType, player, challengeId.value, role, t)

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
        s"""INSERT INTO match (game_id, match_id, creator, description, settings, tournament_id, pool_id, round, stage_id,
                               status$more)
            SELECT ${ids.game}, '$id', ${ids.player}, '', '{}', ${ids.tournament}, p.pool_id, 1, p.stage_id, '$status'$moreValues
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

    test("a match from both a challenge and a tournament is refused") {
        assert(inRolledBack { (session, xa) =>
            tournament(session).flatMap { ids =>
                refused(
                  session,
                  xa,
                  tournamentMatch(ids, Generators.newMatchId().value.toString, "S", s"challenge_id=${ids.challenge}")
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
                  tournamentMatch(ids, Generators.newMatchId().value.toString, "S", "rubber=true;ties_allowed=true")
                )
            }
        })
    }

    test("a challenge's seat must have a role, while a tournament's may wait for one") {
        val (challengeSeat, tournamentSeat) = inRolledBack { (session, xa) =>
            for {
                ids <- tournament(session)
                matchId = Generators.newMatchId().value.toString
                _ <- exec(session, tournamentMatch(ids, matchId, "C"))
                entrant <- long(
                  session,
                  s"SELECT entrant_id FROM entrant WHERE game_id = ${ids.game} AND tournament_id = ${ids.tournament}"
                )
                seat = (origin: String, value: Long, more: String) =>
                    s"""INSERT INTO participant (game_id, game_type, match_id, player_id, $origin, notify_match_started,
                                                notify_turn_taken, notify_your_turn, notify_match_ended$more)
                        VALUES (${ids.game}, '${ids.gameType}', '$matchId', ${ids.player}, $value, true, true, true, true"""
                // A challenge seat in the challenge's own match, rated but with no role: the role is what is refused.
                challengeMatch = Generators.newMatchId().value.toString
                _ <- exec(
                  session,
                  s"""INSERT INTO match (game_id, match_id, challenge_id, creator, description, settings, start, status)
                      VALUES (${ids.game}, '$challengeMatch', ${ids.challenge}, ${ids.player}, '', '{}', now(), 'P')"""
                )
                challengeSeat <- refused(
                  session,
                  xa,
                  seat("challenge_id", ids.challenge, ", elo_start")
                      .replace(s"'$matchId'", s"'$challengeMatch'") + ", 1500)"
                )
                tournamentSeat <- refused(
                  session,
                  xa,
                  seat("tournament_id", ids.tournament, ", entrant_id") + s", $entrant)"
                )
            } yield (challengeSeat, tournamentSeat)
        }
        assert(challengeSeat, "a challenge seat with no role was accepted")
        assert(!tournamentSeat, "a tournament seat with no role was refused")
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
