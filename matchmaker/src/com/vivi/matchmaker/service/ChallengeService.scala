package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import com.vivi.matchmaker.model._
import skunk.Session
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.persistence.{
    AcceptanceRepo,
    CharacterRepo,
    GameRepo,
    ChallengeRepo,
    InvitationRepo,
    LockedChallenge,
    PlayerRepo,
    TextCodec
}

/** Creates and deletes challenges, and invites players to them. For a `'C'`-type game (a [[CharacterChallenge]]), both
  * operations are authorized by `callerExternalId` matching the externalId of the player who owns the challenge's
  * character, same as before. For a `'P'`-type game (a [[PlainChallenge]]) there is no character to authorize through,
  * so `callerExternalId` must match the challenger player directly.
  */
class ChallengeService[T](
    sessionPool: SessionPool,
    /* Who gets told about an acceptance is `Notifications`' business, not this service's: all this
     * knows is that one happened. Silent by default, which is what an environment with no queue and
     * no sender is -- so a spec with no opinion about mail constructs this exactly as it did before
     * notifications existed. */
    notifications: Notifications = Notifications.disabled,
    /* How a challenge whose required roles have just filled up gets started, for the challenges
     * offered on those terms (`Challenge.autoStart`), and whether the challenge is now a match.
     *
     * A match rather than "did this start one", which is not the same question and is the wrong one:
     * a start that lost a race to another acceptance filling the same last seat, or to the challenger
     * pressing Start, started nothing and yet leaves a match. See `GameEngineService.AutoStart`.
     *
     * A function
     * rather than a `GameEngineService`, because what this service knows is that a challenge has
     * been accepted: whether that is also the moment a match begins, and everything involved in
     * beginning one, belongs to the service that starts matches.
     *
     * The answer is what decides whether an acceptance is news in its own right -- see `accept` --
     * and the player is who accepted, which is what the mail about the match then opens with.
     *
     * Handed the session this service is already holding, as every `Notifications` method is and for
     * the same reason: a start needs a connection, and borrowing a second one while the first is
     * still held is how a bounded pool deadlocks -- `Services.defaultPoolSize` concurrent accepts
     * would each hold one and wait for one only another holder can give back. Not the transaction,
     * which has committed by then and could not have covered a start anyway: a start talks to the
     * game engine between two transactions of its own, and one transaction across that would hold
     * the challenge's row lock for as long as another system takes to answer -- and would undo a
     * recorded acceptance when that system failed.
     *
     * Starts nothing by default, which is what an environment with no engine is -- so a spec with no
     * opinion about starting constructs this exactly as it did before. */
    autoStart: (Session[IO], GameId, ChallengeId, Player) => IO[Boolean] = (_, _, _, _) => IO.pure(false)
)(using codec: TextCodec[T]) {

    /* The game, read plainly even inside a transaction that writes -- the reference-table exception
     * in CLAUDE.md. The catalogue is small and changes only when an admin edits it, while what is
     * written from it here is `match`, `participant` and `result`; taking the game's row lock on
     * every call would queue a whole game's traffic behind one row for a race nobody runs.
     *
     * The exception is about that asymmetry, not about being a read that does not matter: a caller
     * reading a game in order to rewrite *it* locks it (`GameService.createOrUpdate`). Where an
     * existence check has to outlive the insert that relies on it, `GameRepo.lockForShare` is the
     * middle course -- see `CharacterService.create`. */
    private def requireGame(gameRepo: GameRepo[T], gameId: GameId): IO[Game] =
        gameRepo.read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }

    /** The player a challenge is being created or deleted for, locked for the rest of the transaction so the
      * authorization decided from it cannot be invalidated before the write.
      */
    private def requirePlayer(playerRepo: PlayerRepo, playerId: PlayerId): IO[Player] =
        playerRepo.readForShare(playerId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(NotFoundError(s"no player with id ${playerId.value}"))
        }

    /** Creates a challenge, its challenger's own acceptance of it, and any invitations it is being offered with.
      *
      * All three in the one transaction, because they are one act. Two calls — create, then invite — would leave a
      * window in which a challenge that is not open exists with nobody invited to it, which is a challenge nobody can
      * accept and nobody but its challenger can even see.
      *
      * `invitations` default to none, which is what an open challenge offered to nobody in particular is.
      */
    /** Checks a set of invitations against the game, the challenge's challenger and the seats already spoken for.
      *
      * Shared by `create` and `invite` so that one invitation and five are checked by the same rules. Everything here
      * is refused before anything is written: an invitation is permission, and permission granted to the wrong player
      * or for a seat that is gone is worse than none.
      *
      * @param taken
      *   the roles that are already unavailable — accepted by somebody, or held by another invitation. The caller
      *   assembles it, because the two callers know it from different places: `create` writes the challenger's
      *   acceptance itself, while `invite` reads what is there.
      */
    private def validateInvitations(
        game: Game,
        playerRepo: PlayerRepo,
        challenger: PlayerId,
        invitations: Seq[Invite],
        taken: Set[GameRoleId]
    ): IO[Unit] = {
        val duplicated =
            invitations.groupBy(_.playerId).collect { case (player, invites) if invites.sizeIs > 1 => player }

        for {
            // One invitation per player per challenge, which the primary key also says. Caught here so
            // that a caller who listed somebody twice is told which player, rather than shown a
            // constraint violation as a 500.
            _ <- IO.raiseWhen(duplicated.nonEmpty)(
              ValidationError(
                s"player ${duplicated.head.value} is invited more than once to the same challenge"
              )
            )
            _ <- invitations.traverse_ { invite =>
                for {
                    // The challenger already holds a seat in their own challenge; inviting themselves
                    // would be permission to accept a challenge they cannot accept.
                    _ <- IO.raiseWhen(invite.playerId == challenger)(
                      ValidationError("a challenger cannot be invited to their own challenge")
                    )
                    // Locked for share: the invitation row inserted next references this player, and an
                    // unlocked read would let the account be removed between the check and the insert.
                    _ <- requirePlayer(playerRepo, invite.playerId)
                    _ <- invite.gameRoleId.traverse_ { role =>
                        for {
                            // As everywhere else in this service, a role from another game is a 400
                            // naming the game rather than a foreign-key violation surfacing as a 500.
                            _ <- IO.raiseUnless(game.roles.exists(_.gameRoleId == role))(
                              ValidationError(s"game ${game.gameId.value} has no role ${role.value}")
                            )
                            _ <- IO.raiseWhen(taken.contains(role))(
                              ConflictError(s"role ${role.value} is not free to be offered to another player")
                            )
                        } yield ()
                    }
                } yield ()
            }
            // Two invitations naming one role would hold one seat for two people, and the second of
            // them could never be honoured. The pairwise check is the same rule as `taken` above,
            // asked of the invitations against each other.
            reserved = invitations.flatMap(_.gameRoleId)
            _ <- IO.raiseWhen(reserved.distinct.sizeIs < reserved.size)(
              ValidationError("two invitations to the same challenge cannot hold the same role")
            )
        } yield ()
    }

    def create(challenge: Challenge, callerExternalId: String, invitations: Seq[Invite] = Seq.empty): IO[Challenge] =
        sessionPool.use { session =>
            val gameRepo = new GameRepo[T](session)
            val characterRepo = new CharacterRepo[T](session)
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val invitationRepo = new InvitationRepo(session)
            // Creating a challenge is itself an acceptance of it: the challenger is the first
            // participant. Both rows go in together so a challenge can never exist with its creator
            // missing from its own acceptances.
            val made = session.transaction.use { _ =>
                for {
                    // Unlocked, per the note on `requireGame`: what this decides is the challenge
                    // and acceptance rows written below, not anything about the game itself.
                    game <- requireGame(gameRepo, challenge.gameId)
                    _ <- challenge match {
                        case cc: CharacterChallenge =>
                            for {
                                _ <- IO.raiseUnless(game.gameType == GameType.Character)(
                                  ValidationError(
                                    s"game ${game.gameId.value} does not require a character, but a CharacterChallenge was given"
                                  )
                                )
                                // Locked: ownership is what authorizes this challenge, and the challenge row
                                // inserted below references the character. An unlocked read would let the
                                // character be reassigned or removed between the check and the insert.
                                joined <- characterRepo.readWithOwnerAndGameForUpdate(cc.characterId).flatMap {
                                    case Some(t) => IO.pure(t)
                                    case None =>
                                        IO.raiseError(NotFoundError(s"no character with id ${cc.characterId.value}"))
                                }
                                owner = joined.owner
                                characterGame = joined.game
                                _ <- IO.raiseUnless(challenge.gameId == characterGame.gameId)(
                                  ValidationError(
                                    s"challenge game_id ${challenge.gameId.value} does not match character's game_id ${characterGame.gameId.value}"
                                  )
                                )
                                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                                  UnauthorizedError(
                                    s"caller '$callerExternalId' may not create a challenge for character ${cc.characterId.value}"
                                  )
                                )
                                // The caller owning the character is not enough on its own: challenger names the
                                // player the challenge (and now its implicit acceptance) is recorded under, so it
                                // has to be the character's owner too, not some other player the caller picked.
                                _ <- IO.raiseUnless(challenge.challenger == owner.playerId)(
                                  UnauthorizedError(
                                    s"player ${challenge.challenger.value} does not own character ${cc.characterId.value}"
                                  )
                                )
                            } yield ()
                        case _: PlainChallenge =>
                            for {
                                _ <- IO.raiseUnless(game.gameType == GameType.Plain)(
                                  ValidationError(
                                    s"game ${game.gameId.value} requires a character, but a PlainChallenge was given"
                                  )
                                )
                                challengerPlayer <- requirePlayer(playerRepo, challenge.challenger)
                                _ <- IO.raiseUnless(callerExternalId == challengerPlayer.externalId)(
                                  UnauthorizedError(
                                    s"caller '$callerExternalId' may not create a challenge for player ${challenge.challenger.value}"
                                  )
                                )
                            } yield ()
                    }
                    // As in accept: the role must be one of this game's, checked here so a wrong one is a
                    // 400 rather than a foreign-key violation surfacing as a 500. There is no "no role"
                    // case left to skip — every acceptance names one, and creating a challenge writes the
                    // challenger's acceptance.
                    _ <- IO.raiseUnless(game.roles.exists(_.gameRoleId == challenge.gameRoleId))(
                      ValidationError(s"game ${game.gameId.value} has no role ${challenge.gameRoleId.value}")
                    )
                    // A challenge nobody may accept is not a challenge. Refused here rather than left to
                    // be noticed later, because the only thing that could rescue it is an invitation, and
                    // the caller who meant to send one is right here to be told.
                    _ <- IO.raiseWhen(!challenge.isOpen && invitations.isEmpty)(
                      ValidationError("a challenge that is not open must invite at least one player")
                    )
                    _ <- validateInvitations(
                      game,
                      playerRepo,
                      challenge.challenger,
                      invitations,
                      // The challenger's own role is the one seat already gone at this point: their
                      // acceptance is written below, so nothing has read it yet and it has to be named
                      // here rather than asked for.
                      taken = Set(challenge.gameRoleId)
                    )
                    created <- challengeRepo.create(challenge)
                    _ <- acceptanceRepo.create(created match {
                        case cc: CharacterChallenge =>
                            CharacterAcceptance(cc.challengeId, cc.challenger, cc.gameId, cc.characterId, cc.gameRoleId)
                        case pc: PlainChallenge =>
                            PlainAcceptance(pc.challengeId, pc.challenger, pc.gameId, pc.gameRoleId)
                    })
                    _ <- invitations.traverse_(invite =>
                        invitationRepo.create(
                          Invitation(created.gameId, created.challengeId, invite.playerId, invite.gameRoleId)
                        )
                    )
                    // Carried out of the transaction because the mail needs the invitee's own address and
                    // the name of the seat they were offered, and `game` is in hand here where it is not
                    // afterwards. Read rather than passed for the same reason `accept` reads its actor.
                    invited <- invitations.traverse(invite =>
                        requirePlayer(playerRepo, invite.playerId).map(player => (player, roleName(game, invite)))
                    )
                } yield (created, invited)
            }

            /* After the commit, and nothing about it can fail the create -- see `Notifications`. One
             * mail per invitee, because each is being told about their own invitation and nobody else's:
             * a challenge offered to three people is three private offers that happen to share a row. */
            made.flatMap { (created, invited) =>
                invited
                    .traverse_((player, role) =>
                        notifications.invitationMade(session, created.gameId, created.challengeId, player, role)
                    )
                    .as(created)
            }
        }

    /* The name of the seat an invitation holds, for the mail that offers it. From the game already in
     * hand rather than a query: the roles are what `validateInvitations` has just checked the id
     * against, so a `None` here means the invitation named no role rather than that a role is missing. */
    private def roleName(game: Game, invite: Invite): Option[String] =
        invite.gameRoleId.flatMap(id => game.roles.find(_.gameRoleId == id).map(_.name))

    /** Accepts `challengeId` in game `gameId`, authorized by `callerExternalId`. For a `'C'`-type game's challenge,
      * `characterId` must be `Some`, naming the character accepting on the caller's behalf, and is authorized the same
      * way `create` authorizes a [[CharacterChallenge]]. For a `'P'`-type game's challenge, `characterId` must be
      * `None`, and the caller accepts as themselves. The challenge row is locked (`FOR UPDATE`) before the role check,
      * which is what makes that check race-free against concurrent acceptance attempts: `gameRoleId` must be one of the
      * game's roles and must not already be taken by another acceptance of this challenge, and two players asking for
      * the same free role at once must not both be told yes. That role check is also the capacity check: a challenge
      * holds one acceptance per role of its game and is full when every one of them is taken, so there is no separate
      * count to compare against.
      */
    def accept(
        gameId: GameId,
        challengeId: ChallengeId,
        characterId: Option[CharacterId],
        gameRoleId: GameRoleId,
        callerExternalId: String
    ): IO[Acceptance] =
        sessionPool.use { session =>
            val gameRepo = new GameRepo[T](session)
            val characterRepo = new CharacterRepo[T](session)
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val invitationRepo = new InvitationRepo(session)
            val accepted = session.transaction.use { _ =>
                for {
                    challengeInfo <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
                        case Some(t) => IO.pure(t)
                        case None =>
                            IO.raiseError(
                              NotFoundError("That challenge is no longer there. Whoever offered it has withdrawn it.")
                            )
                    }
                    // A challenge whose start is in flight is spoken for: its roster has already been
                    // turned into participants and handed to the engine, so an acceptance added now would
                    // never reach the match: the roster the engine was given is the match, and nothing
                    // added here joins it. Refused rather than silently lost.
                    //
                    // The message is for whoever pressed Accept, which is why it names no ids and does
                    // not mention the claim: what they need to know is that the match is under way and
                    // the challenge is finished with. The request's own path carries the ids for
                    // anything reading the logs.
                    _ <- challengeInfo.startedMatchId.traverse_ { _ =>
                        IO.raiseError(ConflictError("The match has already started. The challenge is closed."))
                    }
                    gameType = challengeInfo.gameType
                    // A role has to be one of this game's, which the schema's composite foreign key also
                    // enforces — checked here so that a wrong role is a 400 naming the game rather than a
                    // constraint violation surfacing as a 500.
                    // Unlocked, per the note on `requireGame`. The race that matters here is two
                    // players taking the same role, and the challenge's own lock above settles it;
                    // an admin adding a role to the game meanwhile is not one.
                    _ <- requireGame(gameRepo, gameId).flatMap { game =>
                        IO.raiseUnless(game.roles.exists(_.gameRoleId == gameRoleId))(
                          ValidationError(s"game ${gameId.value} has no role ${gameRoleId.value}")
                        )
                    }
                    // And it has to still be free. Under the challenge's lock, so two players asking for the
                    // same role at the same moment cannot both pass this. The unique index on
                    // (game_id, challenge_id, game_role_id) backs it up; this check is what makes the
                    // refusal a 409 naming the role rather than a constraint violation.
                    taken <- acceptanceRepo.rolesForChallenge(gameId, challengeId)
                    _ <- IO.raiseWhen(taken.contains(gameRoleId))(
                      ConflictError(s"role ${gameRoleId.value} is already taken in challenge ${challengeId.value}")
                    )
                    // And it must not be a seat held for somebody else (V22). Asked of every challenge
                    // and not only a closed one: otherwise a role named on an invitation would mean
                    // something on a closed challenge and nothing on an open one, and a challenger who
                    // asked their friend to play the defender and left the rest open would have that
                    // seat taken by a passer-by. Under the challenge's lock, like the check above it.
                    reserved <- invitationRepo.reservedRoles(gameId, challengeId)
                    acceptance <- (gameType, characterId) match {
                        case (GameType.Character, Some(cid)) =>
                            for {
                                // Locked for the same reason as in create: the acceptance about to be written
                                // names this owner and references this character.
                                joined <- characterRepo.readWithOwnerAndGameForUpdate(cid).flatMap {
                                    case Some(t) => IO.pure(t)
                                    case None    => IO.raiseError(NotFoundError(s"no character with id ${cid.value}"))
                                }
                                owner = joined.owner
                                game = joined.game
                                _ <- IO.raiseUnless(callerExternalId == owner.externalId)(
                                  UnauthorizedError(
                                    s"caller '$callerExternalId' may not accept challenge ${challengeId.value} for character ${cid.value}"
                                  )
                                )
                                _ <- IO.raiseUnless(game.gameId == gameId)(
                                  ValidationError(
                                    s"character ${cid.value} is not from the same game as challenge ${challengeId.value}"
                                  )
                                )
                            } yield CharacterAcceptance(
                              challengeId,
                              owner.playerId,
                              gameId,
                              cid,
                              gameRoleId
                            ): Acceptance
                        case (GameType.Plain, None) =>
                            // Locked: the acceptance written below references this player.
                            playerRepo.readByExternalIdForShare(callerExternalId).flatMap {
                                case Some(player) =>
                                    IO.pure(
                                      PlainAcceptance(challengeId, player.playerId, gameId, gameRoleId): Acceptance
                                    )
                                case None => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
                            }
                        case (GameType.Character, None) =>
                            IO.raiseError(
                              ValidationError(s"challenge ${challengeId.value} requires a characterId to accept")
                            )
                        case (GameType.Plain, Some(_)) =>
                            IO.raiseError(
                              ValidationError(s"challenge ${challengeId.value} does not accept a characterId")
                            )
                    }
                    // Whether this player may accept this challenge at all (V22). A challenge that is not
                    // open is accepted only by the players invited to it, and the invitation is read under
                    // the same lock as the acceptance, so a revoke cannot land between the two.
                    //
                    // Resolved against the acceptance's playerId rather than the caller: a character
                    // game's invitation is answered by the character's owner, because an invitation is to
                    // a person and a character is not one.
                    invitation <- invitationRepo.read(gameId, challengeId, acceptance.playerId)
                    _ <- IO.raiseWhen(!challengeInfo.isOpen && invitation.isEmpty)(
                      // Said the same way to a player who was never invited and to one whose invitation
                      // has been withdrawn, because by now those are the same state: withdrawing deletes
                      // the row, and there is nothing left to tell them apart by. The sentence covers
                      // both rather than guessing at which.
                      UnauthorizedError(
                        "This challenge is open only to players invited to it. " +
                            "If you were invited, the invitation has been withdrawn."
                      )
                    )
                    // An invitation that names a role is a seat held for this player, and the offer was to
                    // play *that*. Refused rather than quietly honoured as something else: the challenger
                    // asked for a defender, and a challenge that fills up with the wrong roles is not the
                    // one either of them agreed to.
                    _ <- invitation.flatMap(_.gameRoleId).traverse_ { asked =>
                        IO.raiseUnless(asked == gameRoleId)(
                          ValidationError(
                            s"player ${acceptance.playerId.value} was invited to challenge ${challengeId.value} as role ${asked.value}"
                          )
                        )
                    }
                    // The other side of the same fact: a seat held for somebody else is not free, however
                    // this player came by it.
                    _ <- IO.raiseWhen(
                      reserved.exists((role, held) => role == gameRoleId && held != acceptance.playerId)
                    )(
                      ConflictError(
                        s"role ${gameRoleId.value} in challenge ${challengeId.value} is held for another player"
                      )
                    )
                    // One seat per player per challenge. This check is the whole of that rule: since V5 the
                    // acceptance key is (game_id, challenge_id, game_role_id), so the database will happily
                    // hold two rows for one player, and nothing but this refuses them. It is under the
                    // challenge's FOR UPDATE lock, taken above, so two simultaneous accepts by the same
                    // player cannot both find nothing here.
                    //
                    // The rule may be relaxed one day — a player holding two seats in a six-player game is
                    // a coherent thing to want — and relaxing it means deleting these four lines and
                    // nothing else, which is why it lives here rather than in the schema.
                    already <- acceptanceRepo.hasAccepted(gameId, challengeId, acceptance.playerId)
                    _ <- IO.raiseWhen(already)(
                      ConflictError(
                        s"player ${acceptance.playerId.value} has already accepted challenge ${challengeId.value}"
                      )
                    )
                    created <- acceptanceRepo.create(acceptance)
                    // Carried out of the transaction because it is the one thing the notification
                    // cannot read for itself: it addresses the other players by saying who accepted.
                    actor <- requirePlayer(playerRepo, created.playerId)
                } yield (created, actor, invitation.isDefined)
            }

            /* After the commit, and nothing about it can fail the accept -- see `Notifications`.
             * Outside the transaction on purpose: it holds the challenge's FOR UPDATE lock, and half a
             * dozen reads and a queue call taken inside it would keep every other player trying to
             * accept the same challenge waiting on an email. */
            accepted.flatMap { (created, actor, wasInvited) =>
                for {
                    /* The start first, because whether it happened is what this acceptance *is*.
                     *
                     * On a challenge offered as starting itself, the acceptance that fills the last
                     * required role is not news about a challenge -- it is the match beginning, and
                     * `matchStarted` tells everyone so, the challenger included. Sending both would
                     * write to them twice about one event, and the first of the two would be about a
                     * challenge that no longer exists to be accepted or started.
                     *
                     * The same holds when the match was started by something else in the same moment
                     * -- the acceptance that filled the other last seat, or the challenger pressing
                     * Start -- which is why the question asked is "is this a match now" and not "did I
                     * start one". Both answer true, and in both this acceptance has been overtaken:
                     * telling the players their challenge is ready to start, after they have been told
                     * the match began, describes something that is over.
                     *
                     * `false` covers every other case and they all want the ordinary mail: an
                     * acceptance that leaves a role unfilled, a challenge that was not offered on these
                     * terms, and a start that was meant to happen and failed -- that last one
                     * especially, since the challenge is then still there to be started by hand and the
                     * mail is what says so.
                     *
                     * Neither can fail this accept: the acceptance is recorded, `startIfReady` swallows
                     * and logs whatever it runs into, and `Notifications` does the same. */
                    isMatch <- autoStart(session, gameId, challengeId, actor)
                    /* `wasInvited` reaches the challenger and nobody else: their invitation was taken
                     * up, which is more than that somebody accepted. It is one mail either way -- the
                     * kind is chosen from what they have asked for -- and it is suppressed with the rest
                     * of this when the acceptance started the match, for the same reason. */
                    _ <- IO.unlessA(isMatch)(
                      notifications.challengeAccepted(session, gameId, challengeId, actor, wasInvited)
                    )
                } yield created
            }
        }

    /** The open challenges for a game, which any registered player may browse in order to accept one.
      *
      * The caller is resolved to a player rather than merely checked, because what the list holds depends on who is
      * asking: a challenge that is full but not yet started is shown only to the players in it. See
      * [[ChallengeRepo.listByGame]].
      */
    def listByGame(gameId: GameId, callerExternalId: String): IO[List[ChallengeSummary]] =
        sessionPool.use { session =>
            for {
                caller <- new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
                    case Some(player) => IO.pure(player)
                    case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
                }
                challenges <- new ChallengeRepo(session).listByGame(gameId, caller.playerId)
                // One query for the game rather than one per challenge, and joined here rather than in
                // the listing query: invitations are rows in another table, and that query is already
                // asking three questions of `challenge`.
                invitations <- new InvitationRepo(session).listForGame(gameId)
            } yield challenges.map(summary =>
                summary.copy(invitations = invitations.getOrElse(summary.challenge.challengeId, Nil))
            )
        }

    /** Everything `callerExternalId` has been invited to and could still accept, newest first.
      *
      * Across every game, because that is the question a player's home page asks — where the game screen asks
      * [[listByGame]] instead. No authorization beyond being registered: these are invitations addressed to the caller,
      * and the query is what restricts them to that.
      */
    def invitationsFor(callerExternalId: String): IO[List[ChallengeInvitation]] =
        sessionPool.use { session =>
            for {
                caller <- new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
                    case Some(player) => IO.pure(player)
                    case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
                }
                invitations <- new InvitationRepo(session).listForPlayer(caller.playerId)
            } yield invitations
        }

    def delete(gameId: GameId, challengeId: ChallengeId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            val characterRepo = new CharacterRepo[T](session)
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            session.transaction.use { _ =>
                for {
                    // Locked before anything else, for the same reason accept locks: the delete below must
                    // not race a start of the same challenge. Without the lock a delete could land between
                    // a start's first and last transactions and pull the challenge out from under it,
                    // leaving the match already handed to the engine with no challenge to retire.
                    locked <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
                        case Some(l) => IO.pure(l)
                        case None =>
                            IO.raiseError(
                              NotFoundError("That challenge is no longer there. Whoever offered it has withdrawn it.")
                            )
                    }
                    _ <- locked.startedMatchId.traverse_ { _ =>
                        IO.raiseError(
                          ConflictError("The match has already started, so the challenge can no longer be deleted.")
                        )
                    }
                    challenge <- challengeRepo.read(gameId, challengeId).flatMap {
                        case Some(c) => IO.pure(c)
                        case None =>
                            IO.raiseError(
                              NotFoundError("That challenge is no longer there. Whoever offered it has withdrawn it.")
                            )
                    }
                    _ <- challenge match {
                        case cc: CharacterChallenge =>
                            // Locked: the owner read here is the only thing authorizing the delete below.
                            characterRepo.readWithOwnerAndGameForUpdate(cc.characterId).flatMap {
                                case Some(joined) =>
                                    IO.raiseUnless(callerExternalId == joined.owner.externalId)(
                                      UnauthorizedError(
                                        s"caller '$callerExternalId' may not delete challenge ${challengeId.value}"
                                      )
                                    )
                                case None =>
                                    IO.raiseError(NotFoundError(s"no character with id ${cc.characterId.value}"))
                            }
                        case pc: PlainChallenge =>
                            requirePlayer(playerRepo, pc.challenger).flatMap { challenger =>
                                IO.raiseUnless(callerExternalId == challenger.externalId)(
                                  UnauthorizedError(
                                    s"caller '$callerExternalId' may not delete challenge ${challengeId.value}"
                                  )
                                )
                            }
                    }
                    _ <- acceptanceRepo.deleteAllForChallenge(gameId, challengeId)
                    // Before the challenge, because `invitation` has a foreign key to it. Deleted here
                    // rather than by a cascade so that the rows going is something this code says.
                    _ <- new InvitationRepo(session).deleteAllForChallenge(gameId, challengeId)
                    _ <- challengeRepo.delete(gameId, challengeId)
                } yield ()
            }
        }

    /** Invites `invite.playerId` to an existing challenge, and holds a seat for them if it names a role.
      *
      * Only the challenger may: an invitation is their offer to make. Authorized against the challenge's `challenger`
      * rather than through the character an owner holds, because `create` has already established that those are the
      * same player for a character challenge — and because what is being written here is permission to accept, which is
      * about the challenge and not about anybody's character.
      *
      * A second invitation for a player already invited is a [[ConflictError]] rather than a change to the first: an
      * invitation naming a different role would move a seat somebody may already have accepted into, so changing one
      * means revoking it and inviting again, where both halves are checked.
      */
    def invite(
        gameId: GameId,
        challengeId: ChallengeId,
        invite: Invite,
        callerExternalId: String
    ): IO[Invitation] =
        sessionPool.use { session =>
            val gameRepo = new GameRepo[T](session)
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val invitationRepo = new InvitationRepo(session)
            val invited = session.transaction.use { _ =>
                for {
                    // Locked first, like every other write to a challenge: an invitation added between a
                    // start's two transactions would be permission to accept a challenge that is already a
                    // match.
                    locked <- requireLocked(challengeRepo, gameId, challengeId)
                    _ <- refuseStarted(locked)
                    challenge <- requireChallenge(challengeRepo, gameId, challengeId)
                    _ <- requireChallenger(playerRepo, challenge, callerExternalId, "invite to")
                    game <- requireGame(gameRepo, gameId)
                    accepted <- acceptanceRepo.rolesForChallenge(gameId, challengeId)
                    reserved <- invitationRepo.reservedRoles(gameId, challengeId)
                    _ <- validateInvitations(
                      game,
                      playerRepo,
                      challenge.challenger,
                      Seq(invite),
                      taken = accepted.toSet ++ reserved.map((role, _) => role)
                    )
                    already <- invitationRepo.read(gameId, challengeId, invite.playerId)
                    _ <- IO.raiseWhen(already.isDefined)(
                      ConflictError(
                        s"player ${invite.playerId.value} has already been invited to challenge ${challengeId.value}"
                      )
                    )
                    /* And nobody who is already in it.
                     *
                     * `taken` above refuses an invitation to the *seat* they are sitting in, which is
                     * the case that looks like this one and is not it: an invitation naming no role at
                     * all held nothing, cleared every check, and wrote a row that neither side could
                     * then remove. [[reject]] refuses them, because their invitation is what permits
                     * the seat they are in; [[revoke]] refuses the challenger, for the same reason and
                     * in the same words. So the row outlived every way of getting rid of it short of
                     * the acceptance going first, and a mail went out inviting somebody to a challenge
                     * they had already joined.
                     *
                     * Under the challenge's lock, with the two checks above, so an acceptance landing
                     * between this and the insert cannot leave that state either. */
                    seated <- acceptanceRepo.hasAccepted(gameId, challengeId, invite.playerId)
                    _ <- IO.raiseWhen(seated)(
                      ConflictError(
                        s"player ${invite.playerId.value} has already accepted challenge ${challengeId.value}"
                      )
                    )
                    created <- invitationRepo.create(
                      Invitation(gameId, challengeId, invite.playerId, invite.gameRoleId)
                    )
                    player <- requirePlayer(playerRepo, invite.playerId)
                } yield (created, player, roleName(game, invite))
            }

            /* After the commit, like every other notification here: an invitation that has been made is
             * not undone by a mail that could not be sent. */
            invited.flatMap { (created, player, role) =>
                notifications.invitationMade(session, gameId, challengeId, player, role).as(created)
            }
        }

    /** Turns down an invitation, which deletes it.
      *
      * The challenge survives, and deliberately: its other invitees may still accept, and its challenger may invite
      * somebody else. A challenge whose last invitation is rejected is left for them to delete or re-open — they are
      * the only one who can say which, and removing their challenge on somebody else's decision is not this service's
      * to make.
      *
      * A player who has already accepted is refused rather than obliged. Deleting their invitation would leave them
      * holding the seat they had been invited into with no permission to be there — which is not nothing, since backing
      * out and changing their mind would then be refused — and it would tell the challenger their seat is free to offer
      * again while it is still taken. [[AcceptanceService.delete]] is the way out of a challenge already joined, and
      * the refusal says so.
      */
    def reject(gameId: GameId, challengeId: ChallengeId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val invitationRepo = new InvitationRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val rejected = session.transaction.use { _ =>
                for {
                    locked <- requireLocked(challengeRepo, gameId, challengeId)
                    _ <- refuseStarted(locked)
                    caller <- requireCaller(playerRepo, callerExternalId)
                    // The invitation is what authorizes this, so its absence is the refusal: a player with
                    // no invitation to this challenge has nothing to reject, which is a 404 about the
                    // invitation rather than a 403 about them.
                    _ <- invitationRepo.read(gameId, challengeId, caller.playerId).flatMap {
                        case Some(_) => IO.unit
                        case None =>
                            IO.raiseError(
                              NotFoundError("That invitation is no longer there. It may have been withdrawn.")
                            )
                    }
                    // Under the challenge's lock, like the invitation read above and for the same reason:
                    // an acceptance landing between this check and the delete would leave exactly the
                    // state this refuses.
                    accepted <- acceptanceRepo.hasAccepted(gameId, challengeId, caller.playerId)
                    _ <- IO.raiseWhen(accepted)(
                      ConflictError(
                        "You have already accepted this challenge, so there is no invitation left to turn down. " +
                            "Back out of the challenge instead."
                      )
                    )
                    _ <- invitationRepo.delete(gameId, challengeId, caller.playerId)
                } yield caller
            }

            /* After the commit. The challenger is the only one told, and they are told by `Notifications`
             * reading the challenge for itself -- all this knows is who said no. */
            rejected.flatMap(caller => notifications.invitationRejected(session, gameId, challengeId, caller))
        }

    /** Takes an invitation back. The challenger's mirror of [[reject]], and the only way to correct one that was sent
      * to the wrong player or for the wrong seat.
      *
      * A player who has already accepted is refused, as they are in [[reject]] and for the same reason: their
      * invitation is what permits the seat they are sitting in, and taking it back without taking the seat leaves the
      * two disagreeing. A challenger who wants a player out of their challenge removes the acceptance, which they may —
      * see [[AcceptanceService.delete]]. That also keeps a revoke from being a way to eject a player through a route
      * that reports nothing to them.
      */
    def revoke(
        gameId: GameId,
        challengeId: ChallengeId,
        playerId: PlayerId,
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            val playerRepo = new PlayerRepo(session)
            val challengeRepo = new ChallengeRepo(session)
            val invitationRepo = new InvitationRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            session.transaction.use { _ =>
                for {
                    locked <- requireLocked(challengeRepo, gameId, challengeId)
                    _ <- refuseStarted(locked)
                    challenge <- requireChallenge(challengeRepo, gameId, challengeId)
                    _ <- requireChallenger(playerRepo, challenge, callerExternalId, "revoke an invitation to")
                    _ <- invitationRepo.read(gameId, challengeId, playerId).flatMap {
                        case Some(_) => IO.unit
                        case None =>
                            IO.raiseError(
                              NotFoundError(
                                "That invitation is no longer there. It may already have been turned down."
                              )
                            )
                    }
                    accepted <- acceptanceRepo.hasAccepted(gameId, challengeId, playerId)
                    _ <- IO.raiseWhen(accepted)(
                      ConflictError(
                        "That player has already accepted this challenge. Remove their acceptance instead."
                      )
                    )
                    _ <- invitationRepo.delete(gameId, challengeId, playerId)
                } yield ()
            }
        }

    /* The challenge, locked for the rest of the transaction. Every write to a challenge takes this
     * first, for the reason `accept` and `delete` already give: a start of the same challenge must
     * not land in the middle of one. */
    private def requireLocked(
        challengeRepo: ChallengeRepo,
        gameId: GameId,
        challengeId: ChallengeId
    ): IO[LockedChallenge] =
        challengeRepo.readForUpdate(gameId, challengeId).flatMap {
            case Some(locked) => IO.pure(locked)
            case None =>
                IO.raiseError(NotFoundError("That challenge is no longer there. Whoever offered it has withdrawn it."))
        }

    /* A challenge whose start is in flight is spoken for: its roster has been handed to the engine,
     * and nothing about who may be invited to it means anything any more.
     *
     * What was being attempted is not named: invite, reject and revoke all fail for the one reason, and
     * the reason is the part worth saying. */
    private def refuseStarted(locked: LockedChallenge): IO[Unit] =
        locked.startedMatchId.traverse_ { _ =>
            IO.raiseError(ConflictError("The match has already started. The challenge is closed."))
        }

    private def requireChallenge(
        challengeRepo: ChallengeRepo,
        gameId: GameId,
        challengeId: ChallengeId
    ): IO[Challenge] =
        challengeRepo.read(gameId, challengeId).flatMap {
            case Some(challenge) => IO.pure(challenge)
            case None =>
                IO.raiseError(NotFoundError("That challenge is no longer there. Whoever offered it has withdrawn it."))
        }

    private def requireCaller(playerRepo: PlayerRepo, callerExternalId: String): IO[Player] =
        playerRepo.readByExternalIdForShare(callerExternalId).flatMap {
            case Some(player) => IO.pure(player)
            case None         => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }

    /* Whoever offered the challenge, and nobody else. `action` names what is being refused. */
    private def requireChallenger(
        playerRepo: PlayerRepo,
        challenge: Challenge,
        callerExternalId: String,
        action: String
    ): IO[Player] =
        requireCaller(playerRepo, callerExternalId).flatTap { caller =>
            IO.raiseUnless(caller.playerId == challenge.challenger)(
              UnauthorizedError(
                s"caller '$callerExternalId' may not $action challenge ${challenge.challengeId.value}"
              )
            )
        }

}
