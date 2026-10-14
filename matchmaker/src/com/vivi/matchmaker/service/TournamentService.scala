package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import java.time.Instant
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.{
    Env,
    Standings,
    Tournament as Progress,
    TournamentDesign,
    TournamentGame,
    TournamentState,
    Validation
}
import com.vivi.matchmaker.model.tournament.standard.Challenge
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.persistence._
import com.vivi.matchmaker.util.ChallengeSettings

/** Tournaments, from their creation to their start (`tournament-design.md`, new-design.md Phase 4): designing one,
  * inviting and entering players, and starting it. Playing its rounds is [[TournamentRounds]]'.
  *
  * A challenge is a tournament too, of the challenge model ([[Challenge]]): one match of everyone who enters. Its
  * screens make one with [[createChallenge]], which opens and enters it in one go, and list them with
  * [[challengesForGame]] and [[challengesFor]]; everything after that is the same calls as any tournament's.
  *
  * What happens to entries and invitations is mailed, after the commit, by [[Notifications]].
  *
  * Every call that changes a tournament is one transaction, and reads the tournament `FOR UPDATE` first: who owns it,
  * what state it is in and what entries it has all decide what the call may do. The game it is played in is read
  * plainly, as `game` is everywhere a write lands elsewhere: its catalogue changes when an admin edits it, rarely and
  * never in step with a tournament's entries (CLAUDE.md, "a reference table read on the way to a write elsewhere").
  */
