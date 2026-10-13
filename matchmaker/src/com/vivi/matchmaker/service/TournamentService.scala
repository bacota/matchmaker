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
import com.vivi.matchmaker.persistence._

/** Tournaments, from their creation to their start (`tournament-design.md`, new-design.md Phase 4): designing one,
  * inviting and entering players, and starting it. Playing its rounds is [[TournamentRounds]]'.
  *
  * Every call that changes a tournament is one transaction, and reads the tournament `FOR UPDATE` first: who owns it,
  * what state it is in and what entries it has all decide what the call may do. The game it is played in is read
  * plainly, as `game` is everywhere a write lands elsewhere: its catalogue changes when an admin edits it, rarely and
  * never in step with a tournament's entries (CLAUDE.md, "a reference table read on the way to a write elsewhere").
  */
class TournamentService[T](
    sessionPool: SessionPool,
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
                invited <- new TournamentInvitationRepo(session).list(gameId, tournamentId)
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
              }
            )
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
                    poolSize = defaultPoolSize(game, model, request)
                    _ <- checkSettings(game, model.design, request, poolSize)
                    made <- new TournamentRepo(session).create(
                      Tournament(
                        gameId,
                        TournamentId.unassigned,
                        request.name.trim,
                        request.description.trim,
                        caller.playerId,
                        model.tournamentModelId,
                        poolSize,
                        request.settings
                      )
                    )
                    _ <- new StageRepo(session).copy(gameId, made.tournamentId, model.design)
                    _ <- new PoolRepo(session).createRoot(gameId, made.tournamentId)
                } yield made
            }
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
                                model <- requireModel(session, gameId, request.tournamentModelId)
                                poolSize = defaultPoolSize(game, model, request)
                                _ <- checkSettings(game, model.design, request, poolSize)
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
                              settings = request.settings
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
                            } yield t.copy(
                              name = request.name.trim,
                              description = request.description.trim,
                              settings = t.settings.copy(
                                autoRounds = s.autoRounds,
                                lateEntries = s.lateEntries,
                                roundDuration = s.roundDuration.orElse(t.settings.roundDuration)
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

    /** The owner inviting a player to an invitational tournament. */
    def invite(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireOwned(session, gameId, tournamentId, caller)
                    _ <- IO.raiseWhen(t.status == TournamentStatus.Finished || t.status == TournamentStatus.Cancelled)(
                      ConflictError("this tournament is over")
                    )
                    _ <- new PlayerRepo(session).readForShare(playerId).flatMap {
                        case Some(_) => IO.unit
                        case None    => IO.raiseError(NotFoundError(s"no player $playerId"))
                    }
                    _ <- new TournamentInvitationRepo(session).invite(gameId, tournamentId, playerId)
                } yield ()
            }
        }

    /** Takes an invitation back: the owner's revoking it, or the invited player's declining. */
    def revoke(gameId: GameId, tournamentId: TournamentId, playerId: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session, gameId, tournamentId)
                    _ <- IO.raiseUnless(t.owner == caller.playerId || caller.playerId == playerId)(
                      UnauthorizedError("only the tournament's owner, or the player invited, may remove an invitation")
                    )
                    _ <- new TournamentInvitationRepo(session).revoke(gameId, tournamentId, playerId)
                } yield ()
            }
        }

    // ---- entries ---------------------------------------------------------------------------

    /** The caller entering a tournament — with a character of theirs, in a character game — or coming back to one they
      * withdrew from.
      *
      * The tournament must be open, or running with late entries allowed; an invitational one must have invited them
      * (its owner needs no invitation); their rating must be within its limits; and they may not hold more entries than
      * it allows. A late entry joins the tournament's stratum 0 straight away, to play from its next stage run.
      */
    def enter(
        gameId: GameId,
        tournamentId: TournamentId,
        characterId: Option[CharacterId],
        callerExternalId: String
    ): IO[Entrant] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val entrants = new EntrantRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session, gameId, tournamentId)
                    game <- requireGame(session, gameId)
                    late = t.status == TournamentStatus.Running
                    _ <- IO.raiseUnless(t.status == TournamentStatus.Open || (late && t.settings.lateEntries))(
                      ConflictError(
                        if (late) "this tournament has started, and takes no late entries"
                        else s"this tournament is not open for entries; it is ${t.status.label.toLowerCase}"
                      )
                    )
                    invited <- new TournamentInvitationRepo(session).isInvited(gameId, tournamentId, caller.playerId)
                    _ <- IO.raiseUnless(!t.settings.invitational || invited || t.owner == caller.playerId)(
                      UnauthorizedError("this tournament is by invitation, and you have not been invited")
                    )
                    _ <- checkCharacter(session, game, characterId, caller)
                    existing <- entrants.list(gameId, tournamentId)
                    mine = existing.filter(_.playerId == caller.playerId)
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
                                    if (t.settings.entriesPerPlayer == 1) "you have already entered this tournament"
                                    else
                                        s"you already have the ${t.settings.entriesPerPlayer} entries this tournament allows"
                                  )
                                )
                                _ <- IO.raiseWhen(characterId.exists(c => existing.exists(_.characterId.contains(c))))(
                                  ConflictError("that character has already entered this tournament")
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
                } yield entrant
            }
        }

    /** Withdraws an entry: its player's to do, or the owner's. Before the tournament starts the entry is simply
      * removed; once it is running, the entrant is put in no pool from then on, and a match they are already seated in
      * is theirs to forfeit.
      */
    def withdraw(gameId: GameId, tournamentId: TournamentId, entrantId: EntrantId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val entrants = new EntrantRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournamentForUpdate(session, gameId, tournamentId)
                    found <- entrants.readForUpdate(gameId, entrantId).flatMap {
                        case Some((tid, player, withdrawnAt)) if tid == tournamentId => IO.pure((player, withdrawnAt))
                        case _ => IO.raiseError(NotFoundError(s"no entry $entrantId in tournament $tournamentId"))
                    }
                    (player, withdrawnAt) = found
                    _ <- IO.raiseUnless(player == caller.playerId || t.owner == caller.playerId)(
                      UnauthorizedError("only the player who entered, or the tournament's owner, may withdraw an entry")
                    )
                    _ <- t.status match {
                        case TournamentStatus.Draft | TournamentStatus.Open => entrants.delete(gameId, entrantId)
                        case TournamentStatus.Running if withdrawnAt.isEmpty =>
                            requireGame(session, gameId).flatMap { game =>
                                entrants.setWithdrawnAt(gameId, entrantId, Some(now())) *>
                                    updateState(session, t, game)(Progress.withdraw(_, entrantId.value))
                            }
                        case TournamentStatus.Running => IO.raiseError(ConflictError("that entry is already withdrawn"))
                        case _                        => IO.raiseError(ConflictError("this tournament is over"))
                    }
                } yield ()
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
                        _ <- IO.raiseUnless(t.status == TournamentStatus.Draft || t.status == TournamentStatus.Open)(
                          ConflictError(s"this tournament cannot be started; it is ${t.status.label.toLowerCase}")
                        )
                        game <- requireGame(session, gameId)
                        entries <- new EntrantRepo(session).list(gameId, tournamentId).map(_.filterNot(_.withdrawn))
                        _ <- IO.raiseWhen(entries.sizeIs < math.max(2, game.requiredSeats))(
                          ConflictError(
                            s"a tournament needs at least ${math.max(2, game.requiredSeats)} entries to start"
                          )
                        )
                        design <- requireDesign(session, t)
                        problems = Validation
                            .validate(design, TournamentGame.of(game), t.settings.maxTurns, Some(t.defaultPoolSize))
                            .filterNot(_.warning)
                        _ <- IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.map(_.describe).mkString("; ")))
                        ratings <- currentRatings(session, gameId, entries)
                        state = Progress.start(
                          entries.map(_.entrantId.value),
                          env(t, design, game, ratings),
                          TournamentStateRepo.NewPoolIds
                        )
                        running = t.copy(status = TournamentStatus.Running, startedAt = Some(now()))
                        _ <- new TournamentRepo(session).update(running)
                        _ <- new TournamentRepo(session).writeState(gameId, tournamentId, state)
                    } yield running
                }
            }
            .flatTap(t => IO.whenA(t.settings.autoRounds)(started(t.gameId, t.tournamentId)))

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
        Env(design, TournamentGame.of(game), ratings, Some(t.defaultPoolSize), t.settings.concurrentRounds)

    /* The model a tournament is made from: one the game offers, with a classification. Read plainly, as the game is:
     * a game's admin edits its models rarely and never in step with a tournament being made from one, and the stages
     * written from it are the tournament's own (CLAUDE.md, "a reference table read on the way to a write elsewhere"). */
    private def requireModel(session: skunk.Session[IO], gameId: GameId, id: TournamentModelId): IO[TournamentModel] =
        new TournamentModelRepo(session).read(gameId, id).flatMap {
            case Some(m) if m.classification.isDefined => IO.pure(m)
            case _ => IO.raiseError(NotFoundError(s"game $gameId offers no tournament model $id"))
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

    private def checkSettings(
        game: Game,
        design: TournamentDesign,
        request: TournamentRequest,
        poolSize: Int
    ): IO[Unit] = {
        val s = request.settings
        val problems =
            List(
              Option.when(request.name.trim.isEmpty)("a tournament needs a name"),
              Option.when(poolSize < 1)("a pool must hold at least one participant"),
              Option.when(s.roundDuration.exists(d => d.isZero || d.isNegative))("a round duration must be positive"),
              Option.when(s.entriesPerPlayer < 1)("a player must be allowed at least one entry"),
              Option.when(s.minRating.zip(s.maxRating).exists((lo, hi) => lo > hi))(
                "the minimum rating cannot be above the maximum"
              ),
              Option.when(s.maxTurns.exists(_ < 1))("the maximum number of turns must be at least 1")
            ).flatten ++
                Validation
                    .validate(design, TournamentGame.of(game), s.maxTurns, Some(poolSize))
                    .filterNot(_.warning)
                    .map(_.describe)
        IO.raiseWhen(problems.nonEmpty)(ValidationError(problems.mkString("; ")))
    }

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
