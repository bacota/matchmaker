package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import cats.syntax.all._
import java.security.SecureRandom
import java.time.Instant
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{EngineAccessRepo, Repos, TextCodec}

/** Engines only listed players may play.
  *
  * An engine is the games sharing a `game.external_id`. Restricting one does not reach into the engine: matchmaker
  * decides every seat, and an engine already refuses a caller with no seat, so refusing an unlisted player an entry
  * ([[EngineAccessService.requireAllowed]], called by `TournamentService.enter` and `create`) and a character
  * (`CharacterService.create` and `transfer`) keeps them out of its matches everywhere.
  *
  * Taking a player off the list refuses their next entry; what they are already in plays out.
  *
  * Who manages the list is an overall admin, or an admin of the game named in the request; the list is the engine's, so
  * it governs every game the engine serves. A player can also let themselves in with a code those admins made.
  */
class EngineAccessService[T](sessionPool: SessionPool)(using TextCodec[T]) {

    /** Who may play the game's engine, for its admins. */
    def access(gameId: GameId, callerExternalId: String): IO[EngineAccess] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                game <- requireManager(repos, gameId, callerExternalId)
                engine = EngineId(game.externalId)
                isRestricted <- repos.engineAccess.isRestricted(engine)
                players <- repos.engineAccess.listPlayers(engine)
                codes <- repos.engineAccess.listCodes(engine)
            } yield EngineAccess(isRestricted = isRestricted, players = players, codes = codes)
        }

    /** Restricts the game's engine to its listed players, or opens it to everybody. Neither touches the list. */
    def setRestricted(gameId: GameId, isRestricted: Boolean, callerExternalId: String): IO[Unit] =
        managing(gameId, callerExternalId) { (repos, game, caller) =>
            val engine = EngineId(game.externalId)
            if (isRestricted) repos.engineAccess.restrict(engine, caller.playerId)
            else repos.engineAccess.unrestrict(engine)
        }

    /** Lets the player called `nickname` play the game's engine. Letting in somebody already listed changes nothing. */
    def grant(gameId: GameId, nickname: String, callerExternalId: String): IO[Unit] =
        managing(gameId, callerExternalId) { (repos, game, caller) =>
            for {
                // For share: the row about to be written references this player.
                player <- repos.players.readByNicknameForShare(nickname.trim).flatMap {
                    case Some(p) => IO.pure(p)
                    case None    => IO.raiseError(ValidationError(ErrorText.player.noneCalled(nickname.trim)))
                }
                _ <- repos.engineAccess.grant(
                  engine = EngineId(game.externalId),
                  player = player.playerId,
                  grantedBy = Some(caller.playerId),
                  code = None
                )
            } yield ()
        }

    /** Takes the player off the game's engine's list. Their next entry is refused; what they are in plays out. */
    def revoke(gameId: GameId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        managing(gameId, callerExternalId) { (repos, game, _) =>
            repos.engineAccess.revoke(EngineId(game.externalId), playerId).flatMap { wasListed =>
                if (wasListed) IO.unit
                else
                    repos.players.read(playerId).flatMap { p =>
                        IO.raiseError(
                          NotFoundError(
                            ErrorText.engineAccess.notListed(
                              nickname = p.fold("that player")(_.nickname),
                              game = game.displayName
                            )
                          )
                        )
                    }
            }
        }

    /** Makes a code that lets whoever redeems it play the game's engine: answered with the code itself, this once. */
    def createCode(
        gameId: GameId,
        label: String,
        maxUses: Option[Int],
        expiresAt: Option[Instant],
        callerExternalId: String
    ): IO[NewEngineAccessCode] =
        for {
            _ <- IO.raiseWhen(label.trim.isEmpty)(ValidationError(ErrorText.engineAccess.blankLabel))
            _ <- IO.raiseWhen(maxUses.exists(_ < 1))(ValidationError(ErrorText.engineAccess.badMaxUses))
            now <- IO.realTimeInstant
            _ <- IO.raiseWhen(expiresAt.exists(!_.isAfter(now)))(ValidationError(ErrorText.engineAccess.pastExpiry))
            code = EngineAccessService.newCode()
            made <- managing(gameId, callerExternalId) { (repos, game, caller) =>
                repos.engineAccess.createCode(
                  EngineAccessRepo.NewCode(
                    engine = EngineId(game.externalId),
                    codeHash = EngineAccessService.hashOf(code),
                    label = label.trim,
                    maxUses = maxUses,
                    expiresAt = expiresAt,
                    createdBy = caller.playerId
                  )
                )
            }
        } yield NewEngineAccessCode(code = code, access = made)

    /** Revokes one of the game's engine's codes. Players it already let in stay listed. */
    def revokeCode(gameId: GameId, codeId: EngineAccessCodeId, callerExternalId: String): IO[Unit] =
        managing(gameId, callerExternalId) { (repos, game, _) =>
            repos.engineAccess.revokeCode(EngineId(game.externalId), codeId).flatMap { found =>
                IO.raiseUnless(found)(NotFoundError(ErrorText.engineAccess.noSuchCode(game.displayName)))
            }
        }

    /** Lets the caller in with a code: answered with the names of the games it opened.
      *
      * A revoked, expired, used-up and unknown code are refused alike, so that a caller learns nothing from guessing. A
      * caller already listed is let in without spending a use.
      */
    def redeem(code: String, callerExternalId: String): IO[RedeemedAccess] = {
        val normalized = EngineAccessService.normalize(code)
        IO.raiseWhen(normalized.isEmpty)(ValidationError(ErrorText.engineAccess.blankCode)) *>
            sessionPool.use { session =>
                val repos = Repos(session)
                session.transaction.use { _ =>
                    for {
                        // For share: the row about to be written references the caller.
                        caller <- repos.players.readByExternalIdForShare(callerExternalId).flatMap {
                            case Some(p) => IO.pure(p)
                            case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                        }
                        now <- IO.realTimeInstant
                        // For update: its uses and whether it is revoked decide the write, and two players
                        // redeeming the last use of one code must queue.
                        found <- repos.engineAccess
                            .codeByHashForUpdate(EngineAccessService.hashOf(normalized))
                            .flatMap {
                                case Some(f) => IO.pure(f)
                                case None    => IO.raiseError(ValidationError(ErrorText.engineAccess.badCode))
                            }
                        // A player already listed is let in whatever the code's state: there is nothing to spend.
                        isListed <- repos.engineAccess.isListedForShare(found.engine, caller.playerId)
                        _ <- IO.raiseUnless(isListed || found.code.isRedeemable(now))(
                          ValidationError(ErrorText.engineAccess.badCode)
                        )
                        isNew <- repos.engineAccess.grant(
                          engine = found.engine,
                          player = caller.playerId,
                          grantedBy = None,
                          code = Some(found.code.codeId)
                        )
                        _ <- IO.whenA(isNew)(repos.engineAccess.countUse(found.code.codeId))
                        // Plainly: the names are only for telling the caller, not for deciding the write.
                        names <- repos.games[T].readDisplayNamesByExternalId(found.engine.value)
                    } yield RedeemedAccess(names)
                }
            }
    }

    /** The games the caller may not play, for the UI to offer a code in place of an entry. None for an overall admin.
      */
    def barred(callerExternalId: String): IO[List[GameId]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            repos.players.readByExternalId(callerExternalId).flatMap {
                case None                 => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                case Some(p) if p.isAdmin => IO.pure(Nil)
                case Some(p)              => repos.engineAccess.barredGames(p.playerId)
            }
        }

    /* A change to who plays the game's engine, made in one transaction once the caller is known to manage it. */
    private def managing[A](gameId: GameId, callerExternalId: String)(
        change: (Repos, Game, Player) => IO[A]
    ): IO[A] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                for {
                    caller <- repos.players.readByExternalIdForShare(callerExternalId).flatMap {
                        case Some(p) => IO.pure(p)
                        case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                    }
                    // For share: an admin giving the game to another engine waits for this change to the old one's
                    // list, rather than leaving it written against an engine the game no longer is.
                    _ <- repos
                        .games[T]
                        .lockForShare(gameId)
                        .flatMap(found => IO.raiseWhen(found.isEmpty)(NotFoundError(ErrorText.game.notFound)))
                    game <- requireGameRow(repos, gameId)
                    _ <- GameAdmins.require(
                      repos = repos,
                      caller = caller.playerId,
                      gameId = gameId,
                      refusal = ErrorText.engineAccess.manageNotAllowed(game.displayName)
                    )
                    result <- change(repos, game, caller)
                } yield result
            }
        }

    /* The game, once the caller is known to manage it: for a read, with no transaction. */
    private def requireManager(repos: Repos, gameId: GameId, callerExternalId: String): IO[Game] =
        for {
            caller <- repos.players.readByExternalId(callerExternalId).flatMap {
                case Some(p) => IO.pure(p)
                case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
            }
            game <- requireGameRow(repos, gameId)
            allowed <-
                if (caller.isAdmin) IO.pure(true) else repos.gameAdmins.isAdmin(caller.playerId, gameId)
            _ <- IO.raiseUnless(allowed)(UnauthorizedError(ErrorText.engineAccess.manageNotAllowed(game.displayName)))
        } yield game

    private def requireGameRow(repos: Repos, gameId: GameId): IO[Game] =
        repos.games[T].read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
        }
}