class TournamentService[T](
    sessionPool: SessionPool,
    notifications: Notifications = Notifications.disabled,
    now: () => Instant = () => Instant.now(),
    /** What a tournament with automatic rounds does as it starts: begin its first. */
    started: (GameId, TournamentId) => IO[Unit] = (_, _) => IO.unit
)(using codec: TextCodec[T]) {

    // ---- reading ---------------------------------------------------------------------------

    /** A game's tournaments, as the caller sees them in a list. */
    def listForGame(gameId: GameId, callerExternalId: String): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).summariesForGame(gameId, p.playerId)
            )
        }

    /** Where the caller stands in every tournament they have entered, those under way first: their seed and stratum as
      * they stand, and their final place once one is over. A ladder that runs for ever is one of these, always under
      * way.
      */
    def standings(callerExternalId: String): IO[List[TournamentStanding]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).standingsForPlayer(p.playerId)
            )
        }

    /** The tournaments the caller owns, has entered, or is invited to. */
    def mine(callerExternalId: String): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).summariesForPlayer(p.playerId)
            )
        }

    /** A tournament's page: the tournament, its entries and invitations, its state, and the round being played. Any
      * registered player may look.
      */
    def detail(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[TournamentDetail] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                summary <- new TournamentRepo(session).summary(gameId, tournamentId, caller.playerId).flatMap {
                    case Some(s) => IO.pure(s)
                    case None    => IO.raiseError(NotFoundError(s"no tournament $tournamentId in game $gameId"))
                }
                design <- new StageRepo(session).design(gameId, tournamentId).map(_.getOrElse(TournamentDesign(Nil)))
                entries <- new EntrantRepo(session).list(gameId, tournamentId)
                invited <- new InvitationRepo(session).list(gameId, tournamentId)
                invitedCharacters <- new CharacterInvitationRepo(session).list(gameId, tournamentId)
                // Only a tournament that has started has any progress to read.
                state <-
                    if (summary.tournament.startedAt.isEmpty) IO.pure(None)
                    else new TournamentRepo(session).readState(gameId, tournamentId)
                round <- state.filter(_.round > 0) match {
                    case Some(s) => new TournamentMatchRepo(session).listRound(gameId, tournamentId, s.round)
                    case None    => IO.pure(Nil)
                }
                game <- new GameRepo[T](session).read(gameId)
            } yield TournamentDetail(
              summary,
              design,
              entries,
              invited.map((id, name) => PlayerName(id, name)),
              // Pools still being played shown as their finished matches stand.
              state.map(s => game.fold(s)(g => Standings.provisional(s, design, TournamentGame.of(g)))),
              round.map { m =>
                  val by =
                      game.flatMap(g => TournamentRounds.chooseBy(summary.tournament, g, m))
                  val v = TournamentService.view(m)
                  v.copy(seats = v.seats.map(s => if (s.choosing) s.copy(chooseBy = by) else s))
              },
              invitedCharacters
            )
        }

    // ---- challenges ------------------------------------------------------------------------

    /** A game's open challenges as the caller may see them: every one that anybody may enter, and an invitational one
      * only if the caller owns it, has entered it or is invited to it. Newest first.
      */
    def challengesForGame(gameId: GameId, callerExternalId: String): IO[List[ChallengeSummary]] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                all <- new TournamentRepo(session).summariesForGame(gameId, caller.playerId)
                visible = all.filter(s =>
                    TournamentService.isOpenChallenge(s) &&
                        (!s.tournament.settings.invitational || s.isOwner || s.entered || s.invited)
                )
                summaries <- withEntries(session, visible)
            } yield summaries
        }

    /** The open challenges the caller owns, has entered or is invited to, in every game, newest first: what the home
      * page lists as invitations, challenges waiting on the caller to start them, and challenges they are waiting on.
      */
    def challengesFor(callerExternalId: String): IO[List[ChallengeSummary]] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                mine <- new TournamentRepo(session).summariesForPlayer(caller.playerId)
                summaries <- withEntries(session, mine.filter(TournamentService.isOpenChallenge))
            } yield summaries
        }

    /* Each challenge with who has entered it and who is invited: a query or two each, for the handful of open
     * challenges a list holds. */
    private def withEntries(
        session: skunk.Session[IO],
        summaries: List[TournamentSummary]
    ): IO[List[ChallengeSummary]] =
        summaries.traverse { s =>
            val t = s.tournament
            (
              new EntrantRepo(session).list(t.gameId, t.tournamentId).map(_.filterNot(_.withdrawn)),
              new InvitationRepo(session).list(t.gameId, t.tournamentId),
              new CharacterInvitationRepo(session).list(t.gameId, t.tournamentId)
            ).mapN((entries, invited, characters) =>
                ChallengeSummary(s, entries, invited.map((id, name) => PlayerName(id, name)), characters)
            )
        }

    /** A new challenge: a tournament of the challenge model, opened at once, with the caller's entry in it — unless a
      * game's admin is offering a match they will not play in — and its invitations, all in one transaction, because
      * they are one act. A challenge that is not open must invite somebody, or nobody could ever enter it: players in a
      * plain game, characters in a character game, whoever owns them when they answer.
      *
      * Its pool size is how many it seats and the most entries it takes; it starts itself when they are reached if
      * `autoStart`, and is otherwise started by its owner like any tournament. Its rounds are automatic, so starting it
      * begins its one round, and its players then choose their roles.
      *
      * Offering a match one will not play in, or one that is rated, is a game's admin's to do; and one whose owner does
      * not play has to start by itself, since nobody would be watching for it to fill.
      */
    def createChallenge(gameId: GameId, request: ChallengeRequest, callerExternalId: String): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        game <- requireGame(session, gameId)
                        _ <- TournamentModelService.ensure(session, Challenge)
                        model <- requireModel(session, gameId, Challenge.modelId)
                        _ <- IO.raiseWhen(!request.plays && !request.autoStart)(
                          ValidationError(
                            "a challenge whose challenger will not play in it has to start when it is full"
                          )
                        )
                        _ <- IO.whenA(!request.plays || !request.friendly)(requireGameAdmin(session, caller, game))
                        invited = request.invitations.distinct
                        invitedCharacters = request.characterInvitations.distinct
                        _ <- requireInvitationKind(game, invited.size, invitedCharacters.size)
                        _ <- IO.raiseWhen(!request.open && invited.isEmpty && invitedCharacters.isEmpty)(
                          ValidationError(
                            s"a challenge that is not open must invite at least one ${inviteeNoun(game)}"
                          )
                        )
                        _ <- IO.raiseWhen(request.plays && invited.contains(caller.playerId))(
                          ValidationError("a challenger cannot be invited to their own challenge")
                        )
                        invitees <- invited.traverse(id =>
                            new PlayerRepo(session).readForShare(id).flatMap {
                                case Some(p) => IO.pure(p)
                                case None    => IO.raiseError(NotFoundError(s"no player $id"))
                            }
                        )
                        owners <- invitedCharacters.traverse(id => invitableOwner(session, game, id))
                        _ <- IO.raiseWhen(request.plays && owners.exists(_.playerId == caller.playerId))(
                          ValidationError("a challenger cannot invite their own characters to their own challenge")
                        )
                        poolSize = request.poolSize.getOrElse(game.poolSeats)
                        made <- createIn(
                          session,
                          caller,
                          game,
                          model,
                          TournamentRequest(
                            name = s"${caller.nickname}'s challenge",
                            tournamentModelId = model.tournamentModelId,
                            defaultPoolSize = Some(poolSize),
                            settings = TournamentSettings(
                              autoRounds = true,
                              friendly = request.friendly,
                              invitational = !request.open,
                              roundDuration = request.roundDuration,
                              isPublic = request.isPublic,
                              gameSettings = request.gameSettings,
                              live = request.live,
                              start = request.start,
                              autoStart = request.autoStart
                            )
                          ),
                          TournamentStatus.Open
                        )
                        _ <- invitees.traverse_(p =>
                            new InvitationRepo(session).invite(gameId, made.tournamentId, p.playerId)
                        )
                        _ <- invitedCharacters.traverse_(c =>
                            new CharacterInvitationRepo(session).invite(gameId, made.tournamentId, c)
                        )
                        entered <-
                            if (request.plays) enterIn(session, made, game, caller, request.characterId).map(_._2)
                            else IO.pure(None)
                    } yield (entered.getOrElse(made), invitees ++ owners)
                }
                .flatMap { (made, invitees) =>
                    invitees.traverse_(notifications.invitationMade(session, made, _)) *>
                        IO.whenA(made.status == TournamentStatus.Running)(started(made.gameId, made.tournamentId))
                            .as(made)
                }
        }

    // ---- designing -------------------------------------------------------------------------

    /** A new tournament, in draft, owned by the caller: made from one of the game's models, whose stages are copied
      * into it, with its root pool.
      */
    def create(gameId: GameId, request: TournamentRequest, callerExternalId: String): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    game <- requireGame(session, gameId)
                    model <- requireModel(session, gameId, request.tournamentModelId)
                    made <- createIn(session, caller, game, model, request, TournamentStatus.Draft)
                } yield made
            }
        }

    /* A tournament written, with its stages and root pool, under the caller's transaction. */
    private def createIn(
        session: skunk.Session[IO],
        caller: Player,
        game: Game,
        model: TournamentModel,
        request: TournamentRequest,
        status: TournamentStatus
    ): IO[Tournament] = {
        val poolSize = defaultPoolSize(game, model, request)
        val settings = TournamentService.settingsFor(model, poolSize, request.settings)
        for {
            _ <- checkSettings(game, model, request, settings, poolSize)
            made <- new TournamentRepo(session).create(
              Tournament(
                game.gameId,
                TournamentId.unassigned,
                request.name.trim,
                request.description.trim,
                caller.playerId,
                model.tournamentModelId,
                poolSize,
                settings,
                status
              )
            )
            _ <- new StageRepo(session).copy(game.gameId, made.tournamentId, model.design)
            _ <- new PoolRepo(session).createRoot(game.gameId, made.tournamentId)
        } yield made
    }

    /** The owner's edit of a tournament. Before it starts, anything; once it is running, its name and description, its
      * owner, and the settings that apply to rounds still to come. Never once it is over.
      */
    def update(
        gameId: GameId,
        tournamentId: TournamentId,
        request: TournamentRequest,
        callerExternalId: String
    ): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireOwned(session, gameId, tournamentId, caller)
                    game <- requireGame(session, gameId)
                    owner <- request.owner
                        .filterNot(_ == t.owner)
                        .traverse(id =>
                            new PlayerRepo(session).readForShare(id).flatMap {
                                case Some(p) => IO.pure(p.playerId)
                                case None    => IO.raiseError(NotFoundError(s"no player $id"))
                            }
                        )
                    updated <- t.status match {
                        case TournamentStatus.Draft | TournamentStatus.Open =>
                            for {
                                model <- requireModel(
                                  session,
                                  gameId,
                                  request.tournamentModelId,
                                  kept = Some(t.tournamentModelId)
                                )
                                poolSize = defaultPoolSize(game, model, request)
                                settings = TournamentService.settingsFor(model, poolSize, request.settings)
                                _ <- checkSettings(game, model, request, settings, poolSize)
                                entries <- new EntrantRepo(session).list(gameId, tournamentId)
                                _ <- IO.raiseWhen(settings.maxEntrants.exists(_ < entries.count(!_.withdrawn)))(
                                  ValidationError("it already has more entries than that")
                                )
                                // Made from another model: its stages are the new model's from now on.
                                _ <- IO.whenA(model.tournamentModelId != t.tournamentModelId)(
                                  new StageRepo(session).removeAll(gameId, tournamentId) *>
                                      new StageRepo(session).copy(gameId, tournamentId, model.design)
                                )
                            } yield t.copy(
                              name = request.name.trim,
                              description = request.description.trim,
                              tournamentModelId = model.tournamentModelId,
                              defaultPoolSize = poolSize,
                              settings = settings
                            )
                        case TournamentStatus.Running =>
                            val s = request.settings
                            for {
                                _ <- IO.raiseWhen(request.name.trim.isEmpty)(
                                  ValidationError("a tournament needs a name")
                                )
                                _ <- IO.raiseWhen(s.roundDuration.exists(d => d.isZero || d.isNegative))(
                                  ValidationError("a round duration must be positive")
                                )
                                roundDuration = s.roundDuration.orElse(t.settings.roundDuration)
                                _ <- IO.raiseWhen(t.settings.live && !roundDuration.exists(_.getSeconds >= 1))(
                                  ValidationError("a live tournament needs a round duration of at least a second")
                                )
                            } yield t.copy(
                              name = request.name.trim,
                              description = request.description.trim,
                              settings = t.settings.copy(
                                autoRounds = s.autoRounds,
                                lateEntries = s.lateEntries,
                                roundDuration = roundDuration
                              )
                            )
                        case _ => IO.raiseError(ConflictError("a tournament that is over cannot be changed"))
                    }
                    withOwner = updated.copy(owner = owner.getOrElse(updated.owner))
                    _ <- new TournamentRepo(session).update(withOwner)
                } yield withOwner
            }
        }

    /** Opens a draft tournament for entries. */
    def open(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Tournament] =
        transition(gameId, tournamentId, callerExternalId) { t =>
            if (t.status == TournamentStatus.Draft) IO.pure(t.copy(status = TournamentStatus.Open))
            else
                IO.raiseError(
                  ConflictError(s"only a draft can be opened; this tournament is ${t.status.label.toLowerCase}")
                )
        }

    /** Calls a tournament off. Its matches already being played are left to finish as matches. */
    def cancel(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Tournament] =
        transition(gameId, tournamentId, callerExternalId) { t =>
            t.status match {
                case TournamentStatus.Finished | TournamentStatus.Cancelled =>
                    IO.raiseError(ConflictError("this tournament is already over"))
                case _ => IO.pure(t.copy(status = TournamentStatus.Cancelled, endedAt = Some(now())))
            }
        }

    // ---- invitations -----------------------------------------------------------------------

    /** The owner inviting a player to an invitational tournament of a plain game. Inviting somebody already invited
      * changes nothing, and is not mailed a second time. A character game invites characters: see [[inviteCharacter]].
      */
    def invite(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val invitations = new InvitationRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireOwned(session, gameId, tournamentId, caller)
                        _ <- IO.raiseWhen(
                          t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled
                        )(
                          ConflictError("this tournament is over")
                        )
                        game <- requireGame(session, gameId)
                        _ <- requireInvitationKind(game, players = 1, characters = 0)
                        invitee <- new PlayerRepo(session).readForShare(playerId).flatMap {
                            case Some(p) => IO.pure(p)
                            case None    => IO.raiseError(NotFoundError(s"no player $playerId"))
                        }
                        already <- invitations.isInvited(gameId, tournamentId, playerId)
                        _ <- invitations.invite(gameId, tournamentId, playerId)
                    } yield Option.unless(already)((t, invitee))
                }
                .flatMap(_.traverse_((t, invitee) => notifications.invitationMade(session, t, invitee)))
        }

    /** Takes an invitation back: the owner's revoking it, or the invited player's turning it down — which is mailed to
      * the owner, since nothing else would tell them.
      */
    def revoke(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val invitations = new InvitationRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId || caller.playerId == playerId)(
                          UnauthorizedError(
                            "only the tournament's owner, or the player invited, may remove an invitation"
                          )
                        )
                        was <- invitations.isInvited(gameId, tournamentId, playerId)
                        _ <- invitations.revoke(gameId, tournamentId, playerId)
                    } yield Option.when(was && caller.playerId == playerId && t.owner != caller.playerId)((t, caller))
                }
                .flatMap(_.traverse_((t, declined) => notifications.invitationRejected(session, t, declined)))
        }

    /** The owner inviting a character to an invitational tournament of a character game, where it is the character that
      * plays. Whoever owns it when they answer may enter with it, so the invitation follows the character if it is
      * handed on; it is mailed to its owner now. Inviting a character already invited changes nothing, and is not
      * mailed a second time.
      */
    def inviteCharacter(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: CharacterId,
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val invitations = new CharacterInvitationRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireOwned(session, gameId, tournamentId, caller)
                        _ <- IO.raiseWhen(
                          t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled
                        )(
                          ConflictError("this tournament is over")
                        )
                        game <- requireGame(session, gameId)
                        _ <- requireInvitationKind(game, players = 0, characters = 1)
                        owner <- invitableOwner(session, game, characterId)
                        already <- invitations.isInvited(gameId, tournamentId, characterId)
                        _ <- invitations.invite(gameId, tournamentId, characterId)
                    } yield Option.unless(already)((t, owner))
                }
                .flatMap(_.traverse_((t, owner) => notifications.invitationMade(session, t, owner)))
        }

    /** Takes a character's invitation back: the owner of the tournament revoking it, or the character's owner now
      * turning it down — which is mailed to the tournament's owner, since nothing else would tell them.
      */
    def revokeCharacter(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: CharacterId,
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val invitations = new CharacterInvitationRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        // Held, so that its owner -- who may answer for it -- cannot change before the delete.
                        character <- new CharacterRepo[T](session).readForShare(characterId)
                        characterOwner = character.filter(_.gameId == gameId).flatMap(_.playerId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId || characterOwner.contains(caller.playerId))(
                          UnauthorizedError(
                            "only the tournament's owner, or the owner of the character invited, may remove an invitation"
                          )
                        )
                        was <- invitations.isInvited(gameId, tournamentId, characterId)
                        _ <- invitations.revoke(gameId, tournamentId, characterId)
                    } yield Option.when(was && characterOwner.contains(caller.playerId) && t.owner != caller.playerId)(
                      (t, caller)
                    )
                }
                .flatMap(_.traverse_((t, declined) => notifications.invitationRejected(session, t, declined)))
        }

    // ---- entries ---------------------------------------------------------------------------

    /** The caller entering a tournament — with a character of theirs, in a character game — or coming back to one they
      * withdrew from.
      *
      * The tournament must be open, or running with late entries allowed; an invitational one must have invited them --
      * in a character game, the character they enter with (its owner needs no invitation); their rating must be within
      * its limits; they may not hold more entries than it allows; and it must not be full. A late entry joins the
      * tournament's stratum 0 straight away, to play from its next stage run. The entry that fills a tournament that
      * starts itself starts it.
      */
    def enter(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: Option[CharacterId],
        callerExternalId: String
    ): IO[Entrant] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        game <- requireGame(session, gameId)
                        invited <- isInvitedAs(session, game, tournamentId, caller, characterId)
                        entered <- enterIn(session, t, game, caller, characterId)
                        (entrant, startedNow) = entered
                    } yield (t, caller, entrant, invited && t.owner != caller.playerId, startedNow)
                }
                .flatMap { (t, caller, entrant, wasInvited, startedNow) =>
                    notifications.entered(session, t, caller, wasInvited) *>
                        startedNow.traverse_(running =>
                            IO.whenA(running.settings.autoRounds)(started(running.gameId, running.tournamentId))
                        ) *>
                        IO.pure(entrant)
                }
        }

    /* An entry, under the tournament's lock, which the caller holds: the entrant, and the tournament as this entry
     * started it if it did. */
    private def enterIn(
        session: skunk.Session[IO],
        t: Tournament,
        game: Game,
        caller: Player,
        characterId: Option[CharacterId]
    ): IO[(Entrant, Option[Tournament])] = {
        val entrants = new EntrantRepo(session)
        val tournamentId = t.tournamentId
        val gameId = t.gameId
        val late = t.status == TournamentStatus.Running
        for {
            _ <- IO.raiseUnless(t.status == TournamentStatus.Open || (late && t.settings.lateEntries))(
              ConflictError(
                if (late) "this tournament has started, and takes no late entries"
                else s"this tournament is not open for entries; it is ${t.status.label.toLowerCase}"
              )
            )
            _ <- checkCharacter(session, game, characterId, caller)
            invited <- isInvitedAs(session, game, tournamentId, caller, characterId)
            _ <- IO.raiseUnless(!t.settings.invitational || invited || t.owner == caller.playerId)(
              UnauthorizedError(
                if (game.gameType == GameType.Character)
                    "this tournament is by invitation, and that character has not been invited"
                else "this tournament is by invitation, and you have not been invited"
              )
            )
            existing <- entrants.list(gameId, tournamentId)
            active = existing.count(!_.withdrawn)
            _ <- IO.raiseWhen(t.settings.maxEntrants.exists(active >= _))(
              ConflictError(s"this ${TournamentService.noun(t)} is full")
            )
            // The entries the caller plays now, which in a character game are their characters' as they stand.
            mine = existing.filter(_.playerId.contains(caller.playerId))
            // Coming back: the withdrawn entry with this character, or with none in a plain game.
            returning = mine.find(e => e.withdrawn && e.characterId == characterId)
            entrant <- returning match {
                case Some(e) =>
                    for {
                        _ <- entrants.setWithdrawnAt(gameId, e.entrantId, None)
                        _ <- IO.whenA(late)(
                          updateState(session, t, game)(s => Progress.reenter(s, e.entrantId.value))
                        )
                    } yield e.copy(withdrawnAt = None)
                case None =>
                    for {
                        _ <- IO.raiseWhen(mine.count(!_.withdrawn) >= t.settings.entriesPerPlayer)(
                          ConflictError(
                            if (t.settings.entriesPerPlayer == 1)
                                s"you have already entered this ${TournamentService.noun(t)}"
                            else s"you already have the ${t.settings.entriesPerPlayer} entries this tournament allows"
                          )
                        )
                        _ <- IO.raiseWhen(characterId.exists(c => existing.exists(_.characterId.contains(c))))(
                          ConflictError(s"that character has already entered this ${TournamentService.noun(t)}")
                        )
                        rating <- ratingOf(session, gameId, caller.playerId)
                        _ <- IO.raiseWhen(
                          t.settings.minRating.exists(rating < _) || t.settings.maxRating.exists(rating > _)
                        )(
                          ValidationError(
                            s"your rating of $rating is outside this tournament's limits" +
                                t.settings.minRating.fold("")(m => s", at least $m") +
                                t.settings.maxRating.fold("")(m => s", at most $m")
                          )
                        )
                        made <- entrants.create(
                          gameId,
                          game.gameType,
                          tournamentId,
                          caller.playerId,
                          characterId,
                          rating
                        )
                        // Seeded against everyone's rating now, the newcomer's among them.
                        ratings <-
                            (if (late) currentRatings(session, gameId, existing.filterNot(_.withdrawn))
                             else IO.pure(Map.empty[Long, Int]))
                        _ <- IO.whenA(late)(requireDesign(session, t).flatMap { design =>
                            updateState(session, t, game) { s =>
                                Progress.lateEntry(
                                  s,
                                  made.entrantId.value,
                                  env(t, design, game, ratings + (made.entrantId.value -> rating))
                                )
                            }
                        })
                    } yield made.copy(nickname = caller.nickname)
            }
            // Full now, and offered as starting itself: this entry is what starts it.
            full = t.settings.maxEntrants.contains(active + 1)
            startedNow <-
                if (t.status == TournamentStatus.Open && t.settings.autoStart && full)
                    startIn(session, t, game).map(Some(_))
                else IO.pure(None)
        } yield (entrant, startedNow)
    }

    /** Withdraws an entry: its player's to do -- in a character game, its character's owner's -- or the owner's. Before
      * the tournament starts the entry is simply removed; once it is running, the entrant is put in no pool from then
      * on, and a match they are already seated in is theirs to forfeit.
      */
    def withdraw(gameId: GameId, tournamentId: TournamentId, entrantId: EntrantId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val entrants = new EntrantRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournamentForUpdate(session, gameId, tournamentId)
                        found <- entrants.readForUpdate(gameId, entrantId).flatMap {
                            case Some((tid, player, withdrawnAt)) if tid == tournamentId =>
                                IO.pure((player, withdrawnAt))
                            case _ => IO.raiseError(NotFoundError(s"no entry $entrantId in tournament $tournamentId"))
                        }
                        (player, withdrawnAt) = found
                        // Whoever plays the entry now -- its player, or its character's owner -- or the owner.
                        _ <- IO.raiseUnless(player.contains(caller.playerId) || t.owner == caller.playerId)(
                          UnauthorizedError(
                            "only the player of an entry, or the tournament's owner, may withdraw it"
                          )
                        )
                        _ <- t.status match {
                            case TournamentStatus.Draft | TournamentStatus.Open => entrants.delete(gameId, entrantId)
                            case TournamentStatus.Running if withdrawnAt.isEmpty =>
                                requireGame(session, gameId).flatMap { game =>
                                    entrants.setWithdrawnAt(gameId, entrantId, Some(now())) *>
                                        updateState(session, t, game)(Progress.withdraw(_, entrantId.value))
                                }
                            case TournamentStatus.Running =>
                                IO.raiseError(ConflictError("that entry is already withdrawn"))
                            case _ => IO.raiseError(ConflictError("this tournament is over"))
                        }
                        // Carried out of the transaction because the entry that knew them may be gone.
                        entrant <- player.flatTraverse(new PlayerRepo(session).read)
                    } yield (t, entrant, caller)
                }
                .flatMap { (t, entrant, caller) =>
                    entrant.traverse_(notifications.withdrawn(session, t, _, caller))
                }
        }

    // ---- starting --------------------------------------------------------------------------

    /** Starts a tournament: seeds its entrants by their ratings now, strongest first, and forms its first stage's
      * pools. Its first round is begun separately — by the owner, or at once for automatic rounds.
      */
    def start(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[Tournament] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireOwned(session, gameId, tournamentId, caller)
                        game <- requireGame(session, gameId)
                        running <- startIn(session, t, game)
                    } yield running
                }
            }
            .flatTap(t => IO.whenA(t.settings.autoRounds)(started(t.gameId, t.tournamentId)))

    /* A start, under the tournament's lock, which the caller holds: by its owner, or by the entry that filled one that
     * starts itself. */
    private def startIn(session: skunk.Session[IO], t: Tournament, game: Game): IO[Tournament] =
        for {
            _ <- IO.raiseUnless(t.status == TournamentStatus.Draft || t.status == TournamentStatus.Open)(
              ConflictError(s"this tournament cannot be started; it is ${t.status.label.toLowerCase}")
            )
            entries <- new EntrantRepo(session).list(t.gameId, t.tournamentId).map(_.filterNot(_.withdrawn))
            least = math.max(2, game.requiredSeats)
            _ <- IO.raiseWhen(entries.sizeIs < least)(
              ConflictError(s"a ${TournamentService.noun(t)} needs at least $least entries to start")
            )
            design <- requireDesign(session, t)
            problems = Validation
                .validate(design, TournamentGame.of(game), t.settings.maxTurns, Some(t.defaultPoolSize))
                .filterNot(_.warning)
            _ <- IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.map(_.describe).mkString("; ")))
            ratings <- currentRatings(session, t.gameId, entries)
            state = Progress.start(
              entries.map(_.entrantId.value),
              env(t, design, game, ratings),
              TournamentStateRepo.NewPoolIds
            )
            running = t.copy(status = TournamentStatus.Running, startedAt = Some(now()))
            _ <- new TournamentRepo(session).update(running)
            _ <- new TournamentRepo(session).writeState(t.gameId, t.tournamentId, state)
        } yield running

    // ---- shared ----------------------------------------------------------------------------

    private def transition(gameId: GameId, tournamentId: TournamentId, callerExternalId: String)(
        change: Tournament => IO[Tournament]
    ): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireOwned(session, gameId, tournamentId, caller)
                    changed <- change(t)
                    _ <- new TournamentRepo(session).update(changed)
                } yield changed
            }
        }

    /** Rewrites a running tournament's state, read under the tournament's lock, which the caller already holds. */
    private def updateState(session: skunk.Session[IO], t: Tournament, game: Game)(
        change: TournamentState => TournamentState
    ): IO[Unit] =
        new TournamentRepo(session).readState(t.gameId, t.tournamentId).flatMap {
            case Some(s) => new TournamentRepo(session).writeState(t.gameId, t.tournamentId, change(s))
            case None    => IO.unit
        }

    private def env(t: Tournament, design: TournamentDesign, game: Game, ratings: Map[Long, Int]): Env =
        Env(
          design,
          TournamentGame.of(game).copy(chooseRoles = t.settings.chooseRoles),
          ratings,
          Some(t.defaultPoolSize),
          t.settings.concurrentRounds
        )

    /* The model a tournament is made from: one the game offers, with a classification and active -- or, if it is the
     * one the tournament being edited was `kept` from all along, whatever has become of it since. The challenge model
     * is every game's, offered or not: a game's challenges are made from it. Held FOR SHARE, the
     * middle course (CLAUDE.md): a sync removing the model looks at the tournaments made from it, and deletes it if
     * nobody has entered one, so the row is held against that until this tournament is written; two tournaments being
     * made from it do not wait on each other. The game's choice of it is read plainly, as the game is. */
    private def requireModel(
        session: skunk.Session[IO],
        gameId: GameId,
        id: TournamentModelId,
        kept: Option[TournamentModelId] = None
    ): IO[TournamentModel] = {
        val repo = new TournamentModelRepo(session)
        for {
            _ <- repo.lockForShare(id)
            model <- repo.read(id)
            offered <- repo.offeredIn(gameId, id)
            found <- model match {
                case Some(m) if kept.contains(id)                                 => IO.pure(m)
                case Some(m) if id == Challenge.modelId && m.active               => IO.pure(m)
                case Some(m) if m.classification.isDefined && m.active && offered => IO.pure(m)
                case _ => IO.raiseError(NotFoundError(s"game $gameId offers no tournament model $id"))
            }
        } yield found
    }

    /** A tournament's design, from its stages. */
    private def requireDesign(session: skunk.Session[IO], t: Tournament): IO[TournamentDesign] =
        new StageRepo(session).design(t.gameId, t.tournamentId).flatMap {
            case Some(d) => IO.pure(d)
            case None    => IO.raiseError(NotFoundError(s"no tournament ${t.tournamentId} in game ${t.gameId}"))
        }

    /** The default pool size a request asks for: its own, else the model's suggestion, else the game's R. */
    private def defaultPoolSize(game: Game, model: TournamentModel, request: TournamentRequest): Int =
        request.defaultPoolSize.orElse(model.poolSize).getOrElse(game.poolSeats)

    /* Everything wrong with a tournament's settings, as one refusal. `settings` are the request's as the model makes
     * them (`TournamentService.settingsFor`). */
    private def checkSettings(
        game: Game,
        model: TournamentModel,
        request: TournamentRequest,
        settings: TournamentSettings,
        poolSize: Int
    ): IO[Unit] = {
        val s = settings
        val challenge = model.tournamentModelId == Challenge.modelId
        val least = math.max(2, game.requiredSeats)
        val problems =
            List(
              Option.when(request.name.trim.isEmpty)("a tournament needs a name"),
              Option.when(poolSize < 1)("a pool must hold at least one participant"),
              Option.when(s.roundDuration.exists(d => d.isZero || d.isNegative))("a round duration must be positive"),
              Option.when(s.entriesPerPlayer < 1)("a player must be allowed at least one entry"),
              Option.when(s.minRating.zip(s.maxRating).exists((lo, hi) => lo > hi))(
                "the minimum rating cannot be above the maximum"
              ),
              Option.when(s.maxTurns.exists(_ < 1))("the maximum number of turns must be at least 1"),
              Option.when(s.maxEntrants.exists(_ < 1))("it must take at least one entry"),
              Option.when(s.autoStart && s.maxEntrants.isEmpty)(
                "only a tournament that takes a limited number of entries can start itself when it is full"
              ),
              // The engine plays a live match against the clock it is given, which is the round's.
              Option.when(s.live && !s.roundDuration.exists(_.getSeconds >= 1))(
                "a live tournament needs a round duration of at least a second: it is the clock the game engine " +
                    "plays each match against"
              ),
              // Held to the values the game allows here, where the owner can be told, rather than found out when a
              // match starts and the engine is sent something it cannot play.
              ChallengeSettings.problem(s.gameSettings, parameterValues(game)),
              // A challenge is one match of everyone in it: no more of them than the game has roles, and enough to
              // fill the ones it requires.
              Option.when(challenge && (poolSize < least || poolSize > game.roles.size))(
                if (game.roles.sizeIs < least) s"${game.displayName} cannot be played as a challenge"
                else s"a challenge seats between $least and ${game.roles.size} players"
              )
            ).flatten ++
                Validation
                    .validate(model.design, TournamentGame.of(game), s.maxTurns, Some(poolSize))
                    .filterNot(_.warning)
                    .map(_.describe)
        IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.mkString("; ")))
    }

    /* Whether an entry by `caller` -- with `characterId`, in a character game -- answers an invitation: theirs in a plain
     * game, the character's in a character game. */
    private def isInvitedAs(
        session: skunk.Session[IO],
        game: Game,
        tournamentId: TournamentId,
        caller: Player,
        characterId: Option[CharacterId]
    ): IO[Boolean] =
        game.gameType match {
            case GameType.Plain => new InvitationRepo(session).isInvited(game.gameId, tournamentId, caller.playerId)
            case GameType.Character =>
                characterId.fold(IO.pure(false))(
                  new CharacterInvitationRepo(session).isInvited(game.gameId, tournamentId, _)
                )
        }

    /* Which kind of invitation a game takes: a plain game invites players and a character game invites characters, and
     * a request carrying the other kind is refused rather than half-honoured. */
    private def requireInvitationKind(game: Game, players: Int, characters: Int): IO[Unit] =
        game.gameType match {
            case GameType.Character =>
                IO.raiseWhen(players > 0)(
                  ValidationError(s"${game.displayName} is played by characters: invite a character, not a player")
                )
            case GameType.Plain =>
                IO.raiseWhen(characters > 0)(
                  ValidationError(s"${game.displayName} has no characters to invite: invite a player")
                )
        }

    private def inviteeNoun(game: Game): String = if (game.gameType == GameType.Character) "character" else "player"

    /* The owner a character to be invited has now: a character of this game, held FOR SHARE so that it cannot go
     * before the invitation naming it is written, and owned by somebody, since nobody could answer for it otherwise. */
    private def invitableOwner(session: skunk.Session[IO], game: Game, characterId: CharacterId): IO[Player] =
        for {
            character <- new CharacterRepo[T](session).readForShare(characterId).flatMap {
                case Some(c) if c.gameId == game.gameId => IO.pure(c)
                case _ => IO.raiseError(NotFoundError(s"${game.displayName} has no character $characterId"))
            }
            ownerId <- IO.fromOption(character.playerId)(
              ValidationError(s"${character.name} has nobody to answer an invitation for it")
            )
            owner <- new PlayerRepo(session).readForShare(ownerId).flatMap {
                case Some(p) => IO.pure(p)
                case None    => IO.raiseError(NotFoundError(s"no player $ownerId"))
            }
        } yield owner

    /* Each of the game's parameters by name, with the values a tournament may choose for it. */
    private def parameterValues(game: Game): Map[String, Seq[String]] =
        game.parameters.map(p => p.name -> p.values.map(v => codec.encode(v.value.asInstanceOf[T]))).toMap

    /* That `player` administers `game`: an overall admin, or one of the game's own. Their admin row is held FOR SHARE,
     * so that losing it waits for what it is permitting to be written. */
    private def requireGameAdmin(session: skunk.Session[IO], player: Player, game: Game): IO[Unit] =
        for {
            allowed <-
                if (player.isAdmin) IO.pure(true)
                else new GameAdminRepo(session).isAdminForShare(player.playerId, game.gameId)
            _ <- IO.raiseUnless(allowed)(
              UnauthorizedError(
                s"only an admin of ${game.displayName} may offer a match they will not play in, or a rated one"
              )
            )
        } yield ()

    /** A character game's entry names a character of the caller's; a plain game's names none. */
    private def checkCharacter(
        session: skunk.Session[IO],
        game: Game,
        characterId: Option[CharacterId],
        caller: Player
    ): IO[Unit] =
        (game.gameType, characterId) match {
            case (GameType.Plain, None) => IO.unit
            case (GameType.Plain, Some(_)) =>
                IO.raiseError(ValidationError("this game has no characters to enter with"))
            case (GameType.Character, None) =>
                IO.raiseError(ValidationError("enter this game's tournament with one of your characters"))
            case (GameType.Character, Some(c)) =>
                new CharacterRepo[T](session).readForShare(c).flatMap {
                    case Some(ch) if ch.gameId == game.gameId && ch.playerId.contains(caller.playerId) => IO.unit
                    case _ => IO.raiseError(ValidationError("that is not one of your characters in this game"))
                }
        }

    /** A player's overall rating in the game, or the starting rating if they have none. */
    private def ratingOf(session: skunk.Session[IO], gameId: GameId, player: PlayerId): IO[Int] =
        new EloRatingRepo(session).read(gameId, player).map(_.fold(EloRating.initial)(_.rating))

    /** Each entrant's rating now — a character entry's owner's — by entrant id, for seeding. */
    private def currentRatings(session: skunk.Session[IO], gameId: GameId, entries: List[Entrant]): IO[Map[Long, Int]] =
        new EntrantRepo(session)
            .players(gameId, entries.headOption.map(_.tournamentId).getOrElse(TournamentId.unassigned))
            .flatMap { players =>
                entries
                    .traverse { e =>
                        players
                            .get(e.entrantId)
                            .flatten
                            .fold(IO.pure(e.ratingAtEntry))(ratingOf(session, gameId, _))
                            .map(e.entrantId.value -> _)
                    }
                    .map(_.toMap)
            }

    private def requireCaller(session: skunk.Session[IO], externalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(externalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no player for caller '$externalId'"))
        }

    // Read plainly: see the class's note on the game.
    private def requireGame(session: skunk.Session[IO], gameId: GameId): IO[Game] =
        new GameRepo[T](session).read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game $gameId"))
        }

    private def requireTournamentForUpdate(
        session: skunk.Session[IO],
        gameId: GameId,
        id: TournamentId
    ): IO[Tournament] =
        new TournamentRepo(session).readForUpdate(gameId, id).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no tournament $id in game $gameId"))
        }

    private def requireOwned(
        session: skunk.Session[IO],
        gameId: GameId,
        id: TournamentId,
        caller: Player
    ): IO[Tournament] =
        requireTournamentForUpdate(session, gameId, id).flatMap { t =>
            if (t.owner == caller.playerId) IO.pure(t)
            else IO.raiseError(UnauthorizedError("only the tournament's owner may do that"))
        }
}

