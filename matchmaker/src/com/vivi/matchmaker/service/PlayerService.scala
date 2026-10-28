package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import cats.effect.IO
import skunk.SqlState
import com.vivi.matchmaker.model.{Player, PlayerSearchResult}
import com.vivi.matchmaker.persistence.{PlayerRepo, Repos}

/** The caller's own player record: reading it, and the two parts of it they may change.
  *
  * Registration lives in `RegistrationService`. The password is not here at all and never will be — it belongs to the
  * Cognito identity, the browser changes it against Cognito directly, and matchmaker never sees it.
  *
  * The email address is a halfway case. Cognito still owns it: it is the username on the pool, the browser changes it
  * there, and Cognito verifies the new address before the change takes effect. What `updateEmail` does is record the
  * result, taken from the verified claim of a token Cognito issued after the change, so that matchmaker has somewhere
  * to write to (see `player.email`). A browser that changes the address and then fails to report it is the one way the
  * copy can go stale, and the next sign-in reports it again.
  */
class PlayerService(sessionPool: SessionPool) {

    /** The player registered under `callerExternalId`.
      *
      * An unknown externalId is `UnauthorizedError`, not `NotFoundError`: the caller holds a valid identity that has
      * never registered, and the fix is to register, not to look elsewhere.
      */
    def me(callerExternalId: String): IO[Player] =
        sessionPool.use { session =>
            val repos = Repos(session)
            repos.players.readByExternalId(callerExternalId).flatMap {
                case Some(player) => IO.pure(player)
                case None         => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
            }
        }

    /** Players whose nickname begins with `prefix`, for the search box.
      *
      * Case insensitive, because the normalized comparison intentionally folds names while preserving the registered
      * spelling in the result. Two players may be registered as "Ash" and "ash", and a search offers both rather than
      * treating them as one.
      *
      * At most [[PlayerService.searchLimit]] players, with `hasMore` saying there were others. A prefix of one letter
      * can match most of the register, and a page of everybody is not an answer -- so the reply says plainly that it is
      * a page, and the remedy is the one the searcher already has: type more of the name. No offset paging: the next
      * page of a name search is not what anybody wants, the narrower prefix is.
      *
      * Asks for one row more than it shows, which is how `hasMore` is known. Counting the matches instead would be a
      * second query over the same predicate to answer a question with only two useful values.
      *
      * A blank prefix is refused rather than answered with the first 25 players in the register: it is what an empty
      * search box sends, and a list of strangers is not what pressing Search on nothing meant to ask for.
      */
    def search(callerExternalId: String, prefix: String): IO[PlayerSearchResult] = {
        val trimmed = prefix.trim
        IO.raiseWhen(trimmed.isEmpty)(ValidationError(ErrorText.search.blankPrefix)) *>
            sessionPool.use { session =>
                val repos = Repos(session)
                for {
                    // Registered callers only, like every other route: the search says who plays here,
                    // which is for the people who do.
                    _ <- requireRegistered(repos.players, callerExternalId)
                    found <- repos.players.searchByNicknamePrefix(trimmed, PlayerService.searchLimit + 1)
                } yield PlayerSearchResult(
                  found.take(PlayerService.searchLimit),
                  hasMore = found.sizeIs > PlayerService.searchLimit
                )
            }
    }

    /** Renames the caller.
      *
      * Only the nickname: `isAdmin` and `externalId` are copied through from the stored row rather than taken from the
      * caller, so this route cannot be used to grant oneself admin or to take over another identity.
      *
      * Scoped to whoever is calling, so there is no target to authorize — a player can only rename themselves, and an
      * unregistered caller gets the same `UnauthorizedError` `me` gives.
      */
    def updateNickname(callerExternalId: String, nickname: String): IO[Player] =
        IO.raiseWhen(nickname.trim.isEmpty)(ValidationError(ErrorText.player.blankNickname)) *>
            sessionPool.use { session =>
                val repos = Repos(session)
                /* Read and write in one transaction, and the read takes the row's lock: `update`
                 * writes the whole row, so the values it carries through -- `isAdmin`, `externalId`
                 * -- are whatever this read saw. Outside the lock a change committing in the gap
                 * would be restated back to its old value by this rename. */
                session.transaction.use { _ =>
                    for {
                        player <- requireCaller(repos.players, callerExternalId)
                        renamed = player.copy(nickname = nickname.trim)
                        _ <- repos.players.update(renamed).recoverWith { case SqlState.UniqueViolation(_) =>
                            IO.raiseError(ConflictError(ErrorText.player.nicknameTaken(renamed.nickname)))
                        }
                    } yield renamed
                }
            }

    /** Records the address the caller signs in with.
      *
      * `email` is the verified `email` claim of the token the request carried, which the router has from
      * `Authenticator.verifiedEmailOf` — never an address from the request body, which would let a caller point their
      * notifications at a mailbox that is not theirs. Cognito fixes a token's claims when it issues it, so the browser
      * asks for this at the two moments its token is new enough to name the current address: on sign-in, and after a
      * confirmed change once it has redeemed its refresh token for a new one.
      *
      * Writes the address and nothing else — `PlayerRepo.updateEmail`, not `update` — so that this cannot restate a
      * nickname from a `Player` read before it, and a rename cannot restate an address. Idempotent: the ordinary case
      * is that the claim and the stored value already agree, and the caller does not send anything then.
      */
    def updateEmail(callerExternalId: String, email: String): IO[Player] =
        validEmail(email).flatMap { address =>
            sessionPool.use { session =>
                val repos = Repos(session)
                // One transaction, and the read locks: the row written is the row read, so a caller
                // deregistering or renaming in between cannot be written around.
                session.transaction.use { _ =>
                    for {
                        player <- requireCaller(repos.players, callerExternalId)
                        _ <- repos.players.updateEmail(player.playerId, Some(address))
                    } yield player.copy(email = Some(address))
                }
            }
        }

    /* That the caller is a registered player, for the search above -- which is about somebody
     * else, and needs no row of the caller's beyond the fact that there is one. Unlocked -- nothing
     * here writes, and what is being checked is existence, which a read of somebody else's page
     * does not depend on staying true for. */
    private def requireRegistered(repo: PlayerRepo, callerExternalId: String): IO[Player] =
        repo.readByExternalId(callerExternalId).flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
        }

    /* The caller's own row, locked, for the two calls that rewrite it. Unauthorized rather than
     * NotFound for the reason `me` gives. */
    private def requireCaller(repo: PlayerRepo, callerExternalId: String): IO[Player] =
        repo.readByExternalIdForUpdate(callerExternalId).flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
        }

    /* Enough of a check to catch a blank field or an obvious mistype, and no more.
     *
     * Deliberately not a grammar for addresses: this one is a verified claim, so Cognito has
     * already mailed it and had the code answered, which is a stronger statement about deliverability than any pattern, and a
     * stricter rule here could only reject an address that demonstrably works. */
    private def validEmail(email: String): IO[String] = {
        val trimmed = email.trim
        val plausible = trimmed.count(_ == '@') == 1 && !trimmed.startsWith("@") && !trimmed.endsWith("@") &&
            !trimmed.exists(_.isWhitespace)
        IO.raiseUnless(plausible)(ValidationError(ErrorText.email.notAnAddress(email))).as(trimmed)
    }
}

object PlayerService {

    /** How many players a nickname search answers with at most. A screenful, with `hasMore` on the reply to say when it
      * is not all of them; the cure for hitting it is a longer prefix, not another page.
      */
    val searchLimit: Int = 25
}
