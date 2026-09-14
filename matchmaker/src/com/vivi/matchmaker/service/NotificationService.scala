package com.vivi.matchmaker.service

import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{NotificationRepo, PlayerRepo}

/** What each player wants to be told about.
  *
  * Three levels are writable here, and every one of them is the caller's own: their settings in general, their settings
  * for one game, and their settings for one match. There is deliberately no route to anyone else's — a preference is
  * not something another player, or an admin, has any business changing — so, like `AcceptanceService.mine`, most of
  * these take no player id and there is nothing to refuse.
  *
  * The fourth level, a game's defaults, is not here: it belongs to the game's definition, is set by the admin who
  * registers it, and travels with the rest of `Game` through `GameService`.
  *
  * What reads all four and decides is `NotificationPolicy`, in the model. This service only records answers.
  */
class NotificationService(sessionPool: SessionPool) {

    /** Everything the caller's own settings screen shows: what they have said in general, and the games they have said
      * something particular about.
      */
    def mine(callerExternalId: String): IO[NotificationSettings] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                overall <- repo.readForPlayer(player.playerId)
                games <- repo.listForPlayer(player.playerId)
            } yield NotificationSettings(overall, games)
        }

    def updateMine(callerExternalId: String, preferences: NotificationPreferences): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                _ <- repo.updateForPlayer(player.playerId, preferences)
            } yield ()
        }

    /** Records what the caller wants to hear about one game, creating their `player_game` row if this is the first
      * thing they have said about it.
      *
      * The game is checked for existence first so that a bad id is a 404 rather than a foreign key violation, which
      * would reach the caller as a 500 about something they cannot act on.
      */
    def updateForGame(callerExternalId: String, gameId: GameId, preferences: NotificationPreferences): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                exists <- repo.gameExists(gameId)
                _ <- IO.raiseUnless(exists)(NotFoundError(s"no game with id ${gameId.value}"))
                _ <- repo.updateForPlayerGame(player.playerId, gameId, preferences)
            } yield ()
        }

    /** What the caller has said about one match. `unset` when they have said nothing, which is what the form shows as
      * "Use Default" throughout.
      *
      * Refused unless the caller is in the match: the answer would otherwise tell someone who is not playing that a
      * match exists, and there is nothing for them to set.
      */
    def forMatch(callerExternalId: String, gameId: GameId, matchId: MatchId): IO[NotificationPreferences] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                // The read itself is the membership check: no seat, no row. A row of NULLs is a player
                // who is in the match and has said nothing, which is `unset` rather than absent.
                preferences <- repo.readForPlayerInMatch(gameId, matchId, player.playerId).flatMap {
                    case Some(found) => IO.pure(found)
                    case None =>
                        IO.raiseError(
                          NotFoundError(
                            s"player ${player.playerId.value} has no seat in match ${matchId.value} of game ${gameId.value}"
                          )
                        )
                }
            } yield preferences
        }

    /** Records what the caller wants to hear about one match, on every seat they hold in it.
      *
      * The write itself says whether they are in the match — it touches their rows and no others — so there is no
      * separate check to go stale between the two.
      */
    def updateForMatch(
        callerExternalId: String,
        gameId: GameId,
        matchId: MatchId,
        preferences: NotificationPreferences
    ): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                written <- repo.updateForPlayerInMatch(gameId, matchId, player.playerId, preferences)
                _ <- IO.raiseUnless(written)(
                  NotFoundError(
                    s"player ${player.playerId.value} has no seat in match ${matchId.value} of game ${gameId.value}"
                  )
                )
            } yield ()
        }

    /* Every route here acts on the caller's own settings, so every one of them starts by turning the
     * token's identity into a player -- and a caller with no player has nothing here to read or
     * write, which is Unauthorized rather than NotFound for the same reason it is in
     * `AcceptanceService.mine`: the identity is real, it is simply not a player yet. */
    private def callerPlayer(session: skunk.Session[IO], callerExternalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(s"no player for caller '$callerExternalId'"))
        }
}
