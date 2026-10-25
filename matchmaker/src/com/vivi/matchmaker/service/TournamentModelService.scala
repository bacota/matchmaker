package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import skunk.implicits._
import skunk.codec.all.int8
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{TournamentGame, Validation}
import com.vivi.matchmaker.model.tournament.standard.{RoundRobin, StandardModel, StandardModels}
import com.vivi.matchmaker.persistence._

/** The models every game's tournaments are made from: the standard formats the code defines ([[StandardModels]]), which
  * a site admin syncs into the database, and which each game's admins choose to offer. Nobody makes a model of their
  * own.
  *
  * Every change is one transaction. A sync locks each model it writes or removes; choosing one for a game, as making a
  * tournament from one, holds it FOR SHARE, so that a sync waits for either to be written and neither waits on another
  * of its kind. The game is read plainly, as everywhere a write lands elsewhere.
  */
class TournamentModelService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

    /** The models the game offers -- those its admins chose that are still active -- by classification and then name.
      * Any registered player may look.
      */
    def list(gameId: GameId, callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId) *> new TournamentModelRepo(session).listOffered(gameId)
        }

    /** Every model a game could offer, by classification and then name. Any registered player may look. */
    def listAll(callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId) *> new TournamentModelRepo(session).listActive
        }

    /** A game's admin offering a model in the game; nothing if it is offered already. The models the game now offers.
      */
    def offer(gameId: GameId, id: TournamentModelId, callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentModelRepo(session)
                for {
                    _ <- requireAdministered(session = session, gameId = gameId, callerExternalId = callerExternalId)
                    // Held against a sync removing it before the choice is written.
                    found <- repo.lockForShare(id)
                    model <- if (found) repo.read(id) else IO.pure(None)
                    _ <- model match {
                        case Some(m) if m.isActive && m.isRoot => repo.offer(gameId, id)
                        case _ => IO.raiseError(NotFoundError(ErrorText.model.noneToOffer))
                    }
                    offered <- repo.listOffered(gameId)
                } yield offered
            }
        }

    /** A game's admin no longer offering a model in the game; nothing if it was not. Tournaments already made from it
      * keep it. The models the game now offers.
      */
    def withdraw(gameId: GameId, id: TournamentModelId, callerExternalId: String): IO[List[TournamentModel]] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentModelRepo(session)
                for {
                    _ <- requireAdministered(session = session, gameId = gameId, callerExternalId = callerExternalId)
                    _ <- repo.withdraw(gameId, id)
                    offered <- repo.listOffered(gameId)
                } yield offered
            }
        }

    /** A site admin making the stored models what the code defines ([[TournamentModelService.sync]]). */
    def sync(callerExternalId: String): IO[TournamentModelSync] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    _ <- IO.raiseUnless(caller.isAdmin)(
                      UnauthorizedError(ErrorText.model.syncNotAdmin)
                    )
                    done <- TournamentModelService.sync(session)
                } yield done
            }
        }

    private def requireCaller(session: Session[IO], externalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(externalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
        }

    // Read plainly: see the class's note on the game.
    private def requireAdministered(session: Session[IO], gameId: GameId, callerExternalId: String): IO[Game] =
        for {
            caller <- requireCaller(session, callerExternalId)
            game <- new GameRepo[T](session).read(gameId).flatMap {
                case Some(g) => IO.pure(g)
                case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
            }
            _ <- GameAdmins.require(
              session = session,
              caller = caller.playerId,
              gameId = gameId,
              refusal = ErrorText.game.notItsAdmin(game.displayName)
            )
        } yield game
}

object TournamentModelService {

    /** The transaction-scoped advisory lock every sync takes first, so that two never race to add the same model: a row
      * lock cannot hold a model that is not there yet.
      */
    val SyncLock: Long = 0x746d73796e63L // "tmsync"

    // The function returns void, which skunk cannot decode; as text it is the empty string.
    private val takeSyncLock: skunk.Query[Long, String] =
        sql"SELECT pg_advisory_xact_lock($int8)::text".query(skunk.codec.all.text)

    private val takeSyncLockShared: skunk.Query[Long, String] =
        sql"SELECT pg_advisory_xact_lock_shared($int8)::text".query(skunk.codec.all.text)

