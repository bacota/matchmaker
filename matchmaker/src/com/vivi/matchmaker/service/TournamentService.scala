package com.vivi.matchmaker.service

import scala.util.Random
import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import java.time.Duration
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    EloRatingRepo,
    EntryRepo,
    FixtureRepo,
    GameAdminRepo,
    GameRepo,
    PlayerRepo,
    TextCodec,
    TournamentParticipantRepo,
    TournamentRepo
}
import com.vivi.matchmaker.tournament.Seeding
import com.vivi.matchmaker.notify.Notifications

/** Tournaments, from creation to the start: creating and editing one, handing it to a new owner, inviting players to
  * it, entering and withdrawing, and starting it — seeding the field and laying out every round's pools.
  *
  * Every call that writes is one transaction, and every read a write is decided from is taken `FOR UPDATE` (or `FOR
  * SHARE`, where the read only has to outlive the write: an entry holds the tournament against a start). The game and
  * its roles are read plainly, under the reference-table exception: see `requireGame`.
  */
class TournamentService[T](
    sessionPool: SessionPool,
    notifications: Notifications = Notifications.disabled,
    /* A running round's completion, looked at again when the field changes: a withdrawal can leave it nothing to play. */
    fieldChanged: (GameId, TournamentId) => IO[Unit] = (_, _) => IO.unit,
    /* How the started rounds stand, for the tournament page: `TournamentPlayService.progress`. */
    progress: (GameId, TournamentId) => IO[TournamentProgress] = (_, _) => IO.pure(TournamentProgress())
)(using codec: TextCodec[T]) {

    /** Kinds of tournament that can be started so far. The rest can be created, and wait for the phases that build
      * them.
      */
    private val startable: Set[TournamentType] = Set(TournamentType.SingleElim, TournamentType.RoundRobin)

    // ---- reading ----------------------------------------------------------------------------------

    /** A game's tournaments, newest first, for any signed-in player. */
    def listForGame(gameId: GameId, callerExternalId: String): IO[List[Tournament]] =
        sessionPool.use { session =>
            requireCaller(session, callerExternalId) *> new TournamentRepo(session).listForGame(gameId)
        }

    /** The caller's tournaments: those they own, have entered, or are invited to. */
    def mine(callerExternalId: String): IO[List[TournamentSummary]] =
        sessionPool.use { session =>
            val repo = new TournamentRepo(session)
            for {
                caller <- requireCaller(session, callerExternalId)
                tournaments <- repo.listForPlayer(caller.playerId)
                entered <- repo.enteredBy(caller.playerId)
                invited <- repo.invitationsOf(caller.playerId)
            } yield tournaments.map { t =>
                val key = (t.gameId, t.tournamentId)
                TournamentSummary(t, t.owner == caller.playerId, entered.contains(key), invited.contains(key))
            }
        }

    /** The tournament page: the tournament, its field, its rounds and pools, and — for its owner — its invitations. */
    def detail(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[TournamentDetail] =
        sessionPool.use { session =>
            val tournaments = new TournamentRepo(session)
            val fixtures = new FixtureRepo(session)
            val players = new PlayerRepo(session)
            val characters = new CharacterRepo[T](session)
            for {
                caller <- requireCaller(session, callerExternalId)
                t <- requireTournament(tournaments.read(gameId, tournamentId), gameId, tournamentId)
                owner <- players.read(t.owner).map(_.map(p => PublicPlayer(p.playerId, p.nickname)))
                entries <- new EntryRepo(session).listForTournament(gameId, tournamentId)
                participants <- new TournamentParticipantRepo(session).list(gameId, tournamentId)
                entrants <- entries.traverse { e =>
                    for {
                        player <- players.read(e.playerId)
                        character <- e.characterId.flatTraverse(characters.read)
                    } yield TournamentEntrant(
                      e.entryId,
                      player.map(p => PublicPlayer(p.playerId, p.nickname)).getOrElse(PublicPlayer(e.playerId, "")),
                      character.map(c => CharacterName(c.characterId, c.gameId, c.name)),
                      participants.find(_.entryId == e.entryId)
                    )
                }
                rounds <- fixtures.listRounds(gameId, tournamentId)
                pools <- fixtures.listFixtures(gameId, tournamentId)
                slots <- fixtures.listSlots(gameId, tournamentId)
                owned = t.owner == caller.playerId
                invitedPlayers <-
                    if (!owned) IO.pure(Nil)
                    else
                        tournaments
                            .invitations(gameId, tournamentId)
                            .flatMap(_.flatTraverse(id => players.read(id).map(_.toList)))
                            .map(_.map(p => PublicPlayer(p.playerId, p.nickname)))
                invitedCharacters <-
                    if (!owned) IO.pure(Nil)
                    else
                        tournaments
                            .characterInvitations(gameId, tournamentId)
                            .flatMap(_.flatTraverse(id => characters.read(id).map(_.toList)))
                            .map(_.map(c => CharacterName(c.characterId, c.gameId, c.name)))
                standing <- if (t.started) progress(gameId, tournamentId) else IO.pure(TournamentProgress())
            } yield TournamentDetail(
              t,
              owner.getOrElse(PublicPlayer(t.owner, "")),
              // By seed once seeded; in the order they entered before.
              entrants.sortBy(e => (e.participant.map(_.seed).getOrElse(Int.MaxValue), e.entryId.value)),
              rounds,
              pools.map(f => TournamentPool(f, slots.filter(_.fixtureId == f.fixtureId))),
              invitedPlayers,
              invitedCharacters,
              standing
            )
        }

    // ---- creating and editing ------------------------------------------------------------------------

    /** Creates a tournament, owned by the caller. Only a game's admin may make one that is not friendly. */
    def create(tournament: Tournament, callerExternalId: String): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                for {
                    caller <- requireCaller(session, callerExternalId)
                    game <- requireGame(session, tournament.gameId)
                    _ <- IO.raiseUnless(game.active)(ValidationError(s"game ${game.gameId.value} is not active"))
                    t = tournament.copy(owner = caller.playerId, startedAt = None, endedAt = None)
                    _ <- validate(t, game)
                    _ <- IO.unlessA(t.friendly)(requireGameAdmin(session, caller, game.gameId))
                    created <- new TournamentRepo(session).create(t)
                } yield created
            }
        }

    /** The owner editing a tournament.
      *
      * Before the start, anything but its game and owner may change. After it, the bracket is laid out and the field
      * seeded, so its class, type, pool size and how many go through are fixed — a round's own settings are where those
      * change from then on — and so is whether it is friendly, which every match already played was created as. Whether
      * it is live may change at any time, and takes effect from the next round started.
      */
    def update(gameId: GameId, tournamentId: TournamentId, edit: Tournament, callerExternalId: String): IO[Tournament] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    existing <- requireTournament(repo.readForUpdate(gameId, tournamentId), gameId, tournamentId)
                    _ <- requireOwner(existing, caller)
                    game <- requireGame(session, gameId)
                    edited = edit.copy(
                      gameId = gameId,
                      tournamentId = tournamentId,
                      owner = existing.owner,
                      startedAt = existing.startedAt,
                      endedAt = existing.endedAt
                    )
                    _ <- IO.raiseWhen(existing.started && edited.friendly != existing.friendly)(
                      ConflictError("whether a tournament is friendly cannot change once it has started")
                    )
                    _ <- IO.raiseWhen(
                      existing.started && (edited.tournamentClass != existing.tournamentClass ||
                          edited.elimination != existing.elimination)
                    )(
                      ConflictError(
                        "a started tournament's kind and pools are fixed; change a round's settings when starting it"
                      )
                    )
                    _ <- IO.raiseWhen(edited.tournamentClass != existing.tournamentClass)(
                      ValidationError("a tournament's class cannot change; create another")
                    )
                    _ <- validate(edited, game)
                    _ <- IO.whenA(!edited.friendly && existing.friendly)(requireGameAdmin(session, caller, gameId))
                    _ <- repo.update(edited)
                } yield edited
            }
        }

    /** A game's admin handing a tournament to a new owner. */
    def setOwner(gameId: GameId, tournamentId: TournamentId, newOwner: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    _ <- requireTournament(repo.readForUpdate(gameId, tournamentId), gameId, tournamentId)
                    _ <- requireGameAdmin(session, caller, gameId)
                    // FOR SHARE, so the player cannot be deleted under the change that names them.
                    _ <- new PlayerRepo(session).readForShare(newOwner).flatMap {
                        case Some(_) => IO.unit
                        case None    => IO.raiseError(NotFoundError(s"no player with id ${newOwner.value}"))
                    }
                    _ <- repo.setOwner(gameId, tournamentId, newOwner)
                } yield ()
            }
        }

    // ---- invitations -----------------------------------------------------------------------------------

    /** The owner inviting a player, or in a character game a character, to the tournament. */
    def invite(
        gameId: GameId,
        tournamentId: TournamentId,
        player: Option[PlayerId],
        character: Option[CharacterId],
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction
                .use { _ =>
                    val repo = new TournamentRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournament(repo.readForShare(gameId, tournamentId), gameId, tournamentId)
                        _ <- requireOwner(t, caller)
                        game <- requireGame(session, gameId)
                        invited <- (player, character, game.gameType) match {
                            case (Some(p), None, GameType.Plain) =>
                                new PlayerRepo(session).readForShare(p).flatMap {
                                    case Some(_) => repo.invite(gameId, tournamentId, p).as(Some(p))
                                    case None    => IO.raiseError(NotFoundError(s"no player with id ${p.value}"))
                                }
                            case (None, Some(c), GameType.Character) =>
                                new CharacterRepo[T](session).readForShare(c).flatMap {
                                    case Some(found) if found.gameId == gameId =>
                                        repo.inviteCharacter(gameId, tournamentId, c).as(found.playerId)
                                    case _ =>
                                        IO.raiseError(NotFoundError(s"no character ${c.value} in game ${gameId.value}"))
                                }
                            case (_, _, GameType.Plain) =>
                                IO.raiseError(
                                  ValidationError("invite a player, by id, to a tournament of a plain game")
                                )
                            case (_, _, GameType.Character) =>
                                IO.raiseError(
                                  ValidationError("invite a character, by id, to a tournament of a character game")
                                )
                        }
                    } yield (t, caller, invited)
                }
                .flatMap { (t, caller, invited) =>
                    // After the commit, and unable to fail the invitation: told to the player, or the character's owner.
                    invited.traverse_(notifications.tournamentInvited(session, t, _, caller.nickname))
                }
        }

    /** Removes a player's invitation: the owner withdrawing it, or the player declining it. */
    def uninvite(gameId: GameId, tournamentId: TournamentId, player: PlayerId, callerExternalId: String): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournament(repo.readForShare(gameId, tournamentId), gameId, tournamentId)
                    _ <- IO.raiseUnless(t.owner == caller.playerId || player == caller.playerId)(
                      UnauthorizedError("only the tournament's owner, or the player invited, may remove an invitation")
                    )
                    removed <- repo.uninvite(gameId, tournamentId, player)
                    _ <- IO.raiseUnless(removed)(NotFoundError(s"player ${player.value} is not invited"))
                } yield ()
            }
        }

    /** Removes a character's invitation: the owner withdrawing it, or the character's owner declining it. */
    def uninviteCharacter(
        gameId: GameId,
        tournamentId: TournamentId,
        character: CharacterId,
        callerExternalId: String
    ): IO[Unit] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    t <- requireTournament(repo.readForShare(gameId, tournamentId), gameId, tournamentId)
                    owner <- new CharacterRepo[T](session).readForShare(character).map(_.flatMap(_.playerId))
                    _ <- IO.raiseUnless(t.owner == caller.playerId || owner.contains(caller.playerId))(
                      UnauthorizedError(
                        "only the tournament's owner, or the character's owner, may remove a character's invitation"
                      )
                    )
                    removed <- repo.uninviteCharacter(gameId, tournamentId, character)
                    _ <- IO.raiseUnless(removed)(NotFoundError(s"character ${character.value} is not invited"))
                } yield ()
            }
        }

    // ---- entries ----------------------------------------------------------------------------------

    /** The caller entering the tournament — in a character game, as one of their characters.
      *
      * An invitational tournament needs an invitation, by name or for a character the caller owns. An open one checks
      * the caller's overall rating against its bounds; a player with no rating yet is at the starting rating. A
      * tournament that has started takes entries only if it is a ladder.
      */
    def enter(
        gameId: GameId,
        tournamentId: TournamentId,
        character: Option[CharacterId],
        callerExternalId: String
    ): IO[TournamentEntry] =
        sessionPool.use { session =>
            session.transaction.use { _ =>
                val repo = new TournamentRepo(session)
                for {
                    caller <- requireCaller(session, callerExternalId)
                    // Held against a start, which would seed the field without this entry.
                    t <- requireTournament(repo.readForShare(gameId, tournamentId), gameId, tournamentId)
                    _ <- IO.raiseWhen(t.ended)(ConflictError("the tournament is over"))
                    _ <- IO.raiseWhen(t.started && t.tournamentClass != TournamentClass.Ladder)(
                      ConflictError("the tournament has started and takes no more entries")
                    )
                    game <- requireGame(session, gameId)
                    _ <- (game.gameType, character) match {
                        case (GameType.Character, None) =>
                            IO.raiseError(ValidationError("a character game is entered as a character"))
                        case (GameType.Plain, Some(_)) =>
                            IO.raiseError(ValidationError("a plain game has no characters to enter as"))
                        case _ => IO.unit
                    }
                    // The character, the caller's, and held so that it cannot change hands under the entry.
                    _ <- character.traverse_ { c =>
                        new CharacterRepo[T](session).readForShare(c).flatMap {
                            case Some(found) if found.gameId == gameId && found.playerId.contains(caller.playerId) =>
                                IO.unit
                            case _ =>
                                IO.raiseError(
                                  UnauthorizedError(s"character ${c.value} is not the caller's in this game")
                                )
                        }
                    }
                    _ <-
                        if (t.invitational)
                            repo.isInvitedForShare(gameId, tournamentId, caller.playerId).flatMap { invited =>
                                IO.raiseUnless(invited)(UnauthorizedError("the tournament is by invitation only"))
                            }
                        else checkRating(session, t, caller.playerId)
                    existing <- new EntryRepo(session).listForPlayer(gameId, tournamentId, caller.playerId)
                    _ <- IO.raiseWhen(existing.sizeIs >= t.maxEntriesPerPlayer)(
                      ConflictError("the caller has already entered this tournament")
                    )
                    entry <- new EntryRepo(session)
                        .create(TournamentEntry(gameId, tournamentId, EntryId(0), caller.playerId, character))
                        .adaptError(uniqueViolation("the caller has already entered this tournament"))
                } yield entry
            }
        }

    /** Withdraws an entry: its player, or the tournament's owner. Before the start the entry is removed; after it, its
      * participant is marked withdrawn, and any slot of a round not yet started that it would have filled is played as
      * a bye.
      */
    def withdraw(gameId: GameId, tournamentId: TournamentId, entryId: EntryId, callerExternalId: String): IO[Unit] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    val entries = new EntryRepo(session)
                    val participants = new TournamentParticipantRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournament(
                          new TournamentRepo(session).readForShare(gameId, tournamentId),
                          gameId,
                          tournamentId
                        )
                        entry <- entries.read(gameId, tournamentId, entryId).flatMap {
                            case Some(e) => IO.pure(e)
                            case None => IO.raiseError(NotFoundError(s"no entry ${entryId.value} in this tournament"))
                        }
                        _ <- IO.raiseUnless(entry.playerId == caller.playerId || t.owner == caller.playerId)(
                          UnauthorizedError("only the entrant, or the tournament's owner, may withdraw an entry")
                        )
                        seeded <- participants.readByEntryForUpdate(gameId, tournamentId, entryId)
                        _ <- seeded match {
                            case Some(p) =>
                                participants.setWithdrawn(gameId, tournamentId, p.tournamentParticipantId, true)
                            case None => entries.delete(gameId, tournamentId, entryId)
                        }
                    } yield seeded.isDefined
                }
            }
            .flatMap(started => IO.whenA(started)(fieldChanged(gameId, tournamentId)))

    // ---- starting -------------------------------------------------------------------------------------

    /** The owner starting the tournament: the field seeded by overall rating — a character by its owner's — with ties
      * drawn at random, and every round's pools laid out. Round 1 is not started: the owner starts each round.
      */
    def start(gameId: GameId, tournamentId: TournamentId, callerExternalId: String): IO[TournamentDetail] =
        sessionPool
            .use { session =>
                session.transaction.use { _ =>
                    val repo = new TournamentRepo(session)
                    for {
                        caller <- requireCaller(session, callerExternalId)
                        t <- requireTournament(repo.readForUpdate(gameId, tournamentId), gameId, tournamentId)
                        _ <- requireOwner(t, caller)
                        _ <- IO.raiseWhen(t.started)(ConflictError("the tournament has already started"))
                        settings <- t.elimination.filter(e => startable.contains(e.tournamentType)) match {
                            case Some(e) if t.tournamentClass == TournamentClass.Elimination => IO.pure(e)
                            case _ =>
                                IO.raiseError(ValidationError("this kind of tournament cannot be started yet"))
                        }
                        entries <- new EntryRepo(session).listForTournament(gameId, tournamentId)
                        _ <- IO.raiseWhen(entries.sizeIs < 2)(
                          ValidationError("a tournament needs at least two entrants to start")
                        )
                        ratings <- entries.traverse(e => ratingOf(session, gameId, e).map(e -> _))
                        // Seeded by the tournament's id, so the same field is drawn the same way however often asked.
                        order = Seeding.initial(ratings, Random(tournamentId.value))
                        participants <- order.zipWithIndex.traverse { (e, i) =>
                            new TournamentParticipantRepo(session).create(
                              TournamentParticipant(
                                gameId,
                                tournamentId,
                                TournamentParticipantId(0),
                                e.entryId,
                                i + 1,
                                i + 1
                              )
                            )
                        }
                        _ <- TournamentLayout.layOut(
                          session,
                          gameId,
                          tournamentId,
                          TournamentLayout.bracket(settings, participants.size)
                        )
                        _ <- repo.start(gameId, tournamentId)
                    } yield ()
                }
            }
            .flatMap(_ => detail(gameId, tournamentId, callerExternalId))

    // ---- rules ---------------------------------------------------------------------------------------

    /** What every tournament must be, as created or edited, against its game. */
    private def validate(t: Tournament, game: Game): IO[Unit] = {
        val mandatory = game.roles.count(!_.optional)
        val problems = List(
          Option.when(t.name.trim.isEmpty)("a tournament needs a name"),
          Option.when(t.maxEntriesPerPlayer != 1)("a player may enter a tournament only once"),
          Option.when(t.roundDuration.compareTo(Duration.ofSeconds(2)) < 0)("a round must last at least two seconds"),
          Option.when(t.rotations < 0)("rotations cannot be negative"),
          Option.when(t.minRating.zip(t.maxRating).exists(_ > _))("the lowest rating allowed is above the highest"),
          Option.when(t.tournamentClass == TournamentClass.Cyclic && game.gameType != GameType.Character)(
            "only a character game may have a cyclic tournament"
          ),
          (t.tournamentClass, t.elimination) match {
              case (TournamentClass.Ladder, Some(_)) => Some("a ladder has no pools to set")
              case (TournamentClass.Ladder, None)    => None
              case (_, None)                         => Some("an elimination tournament needs its pools set")
              case (_, Some(e)) =>
                  Option
                      .when(e.poolSize < mandatory)(
                        s"a pool must hold at least the game's $mandatory required role(s)"
                      )
                      .orElse(Option.when(e.poolSize < 2)("a pool needs at least two players"))
                      .orElse(
                        Option.when(e.minPoolAdvance < 1 || e.minPoolAdvance >= e.poolSize)(
                          "between one player and one fewer than a whole pool must go through"
                        )
                      )
                      .orElse(Option.when(e.eliminationRotations < 0)("rotations cannot be negative"))
                      .orElse(
                        Option.when(
                          Set(TournamentType.DoubleElim).contains(e.tournamentType) &&
                              e.poolSize != 2 && e.minPoolAdvance != 1
                        )("a double elimination needs pools of two, or one going through")
                      )
                      .orElse(
                        Option.when(
                          Set(TournamentType.ZeroElim, TournamentType.Repechage).contains(e.tournamentType) &&
                              e.poolSize != 2
                        )(s"a ${e.tournamentType.label.toLowerCase} tournament needs pools of two")
                      )
          }
        ).flatten
        problems.headOption.fold(IO.unit)(why => IO.raiseError(ValidationError(why)))
    }

    /** An open tournament's rating bounds, held against the caller's overall rating — the starting rating if they have
      * none.
      */
    private def checkRating(session: Session[IO], t: Tournament, player: PlayerId): IO[Unit] =
        if (t.minRating.isEmpty && t.maxRating.isEmpty) IO.unit
        else
            new EloRatingRepo(session).read(t.gameId, player).flatMap { found =>
                val rating = found.map(_.rating).getOrElse(EloRating.initial)
                IO.raiseWhen(t.minRating.exists(rating < _) || t.maxRating.exists(rating > _))(
                  ValidationError(
                    s"the tournament is for ratings ${t.minRating.fold("")(_.toString)}–" +
                        s"${t.maxRating.fold("")(_.toString)}; the caller's is $rating"
                  )
                )
            }

    /** What an entry is seeded by: its player's overall rating, or for a character its owner's now; the starting rating
      * for anybody with none.
      */
    private def ratingOf(session: Session[IO], gameId: GameId, entry: TournamentEntry): IO[Int] =
        for {
            player <- entry.characterId match {
                case None    => IO.pure(Some(entry.playerId))
                case Some(c) => new CharacterRepo[T](session).read(c).map(_.flatMap(_.playerId))
            }
            rating <- player.flatTraverse(p => new EloRatingRepo(session).read(gameId, p))
        } yield rating.map(_.rating).getOrElse(EloRating.initial)

    private def requireCaller(session: Session[IO], callerExternalId: String): IO[Player] =
        new PlayerRepo(session).readByExternalId(callerExternalId).flatMap {
            case Some(p) => IO.pure(p)
            case None    => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
        }

    /* The game, read plainly even inside a transaction that writes: the reference-table exception in
     * CLAUDE.md. What is written from it is a tournament and its rows, and an admin editing the game's
     * roles while a tournament is created is not a race anybody runs. */
    private def requireGame(session: Session[IO], gameId: GameId): IO[Game] =
        new GameRepo[T](session).read(gameId).flatMap {
            case Some(g) => IO.pure(g)
            case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
        }

    private def requireTournament(read: IO[Option[Tournament]], gameId: GameId, id: TournamentId): IO[Tournament] =
        read.flatMap {
            case Some(t) => IO.pure(t)
            case None    => IO.raiseError(NotFoundError(s"no tournament ${id.value} in game ${gameId.value}"))
        }

    private def requireOwner(t: Tournament, caller: Player): IO[Unit] =
        IO.raiseUnless(t.owner == caller.playerId)(UnauthorizedError("only the tournament's owner may do that"))

    /** An overall admin, or an admin of the game — held, so that a revoke cannot race what it permits. */
    private def requireGameAdmin(session: Session[IO], caller: Player, gameId: GameId): IO[Unit] =
        (if (caller.isAdmin) IO.pure(true) else new GameAdminRepo(session).isAdminForShare(caller.playerId, gameId))
            .flatMap(admin => IO.raiseUnless(admin)(UnauthorizedError("only an admin of the game may do that")))

    private def uniqueViolation(message: String): PartialFunction[Throwable, Throwable] = {
        case e: skunk.exception.PostgresErrorException if e.code == "23505" => ConflictError(message)
    }
}
