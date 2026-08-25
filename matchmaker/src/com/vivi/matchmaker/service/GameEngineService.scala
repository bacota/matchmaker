package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import scala.concurrent.duration._
import java.time.Instant
import java.util.UUID
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence._

/** One participant's outcome as the game engine reports it at the end of a match. */
case class ReportedResult(participantId: ParticipantId, rank: Int, scores: Map[String, Any], isWinner: Boolean)

/** The four exchanges between matchmaker and a game engine, as described in
  * `interaction-design.txt`:
  *
  *   1. [[start]] — a challenger turns their fully-accepted challenge into a match. Matchmaker
  *      asks the engine to create a game and records the urls it gets back.
  *   2. [[recordMove]] — the engine calls back when a player has moved; matchmaker updates whose
  *      turn it is.
  *   3. [[recordResults]] — the engine calls back when the match is over; matchmaker completes
  *      the match and its participants and writes the result rows.
  *   4. [[refresh]] — a participant asks matchmaker to re-check with the engine, in case a
  *      callback was lost or the engine ended the match on a timer.
  *
  * The two callbacks are authorized as the *game*, not as a player: their `callerExternalId` is
  * matched against `game.external_id`, the shared secret that identifies a game engine to
  * matchmaker — the same rule `CharacterService.updateState` uses. `start` and `refresh` are
  * ordinary player-authorized calls.
  */
