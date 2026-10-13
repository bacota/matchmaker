package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.StandardDesigns
import com.vivi.matchmaker.persistence.{PoolRepo, StageRepo, TournamentModelRepo, TournamentRepo}

/** Tournaments for specs (V52): a game's model of a standard format, and a tournament made from one. */
object TournamentFixtures {

    /** The game's model of the standard format `key`, adding the standard formats it does not offer yet first. */
    def model(session: Session[IO], game: Game, key: String): IO[TournamentModel] =
        for {
            _ <- TournamentModelService.seed(session, game)
            offered <- new TournamentModelRepo(session).listOffered(game.gameId)
            name = StandardDesigns.find(key).map(_.name).getOrElse(key)
            found <- IO.fromOption(offered.find(_.name == name))(new IllegalArgumentException(s"no model of $key"))
        } yield found

    /** A draft tournament of `game`, owned by `owner`, made from its model of `key` as `TournamentService.create` makes
      * one: the row, its stages and its root pool.
      */
    def tournament(
        session: Session[IO],
        game: Game,
        owner: PlayerId,
        key: String = "single-elimination"
    ): IO[Tournament] =
        for {
            m <- model(session, game, key)
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
}
