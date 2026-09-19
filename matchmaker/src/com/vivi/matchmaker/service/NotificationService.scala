package com.vivi.matchmaker.service

import java.time.Instant
import cats.effect.IO
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{NotificationRepo, PlayerRepo, SuppressionRepo}

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
  * The three read as a chain only when a seat is created: since V14 a seat carries its own eleven answers and nothing
  * reads past it, so changing a game's settings does not change a match already being played. What makes that sayable
  * is the cascades below — the offers `updateMine` and `updateForGame` take, which carry a player's answers into the
  * rows that had inherited from them.
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
                /* And whether any of it is reaching them.
                 *
                 * Read here rather than from a route of its own because a screen that showed the
                 * form before it knew the form was moot would be showing eleven settings that change
                 * nothing. Read against the player's *stored* address, which is the one the send path
                 * writes to -- a player who has just changed their address in Cognito and not signed
                 * in again still has the old one here, and is correctly still told about the old
                 * one's bounce, because that is still where their mail is going.
                 *
                 * A row that exists but is not acted on -- one transient delay, or a released row --
                 * is not news, and is filtered out here rather than shown as a warning about nothing. */
                suppressed <- player.email.map(_.trim).filter(_.nonEmpty) match {
                    case None => IO.pure(None)
                    case Some(address) =>
                        new SuppressionRepo(session)
                            .read(address)
                            .map(_.filter(_.active(Instant.now())).map(EmailSuppression.Notice.of))
                }
            } yield NotificationSettings(overall, games, suppressed)
        }

    /** Records the caller's defaults, and optionally carries them downwards.
      *
      * `applyToGames` copies the change into every game they have said something about, so that none of those games
      * goes on answering that question differently. `applyToMatches` re-stamps the seats in the matches they are still
      * playing from the chain as it then stands — which is why the order below is not arbitrary: the games are aligned
      * first, so that the seats are stamped from what the player has just asked for rather than from what they asked
      * for last time.
      *
      * Both cascades carry only what this save changed, which is what the read below is for. A player changing one
      * question has said something about that question and nothing about the other seven — and one of those seven may
      * be a mute they put on a single match, or an answer they gave one game on purpose. Saving the same answers twice
      * cascades nothing the second time, because nothing changed.
      *
      * `applyToMatches` is only offered alongside `applyToGames` by the screen that calls this, because a player who
      * has left one game answering differently on purpose has not asked for their defaults to reach that game's
      * matches. It is not refused here, though: the two are independent writes and either is a coherent thing to want.
      *
      * All of it in one transaction, and the read takes the row's lock rather than merely sharing a transaction with
      * the write — a plain `SELECT` would be re-readable by a second save, which would then diff against answers this
      * one has already replaced and cascade a change that had nothing left to carry. The row and the rows beneath it
      * would disagree about which save had happened, and no screen shows that.
      */
    def updateMine(
        callerExternalId: String,
        preferences: NotificationPreferences,
        applyToGames: Boolean = false,
        applyToMatches: Boolean = false
    ): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            session.transaction.use { _ =>
                // Inside the transaction, like every other read here: the row written below is the
                // caller's, so which player that is has to be settled by the same transaction that
                // writes it rather than by a statement that committed before it began.
                callerPlayer(session, callerExternalId).flatMap { player =>
                    for {
                        before <- repo.readForPlayerForUpdate(player.playerId)
                        changed = before.differences(preferences)
                        _ <- repo.updateForPlayer(player.playerId, preferences)
                        // Nothing changed is nothing to carry anywhere, whatever was ticked.
                        _ <- IO.whenA(applyToGames && changed.nonEmpty)(
                          repo.alignGamesWithPlayer(player.playerId, preferences, changed)
                        )
                        _ <- IO.whenA(applyToMatches && changed.nonEmpty)(
                          repo.applyToMatches(player.playerId, None, changed)
                        )
                    } yield ()
                }
            }
        }

    /** Records what the caller wants to hear about one game, creating their `player_game` row if this is the first
      * thing they have said about it.
      *
      * `applyToMatches` carries the change into the matches of that game they are still playing. Without it the change
      * governs the matches they start from now on and leaves the ones they are in alone, which is the whole point of a
      * seat answering for itself — so the offer is what makes "and I meant the matches I am in too" sayable.
      *
      * Only what this save changed reaches those seats, which is what the read below is for: a player who turns off
      * turn-taken for a game has said nothing about the match whose results they muted last week, and re-stamping all
      * eleven columns would have unmuted it.
      *
      * The game is checked for existence first so that a bad id is a 404 rather than a foreign key violation, which
      * would reach the caller as a 500 about something they cannot act on.
      */
    def updateForGame(
        callerExternalId: String,
        gameId: GameId,
        preferences: NotificationPreferences,
        applyToMatches: Boolean = false
    ): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            session.transaction.use { _ =>
                for {
                    // Caller and game resolved inside the transaction: both decide what is written
                    // below, and the existence check in particular is what turns a bad id into a 404
                    // rather than the foreign key violation a game deleted in the gap would produce.
                    player <- callerPlayer(session, callerExternalId)
                    exists <- repo.gameExists(gameId)
                    _ <- IO.raiseUnless(exists)(NotFoundError(s"no game with id ${gameId.value}"))
                    // The player's row, not this game's: the same lock every level of this service
                    // takes, and the only one available when the `player_game` row does not exist
                    // yet. See `NotificationRepo.lockSettings`.
                    _ <- repo.lockSettings(player.playerId)
                    before <- repo.readForPlayerGame(player.playerId, gameId)
                    changed = before.differences(preferences)
                    _ <- repo.updateForPlayerGame(player.playerId, gameId, preferences)
                    _ <- IO.whenA(applyToMatches && changed.nonEmpty)(
                      repo.applyToMatches(player.playerId, Some(gameId), changed)
                    )
                } yield ()
            }
        }

    /** What the caller's seats in one match say. Every kind a seat can answer, all of them said: a seat cannot leave
      * one unsaid, so the form that shows these has no "Use Default" option and nothing to fall back to.
      *
      * Four kinds rather than eleven since V24 — a seat is only ever asked about the match it is in, which is what
      * [[com.vivi.matchmaker.model.SeatNotifications]] holds.
      *
      * Refused unless the caller is in the match: the answer would otherwise tell someone who is not playing that a
      * match exists, and there is nothing for them to set.
      */
    def forMatch(callerExternalId: String, gameId: GameId, matchId: MatchId): IO[SeatNotifications] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            for {
                player <- callerPlayer(session, callerExternalId)
                // The read itself is the membership check: no seat, no row.
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
        preferences: SeatNotifications
    ): IO[Unit] =
        sessionPool.use { session =>
            val repo = new NotificationRepo(session)
            // One statement's worth of writing, but still a transaction: the caller resolved above
            // is the key the write is scoped by, and the two belong to one another.
            session.transaction.use { _ =>
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
