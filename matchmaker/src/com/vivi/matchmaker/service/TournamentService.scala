package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
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
import com.vivi.matchmaker.model.tournament.standard.RoundRobin
import com.vivi.matchmaker.notify.Notifications
import com.vivi.matchmaker.persistence._
import com.vivi.matchmaker.util.ChallengeSettings

/** Tournaments, from their creation to their start (`tournament-design.md`, new-design.md Phase 4): designing one,
  * inviting and entering players, and starting it. Playing its rounds is [[TournamentRounds]]'.
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

    /** A game's open competitions as the caller may see them: their own not yet started, then those taking entries that
      * they could enter -- any that anybody may enter, and an invitational one they are invited to or have entered,
      * while they have an entry left in it. Newest first within each.
      */
    def listForGame(gameId: GameId, callerExternalId: String): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).openForGame(gameId, p.playerId)
            )
        }

    /** Where the caller stands in every tournament they have entered, bar the challenges that are over, those under way
      * first: their seed and stratum as they stand, and their final place once one is over. A ladder that runs for ever
      * is one of these, always under way.
      */
    def standings(callerExternalId: String): IO[List[TournamentStanding]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).standingsForPlayer(p.playerId)
            )
        }

    /** The tournaments the caller owns, has entered, or is invited to that are not over. */
    def mine(callerExternalId: String): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).summariesForPlayer(p.playerId)
            )
        }

    /** The tournaments the caller owns that finished or were cancelled within the last `frame`, the most recently ended
      * first: a window of time, as a player's completed matches are.
      */
    def endedOwned(callerExternalId: String, frame: CompletedFrame): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId).flatMap(p =>
                new TournamentRepo(session).endedOwnedSince(p.playerId, now().minus(frame.span))
            )
        }

    /** A tournament's page: the tournament, its entries and invitations, its state, and the round being played. Any
      * registered player may look.
      */
    def detail(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[TournamentDetail] =
        sessionPool.use { session =>
            for {
                caller <- requireCaller(session, callerExternalId)
                summary <- new TournamentRepo(session)
                    .summary(game = gameId, id = tournamentId, viewer = caller.playerId)
                    .flatMap {
                        case Some(s) => IO.pure(s)
                        case None    => IO.raiseError(NotFoundError(ErrorText.tournament.notFound))
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
                    case Some(s) =>
                        new TournamentMatchRepo(session).listRound(
                          game = gameId,
                          tournament = tournamentId,
                          round = s.round
                        )
                    case None => IO.pure(Nil)
                }
                game <- new GameRepo[T](session).read(gameId).flatMap {
                    case Some(g) => IO.pure(g)
                    case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
                }
            } yield TournamentDetail(
              summary = summary,
              gameType = game.gameType,
              design = design,
              entries = entries,
              invited = invited.map((id, name) => PlayerName(id, name)),
              // Pools still being played shown as their finished matches stand.
              state = state.map(s =>
                  Standings.provisional(
                    state = s,
                    design = design,
                    game = TournamentGame.of(game, summary.tournament.settings.playersPerMatch)
                  )
              ),
              round = round.map { m =>
                  val by = TournamentRounds.chooseBy(t = summary.tournament, game = game, tm = m)
                  val v = TournamentService.view(m)
                  v.copy(seats = v.seats.map(s => if (s.isChoosing) s.copy(chooseBy = by) else s))
              },
              invitedCharacters = invitedCharacters
            )
        }

    // ---- designing -------------------------------------------------------------------------

    /** A new tournament, open for entries, owned by the caller: made from one of the game's models, whose stages are
      * copied into it, with its root pool. Only an admin of the game may make a rated one.
      *
      * With `request.entry` the caller enters it as it is made, held to every rule of [[enter]], so that an entry
      * refused makes no tournament either. Nobody is told of that entry: the owner is the only one who would be, and
      * they made it.
      */
    def create(gameId: GameId, request: TournamentRequest, callerExternalId: String): IO[Tournament] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        game <- requireGame(session, gameId)
                        _ <- IO.whenA(!request.settings.isFriendly)(
                          requireGameAdmin(session = session, player = caller, game = game)
                        )
                        model <- requireModel(session = session, gameId = gameId, id = request.tournamentModelId)
                        made <- createIn(
                          session = session,
                          caller = caller,
                          game = game,
                          model = model,
                          request = request
                        )
                        // No lock to take: nobody else can see the row until this transaction commits. Answered as the
                        // entry left it, which is started when it fills one that starts itself.
                        entered <- request.entry.traverse(e =>
                            enterIn(
                              session = session,
                              t = made,
                              game = game,
                              caller = caller,
                              characterId = e.characterId
                            )
                        )
                    } yield entered.flatMap(_._2).getOrElse(made)
                }
            }
            .flatTap(t =>
                IO.whenA(t.status == TournamentStatus.Running && t.settings.isAutoStarted)(
                  started(t.gameId, t.tournamentId)
                )
            )

    /* A tournament written, open for entries, with its stages and root pool, under the caller's transaction. */
    private def createIn(
        session: skunk.Session[IO],
        caller: Player,
        game: Game,
        model: TournamentModel,
        request: TournamentRequest
    ): IO[Tournament] = {
        val poolSize = defaultPoolSize(game = game, model = model, request = request)
        val settings = TournamentService.settingsFor(model, request.settings)
        for {
            _ <- checkSettings(game = game, model = model, request = request, settings = settings, poolSize = poolSize)
            made <- new TournamentRepo(session).create(
              Tournament(
                gameId = game.gameId,
                tournamentId = TournamentId.unassigned,
                name = request.name.trim,
                description = request.description.trim,
                owner = caller.playerId,
                tournamentModelId = model.tournamentModelId,
                defaultPoolSize = poolSize,
                settings = settings,
                status = TournamentStatus.Open
              )
            )
            _ <- new StageRepo(session).copy(game = game.gameId, tournament = made.tournamentId, design = model.design)
            _ <- new PoolRepo(session).createRoot(game.gameId, made.tournamentId)
        } yield made
    }

    /** The owner's edit of a tournament. Before it starts, anything -- but only an admin of the game may make a
      * friendly one rated; once it is running, its name and description, its owner, and the settings that apply to
      * rounds still to come. Never once it is over.
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
                    // The model before the tournament, the order `create` and a model sync take them in: a sync
                    // locks a model and then every tournament made from it, so taking the tournament first would
                    // deadlock against one removing the model this tournament is made from. `requireModel` takes
                    // it again below, which a lock already held makes free.
                    //
                    // And the sync lock, shared, before either: a sync removes its models one after another, and
                    // one removing both the model this tournament is made from and the model it is moving to would
                    // lock the first and its tournaments, then wait on this edit's lock on the second while this
                    // waits on the tournament. Shared, so that edits do not queue for one another.
                    _ <- TournamentModelService.lockSyncShared(session)
                    _ <- new TournamentModelRepo(session).lockForShare(request.tournamentModelId)
                    t <- requireOwned(session = session, gameId = gameId, id = tournamentId, caller = caller)
                    game <- requireGame(session, gameId)
                    owner <- request.owner
                        .filterNot(_ == t.owner)
                        .traverse(id =>
                            new PlayerRepo(session).readForShare(id).flatMap {
                                case Some(p) => IO.pure(p.playerId)
                                case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
                            }
                        )
                    updated <- t.status match {
                        case TournamentStatus.Open =>
                            for {
                                model <- requireModel(
                                  session = session,
                                  gameId = gameId,
                                  id = request.tournamentModelId,
                                  kept = Some(t.tournamentModelId)
                                )
                                poolSize = defaultPoolSize(game = game, model = model, request = request)
                                settings = TournamentService.settingsFor(model, request.settings)
                                _ <- IO.whenA(t.settings.isFriendly && !settings.isFriendly)(
                                  requireGameAdmin(session = session, player = caller, game = game)
                                )
                                _ <- checkSettings(
                                  game = game,
                                  model = model,
                                  request = request,
                                  settings = settings,
                                  poolSize = poolSize
                                )
                                entries <- new EntrantRepo(session).list(gameId, tournamentId)
                                _ <- IO.raiseWhen(settings.maxEntrants.exists(_ < entries.count(!_.isWithdrawn)))(
                                  ValidationError(ErrorText.tournament.moreEntriesThanThat)
                                )
                                // Made from another model: its stages are the new model's from now on.
                                _ <- IO.whenA(model.tournamentModelId != t.tournamentModelId)(
                                  new StageRepo(session).removeAll(gameId, tournamentId) *>
                                      new StageRepo(session)
                                          .copy(game = gameId, tournament = tournamentId, design = model.design)
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
                                  ValidationError(ErrorText.tournament.blankName)
                                )
                                _ <- IO.raiseWhen(s.roundDuration.exists(d => d.isZero || d.isNegative))(
                                  ValidationError(ErrorText.tournament.roundNotPositive)
                                )
                                roundDuration = s.roundDuration.orElse(t.settings.roundDuration)
                                _ <- IO.raiseWhen(t.settings.isLive && !roundDuration.exists(_.getSeconds >= 1))(
                                  ValidationError(ErrorText.tournament.liveRoundTooShort)
                                )
                            } yield t.copy(
                              name = request.name.trim,
                              description = request.description.trim,
                              settings = t.settings.copy(
                                isAutoStarted = s.isAutoStarted,
                                hasLateEntries = s.hasLateEntries,
                                roundDuration = roundDuration
                              )
                            )
                        case _ => IO.raiseError(ConflictError(ErrorText.tournament.overUnchangeable))
                    }
                    withOwner = updated.copy(owner = owner.getOrElse(updated.owner))
                    _ <- new TournamentRepo(session).update(withOwner)
                } yield withOwner
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
                        t <- requireOwned(session = session, gameId = gameId, id = tournamentId, caller = caller)
                        _ <- IO.raiseWhen(
                          t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled
                        )(
                          ConflictError(ErrorText.tournament.over)
                        )
                        game <- requireGame(session, gameId)
                        _ <- requireInvitationKind(game = game, players = 1, characters = 0)
                        invitee <- new PlayerRepo(session).readForShare(playerId).flatMap {
                            case Some(p) => IO.pure(p)
                            case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
                        }
                        already <- invitations.isInvited(game = gameId, tournament = tournamentId, player = playerId)
                        _ <- invitations.invite(game = gameId, tournament = tournamentId, player = playerId)
                    } yield Option.unless(already)((t, invitee))
                }
                .flatMap(
                  _.traverse_((t, invitee) =>
                      notifications.invitationMade(session = session, tournament = t, invited = invitee)
                  )
                )
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
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId || caller.playerId == playerId)(
                          UnauthorizedError(ErrorText.tournament.uninvitePlayerNotAllowed)
                        )
                        was <- invitations.isInvited(game = gameId, tournament = tournamentId, player = playerId)
                        _ <- invitations.revoke(game = gameId, tournament = tournamentId, player = playerId)
                    } yield Option.when(was && caller.playerId == playerId && t.owner != caller.playerId)((t, caller))
                }
                .flatMap(
                  _.traverse_((t, declined) =>
                      notifications.invitationRejected(session = session, tournament = t, rejector = declined)
                  )
                )
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
                        t <- requireOwned(session = session, gameId = gameId, id = tournamentId, caller = caller)
                        _ <- IO.raiseWhen(
                          t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled
                        )(
                          ConflictError(ErrorText.tournament.over)
                        )
                        game <- requireGame(session, gameId)
                        _ <- requireInvitationKind(game = game, players = 0, characters = 1)
                        owner <- invitableOwner(session = session, game = game, characterId = characterId)
                        already <- invitations.isInvited(
                          game = gameId,
                          tournament = tournamentId,
                          character = characterId
                        )
                        _ <- invitations.invite(game = gameId, tournament = tournamentId, character = characterId)
                    } yield Option.unless(already)((t, owner))
                }
                .flatMap(
                  _.traverse_((t, owner) =>
                      notifications.invitationMade(session = session, tournament = t, invited = owner)
                  )
                )
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
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        // Held, so that its owner -- who may answer for it -- cannot change before the delete.
                        character <- new CharacterRepo[T](session).readForShare(characterId)
                        characterOwner = character.filter(_.gameId == gameId).flatMap(_.playerId)
                        _ <- IO.raiseUnless(t.owner == caller.playerId || characterOwner.contains(caller.playerId))(
                          UnauthorizedError(ErrorText.tournament.uninviteCharacterNotAllowed)
                        )
                        was <- invitations.isInvited(game = gameId, tournament = tournamentId, character = characterId)
                        _ <- invitations.revoke(game = gameId, tournament = tournamentId, character = characterId)
                    } yield Option.when(was && characterOwner.contains(caller.playerId) && t.owner != caller.playerId)(
                      (t, caller)
                    )
                }
                .flatMap(
                  _.traverse_((t, declined) =>
                      notifications.invitationRejected(session = session, tournament = t, rejector = declined)
                  )
                )
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
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        game <- requireGame(session, gameId)
                        invited <- isInvitedAs(
                          session = session,
                          game = game,
                          tournamentId = tournamentId,
                          caller = caller,
                          characterId = characterId
                        )
                        entered <- enterIn(
                          session = session,
                          t = t,
                          game = game,
                          caller = caller,
                          characterId = characterId
                        )
                        (entrant, startedNow) = entered
                    } yield (t, caller, entrant, invited && t.owner != caller.playerId, startedNow)
                }
                .flatMap { (t, caller, entrant, wasInvited, startedNow) =>
                    notifications.entered(session = session, tournament = t, actor = caller, wasInvited = wasInvited) *>
                        startedNow.traverse_(running =>
                            IO.whenA(running.settings.isAutoStarted)(started(running.gameId, running.tournamentId))
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
            _ <- IO.raiseUnless(
              t.status == TournamentStatus.Open || (late && t.settings.hasLateEntries && !t.isStopped)
            )(
              ConflictError(
                if (late && t.isStopped) ErrorText.tournament.stoppedNoEntries
                else if (late) ErrorText.tournament.noLateEntries
                else ErrorText.tournament.notOpen(t.status.label.toLowerCase)
              )
            )
            _ <- checkCharacter(session = session, game = game, characterId = characterId, caller = caller)
            invited <- isInvitedAs(
              session = session,
              game = game,
              tournamentId = tournamentId,
              caller = caller,
              characterId = characterId
            )
            _ <- IO.raiseUnless(!t.settings.isInvitational || invited || t.owner == caller.playerId)(
              UnauthorizedError(
                if (game.gameType == GameType.Character) ErrorText.tournament.characterNotInvited
                else ErrorText.tournament.notInvited
              )
            )
            existing <- entrants.list(gameId, tournamentId)
            active = existing.count(!_.isWithdrawn)
            _ <- IO.raiseWhen(t.settings.maxEntrants.exists(active >= _))(
              ConflictError(ErrorText.tournament.full(TournamentService.noun(t)))
            )
            // The entries the caller plays now, which in a character game are their characters' as they stand.
            mine = existing.filter(_.playerId.contains(caller.playerId))
            // Coming back: the withdrawn entry with this character, or with none in a plain game.
            returning = mine.find(e => e.isWithdrawn && e.characterId == characterId)
            entrant <- returning match {
                case Some(e) =>
                    for {
                        _ <- entrants.setWithdrawnAt(
                          game = gameId,
                          tournament = tournamentId,
                          id = e.entrantId,
                          at = None
                        )
                        _ <- IO.whenA(late)(
                          updateState(session = session, t = t, game = game)(s =>
                              Progress.reenter(s, e.entrantId.value)
                          )
                        )
                    } yield e.copy(withdrawnAt = None)
                case None =>
                    for {
                        _ <- IO.raiseWhen(mine.count(!_.isWithdrawn) >= t.settings.entriesPerPlayer)(
                          ConflictError(
                            if (t.settings.entriesPerPlayer == 1)
                                ErrorText.tournament.alreadyEntered(TournamentService.noun(t))
                            else ErrorText.tournament.allEntriesUsed(t.settings.entriesPerPlayer.toString)
                          )
                        )
                        _ <- IO.raiseWhen(characterId.exists(c => existing.exists(_.characterId.contains(c))))(
                          ConflictError(ErrorText.tournament.characterAlreadyEntered(TournamentService.noun(t)))
                        )
                        rating <- ratingOf(session = session, gameId = gameId, player = caller.playerId)
                        _ <- IO.raiseWhen(
                          t.settings.minRating.exists(rating < _) || t.settings.maxRating.exists(rating > _)
                        )(
                          ValidationError(
                            ErrorText.tournament.ratingOutside(rating.toString) +
                                t.settings.minRating.fold("")(m => ErrorText.tournament.ratingAtLeast(m.toString)) +
                                t.settings.maxRating.fold("")(m => ErrorText.tournament.ratingAtMost(m.toString))
                          )
                        )
                        made <- entrants.create(
                          game = gameId,
                          gt = game.gameType,
                          tournament = tournamentId,
                          player = caller.playerId,
                          character = characterId,
                          rating = rating
                        )
                        // Seeded against everyone's rating now, the newcomer's among them.
                        ratings <-
                            (if (late)
                                 currentRatings(
                                   session = session,
                                   gameId = gameId,
                                   entries = existing.filterNot(_.isWithdrawn)
                                 )
                             else IO.pure(Map.empty[Long, Int]))
                        _ <- IO.whenA(late)(requireDesign(session, t).flatMap { design =>
                            updateState(session = session, t = t, game = game) { s =>
                                Progress.lateEntry(
                                  state = s,
                                  entrant = made.entrantId.value,
                                  env = env(
                                    t = t,
                                    design = design,
                                    game = game,
                                    ratings = ratings + (made.entrantId.value -> rating)
                                  )
                                )
                            }
                        })
                    } yield made.copy(nickname = caller.nickname)
            }
            // Full now, and offered as starting itself: this entry is what starts it.
            full = t.settings.maxEntrants.contains(active + 1)
            startedNow <-
                if (t.status == TournamentStatus.Open && t.settings.isAutoStarted && full)
                    startIn(session = session, t = t, game = game).map(Some(_))
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
                        t <- requireTournamentForUpdate(session = session, gameId = gameId, id = tournamentId)
                        found <- entrants
                            .readForUpdate(game = gameId, tournament = tournamentId, id = entrantId)
                            .flatMap(IO.fromOption(_)(NotFoundError(ErrorText.tournament.noSuchEntry)))
                        (player, withdrawnAt) = found
                        // Whoever plays the entry now -- its player, or its character's owner -- or the owner.
                        _ <- IO.raiseUnless(player.contains(caller.playerId) || t.owner == caller.playerId)(
                          UnauthorizedError(ErrorText.tournament.withdrawNotAllowed)
                        )
                        _ <- t.status match {
                            case TournamentStatus.Open =>
                                entrants.delete(game = gameId, tournament = tournamentId, id = entrantId)
                            case TournamentStatus.Running if withdrawnAt.isEmpty =>
                                requireGame(session, gameId).flatMap { game =>
                                    entrants.setWithdrawnAt(
                                      game = gameId,
                                      tournament = tournamentId,
                                      id = entrantId,
                                      at = Some(now())
                                    ) *>
                                        updateState(session = session, t = t, game = game)(
                                          Progress.withdraw(_, entrantId.value)
                                        )
                                }
                            case TournamentStatus.Running =>
                                IO.raiseError(ConflictError(ErrorText.tournament.alreadyWithdrawn))
                            case _ => IO.raiseError(ConflictError(ErrorText.tournament.over))
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
                        t <- requireOwned(session = session, gameId = gameId, id = tournamentId, caller = caller)
                        game <- requireGame(session, gameId)
                        running <- startIn(session = session, t = t, game = game)
                    } yield running
                }
            }
            .flatTap(t => IO.whenA(t.settings.isAutoStarted)(started(t.gameId, t.tournamentId)))

    /* A start, under the tournament's lock, which the caller holds: by its owner, or by the entry that filled one that
     * starts itself. */
    private def startIn(session: skunk.Session[IO], t: Tournament, game: Game): IO[Tournament] =
        for {
            _ <- IO.raiseUnless(t.status == TournamentStatus.Open)(
              ConflictError(ErrorText.tournament.cannotStart(t.status.label.toLowerCase))
            )
            entries <- new EntrantRepo(session).list(t.gameId, t.tournamentId).map(_.filterNot(_.isWithdrawn))
            least = math.max(2, game.requiredSeats)
            _ <- IO.raiseWhen(entries.sizeIs < least)(
              ConflictError(
                ErrorText.tournament.tooFewToStart(noun = TournamentService.noun(t), least = least.toString)
              )
            )
            design <- requireDesign(session, t)
            // A pool of everyone entered is as many as have.
            sized = t.copy(defaultPoolSize = t.defaultPoolSize.orElse(Some(entries.size)))
            problems = Validation
                .validate(
                  design = design,
                  game = TournamentGame.of(game, t.settings.playersPerMatch),
                  maxTurns = t.settings.maxTurns,
                  defaultPoolSize = sized.defaultPoolSize
                )
                .filterNot(_.isWarning)
            _ <- IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.map(_.describe).mkString("; ")))
            ratings <- currentRatings(session = session, gameId = t.gameId, entries = entries)
            state = Progress.start(
              entrants = entries.map(_.entrantId.value),
              env = env(t = sized, design = design, game = game, ratings = ratings),
              firstPoolId = TournamentStateRepo.NewPoolIds
            )
            running = sized.copy(status = TournamentStatus.Running, startedAt = Some(now()))
            _ <- new TournamentRepo(session).update(running)
            _ <- new TournamentRepo(session).writeState(game = t.gameId, id = t.tournamentId, state = state)
        } yield running

    // ---- shared ----------------------------------------------------------------------------

    /** Rewrites a running tournament's state, read under the tournament's lock, which the caller already holds. */
    private def updateState(session: skunk.Session[IO], t: Tournament, game: Game)(
        change: TournamentState => TournamentState
    ): IO[Unit] =
        new TournamentRepo(session).readState(t.gameId, t.tournamentId).flatMap {
            case Some(s) =>
                new TournamentRepo(session).writeState(game = t.gameId, id = t.tournamentId, state = change(s))
            case None => IO.unit
        }

    private def env(t: Tournament, design: TournamentDesign, game: Game, ratings: Map[Long, Int]): Env =
        Env(
          design = design,
          game = TournamentGame.of(game, t.settings.playersPerMatch).copy(hasRoleChoice = t.settings.hasRoleChoice),
          ratings = ratings,
          defaultPoolSize = t.defaultPoolSize,
          hasConcurrentRounds = t.settings.hasConcurrentRounds
        )

    /* The model a tournament is made from: one the game offers, a root and active -- or, if it is the
     * one the tournament being edited was `kept` from all along, whatever has become of it since. The challenge model
     * is no exception: a game offers challenges as it offers any format. Held FOR SHARE, the
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
                case Some(m) if kept.contains(id)                 => IO.pure(m)
                case Some(m) if m.isRoot && m.isActive && offered => IO.pure(m)
                case _ => IO.raiseError(NotFoundError(ErrorText.model.notOffered))
            }
        } yield found
    }

    /** A tournament's design, from its stages. */
    private def requireDesign(session: skunk.Session[IO], t: Tournament): IO[TournamentDesign] =
        new StageRepo(session).design(t.gameId, t.tournamentId).flatMap {
            case Some(d) => IO.pure(d)
            case None    => IO.raiseError(NotFoundError(ErrorText.tournament.notFound))
        }

    /** The default pool size a request asks for: its own, else the model's suggestion, else the game's R — for a game
      * without roles, the players per match the request asks for.
      */
    /* The pool size asked for, or else the model's suggestion, or else the game's R -- none, for a model whose pool is
     * everyone entered, which `startIn` sets it to. */
    private def defaultPoolSize(game: Game, model: TournamentModel, request: TournamentRequest): Option[Int] =
        request.defaultPoolSize
            .orElse(model.poolSize)
            .orElse(
              Option.unless(model.hasPoolOfEveryone)(
                TournamentGame.of(game, request.settings.playersPerMatch).poolSeats
              )
            )

    /* Everything wrong with a tournament's settings, as one refusal. `settings` are the request's as the model makes
     * them (`TournamentService.settingsFor`). */
    private def checkSettings(
        game: Game,
        model: TournamentModel,
        request: TournamentRequest,
        settings: TournamentSettings,
        poolSize: Option[Int]
    ): IO[Unit] = {
        val s = settings
        val problems =
            List(
              Option.when(request.name.trim.isEmpty)(ErrorText.tournament.blankName),
              Option.when(poolSize.exists(_ < 1))(ErrorText.design.emptyPool),
              Option.when(s.roundDuration.exists(d => d.isZero || d.isNegative))(ErrorText.tournament.roundNotPositive),
              Option.when(s.entriesPerPlayer < 1)(ErrorText.tournament.noEntriesAllowed),
              Option.when(s.minRating.zip(s.maxRating).exists((lo, hi) => lo > hi))(
                ErrorText.tournament.ratingsCrossed
              ),
              Option.when(s.maxTurns.exists(_ < 1))(ErrorText.tournament.noTurns),
              Option.when(s.maxEntrants.exists(_ < 1))(ErrorText.tournament.noEntries),
              // A game with roles seats one player per role, which is not the owner's to change.
              Option.when(s.playersPerMatch.isDefined && !game.isRoleless)(
                ErrorText.tournament.seatsByRole(game.displayName)
              ),
              // The engine plays a live match against the clock it is given, which is the round's.
              Option.when(s.isLive && !s.roundDuration.exists(_.getSeconds >= 1))(
                ErrorText.tournament.liveNeedsClock
              ),
              // Held to the values the game allows here, where the owner can be told, rather than found out when a
              // match starts and the engine is sent something it cannot play.
              ChallengeSettings.problem(s.gameSettings, parameterValues(game))
            ).flatten ++
                Validation
                    .validate(
                      design = model.design,
                      game = TournamentGame.of(game, s.playersPerMatch),
                      maxTurns = s.maxTurns,
                      // A pool of everyone entered checked at the most it takes, and again as it starts.
                      defaultPoolSize = poolSize.orElse(s.maxEntrants)
                    )
                    .filterNot(_.isWarning)
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
            case GameType.Plain =>
                new InvitationRepo(session).isInvited(
                  game = game.gameId,
                  tournament = tournamentId,
                  player = caller.playerId
                )
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
                  ValidationError(ErrorText.tournament.inviteCharacter(game.displayName))
                )
            case GameType.Plain =>
                IO.raiseWhen(characters > 0)(
                  ValidationError(ErrorText.tournament.invitePlayer(game.displayName))
                )
        }

    /* The owner a character to be invited has now: a character of this game, held FOR SHARE so that it cannot go
     * before the invitation naming it is written, and owned by somebody, since nobody could answer for it otherwise. */
    private def invitableOwner(session: skunk.Session[IO], game: Game, characterId: CharacterId): IO[Player] =
        for {
            character <- new CharacterRepo[T](session).readForShare(characterId).flatMap {
                case Some(c) if c.gameId == game.gameId => IO.pure(c)
                case _ => IO.raiseError(NotFoundError(ErrorText.character.notInGame(game.displayName)))
            }
            ownerId <- IO.fromOption(character.playerId)(
              ValidationError(ErrorText.tournament.characterUnowned(character.name))
            )
            owner <- new PlayerRepo(session).readForShare(ownerId).flatMap {
                case Some(p) => IO.pure(p)
                case None    => IO.raiseError(NotFoundError(ErrorText.player.notFound))
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
              UnauthorizedError(ErrorText.tournament.ratedNotAdmin(game.displayName))
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
                IO.raiseError(ValidationError(ErrorText.tournament.noCharacters))
            case (GameType.Character, None) =>
                IO.raiseError(ValidationError(ErrorText.tournament.enterWithCharacter))
            case (GameType.Character, Some(c)) =>
                new CharacterRepo[T](session).readForShare(c).flatMap {
                    case Some(ch) if ch.gameId == game.gameId && ch.playerId.contains(caller.playerId) => IO.unit
                    case _ => IO.raiseError(ValidationError(ErrorText.tournament.notYourCharacter))
                }
        }

    /** A player's overall rating in the game, or the starting rating if they have none. */
    private def ratingOf(session: skunk.Session[IO], gameId: GameId, player: PlayerId): IO[Int] =
        new EloRatingRepo(session).read(gameId, player).map(_.fold(EloRating.initial)(_.rating))

    /** Each of `entries`'s rating now — a character entry's owner's — by entrant id, for seeding. */
    private def currentRatings(session: skunk.Session[IO], gameId: GameId, entries: List[Entrant]): IO[Map[Long, Int]] =
        entries.headOption.fold(IO.pure(Map.empty[Long, Int])) { e =>
            new EntrantRepo(session).ratingsNow(gameId, e.tournamentId).map { ratings =>
                entries.flatMap(e => ratings.get(e.entrantId).map(e.entrantId.value -> _)).toMap
            }
        }

    private def requireCaller(session: skunk.Session[IO], externalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(externalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
        }

    // Read plainly: see the class's note on the game.
    private def requireGame(session: skunk.Session[IO], gameId: GameId): IO[Game] =
        new GameRepo[T](session).read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(ErrorText.game.notFound))
        }

    private def requireTournamentForUpdate(
        session: skunk.Session[IO],
        gameId: GameId,
        id: TournamentId
    ): IO[Tournament] =
        new TournamentRepo(session).readForUpdate(gameId, id).flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(ErrorText.tournament.notFound))
        }

    private def requireOwned(
        session: skunk.Session[IO],
        gameId: GameId,
        id: TournamentId,
        caller: Player
    ): IO[Tournament] =
        requireTournamentForUpdate(session = session, gameId = gameId, id = id).flatMap { t =>
            if (t.owner == caller.playerId) IO.pure(t)
            else IO.raiseError(UnauthorizedError(ErrorText.tournament.notOwner))
        }
}

object TournamentService {

    /** A request's settings as its model makes them: a model whose seats choose their roles makes every tournament of
      * it choose them.
      */
    def settingsFor(model: TournamentModel, settings: TournamentSettings): TournamentSettings =
        settings.copy(hasRoleChoice = settings.hasRoleChoice || model.hasRoleChoice)

    /** What a tournament is called in a refusal: a challenge, or a tournament. */
    def noun(t: Tournament): String = if (t.tournamentModelId == RoundRobin.modelId) "challenge" else "tournament"

    /** A round's match as the tournament's page shows it. */
    def view(m: TournamentMatch): TournamentMatchView = {
        val next = m.seats.filter(s => s.gameRoleId.isEmpty && !s.isForfeit).flatMap(_.chooseOrder).minOption
        TournamentMatchView(
          poolId = m.poolId,
          matchId = m.matchId,
          status = m.status,
          seats = m.seats.map(s =>
              TournamentSeatView(
                entrantId = s.entrantId,
                role = s.gameRoleId,
                isChoosing =
                    m.status == TournamentMatchStatus.Choosing && s.gameRoleId.isEmpty && s.chooseOrder == next &&
                        next.isDefined,
                chooseBy = None
              )
          )
        )
    }
}