object EngineAccessService {

    /** That `player` may play `game`, or an `UnauthorizedError` saying `refusal`: the one check every way into a match
      * makes, inside its own transaction.
      *
      * Passes for an engine that is not restricted, an overall admin, an admin of the game, and a listed player. The
      * restriction and the listing are both held FOR SHARE, so that opening the engine and taking the player off the
      * list both wait for the entry this decides. Only the second needs to: an engine opened partway through an entry
      * may let it in or refuse it, as either serial order would, so holding the restriction costs opening it a short
      * wait and nothing more. An engine restricted while this runs is restricted after it, which leaves the entry
      * standing as any entry made before a restriction stands.
      */
    def requireAllowed(repos: Repos, game: Game, player: Player, refusal: => String): IO[Unit] = {
        val engine = EngineId(game.externalId)
        repos.engineAccess.isRestrictedForShare(engine).flatMap { isRestricted =>
            if (!isRestricted || player.isAdmin) IO.unit
            else
                repos.gameAdmins.isAdminForShare(player.playerId, game.gameId).flatMap { isGameAdmin =>
                    if (isGameAdmin) IO.unit
                    else
                        repos.engineAccess
                            .isListedForShare(engine, player.playerId)
                            .flatMap(isListed => IO.raiseUnless(isListed)(UnauthorizedError(refusal)))
                }
        }
    }

    private val random = new SecureRandom()

    /* RFC 4648's base32 alphabet: no lower case to confuse, and no 0, 1 or 8 to take for O, I or B. */
    private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 125 random bits as 25 base32 characters, in five groups of five joined by dashes for typing:
      * `ABCDE-FGHIJ-KLMNO-PQRST-UVWXY`. The entropy is the defence against guessing: there is no rate limit here.
      */
    def newCode(): String = {
        val chars = Array.fill(25)(Alphabet.charAt(random.nextInt(Alphabet.length)))
        chars.grouped(5).map(_.mkString).mkString("-")
    }

    /** A code as typed, reduced to what was made: upper case, without the dashes and spaces it is written with. */
    def normalize(code: String): String = code.toUpperCase.filter(c => c.isLetterOrDigit)

    /** What a code is stored by: the SHA-256 of its normal form, so that "abcde fghij" redeems "ABCDE-FGHIJ". */
    def hashOf(code: String): Array[Byte] = HandOffService.hashOf(normalize(code))
}
