package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.FixtureRepo
import com.vivi.matchmaker.tournament.{Bracket, PlannedSource}

/** A tournament's bracket, planned by the scheduling core and written as rounds, pools and slots. */
object TournamentLayout {

    /** Every round's pools for `entrants`, by the tournament's kind. */
    def bracket(settings: EliminationSettings, entrants: Int): Bracket =
        settings.tournamentType match {
            case TournamentType.RoundRobin => Bracket.roundRobin(entrants)
            case TournamentType.Playoff    => Bracket.playoff(entrants, settings.poolSize, settings.minPoolAdvance)
            case TournamentType.DoubleElim => Bracket.doubleElimination(entrants, settings.poolSize)
            case TournamentType.ZeroElim   => Bracket.zeroElimination(entrants)
            // Its repechage is laid out once the final pair is known; until then it is single elimination.
            case TournamentType.Repechage =>
                Bracket.singleElimination(entrants, settings.poolSize, settings.minPoolAdvance, consolation = false)
            case _ => Bracket.singleElimination(entrants, settings.poolSize, settings.minPoolAdvance)
        }

    /** Writes every round of `bracket`, its pools, and their slots, naming each earlier pool by the id it was given.
      *
      * A cyclic tournament's later cycles are laid out after the rounds already there: `after` is the last of them, so
      * the bracket's round 1 is written as round `after + 1`, in `cycle`.
      */
    def layOut(
        session: Session[IO],
        gameId: GameId,
        tournamentId: TournamentId,
        bracket: Bracket,
        after: Int = 0,
        cycle: Int = 1
    ): IO[Unit] = {
        val fixtures = new FixtureRepo(session)
        bracket.rounds.zipWithIndex
            .foldLeft(IO.pure(Map.empty[(Int, Int), FixtureId])) { case (done, (pools, i)) =>
                done.flatMap { ids =>
                    val planned = i + 1
                    val round = after + planned
                    fixtures.createRound(
                      TournamentRound(gameId, tournamentId, round, cycle, reseed = bracket.reseed.contains(planned))
                    ) *>
                        pools
                            .traverse { pool =>
                                for {
                                    f <- fixtures.createFixture(
                                      Fixture(gameId, tournamentId, FixtureId(0), round, pool.position)
                                    )
                                    _ <- pool.slots.traverse_ { source =>
                                        val slotSource = source match {
                                            case PlannedSource.Bye        => SlotSource.Bye
                                            case PlannedSource.Seed(seed) => SlotSource.Seed(seed)
                                            case PlannedSource.Winner(r, position, rank) =>
                                                SlotSource.Winner(ids((r, position)), rank)
                                        }
                                        fixtures.createSlot(
                                          FixtureSlot(gameId, tournamentId, f.fixtureId, SlotId(0), slotSource)
                                        )
                                    }
                                } yield (planned, pool.position) -> f.fixtureId
                            }
                            .map(ids ++ _)
                }
            }
            .void
    }

}
