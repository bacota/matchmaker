package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{StandardDesigns, TournamentGame, Validation}
import com.vivi.matchmaker.persistence._

/** The models a game's tournaments are made from (V52): listed for anyone creating a tournament, and created, edited
  * and removed by the game's admins (`design/new-tournament-plan.md`, Phase 4). Letting any player make one is left for
  * later.
  *
  * Every change is one transaction. The game is read plainly, as everywhere a write lands elsewhere; the model an edit
  * or a delete changes is locked first.
  */
class TournamentModelService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

    /** The models the game offers -- not those deactivated -- by classification and then name. Any registered player
      * may look.
      */
    def list(gameId: GameId, callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId) *> new TournamentModelRepo(session).listOffered(gameId)
        }

    /** A new model, from an admin of the game. */
    def create(gameId: GameId, request: TournamentModelRequest, callerExternalId: String): IO[TournamentModel] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    game <- requireAdministered(session, gameId, callerExternalId)
                    _ <- checkModel(game, request)
                    id <- new TournamentModelRepo(session).create(gameId, request)
                    made <- requireModel(session, gameId, id)
                } yield made
            }
        }

    /** An admin's edit of a model. Tournaments already made from it keep the stages they copied. A deactivated model is
      * not edited.
      */
    def update(
        gameId: GameId,
        id: TournamentModelId,
        request: TournamentModelRequest,
        callerExternalId: String
    ): IO[TournamentModel] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    game <- requireAdministered(session, gameId, callerExternalId)
                    _ <- requireLocked(session, gameId, id)
                    current <- requireModel(session, gameId, id)
                    _ <- IO.raiseUnless(current.active)(ConflictError("this model has been deactivated"))
                    _ <- checkModel(game, request)
                    _ <- new TournamentModelRepo(session).update(gameId, id, request)
                    updated <- requireModel(session, gameId, id)
                } yield updated
            }
        }

    /** An admin removing a model, so that it is offered no longer. If nobody has entered a tournament made from it, the
      * model is deleted, and so is every tournament made from it. Otherwise it is only deactivated: kept, with its
      * stages and inner models, for the tournaments made from it, which carry on as they were.
      */
    def remove(gameId: GameId, id: TournamentModelId, callerExternalId: String): IO[TournamentModelRemoval] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val tournaments = new TournamentRepo(session)
                for {
                    _ <- requireAdministered(session, gameId, callerExternalId)
                    _ <- requireLocked(session, gameId, id)
                    // Locked, so that nobody enters one between the look and the delete.
                    madeFrom <- tournaments.lockMadeFrom(gameId, id)
                    entered <- tournaments.anyEntrantMadeFrom(gameId, id)
                    removal <-
                        if (entered)
                            new TournamentModelRepo(session).deactivate(gameId, id).as(TournamentModelRemoval(false, 0))
                        else
                            madeFrom.traverse_(tournaments.delete(gameId, _)) *>
                                new TournamentModelRepo(session)
                                    .delete(gameId, id)
                                    .as(TournamentModelRemoval(true, madeFrom.size))
                } yield removal
            }
        }

    /** The standard formats the game does not offer yet, added as models by an admin: how a game made before models
      * existed gets them. A model of the same name as a standard format is taken to be it, and left alone.
      */
    def addStandard(gameId: GameId, callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    game <- requireAdministered(session, gameId, callerExternalId)
                    added <- TournamentModelService.seed(session, game)
                    models <- new TournamentModelRepo(session).listOffered(gameId)
                } yield models.filter(m => added.contains(m.tournamentModelId))
            }
        }

    private def checkModel(game: Game, request: TournamentModelRequest): IO[Unit] = {
        val problems =
            List(
              Option.when(request.name.trim.isEmpty)("a model needs a name"),
              Option.when(request.poolSize.exists(_ < 1))("a pool must hold at least one participant")
            ).flatten ++
                Validation
                    .designProblems(request.design, TournamentGame.of(game), request.poolSize)
                    .filterNot(_.warning)
                    .map(_.describe)
        IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.mkString("; ")))
    }

    private def requireCaller(session: Session[IO], externalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(externalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$externalId'"))
        }

    // Read plainly: see the class's note on the game.
    private def requireAdministered(session: Session[IO], gameId: GameId, callerExternalId: String): IO[Game] =
        for {
            caller <- requireCaller(session, callerExternalId)
            game <- new GameRepo[T](session).read(gameId).flatMap {
                case Some(g) => IO.pure(g)
                case None    => IO.raiseError(NotFoundError(s"no game $gameId"))
            }
            _ <- GameAdmins.require(
              session,
              caller.playerId,
              gameId,
              s"only an admin of ${game.displayName} may do that"
            )
        } yield game

    private def requireLocked(session: Session[IO], gameId: GameId, id: TournamentModelId): IO[Unit] =
        new TournamentModelRepo(session).lockForUpdate(gameId, id).flatMap { found =>
            IO.raiseUnless(found)(NotFoundError(s"game $gameId has no tournament model $id"))
        }

    private def requireModel(session: Session[IO], gameId: GameId, id: TournamentModelId): IO[TournamentModel] =
        new TournamentModelRepo(session).read(gameId, id).flatMap {
            case Some(m) => IO.pure(m)
            case None    => IO.raiseError(NotFoundError(s"game $gameId has no tournament model $id"))
        }
}

object TournamentModelService {

    /** Adds the standard formats `game` can play and does not offer yet, by name, as models: what a new game is given
      * as it is created, and what an admin's Add standard models does for an older one. Nothing for a game with no role
      * a match could start with. The new models' ids.
      */
    def seed(session: Session[IO], game: Game): IO[List[TournamentModelId]] = {
        val r = TournamentGame.of(game).poolSeats
        val repo = new TournamentModelRepo(session)
        if (r < 1) IO.pure(Nil)
        else
            repo.listClassified(game.gameId).flatMap { existing =>
                // A deactivated one too, which an admin took away and is not given back.
                val names = existing.map(_.name.toLowerCase).toSet
                StandardDesigns
                    .models(r)
                    .filterNot(m => names(m.name.toLowerCase))
                    // Only what the game can play: a ladder needs pools of two or more, for one.
                    .filter(m =>
                        Validation.designProblems(m.design, TournamentGame.of(game), m.poolSize).forall(_.warning)
                    )
                    .traverse(m =>
                        repo.create(
                          game.gameId,
                          TournamentModelRequest(
                            m.name,
                            m.description,
                            Some(m.classification),
                            m.poolSize,
                            design = m.design
                          )
                        )
                    )
            }
    }
}