object TournamentService {

    /** A request's settings as its model makes them: a model whose seats choose their roles makes every tournament of
      * it choose them, and a challenge takes as many entries as its pool seats, and no late ones.
      */
    def settingsFor(model: TournamentModel, poolSize: Int, settings: TournamentSettings): TournamentSettings = {
        val chosen = settings.copy(chooseRoles = settings.chooseRoles || model.chooseRoles)
        if (model.tournamentModelId == Challenge.modelId)
            chosen.copy(maxEntrants = Some(poolSize), lateEntries = false, entriesPerPlayer = 1)
        else chosen
    }

    /** Whether a tournament is a challenge still taking entries. */
    def isOpenChallenge(s: TournamentSummary): Boolean =
        s.tournament.tournamentModelId == Challenge.modelId && s.tournament.status == TournamentStatus.Open

    /** What a tournament is called in a refusal: a challenge, or a tournament. */
    def noun(t: Tournament): String = if (t.tournamentModelId == Challenge.modelId) "challenge" else "tournament"

    /** A round's match as the tournament's page shows it. */
    def view(m: TournamentMatch): TournamentMatchView = {
        val next = m.seats.filter(s => s.gameRoleId.isEmpty && !s.forfeit).flatMap(_.chooseOrder).minOption
        TournamentMatchView(
          m.poolId,
          m.matchId,
          m.status,
          m.seats.map(s =>
              TournamentSeatView(
                s.entrantId,
                s.gameRoleId,
                choosing =
                    m.status == TournamentMatchStatus.Choosing && s.gameRoleId.isEmpty && s.chooseOrder == next &&
                        next.isDefined,
                chooseBy = None
              )
          )
        )
    }
}