class GameEngineService[T](
    sessionPool: SessionPool,
    engine: GameEngineClient,
    callbackBaseUrl: Option[String] = None
)(using codec: TextCodec[T]) {

  /** Turns a challenge into a match: creates the game in the engine, and writes the match and one
    * participant per acceptance.
    *
    * Only the challenger may start their own challenge, and only once at least `minPlayers` have
    * accepted — which is why this is an explicit action rather than something that happens on the
    * last acceptance: a challenge for up to six players may be worth starting with three.
    *
    * A challenge is also refused until every one of its game's required (non-optional) roles has
    * been taken by an acceptance. `minPlayers` alone cannot express that: two players have
    * accepted a tic-tac-toe challenge whether they are X and O or both asked for X, and only one
    * of those is a game that can be played. Optional roles are exactly the ones a match need not
    * wait for.
    *
    * The engine call sits between two transactions rather than inside one. Holding a transaction
    * open across a call to another system would keep locks for as long as that system takes to
    * answer, and no database transaction can roll the engine's game back anyway.
    *
    * The order is: write the match and its participants, ask the engine to create the game, then
    * record the urls it returns. Writing first is what lets the engine be given real participant
    * ids, which is how its callbacks name a seat. If the engine call fails, the half-made match
    * is deleted again and the claim released, so the challenger can simply try again.
    *
    * The challenge itself is never deleted, and its claim is never released once the start has
    * succeeded. It is what the match points at, and its challenger is the match's creator — see
    * `Match.challengeId`. A started challenge is excluded from the open-challenge listings and
    * refuses further acceptances, which is what "spent" now means in place of "gone".
    */
  def start(gameId: GameId, challengeId: ChallengeId, callerExternalId: String): IO[Match] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val playerRepo = new PlayerRepo(session)
      val challengeRepo = new OpenChallengeRepo(session)
      val acceptanceRepo = new AcceptanceRepo(session)
      val characterRepo = new CharacterRepo[T](session)
      val matchRepo = new MatchRepo(session)
      val participantRepo = new ParticipantRepo(session)

      val matchId = MatchId(UUID.randomUUID().toString)

      for {
        prepared <- session.transaction.use { _ =>
          for {
            // Locked first: two clicks of Start on the same challenge must not both get past the
            // checks below, and the challenge is what both would be reading.
            //
            // The lock is necessary but not sufficient. It is released when this transaction
            // commits, which is well before the whole operation finishes — so without the claim
            // below, the second click would simply wait here, then re-read a challenge that
            // still looked startable and make a second match. startedMatchId is the state the
            // lock is guarding, and the unique index on match (game_id, challenge_id) is the
            // database's own last word on it.
            locked <- challengeRepo.readForUpdate(gameId, challengeId).flatMap {
              case Some(l) => IO.pure(l)
              case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
            }
            _ <- locked.startedMatchId.traverse_ { existing =>
              IO.raiseError(
                ConflictError(
                  s"challenge ${challengeId.value} is already being started as match ${existing.value}"
                )
              )
            }
            challenge <- requireChallenge(challengeRepo, gameId, challengeId)
            game <- requireGame(gameRepo, gameId)
            challenger <- playerRepo.read(challenge.challenger).flatMap {
              case Some(p) => IO.pure(p)
              case None    => IO.raiseError(NotFoundError(s"no player with id ${challenge.challenger.value}"))
            }
            _ <- IO.raiseUnless(callerExternalId == challenger.externalId)(
              UnauthorizedError(s"caller '$callerExternalId' may not start challenge ${challengeId.value}")
            )
            roster <- acceptanceRepo.listForChallenge(gameId, challengeId)
            _ <- IO.raiseUnless(roster.sizeIs >= game.minPlayers)(
              ValidationError(
                s"challenge ${challengeId.value} has ${roster.size} acceptance(s); game ${gameId.value} needs at least ${game.minPlayers}"
              )
            )
            taken = roster.map((acceptance, _, _) => acceptance.gameRoleId).toSet
            unfilled = game.roles.filterNot(_.optional).filterNot(role => taken.contains(role.gameRoleId))
            _ <- IO.raiseUnless(unfilled.isEmpty)(
              ValidationError(
                s"challenge ${challengeId.value} cannot start until every role is filled; nobody is playing " +
                  unfilled.map(_.name).mkString(", ")
              )
            )
            newMatch = Match(
              gameId = gameId,
              matchId = matchId,
              // The match's creator, by reference: whoever this challenge's challenger is.
              challengeId = challengeId,
              description = challenge.message,
              completed = false,
              start = challenge.start.getOrElse(Instant.now()),
              timeLimit = challenge.timeLimit,
              settings = challenge.settings,
              isPublic = challenge.isPublic
            )
            saved <- matchRepo.create(newMatch)
            // Under the lock taken above, so the next start of this challenge sees the claim.
            _ <- challengeRepo.claimForStart(gameId, challengeId, matchId)
            participants <- roster.traverse { case (acceptance, externalId, roleName) =>
              participantRepo
                .create(toParticipant(matchId, acceptance))
                .flatMap(p => enginePlayer(characterRepo)(p, acceptance, externalId, roleName))
            }
          } yield (saved, game, challenge, participants)
        }
        (saved, game, challenge, players) = prepared

        response <- engine
          .createGame(game.url, createRequest(matchId, game, challenge, players))
          .onError(_ => undo(session, gameId, challengeId, matchId))

        withUrls = saved.copy(
          statusUrl = Some(response.statusUrl),
          playUrl = Some(response.playUrl),
          publicUrl = response.publicUrl
        )
        // Past this point the engine's game exists, so there is no undoing the start — the only
        // way out is forward. Failing here leaves the challenge claimed and the match urlless,
        // which is the recoverable state `refresh` reports: the claim is now the permanent mark
        // of a spent challenge rather than something that has to be cleaned up.
        started <- retrying(finish(session, withUrls).as(withUrls))
      } yield started
    }

  /* The last step of a start: record the urls the engine gave back.
   *
   * One statement now. It used to delete the challenge and its acceptances as well, in a
   * transaction, because a challenge left standing beside its own match could have been started
   * a second time — that is now the claim's job, and keeping the challenge is what gives the
   * match a creator. */
  private def finish(session: skunk.Session[IO], withUrls: Match): IO[Unit] =
    new MatchRepo(session).update(withUrls)

  /* Retries a database action a few times before giving up. Used only for the work after the
   * engine call, where failing is not an option that leaves a sane state behind — everywhere
   * else a failure simply rolls its transaction back. */
  private def retrying[A](io: IO[A], attempts: Int = 3, delay: FiniteDuration = 100.millis): IO[A] =
    io.handleErrorWith { error =>
      if (attempts <= 1) IO.raiseError(error)
      else IO.sleep(delay) *> retrying(io, attempts - 1, delay * 2)
    }

  /* Undoes the match written before the engine call, and the challenge's start claim, when that
   * call fails. Errors here are swallowed on purpose: the caller is already being told the engine
   * failed, and that is the more useful of the two failures. What is left behind if this does not
   * work is an urlless match with no results, which `refresh` reports as having no status url. */
  private def undo(session: skunk.Session[IO], gameId: GameId, challengeId: ChallengeId, matchId: MatchId): IO[Unit] =
    session.transaction
      .use { _ =>
        new ParticipantRepo(session).deleteForMatch(gameId, matchId) *>
          new MatchRepo(session).delete(gameId, matchId) *>
          // Releasing the claim is what makes "try again" true: the challenge is standing and
          // startable once more. If this is the part that fails, the challenge stays claimed and
          // no further start of it will be accepted — the same outcome as before this claim
          // existed had the deletes failed, and `refresh` still reports the urlless match.
          new OpenChallengeRepo(session).releaseStartClaim(gameId, challengeId)
      }
      .handleError(_ => ())

  /** Step 2: a player has moved. `moved` is the participant who moved and is no longer pending;
    * `next` are the participants whose turn it now is, whose clock starts at `prevMoveAt`.
    *
    * The engine decides turn order, so matchmaker takes what it is told rather than inferring it.
    * A participant named in neither list is left alone — a game where several players move at
    * once reports only the seat that changed.
    *
    * As in [[refresh]], the engine reports when the move happened and matchmaker works out the
    * deadline from the match's `timeLimit` — the time limit came from the challenge, so it is not
    * something the engine is in a position to state.
    */
  def recordMove(
      gameId: GameId,
      matchId: MatchId,
      moved: ParticipantId,
      next: List[ParticipantId],
      prevMoveAt: Option[Instant],
      callerExternalId: String
  ): IO[Unit] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val matchRepo = new MatchRepo(session)
      val participantRepo = new ParticipantRepo(session)
      session.transaction.use { _ =>
        for {
          _ <- authorizeGame(gameRepo, gameId, callerExternalId)
          existing <- requireMatchForUpdate(matchRepo, gameId, matchId)
          _ <- IO.raiseWhen(existing.completed)(
            ValidationError(s"match ${matchId.value} is already completed")
          )
          // The engine has not been told the match was called off — there is no exchange that
          // would tell it — so it will go on reporting moves made on a board matchmaker no
          // longer recognises. Refusing them is what makes a cancel stick.
          _ <- IO.raiseWhen(existing.cancelled)(
            ConflictError(s"match ${matchId.value} was cancelled and is no longer accepting moves")
          )
          mover <- requireParticipant(participantRepo, gameId, matchId, moved)
          _ <- participantRepo.update(withTurn(mover, pending = false, due = None))
          _ <- next.traverse { id =>
            requireParticipant(participantRepo, gameId, matchId, id)
              .flatMap(p => participantRepo.update(withTurn(p, pending = true, due = dueFrom(existing, prevMoveAt))))
          }
        } yield ()
      }
    }

  /** Step 3: the match is over. Completes the match and every participant in it, and writes one
    * result row per reported participant.
    *
    * Idempotent in the only way that matters for a callback that may be retried: a match already
    * completed is left alone rather than double-writing its results.
    *
    * A cancelled match is refused rather than ignored. Its creator called it off, so a result
    * arriving afterwards is a real disagreement between the two systems — the engine let the
    * game finish on a board matchmaker had stopped following — and saying so is more useful than
    * silently discarding it.
    */
  def recordResults(
      gameId: GameId,
      matchId: MatchId,
      results: List[ReportedResult],
      callerExternalId: String
  ): IO[Unit] =
    sessionPool.use { session =>
      val gameRepo = new GameRepo[T](session)
      val matchRepo = new MatchRepo(session)
      val participantRepo = new ParticipantRepo(session)
      val resultRepo = new ResultRepo(session)
      session.transaction.use { _ =>
        for {
          _ <- authorizeGame(gameRepo, gameId, callerExternalId)
          existing <- requireMatchForUpdate(matchRepo, gameId, matchId)
          _ <- IO.raiseWhen(existing.cancelled)(
            ConflictError(s"match ${matchId.value} was cancelled and can have no result")
          )
          _ <-
            if (existing.completed) IO.unit
            else
              for {
                participants <- participantRepo.listForMatch(gameId, matchId)
                known = participants.map(_._1.participantId).toSet
                unknown = results.map(_.participantId).filterNot(known)
                _ <- IO.raiseUnless(unknown.isEmpty)(
                  ValidationError(s"participant(s) ${unknown.map(_.value).mkString(", ")} are not in match ${matchId.value}")
                )
                _ <- participants.traverse((participant, _, _) =>
                  participantRepo.update(withTurn(participant, pending = false, due = None, completed = true))
                )
                _ <- results.traverse(r => resultRepo.create(Result(gameId, r.participantId, r.rank, r.scores, r.isWinner)))
                _ <- matchRepo.update(existing.copy(completed = true))
              } yield ()
        } yield ()
      }
    }

  /** Step 4: re-check a running match with the engine, and apply whatever it says.
    *
    * Open to any participant in the match — it changes nothing a player could not already see,
    * and it exists precisely because a callback can go missing. A match with no `statusUrl` has
    * not been created in the engine, and there is nothing to ask.
    *
    * A cancelled match is returned as it stands, without asking the engine. The engine would
    * answer — its game is still there — and applying what it said would undo the cancel one turn
    * at a time.
    */
  def refresh(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val matchRepo = new MatchRepo(session)
      val participantRepo = new ParticipantRepo(session)

      for {
        prepared <- session.transaction.use { _ =>
          for {
            player <- requireCaller(playerRepo, callerExternalId)
            existing <- requireMatch(matchRepo, gameId, matchId)
            participants <- participantRepo.listForMatch(gameId, matchId)
            _ <- IO.raiseUnless(participants.exists(_._1.playerId == player.playerId))(
              UnauthorizedError(s"caller '$callerExternalId' is not in match ${matchId.value}")
            )
            statusUrl <- existing.statusUrl match {
              case Some(url) => IO.pure(url)
              case None      => IO.raiseError(ValidationError(s"match ${matchId.value} has no status url"))
            }
          } yield (existing, statusUrl)
        }
        (existing, statusUrl) = prepared

        // Already over: the engine has nothing left to tell us that matchmaker would act on, and
        // asking would be a network call per page view.
        result <-
          if (existing.completed || existing.cancelled) IO.pure(existing)
          else
            engine.status(statusUrl).flatMap { status =>
              session.transaction.use { _ =>
                for {
                  current <- requireMatchForUpdate(matchRepo, gameId, matchId)
                  participants <- participantRepo.listForMatch(gameId, matchId)
                  byId = participants.map((p, _, _) => p.participantId -> p).toMap
                  _ <- status.participants.traverse { reported =>
                    byId.get(ParticipantId(reported.participantId)) match {
                      case Some(p) =>
                        participantRepo.update(withTurn(p, reported.pending, dueFrom(current, reported.prevMoveAt), reported.completed))
                      // The engine reporting a seat matchmaker does not have is the engine's
                      // problem to explain, not a reason to abandon the seats it does have.
                      case None => IO.unit
                    }
                  }
                  updated = current.copy(completed = status.completed)
                  _ <- IO.whenA(updated != current)(matchRepo.update(updated))
                } yield updated
              }
            }
      } yield result
    }

  /** The match itself, for a player in it — which is how the UI gets the `playUrl` to send them
    * to the game.
    */
  def read(gameId: GameId, matchId: MatchId, callerExternalId: String): IO[Match] =
    sessionPool.use { session =>
      val playerRepo = new PlayerRepo(session)
      val matchRepo = new MatchRepo(session)
      val participantRepo = new ParticipantRepo(session)
      for {
        player <- requireCaller(playerRepo, callerExternalId)
        existing <- requireMatch(matchRepo, gameId, matchId)
        participants <- participantRepo.listForMatch(gameId, matchId)
        _ <- IO.raiseUnless(participants.exists(_._1.playerId == player.playerId))(
          UnauthorizedError(s"caller '$callerExternalId' is not in match ${matchId.value}")
        )
      } yield existing
    }

  private def createRequest(
      matchId: MatchId,
      game: Game,
      challenge: OpenChallenge,
      players: List[EnginePlayer]
  ): CreateGameRequest =
    CreateGameRequest(
      matchId = matchId.value,
      gameName = game.name,
      isPublic = challenge.isPublic,
      parameters = game.parameters.map(p => p.name -> p.defaultValue.map(v => codec.encode(v.asInstanceOf[T])).getOrElse("")).toMap,
      settings = challenge.settings,
      timeLimitSeconds = challenge.timeLimit.map(_.getSeconds),
      players = players,
      moveCallbackUrl = callbackBaseUrl.map(base => s"$base/games/${game.gameId.value}/matches/${matchId.value}/moves"),
      resultsCallbackUrl = callbackBaseUrl.map(base => s"$base/games/${game.gameId.value}/matches/${matchId.value}/results")
    )

  private def enginePlayer(characterRepo: CharacterRepo[T])(
      participant: Participant,
      acceptance: Acceptance,
      externalId: String,
      roleName: String
  ): IO[EnginePlayer] = {
    val character = acceptance match {
      case ca: CharacterAcceptance => characterRepo.read(ca.characterId)
      case _: PlainAcceptance      => IO.pure(None)
    }
    character.map { c =>
      EnginePlayer(
        cognitoId = externalId,
        // The engine quotes this back in every callback, which is how a move or a result lands on
        // the right row without the engine knowing anything else about matchmaker's model.
        participantId = participant.participantId.value,
        role = Some(roleName),
        characterId = c.map(_.characterId.value),
        characterState = c.map(ch => codec.encode(ch.state))
      )
    }
  }

  private def toParticipant(matchId: MatchId, acceptance: Acceptance): Participant =
    acceptance match {
      case ca: CharacterAcceptance =>
        CharacterParticipant(
          ParticipantId(0),
          ca.gameId,
          matchId,
          ca.playerId,
          pending = false,
          completed = false,
          due = None,
          characterId = ca.characterId,
          gameRoleId = ca.gameRoleId
        )
      case pa: PlainAcceptance =>
        PlainParticipant(
          ParticipantId(0),
          pa.gameId,
          matchId,
          pa.playerId,
          pending = false,
          completed = false,
          due = None,
          gameRoleId = pa.gameRoleId
        )
    }

  /** When a turn that began at `prevMoveAt` — the moment the move before it was made — is due.
    *
    * The engine says when a participant's turn began; how long they get is the match's business,
    * since the time limit came from the challenge rather than from the engine. A match with no
    * time limit has no deadline at all — there is nothing to run out.
    */
  private def dueFrom(m: Match, prevMoveAt: Option[Instant]): Option[Instant] =
    for {
      at <- prevMoveAt
      limit <- m.timeLimit
    } yield at.plus(limit)

  private def withTurn(p: Participant, pending: Boolean, due: Option[Instant], completed: Boolean = false): Participant =
    p match {
      case cp: CharacterParticipant => cp.copy(pending = pending, due = due, completed = completed || cp.completed)
      case pp: PlainParticipant     => pp.copy(pending = pending, due = due, completed = completed || pp.completed)
    }

  private def authorizeGame(gameRepo: GameRepo[T], gameId: GameId, callerExternalId: String): IO[Game] =
    requireGame(gameRepo, gameId).flatTap { game =>
      IO.raiseUnless(callerExternalId == game.externalId)(
        UnauthorizedError(s"invalid game externalId for game ${gameId.value}")
      )
    }

  private def requireGame(gameRepo: GameRepo[T], gameId: GameId): IO[Game] =
    gameRepo.read(gameId).flatMap {
      case Some(g) => IO.pure(g)
      case None    => IO.raiseError(NotFoundError(s"no game with id ${gameId.value}"))
    }

  private def requireCaller(playerRepo: PlayerRepo, callerExternalId: String): IO[Player] =
    playerRepo.readByExternalId(callerExternalId).flatMap {
      case Some(p) => IO.pure(p)
      case None    => IO.raiseError(UnauthorizedError(s"no such user '$callerExternalId'"))
    }

  private def requireChallenge(repo: OpenChallengeRepo, gameId: GameId, challengeId: ChallengeId): IO[OpenChallenge] =
    repo.read(gameId, challengeId).flatMap {
      case Some(c) => IO.pure(c)
      case None    => IO.raiseError(NotFoundError(s"no challenge with id ${challengeId.value} in game ${gameId.value}"))
    }

  private def requireMatch(repo: MatchRepo, gameId: GameId, matchId: MatchId): IO[Match] =
    repo.read(gameId, matchId).flatMap {
      case Some(m) => IO.pure(m)
      case None    => IO.raiseError(NotFoundError(s"no match '${matchId.value}' in game ${gameId.value}"))
    }

  private def requireMatchForUpdate(repo: MatchRepo, gameId: GameId, matchId: MatchId): IO[Match] =
    repo.readForUpdate(gameId, matchId).flatMap {
      case Some(m) => IO.pure(m)
      case None    => IO.raiseError(NotFoundError(s"no match '${matchId.value}' in game ${gameId.value}"))
    }

  private def requireParticipant(
      repo: ParticipantRepo,
      gameId: GameId,
      matchId: MatchId,
      participantId: ParticipantId
  ): IO[Participant] =
    repo.read(gameId, participantId).flatMap {
      case Some(p) if p.matchId == matchId => IO.pure(p)
      case _ => IO.raiseError(NotFoundError(s"no participant ${participantId.value} in match '${matchId.value}'"))
    }
}