    /** Takes [[SyncLock]] for the rest of the transaction. */
    def lockSync(session: Session[IO]): IO[Unit] = session.unique(takeSyncLock)(SyncLock).void

    /** Takes [[SyncLock]] shared for the rest of the transaction: no sync runs meanwhile, and nothing else that takes
      * it shared waits.
      */
    def lockSyncShared(session: Session[IO]): IO[Unit] = session.unique(takeSyncLockShared)(SyncLock).void

    /** Makes the stored models what the code defines, within the caller's transaction: each model the code defines is
      * added if it is not stored, and rewritten if the code's version is newer or it had been deactivated. A stored
      * model the code no longer defines is deleted, with every tournament made from it, while nobody has entered one;
      * otherwise it is deactivated, kept for those tournaments, which carry on as they were.
      */
    def sync(session: Session[IO]): IO[TournamentModelSync] = {
        val repo = new TournamentModelRepo(session)
        val tournaments = new TournamentRepo(session)
        for {
            _ <- lockSync(session)
            written <- StandardModels.all.traverse(m => write(repo, m))
            stored <- repo.listRoots
            gone = stored.filterNot(s => StandardModels.find(s.tournamentModelId.value).isDefined)
            removed <- gone.traverse { s =>
                val id = s.tournamentModelId
                for {
                    _ <- repo.lockForUpdate(id)
                    // Locked, so that nobody enters one between the look and the delete.
                    madeFrom <- tournaments.lockMadeFrom(id)
                    entered <- tournaments.anyEntrantMadeFrom(id)
                    outcome <-
                        if (entered)
                            if (s.isActive) repo.deactivate(id).as(Some(Left(id))) else IO.pure(None)
                        else
                            madeFrom.traverse_((g, t) => tournaments.delete(g, t)) *>
                                repo.delete(id).as(Some(Right(id)))
                } yield outcome
            }
        } yield TournamentModelSync(
          added = written.flatten.collect { case Left(id) => id },
          updated = written.flatten.collect { case Right(id) => id },
          deactivated = removed.flatten.collect { case Left(id) => id },
          deleted = removed.flatten.collect { case Right(id) => id }
        )
    }

    /* One model the code defines, made what the code says: added if it is not stored, rewritten if the code's version
     * is newer or it had been deactivated. Left if added, Right if rewritten. Under the sync lock, which the caller
     * holds; the model is locked before it is looked at, and decided from what is read under the lock. */
    private def write(
        repo: TournamentModelRepo,
        m: StandardModel
    ): IO[Option[Either[TournamentModelId, TournamentModelId]]] = {
        val id = TournamentModelId(m.id)
        repo.lockForUpdate(id) *> repo.read(id).flatMap {
            case None                                            => repo.create(m.model).as(Some(Left(id)))
            case Some(s) if !s.isActive || s.version < m.version => repo.replace(m.model).as(Some(Right(id)))
            case Some(_)                                         => IO.pure(None)
        }
    }

    /** Offers in `game` every active model it can play -- whose design has no problem for it but warnings: what a new
      * game is given as it is created, for its admins to change. The challenge model is not among them: a game's admins
      * offer challenges themselves. The models offered.
      *
      * Under [[SyncLock]], shared, taken before the models are listed: a sync would otherwise delete or deactivate
      * models between the list and the offers -- an offer of a deleted model fails its foreign key -- and lock them in
      * an order of its own against these, a deadlock. Shared, so that games created at once do not queue for it.
      */
    def offerPlayable(session: Session[IO], game: Game): IO[List[TournamentModelId]] = {
        val repo = new TournamentModelRepo(session)
        // A game without roles is judged at its smallest matches: whether a format suits a whole pool in one match is
        // the tournament's to say, which validates it when it is made.
        val tg = TournamentGame.of(game, game.minPlayers)
        lockSyncShared(session) *> repo.listActive.flatMap { models =>
            // None for a game with no role a match could start with.
            val playable =
                if (tg.requiredSeats == 0) Nil
                else
                    models
                        .filter(_.tournamentModelId != RoundRobin.modelId)
                        .filter(m =>
                            Validation
                                .designProblems(design = m.design, game = tg, defaultPoolSize = m.poolSize)
                                .forall(_.isWarning)
                        )
                        .map(_.tournamentModelId)
            playable.traverse_(id => repo.offer(game.gameId, id)).as(playable)
        }
    }
}
