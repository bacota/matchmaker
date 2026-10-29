package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.{IO, Ref}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.{Duration, Instant}
import org.scalacheck.{Gen, Shrink}
import skunk.implicits._
import org.scalacheck.Prop._
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.engine._
import com.vivi.matchmaker.model
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    GameApiKeyRepo,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    PlayerRepo,
    ResultRepo,
    SkunkIdCodecs,
    TestMatches,
    TestSession,
    TurnRepo
}

/** The game-engine interaction, end to end against the real database with a stubbed engine.
  *
  * The engine is the one thing that cannot be stood up here — it is another system entirely — so it is a stub that
  * records what it was asked and answers with fixed urls. Everything on matchmaker's side of the exchange is real: a
  * challenge played up to its match being created, or a match written as one is once created, and the result rows the
  * completion callback produces.
  */
class GameEngineServiceSpec extends PropertySuite {
    TestMigration.ensure()

    // Every generator here feeds a fixture built in the database, so a shrink candidate is not a
    // cheap retry — it is another round of registrations and inserts. Shrinking a failure of one
    // of these properties runs hundreds of them and buries the failure it was meant to report.
    // The inputs are opaque unique strings that shrink tells us nothing about anyway.
    private given noShrink[A]: Shrink[A] = Shrink.shrinkAny

    /** Records the last create request so the tests can assert on what the engine was told. */
    private class StubEngine(
        response: CreateGameResponse = CreateGameResponse(
          statusUrl = "https://engine/status/1",
          playUrl = "https://engine/play/1",
          publicUrl = None
        ),
        var status: GameStatusResponse = GameStatusResponse(isCompleted = false, participants = Nil),
        isFailing: Boolean = false,
        // Run in the middle of a status call, which is the only way a test can reach the window
        // between matchmaker's last read before the engine and its first write after one.
        beforeStatus: IO[Unit] = IO.unit
    ) extends GameEngineClient {
        @volatile var lastRequest: Option[CreateGameRequest] = None
        @volatile var lastUrl: Option[String] = None
        @volatile var lastCreateKey: Option[String] = None
        @volatile var lastStatusKey: Option[String] = None

        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            if (isFailing) IO.raiseError(GameEngineError("engine is down"))
            else IO { lastRequest = Some(request); lastUrl = Some(gameUrl); lastCreateKey = apiKey }.as(response)

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO { lastStatusKey = apiKey } *> beforeStatus.as(status)
    }

    /** An engine that answers a status call by naming the seat it was given first as the one to move, which is what a
      * real engine says about a match that has only just begun. The stub above cannot do this: the participant ids are
      * not known until the match's round has written them, so the answer has to be built from the create request rather
      * than set up in advance.
      */
    private class FirstTurnEngine extends GameEngineClient {
        @volatile private var firstSeat: Option[Long] = None

        def createGame(gameUrl: String, apiKey: Option[String], request: CreateGameRequest): IO[CreateGameResponse] =
            IO { firstSeat = request.players.headOption.map(_.participantId) }
                .as(
                  CreateGameResponse(
                    statusUrl = "https://engine/status/1",
                    playUrl = "https://engine/play/1",
                    publicUrl = None
                  )
                )

        def status(statusUrl: String, apiKey: Option[String], since: Option[Instant] = None): IO[GameStatusResponse] =
            IO.pure(
              GameStatusResponse(
                isCompleted = false,
                participants = firstSeat.toList.map(
                  EngineParticipantStatus(_, isPending = true, isCompleted = false, prevMoveAt = None)
                )
              )
            )
    }

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    private case class Fixture(owner: Player, game: Game, character: Character[String])

    private def makeFixture(
        nickname: String,
        externalId: String,
        gameExternalId: String,
        parameters: Seq[GameParameter[?]] = Seq.empty
    ): IO[Fixture] =
        TestSession.resource.use { session =>
            for {
                owner <- TestServices.services.registration.register(nickname = nickname, externalId = externalId)
                game <- new GameRepo[String](session).create(
                  Game(
                    gameId = GameId.unassigned,
                    gameType = GameType.Character,
                    name = "game",
                    displayName = "game",
                    description = "description",
                    url = "https://engine.example.com/games",
                    isActive = true,
                    // attacker is required, defender is not: a start waits for the first to be taken and
                    // never for the second, which is what lets these tests start a match with the
                    // challenger alone and still have a second role for a second player to accept as.
                    // Each is shown as something other than its name, so that what reaches the engine
                    // (the name) and what reaches a player (the display name) can be told apart.
                    // Defender is recommended rather than optional, so every start here is also the proof
                    // that a recommended role is one a start does not wait for; other specs keep
                    // an optional one.
                    Seq(
                      GameRole(
                        gameRoleId = GameRoleId(0),
                        gameId = GameId.unassigned,
                        name = "attacker",
                        roleType = RoleType.Required,
                        displayName = "Attacker side"
                      ),
                      GameRole(
                        gameRoleId = GameRoleId(0),
                        gameId = GameId.unassigned,
                        name = "defender",
                        roleType = RoleType.Recommended,
                        displayName = "Defender side"
                      )
                    ),
                    parameters,
                    gameExternalId
                  )
                )
                character <- new CharacterRepo[String](session).create(
                  Character(
                    characterId = CharacterId(0),
                    gameId = game.gameId,
                    name = "character",
                    description = "description",
                    state = "",
                    playerId = Some(owner.playerId)
                  )
                )
                _ <- Challenges.offeredIn(game)
            } yield Fixture(owner = owner, game = game, character = character)
        }

    /** The terms a match is played under, as a spec writes one directly: see [[begin]]. */
    private case class Terms(
        isPublic: Boolean = false,
        timeLimit: Option[Duration] = None,
        message: String = "message",
        timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
        timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
        start: Option[Instant] = None,
        settings: String = "{}",
        isLive: Boolean = false
    )

    private def challengeFor(
        fixture: Fixture,
        isPublic: Boolean = false,
        timeLimit: Option[Duration] = None,
        message: String = "message",
        timeLimitKind: TimeLimitKind = TimeLimitKind.PerTurn,
        timeLimitUnit: TimeLimitUnit = TimeLimitUnit.Minutes,
        start: Option[Instant] = None,
        settings: String = "{}",
        live: Boolean = false
    ): Terms = Terms(
      isPublic = isPublic,
      timeLimit = timeLimit,
      message = message,
      timeLimitKind = timeLimitKind,
      timeLimitUnit = timeLimitUnit,
      start = start,
      settings = settings,
      isLive = live
    )

    /** A match of `fixture`'s game begun as its engine has made it -- urls and a start -- written straight into the
      * tables (`TestMatches`), with the owner seated as the game's first role and `other`, if given, as its second.
      *
      * For the specs about what happens once a match exists: moves, results, clocks and refreshes do not care how it
      * came to be, and this gives them a clock of exactly the kind they ask about. How a match comes to be is the
      * challenge specs' further up, which go through the services.
      */
    private def begin(
        fixture: Fixture,
        terms: Terms,
        other: Option[(Player, Character[String])] = None
    ): IO[Match] =
        TestSession.resource.use { session =>
            val matchId = com.vivi.matchmaker.persistence.Generators.newMatchId()
            def seat(player: Player, character: Character[String], role: GameRoleId) =
                TestMatches.writeSeat(
                  session = session,
                  p = CharacterParticipant(
                    participantId = ParticipantId(0),
                    gameId = fixture.game.gameId,
                    matchId = matchId,
                    playerId = player.playerId,
                    isPending = false,
                    isCompleted = false,
                    due = None,
                    character.characterId,
                    Some(role)
                  ),
                  eloStart = EloRating.initial,
                  eloRoleStart = Some(EloRating.initial)
                )
            for {
                written <- TestMatches.writeMatch(
                  session = session,
                  m = Match(
                    gameId = fixture.game.gameId,
                    matchId = matchId,
                    tournamentId = TournamentId.unassigned,
                    description = terms.message,
                    completedAt = None,
                    start = Some(terms.start.getOrElse(Instant.now())),
                    timeLimit = terms.timeLimit,
                    settings = terms.settings,
                    isPublic = terms.isPublic,
                    statusUrl = Some("https://engine/status/1"),
                    playUrl = Some("https://engine/play/1"),
                    publicUrl = Option.when(terms.isPublic)("https://engine/public/1"),
                    timeLimitKind = terms.timeLimitKind,
                    timeLimitUnit = terms.timeLimitUnit,
                    isLive = terms.isLive
                  ),
                  owner = fixture.owner.playerId
                )
                _ <- seat(
                  player = fixture.owner,
                  character = fixture.character,
                  role = fixture.game.roles(0).gameRoleId
                )
                _ <- other.traverse_((player, character) =>
                    seat(player = player, character = character, role = fixture.game.roles(1).gameRoleId)
                )
            } yield written
        }

    /** Another player with a character in `fixture`'s game. */
    private def opponent(services: Services[String], fixture: Fixture, nickname: String, externalId: String) =
        for {
            other <- services.registration.register(nickname = nickname, externalId = externalId)
            character <- TestSession.resource.use { session =>
                new CharacterRepo[String](session)
                    .create(
                      Character(
                        characterId = CharacterId(0),
                        gameId = fixture.game.gameId,
                        name = "other",
                        description = "description",
                        state = "",
                        playerId = Some(other.playerId)
                      )
                    )
            }
        } yield (other, character)

    /** A challenge of `fixture`'s game between its owner, as the attacker, and another player, as the defender, played
      * through the services up to its match being created in the engine.
      */
    private def challenged(
        services: Services[String],
        fixture: Fixture,
        otherExternalId: String,
        roundDuration: Option[Duration] = None,
        live: Boolean = false,
        isPublic: Boolean = false,
        settings: String = "{}"
    ): IO[(Match, Player)] =
        for {
            found <- opponent(
              services = services,
              fixture = fixture,
              nickname = s"other-$otherExternalId",
              externalId = otherExternalId
            )
            (other, otherCharacter) = found
            made <- Challenges.startedMatch(
              services = services,
              game = fixture.game,
              seats = List(
                Challenges.Seat(
                  player = fixture.owner,
                  role = fixture.game.roles(0).gameRoleId,
                  character = Some(fixture.character.characterId)
                ),
                Challenges.Seat(
                  player = other,
                  role = fixture.game.roles(1).gameRoleId,
                  character = Some(otherCharacter.characterId)
                )
              ),
              roundDuration = roundDuration,
              live = live,
              isPublic = isPublic,
              settings = settings
            )
            started <- matchOf(fixture.game.gameId, made._2).map(_.get)
        } yield (started, other)

    private def participantsOf(m: Match): IO[List[Participant]] =
        TestSession.resource.use(session =>
            new ParticipantRepo(session).listForMatch(m.gameId, m.matchId).map(_.map(_._1))
        )

    private def matchOf(gameId: GameId, matchId: MatchId): IO[Option[Match]] =
        TestSession.resource.use(session => new MatchRepo(session).read(gameId, matchId))

    property("a challenge's match is created from the engine's answer, with one seat per entry") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services =
                    TestServices.servicesWith(engine = engine, callbackBaseUrl = Some("https://matchmaker.example.com"))
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      isPublic = true
                    )
                    (started, other) = made
                    participants <- participantsOf(started)
                    // Started, so it is no longer open to enter.
                    remaining <- services.tournaments.listForGame(fixture.game.gameId, externalId)
                } yield {
                    val request = engine.lastRequest.get
                    started.statusUrl.contains("https://engine/status/1") &&
                    started.playUrl.contains("https://engine/play/1") &&
                    started.isPublic &&
                    engine.lastUrl.contains(fixture.game.url) &&
                    request.isPublic &&
                    request.matchId == started.matchId.value.toString &&
                    // The engine is told the participant ids it will quote back in its callbacks, the
                    // players' Cognito ids, and the roles they chose -- by their names, not the display
                    // names players see.
                    request.players.map(_.participantId).toSet == participants.map(_.participantId.value).toSet &&
                    request.players.map(_.cognitoId).toSet == Set(externalId, otherExternalId) &&
                    request.players.flatMap(_.role).toSet == Set("attacker", "defender") &&
                    request.players.find(_.cognitoId == externalId).flatMap(_.role).contains("attacker") &&
                    // And the nickname and character name, for the engine to show each player by.
                    request.players.flatMap(_.nickname).contains(nickname) &&
                    request.players.flatMap(_.characterName).toSet == Set("character", "other") &&
                    request.moveCallbackUrl.exists(_.endsWith(s"/matches/${started.matchId.value}/moves")) &&
                    participants.map(_.playerId).toSet == Set(fixture.owner.playerId, other.playerId) &&
                    remaining.forall(_.tournament.tournamentId != started.tournamentId)
                }
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    /** A game parameter a challenger chooses among: `rounds`, 3 to 5, defaulting to 4. */
    private val roundsParameter: GameParameter[?] =
        GameParameter[String](
          gameId = GameId.unassigned,
          gameParameterId = GameParameterId(0),
          name = "rounds",
          defaultValue = Some("4"),
          values = Seq("3", "4", "5").map(v =>
              GameParameterValue(gameId = GameId.unassigned, gameParameterId = GameParameterId(0), value = v)
          ),
          displayName = "Rounds"
        )

    // Every fixture's game is on the same engine host, which is the case a key filed by host could
    // not tell apart: each game has to present its own key, not whichever was stored first.
    property("a match is created with its own game's API key, though another game shares its engine host") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                // Fresh for each case: keys are unique across games, and the database outlives a run.
                val (otherKey, thisKey) = (s"other-$gameExternalId", s"this-$gameExternalId")
                val result = for {
                    other <- makeFixture(
                      nickname = s"$nickname-o",
                      externalId = s"$externalId-o",
                      gameExternalId = s"$gameExternalId-o"
                    )
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    _ <- TestSession.resource.use { session =>
                        val keys = new GameApiKeyRepo(session)
                        keys.set(other.game.gameId, otherKey) *> keys.set(fixture.game.gameId, thisKey)
                    }
                    _ <- challenged(services = services, fixture = fixture, otherExternalId = otherExternalId)
                } yield (engine.lastCreateKey, engine.lastStatusKey)

                result.timeout(30.seconds).unsafeRunSync() == (Some(thisKey), Some(thisKey))
        }
    }

    property("a match is created with the parameter value its challenge chose, in place of the default") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      parameters = Seq(roundsParameter)
                    )
                    _ <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      settings = """{"rounds":"5"}"""
                    )
                    withChoice = engine.lastRequest.get
                    _ <- challenged(services = services, fixture = fixture, otherExternalId = s"$otherExternalId-2")
                    withoutChoice = engine.lastRequest.get
                } yield withChoice.parameters == Map("rounds" -> "5") &&
                    withChoice.settings.contains("\"rounds\"") &&
                    withoutChoice.parameters == Map("rounds" -> "4")
                result.timeout(60.seconds).unsafeRunSync()
        }
    }

    property("a challenge choosing a value its game does not allow is refused when it is made") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            def offer(fixture: Fixture, settings: String) =
                Challenges
                    .create(
                      tournaments = services.tournaments,
                      game = fixture.game,
                      owner = fixture.owner,
                      characterId = Some(fixture.character.characterId),
                      gameSettings = settings
                    )
                    .attempt
            val result = for {
                fixture <- makeFixture(
                  nickname = nickname,
                  externalId = externalId,
                  gameExternalId = gameExternalId,
                  parameters = Seq(roundsParameter)
                )
                refused <- offer(fixture, """{"rounds":"9"}""")
                notObject <- offer(fixture, "[]")
            } yield refused.left.toOption.exists {
                case ValidationError(msg) => msg.contains("'9' is not a value of rounds; expected one of 3, 4, 5")
                case _                    => false
            } && notObject.left.toOption.exists(_.isInstanceOf[ValidationError])
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("a challenge cannot start until it has enough entries to fill the game's required roles") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                offered <- Challenges.create(
                  tournaments = services.tournaments,
                  game = fixture.game,
                  owner = fixture.owner,
                  characterId = Some(fixture.character.characterId)
                )
                attempt <- services.tournaments
                    .start(
                      gameId = fixture.game.gameId,
                      tournamentId = offered.tournamentId,
                      callerExternalId = externalId
                    )
                    .attempt
            } yield attempt.left.exists(_.isInstanceOf[ConflictError])
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("a challenge is started by its owner and nobody else") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    found <- opponent(
                      services = services,
                      fixture = fixture,
                      nickname = s"other-$nickname",
                      externalId = otherExternalId
                    )
                    (other, otherCharacter) = found
                    t <- Challenges.offered(
                      services = services,
                      game = fixture.game,
                      seats = List(
                        Challenges
                            .Seat(
                              player = fixture.owner,
                              role = fixture.game.roles(0).gameRoleId,
                              character = Some(fixture.character.characterId)
                            ),
                        Challenges.Seat(
                          player = other,
                          role = fixture.game.roles(1).gameRoleId,
                          character = Some(otherCharacter.characterId)
                        )
                      )
                    )
                    attempt <- services.tournaments
                        .start(gameId = fixture.game.gameId, tournamentId = t, callerExternalId = otherExternalId)
                        .attempt
                } yield attempt.left.exists(_.isInstanceOf[UnauthorizedError])
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    // A game the engine never created must not leave a match behind it pretending otherwise: the match goes back to
    // being due, to be created again once the engine is back.
    property("a failed engine call leaves the match due, and a later attempt creates it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                // Settled in the call, so that the failed attempt has been made and undone by the time the last role is
                // chosen, rather than racing the reads below on a fiber of its own.
                val failing = TestServices.servicesInline(engine = new StubEngine(isFailing = true))
                val working = TestServices.servicesWith(engine = StubEngine())
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    found <- opponent(
                      services = failing,
                      fixture = fixture,
                      nickname = s"other-$nickname",
                      externalId = otherExternalId
                    )
                    (other, otherCharacter) = found
                    seats = List(
                      Challenges
                          .Seat(
                            player = fixture.owner,
                            role = fixture.game.roles(0).gameRoleId,
                            character = Some(fixture.character.characterId)
                          ),
                      Challenges.Seat(
                        player = other,
                        role = fixture.game.roles(1).gameRoleId,
                        character = Some(otherCharacter.characterId)
                      )
                    )
                    t <- Challenges.offered(services = failing, game = fixture.game, seats = seats)
                    _ <- Challenges.startAndBegin(services = failing, game = fixture.game, t = t, owner = fixture.owner)
                    matchId <- Challenges.choose(
                      services = failing,
                      game = fixture.game,
                      t = t,
                      seats = seats,
                      viewer = fixture.owner,
                      settled = _ == TournamentMatchStatus.Due
                    )
                    stranded <- matchOf(fixture.game.gameId, matchId)
                    before <- failing.matches.active(externalId)
                    _ <- working.rounds.createMatch(gameId = fixture.game.gameId, tournamentId = t, matchId = matchId)
                    created <- matchOf(fixture.game.gameId, matchId)
                    after <- working.matches.active(externalId)
                } yield stranded.exists(m => m.status == MatchStatus.Due && m.playUrl.isEmpty) &&
                    before.isEmpty &&
                    created.exists(_.playUrl.contains("https://engine/play/1")) &&
                    after.map(_.matchId) == List(matchId)
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a move callback moves the turn on, and is authorized by the game's external id") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val prevMoveAt = Instant.parse("2030-01-01T00:00:00Z")
            // The engine reports when the move was made; the deadline for whoever moves next is that
            // plus the match's time limit.
            val due = prevMoveAt.plusSeconds(300)
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(
                  fixture = fixture,
                  terms = challengeFor(fixture = fixture, timeLimit = Some(Duration.ofMinutes(5)))
                )
                participants <- participantsOf(started)
                seat = participants.head.participantId
                // A player's own token is not a game's secret, and the callback is the game's route.
                refused <- services.engine
                    .recordMove(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      moved = seat,
                      next = Nil,
                      takenAt = prevMoveAt,
                      startedAt = prevMoveAt,
                      callerExternalId = externalId
                    )
                    .attempt
                _ <- services.engine
                    .recordMove(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      moved = seat,
                      next = List(seat),
                      takenAt = prevMoveAt,
                      startedAt = started.start.get,
                      callerExternalId = gameExternalId
                    )
                after <- participantsOf(started)
            } yield refused.left.exists(_.isInstanceOf[UnauthorizedError]) &&
                after.head.isPending &&
                after.head.due.contains(due)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // ---------------------------------------------------------------------------
    // Numbered moves: callbacks that arrive in the opposite order to the moves
    // ---------------------------------------------------------------------------

    private def callback(
        services: Services[String],
        fixture: Fixture,
        started: Match,
        moved: Participant,
        takenAt: Instant,
        startedAt: Instant,
        sequence: Long,
        pending: List[(Participant, Instant)]
    ): IO[Unit] =
        services.engine.recordMove(
          gameId = fixture.game.gameId,
          matchId = started.matchId,
          moved = moved.participantId,
          next = Nil,
          takenAt = takenAt,
          startedAt = startedAt,
          callerExternalId = fixture.game.externalId,
          state = Some(MoveState(sequence, pending.map((p, at) => SeatClock(p.participantId, at))))
        )

    property("a round's resolving move arriving before the round's first move leaves both seats pending") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val roundOne = Instant.parse("2030-01-01T00:00:00Z")
                val firstPlan = roundOne.plusSeconds(60)
                val roundTwo = roundOne.plusSeconds(90)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      challenge = challengeFor(_, timeLimit = Some(Duration.ofMinutes(5)))
                    )
                    (fixture, started, mine, theirs) = seated
                    // Theirs resolved round one and started round two for both seats...
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = roundTwo,
                      startedAt = roundOne,
                      sequence = 2,
                      pending = List(mine -> roundTwo, theirs -> roundTwo)
                    )
                    // ...and mine, the round's first plan, lands afterwards, saying only theirs was pending.
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = firstPlan,
                      startedAt = roundOne,
                      sequence = 1,
                      pending = List(theirs -> roundOne)
                    )
                    after <- participantsOf(started)
                    turns <- turnsOf(started)
                } yield after.forall(_.isPending) &&
                    after.forall(_.due.contains(roundTwo.plusSeconds(300))) &&
                    // The late move's time was still spent, so its turn is kept.
                    turns.map(_.takenAt).toSet == Set(firstPlan, roundTwo)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("numbered moves applied in order leave the seats as the latest move says") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val roundOne = Instant.parse("2030-01-01T00:00:00Z")
                val firstPlan = roundOne.plusSeconds(60)
                val roundTwo = roundOne.plusSeconds(90)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = firstPlan,
                      startedAt = roundOne,
                      sequence = 1,
                      pending = List(theirs -> roundOne)
                    )
                    between <- participantsOf(started)
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = roundTwo,
                      startedAt = roundOne,
                      sequence = 2,
                      pending = List(mine -> roundTwo, theirs -> roundTwo)
                    )
                    after <- participantsOf(started)
                } yield between.find(_.participantId == mine.participantId).exists(!_.isPending) &&
                    between.find(_.participantId == theirs.participantId).exists(_.isPending) &&
                    after.forall(_.isPending)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // Rock-paper-scissors: nobody is ever made pending twice, so a late callback must still come out
    // right. The second throw says nobody is pending, which already accounts for the first.
    property("a first throw arriving after the second leaves nobody pending") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val created = Instant.parse("2030-01-01T00:00:00Z")
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = created.plusSeconds(20),
                      startedAt = created,
                      sequence = 2,
                      pending = Nil
                    )
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = created.plusSeconds(10),
                      startedAt = created,
                      sequence = 1,
                      pending = List(theirs -> created)
                    )
                    after <- participantsOf(started)
                } yield after.forall(!_.isPending)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("a repeated callback changes nothing the first delivery did not") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val created = Instant.parse("2030-01-01T00:00:00Z")
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = created.plusSeconds(10),
                      startedAt = created,
                      sequence = 1,
                      pending = List(theirs -> created)
                    )
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = created.plusSeconds(20),
                      startedAt = created,
                      sequence = 2,
                      pending = List(mine -> created.plusSeconds(20))
                    )
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = created.plusSeconds(20),
                      startedAt = created,
                      sequence = 2,
                      pending = Nil
                    )
                    after <- participantsOf(started)
                    turns <- turnsOf(started)
                } yield after.find(_.participantId == mine.participantId).exists(_.isPending) &&
                    after.find(_.participantId == theirs.participantId).exists(!_.isPending) &&
                    turns.size == 2
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("a status answer older than a move already applied does not overwrite the seats") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val created = Instant.parse("2030-01-01T00:00:00Z")
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = theirs,
                      takenAt = created.plusSeconds(20),
                      startedAt = created,
                      sequence = 2,
                      pending = List(mine -> created.plusSeconds(20), theirs -> created.plusSeconds(20))
                    )
                    // The engine answered before move 2: only theirs pending.
                    _ <- IO {
                        engine.status = GameStatusResponse(
                          isCompleted = false,
                          participants = List(
                            EngineParticipantStatus(
                              participantId = mine.participantId.value,
                              isPending = false,
                              isCompleted = false,
                              None
                            ),
                            EngineParticipantStatus(
                              participantId = theirs.participantId.value,
                              isPending = true,
                              isCompleted = false,
                              Some(created)
                            )
                          ),
                          sequence = Some(1)
                        )
                    }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    stale <- participantsOf(started)
                    // An answer as new as the move is applied.
                    _ <- IO { engine.status = engine.status.copy(sequence = Some(3)) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    fresh <- participantsOf(started)
                } yield stale.forall(_.isPending) &&
                    fresh.find(_.participantId == mine.participantId).exists(!_.isPending) &&
                    fresh.find(_.participantId == theirs.participantId).exists(_.isPending)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The move callback is best-effort, and so are the results: when the last move's callback is
    // lost and the results get through, the match completes and `refresh` stops asking the engine.
    property("for an engine that sends no turns, the results callback asks for those whose callbacks never arrived") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val created = Instant.parse("2030-01-01T00:00:00Z")
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    // Only the first move's callback arrives.
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = created.plusSeconds(10),
                      startedAt = created,
                      sequence = 1,
                      pending = List(theirs -> created.plusSeconds(10))
                    )
                    // The engine has both, the second being the move that ended the match.
                    _ <- IO {
                        engine.status = GameStatusResponse(
                          isCompleted = true,
                          participants = Nil,
                          turns = List(
                            EngineTurn(
                              participantId = mine.participantId.value,
                              takenAt = created.plusSeconds(10),
                              startedAt = Some(created)
                            ),
                            EngineTurn(
                              participantId = theirs.participantId.value,
                              takenAt = created.plusSeconds(40),
                              startedAt = Some(created.plusSeconds(10))
                            )
                          )
                        )
                    }
                    _ <- services.engine.recordResults(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      results = List(
                        ReportedResult(
                          participantId = mine.participantId,
                          rank = 1,
                          scores = Map.empty,
                          isWinner = true
                        ),
                        ReportedResult(
                          participantId = theirs.participantId,
                          rank = 2,
                          scores = Map.empty,
                          isWinner = false
                        )
                      ),
                      callerExternalId = gameExternalId
                    )
                    turns <- turnsOf(started)
                    ended <- matchOf(fixture.game.gameId, started.matchId)
                } yield ended.exists(_.isCompleted) &&
                    turns.map(t => (t.participantId, t.takenAt, t.startedAt)).toSet == Set(
                      (mine.participantId, created.plusSeconds(10), created),
                      (theirs.participantId, created.plusSeconds(40), created.plusSeconds(10))
                    )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The engine's own line about how the match ended, which the finished match is shown with. It is
     * HTML from another system, so it is stored as `SummaryHtml` cleaned it -- the formatting kept,
     * a script turned into text -- and it reaches the player's completed list. */
    property("a summary sent with the results is stored cleaned, and the completed match carries it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- services.engine.recordResults(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      results = List(
                        ReportedResult(
                          participantId = mine.participantId,
                          rank = 1,
                          scores = Map.empty,
                          isWinner = true
                        ),
                        ReportedResult(
                          participantId = theirs.participantId,
                          rank = 2,
                          scores = Map.empty,
                          isWinner = false
                        )
                      ),
                      callerExternalId = gameExternalId,
                      turns = Some(Nil),
                      summary = Some("<strong>me</strong> won<script>alert(1)</script>")
                    )
                    completed <- services.matches.completed(externalId).map(_.matches.toList)
                } yield completed.filter(_.matchId == started.matchId).map(_.resultSummary)
                assertEquals(
                  result.timeout(15.seconds).unsafeRunSync(),
                  List(Some("<strong>me</strong> won&lt;script&gt;alert(1)&lt;/script&gt;"))
                )
        }
    }

    property("turns sent with the results are recorded with them, and the engine is not asked") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                @volatile var asked = 0
                val services = TestServices.servicesWith(engine = StubEngine(beforeStatus = IO { asked += 1 }))
                val created = Instant.parse("2030-01-01T00:00:00Z")
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    // Only the first move's callback arrived; the results carry both.
                    _ <- callback(
                      services = services,
                      fixture = fixture,
                      started = started,
                      moved = mine,
                      takenAt = created.plusSeconds(10),
                      startedAt = created,
                      sequence = 1,
                      pending = List(theirs -> created.plusSeconds(10))
                    )
                    // `start` asks once, for the opening seats; what matters is the results asking nothing.
                    askedBefore = asked
                    _ <- services.engine.recordResults(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      results = List(
                        ReportedResult(
                          participantId = mine.participantId,
                          rank = 1,
                          scores = Map.empty,
                          isWinner = true
                        ),
                        ReportedResult(
                          participantId = theirs.participantId,
                          rank = 2,
                          scores = Map.empty,
                          isWinner = false
                        )
                      ),
                      callerExternalId = gameExternalId,
                      turns = Some(
                        List(
                          EngineTurn(
                            participantId = mine.participantId.value,
                            takenAt = created.plusSeconds(10),
                            startedAt = Some(created)
                          ),
                          EngineTurn(
                            participantId = theirs.participantId.value,
                            takenAt = created.plusSeconds(40),
                            startedAt = Some(created.plusSeconds(10))
                          )
                        )
                      )
                    )
                    turns <- turnsOf(started)
                    ended <- matchOf(fixture.game.gameId, started.matchId)
                } yield ended.exists(_.isCompleted) &&
                    asked == askedBefore &&
                    turns.map(t => (t.participantId, t.takenAt, t.startedAt)).toSet == Set(
                      (mine.participantId, created.plusSeconds(10), created),
                      (theirs.participantId, created.plusSeconds(40), created.plusSeconds(10))
                    )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("results whose turns name someone not in the match are refused, and nothing is written") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = challengeFor(fixture = fixture))
                seat <- participantsOf(started).map(_.head.participantId)
                refused <- services.engine
                    .recordResults(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      results =
                          List(ReportedResult(participantId = seat, rank = 1, scores = Map.empty, isWinner = true)),
                      callerExternalId = gameExternalId,
                      turns = Some(
                        List(EngineTurn(participantId = Long.MaxValue, takenAt = Instant.parse("2030-01-01T00:00:00Z")))
                      )
                    )
                    .attempt
                ended <- matchOf(fixture.game.gameId, started.matchId)
                turns <- turnsOf(started)
            } yield refused.left.exists(_.isInstanceOf[ValidationError]) &&
                ended.exists(!_.isCompleted) &&
                turns.isEmpty
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("an engine that cannot be asked for its turns does not fail the results".tag(Quiet)) {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(
              engine = StubEngine(beforeStatus = IO.raiseError(GameEngineError("engine is down")))
            )
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = challengeFor(fixture = fixture))
                seat <- participantsOf(started).map(_.head.participantId)
                _ <- services.engine.recordResults(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  results = List(ReportedResult(participantId = seat, rank = 1, scores = Map.empty, isWinner = true)),
                  callerExternalId = gameExternalId
                )
                ended <- matchOf(fixture.game.gameId, started.matchId)
            } yield ended.exists(_.isCompleted)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The engine answers a status call; while it is answering, the match ends. The answer then says the
    // match is still running, and must not reopen it -- whether or not the engine numbers its moves.
    property("a status answer from before the match ended neither reopens it nor reseats it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val created = Instant.parse("2030-01-01T00:00:00Z")
                // One run per kind of engine: one that does not number its moves, and one whose answer is
                // numbered below the final move's.
                def run(sequence: Option[Long], suffix: String): IO[Boolean] = {
                    @volatile var duringStatus: IO[Unit] = IO.unit
                    val engine = StubEngine(beforeStatus = IO.defer {
                        // Once: the results callback asks the engine for its turns too.
                        val once = duringStatus
                        duringStatus = IO.unit
                        once
                    })
                    val services = TestServices.servicesWith(engine = engine)
                    for {
                        seated <- twoSeats(
                          services = services,
                          nickname = s"$nickname-$suffix",
                          externalId = s"$externalId-$suffix",
                          gameExternalId = s"$gameExternalId-$suffix",
                          otherExternalId = s"$otherExternalId-$suffix"
                        )
                        (fixture, started, mine, theirs) = seated
                        _ <- IO {
                            engine.status = GameStatusResponse(
                              isCompleted = false,
                              participants = List(
                                EngineParticipantStatus(
                                  participantId = mine.participantId.value,
                                  isPending = true,
                                  isCompleted = false,
                                  Some(created)
                                ),
                                EngineParticipantStatus(
                                  participantId = theirs.participantId.value,
                                  isPending = true,
                                  isCompleted = false,
                                  Some(created)
                                )
                              ),
                              sequence = sequence
                            )
                            duringStatus = for {
                                _ <- sequence.traverse_(_ =>
                                    callback(
                                      services = services,
                                      fixture = fixture,
                                      started = started,
                                      moved = theirs,
                                      takenAt = created.plusSeconds(30),
                                      startedAt = created,
                                      sequence = 2,
                                      pending = Nil
                                    )
                                )
                                _ <- services.engine.recordResults(
                                  gameId = fixture.game.gameId,
                                  matchId = started.matchId,
                                  results = List(
                                    ReportedResult(
                                      participantId = mine.participantId,
                                      rank = 1,
                                      scores = Map.empty,
                                      isWinner = true
                                    ),
                                    ReportedResult(
                                      participantId = theirs.participantId,
                                      rank = 2,
                                      scores = Map.empty,
                                      isWinner = false
                                    )
                                  ),
                                  callerExternalId = fixture.game.externalId
                                )
                            } yield ()
                        }
                        refreshed <- services.engine.refresh(
                          gameId = fixture.game.gameId,
                          matchId = started.matchId,
                          callerExternalId = s"$externalId-$suffix"
                        )
                        ended <- matchOf(fixture.game.gameId, started.matchId)
                        seats <- participantsOf(started)
                    } yield refreshed.isCompleted &&
                        ended.exists(_.isCompleted) &&
                        seats.forall(p => p.isCompleted && !p.isPending)
                }
                val result = for {
                    unnumbered <- run(None, "u")
                    numbered <- run(Some(1L), "n")
                } yield unnumbered && numbered
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a results callback completes the match and writes the result rows") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = challengeFor(fixture = fixture))
                participants <- participantsOf(started)
                seat = participants.head.participantId
                reported = ReportedResult(
                  participantId = seat,
                  rank = 1,
                  scores = Map("points" -> 42.0),
                  isWinner = true
                )
                _ <- services.engine.recordResults(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  results = List(reported),
                  callerExternalId = gameExternalId
                )
                stored <- TestSession.resource.use(session => new ResultRepo(session).read(fixture.game.gameId, seat))
                after <- participantsOf(started)
                completed <- services.matches.completed(externalId).map(_.matches.toList)
                // Not just that it is over, but when: the column is a timestamp, and a match completed
                // by this callback is stamped as the callback runs.
                reread <- services.engine.read(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  callerExternalId = externalId
                )
            } yield stored.contains(
              model.Result(
                gameId = fixture.game.gameId,
                participantId = seat,
                rank = 1,
                scores = Map("points" -> 42.0),
                isWinner = true
              )
            ) &&
                after.head.isCompleted &&
                !after.head.isPending &&
                reread.completedAt.exists(at => !at.isBefore(started.start.get)) &&
                completed.exists(_.matchId == started.matchId)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("refresh applies what the engine reports, for a participant") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val prevMoveAt = Instant.parse("2031-02-03T04:05:00Z")
                // The engine says when the turn started; the deadline is that plus the match's own time
                // limit, which came from the challenge below.
                val due = prevMoveAt.plusSeconds(600)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    _ <- services.registration.register(nickname = s"other-$nickname", externalId = otherExternalId)
                    started <- begin(
                      fixture = fixture,
                      terms = challengeFor(fixture = fixture, timeLimit = Some(Duration.ofMinutes(10)))
                    )
                    participants <- participantsOf(started)
                    seat = participants.head.participantId
                    _ <- IO {
                        engine.status = GameStatusResponse(
                          isCompleted = false,
                          participants = List(
                            EngineParticipantStatus(
                              participantId = seat.value,
                              isPending = true,
                              isCompleted = false,
                              prevMoveAt = Some(prevMoveAt)
                            )
                          )
                        )
                    }
                    // Only someone playing the match may ask about it.
                    refused <- services.engine
                        .refresh(
                          gameId = fixture.game.gameId,
                          matchId = started.matchId,
                          callerExternalId = otherExternalId
                        )
                        .attempt
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    after <- participantsOf(started)
                } yield refused.left.exists(_.isInstanceOf[UnauthorizedError]) &&
                    !refreshed.isCompleted &&
                    after.head.isPending &&
                    after.head.due.contains(due)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The gap around an external call, from CLAUDE.md: `refresh` decides the match is live, asks the
     * engine, and writes the answer -- and the cancel that lands while the engine is answering must
     * survive it. `applyEngineStatus` re-reads under the lock for exactly this. */
    property("a cancel committed while the engine is answering is not undone by the answer") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            // Set up after the fixture, because cancelling needs the match it does not have yet.
            val cancelling: Ref[IO, IO[Unit]] = Ref.unsafe(IO.unit)
            val engine = StubEngine(beforeStatus = cancelling.get.flatten)
            val services = TestServices.servicesWith(engine = engine)
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = challengeFor(fixture = fixture))
                // The engine will claim the match is over, which is the answer that would do the most
                // damage if it were applied: it completes the match and writes it a finish time.
                _ <- IO {
                    engine.status = GameStatusResponse(isCompleted = true, participants = Nil)
                }
                _ <- cancelling.set(
                  services.matches
                      .cancel(gameId = fixture.game.gameId, matchId = started.matchId, callerExternalId = externalId)
                      .void
                )
                refreshed <- services.engine.refresh(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  callerExternalId = externalId
                )
                reread <- matchOf(fixture.game.gameId, started.matchId)
            } yield refreshed.isCancelled && !refreshed.isCompleted &&
                reread.exists(m => m.isCancelled && !m.isCompleted)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The results callback is sent once. When it is lost the engine's status answer still carries the
     * results, and asking for it -- a Refresh, or a Review of the finished board -- is how they are
     * recorded after all: ranks, winner and summary, as the callback would have left them. */
    private def statusWithResults(mine: Participant, theirs: Participant): GameStatusResponse =
        GameStatusResponse(
          isCompleted = true,
          participants = Nil,
          results = Some(
            EngineResults(
              results = List(
                EngineResult(participantId = mine.participantId.value, rank = 1, isWinner = true),
                EngineResult(participantId = theirs.participantId.value, rank = 2, isWinner = false)
              ),
              summary = Some("<strong>me</strong> won")
            )
          )
        )

    property("refresh records the results a status answer carries, when their callback was lost") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- IO { engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    results <- resultsOf(started)
                    completed <- services.matches.completed(externalId).map(_.matches.toList)
                } yield refreshed.isCompleted &&
                    results.map(r => (r.participantId, r.rank, r.isWinner)).toSet == Set(
                      (mine.participantId, 1, true),
                      (theirs.participantId, 2, false)
                    ) &&
                    completed.filter(_.matchId == started.matchId).map(m => (m.resultSummary, m.outcome)) ==
                    List((Some("<strong>me</strong> won"), Some(MatchOutcome.Won)))
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("refresh recovers the results of a match already completed without them") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    // Completed by a status from an engine that sent no results, as before they were sent.
                    _ <- IO { engine.status = GameStatusResponse(isCompleted = true, participants = Nil) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    before <- resultsOf(started)
                    _ <- IO { engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    // A second look finds them recorded, and records nothing more.
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    after <- resultsOf(started)
                } yield before.isEmpty &&
                    after.map(r => (r.participantId, r.rank, r.isWinner)).sortBy(_._2) == List(
                      (mine.participantId, 1, true),
                      (theirs.participantId, 2, false)
                    )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The completed list's own repair: a match in the window it shows with no results is asked about,
     * and one whose engine has answered is not asked again for an hour. */
    private def finishedWithoutResults(mine: Participant, theirs: Participant): GameStatusResponse =
        GameStatusResponse(
          isCompleted = true,
          participants = List(mine, theirs).map(p =>
              EngineParticipantStatus(
                participantId = p.participantId.value,
                isPending = false,
                isCompleted = true,
                prevMoveAt = None
              )
          )
        )

    property("the completed list asks the engine for a finished match's missing results, and shows them") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- IO { engine.status = finishedWithoutResults(mine = mine, theirs = theirs) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    _ <- IO { engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    listed <- services.matches.completed(externalId).map(_.matches.toList)
                    recovered <- resultsOf(started)
                } yield recovered.map(r => (r.participantId, r.rank, r.isWinner)).toSet == Set(
                  (mine.participantId, 1, true),
                  (theirs.participantId, 2, false)
                ) && listed.filter(_.matchId == started.matchId).map(m => (m.outcome, m.resultSummary)) ==
                    List((Some(MatchOutcome.Won), Some("<strong>me</strong> won")))
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("a finished match whose engine answered with no results is not asked about again for an hour") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- IO { engine.status = finishedWithoutResults(mine = mine, theirs = theirs) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    // Answered, with nothing: recorded as asked.
                    _ <- services.matches.completed(externalId)
                    _ <- IO { engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    _ <- services.matches.completed(externalId)
                    within <- resultsOf(started)
                    // An hour on, it is asked again, and this time the engine has them.
                    _ <- TestSession.resource.use(
                      _.execute(
                        sql"""UPDATE match SET results_asked_at = results_asked_at - interval '61 minutes'
                              WHERE match_id = ${SkunkIdCodecs.matchId}""".command
                      )(started.matchId)
                    )
                    _ <- services.matches.completed(externalId)
                    later <- resultsOf(started)
                } yield within.isEmpty && later.map(_.participantId).toSet == Set(
                  mine.participantId,
                  theirs.participantId
                )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The stamp goes down before the engine is asked, so that an engine that is down holds up one load
    // of the list an hour rather than every one.
    property("a finished match whose engine could not be reached is not asked about again for an hour".tag(Quiet)) {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                @volatile var isDown = false
                val engine = StubEngine(beforeStatus =
                    IO.defer(if (isDown) IO.raiseError(GameEngineError("engine is down")) else IO.unit)
                )
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- IO { engine.status = finishedWithoutResults(mine = mine, theirs = theirs) }
                    _ <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    _ <- IO { isDown = true }
                    _ <- services.matches.completed(externalId)
                    // Back, with the results; but the match was asked about within the hour.
                    _ <- IO { isDown = false; engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    _ <- services.matches.completed(externalId)
                    after <- resultsOf(started)
                } yield after.isEmpty
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The active list's repair: a running match that may have ended without matchmaker hearing is asked about, at most
     * every five minutes, and one whose turn is somebody's and not yet run out is not asked about at all. */
    property("the active list asks the engine about a match waiting on nobody, and not again for five minutes") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val asked = java.util.concurrent.atomic.AtomicInteger(0)
                val engine = StubEngine(beforeStatus = IO(asked.incrementAndGet()).void)
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (_, started, _, _) = seated
                    _ <- services.matches.active(externalId)
                    _ <- services.matches.active(externalId)
                    within = asked.get
                    _ <- TestSession.resource.use(
                      _.execute(
                        sql"""UPDATE match SET results_asked_at = results_asked_at - interval '6 minutes'
                              WHERE match_id = ${SkunkIdCodecs.matchId}""".command
                      )(started.matchId)
                    )
                    listed <- services.matches.active(externalId)
                } yield within == 1 && asked.get == 2 && listed.map(_.matchId) == List(started.matchId)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("the active list does not ask the engine about a match whose turn is somebody's and not yet due") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val asked = java.util.concurrent.atomic.AtomicInteger(0)
                val engine = StubEngine(beforeStatus = IO(asked.incrementAndGet()).void)
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (_, started, _, theirs) = seated
                    _ <- TestSession.resource.use(
                      _.execute(
                        sql"""UPDATE participant SET is_pending = true, due = now() + interval '1 day'
                              WHERE participant_id = ${SkunkIdCodecs.participantId}""".command
                      )(theirs.participantId)
                    )
                    listed <- services.matches.active(externalId)
                } yield asked.get == 0 && listed.map(_.matchId) == List(started.matchId)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("the active list completes a match whose engine says it is over, with its results") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    seated <- twoSeats(
                      services = services,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId
                    )
                    (fixture, started, mine, theirs) = seated
                    _ <- IO { engine.status = statusWithResults(mine = mine, theirs = theirs) }
                    listed <- services.matches.active(externalId)
                    ended <- matchOf(fixture.game.gameId, started.matchId)
                    recorded <- resultsOf(started)
                } yield !listed.exists(_.matchId == started.matchId) && ended.exists(_.isCompleted) &&
                    recorded.map(_.participantId).toSet == Set(mine.participantId, theirs.participantId)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // A match with no time limit has no deadline to compute, whatever the engine reports as the
    // start of the turn — there is nothing for the player to run out of.
    property("refresh leaves the due date unset when the match has no time limit") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val engine = StubEngine()
            val services = TestServices.servicesWith(engine = engine)
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = challengeFor(fixture = fixture, timeLimit = None))
                participants <- participantsOf(started)
                seat = participants.head.participantId
                _ <- IO {
                    engine.status = GameStatusResponse(
                      isCompleted = false,
                      participants = List(
                        EngineParticipantStatus(
                          participantId = seat.value,
                          isPending = true,
                          isCompleted = false,
                          prevMoveAt = Some(Instant.parse("2031-02-03T04:05:00Z"))
                        )
                      )
                    )
                }
                _ <- services.engine.refresh(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  callerExternalId = externalId
                )
                after <- participantsOf(started)
            } yield after.head.isPending && after.head.due.isEmpty
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The complaint this was written for: a match began and the player due to move first was never
    // told. Whose turn it is is the engine's to decide and every participant is written with
    // `pending = false`, so without asking, the match would sit in nobody's "waiting on you" list
    // until somebody happened to press Refresh on it.
    /* A two-seat match with a turn clock, started and left with the challenger to move.
     *
     * Both the timeout properties below need the same thing: a real match with somebody's clock
     * running, so that the only question the test asks is what happens when it runs out. */
    private def startedWithClock(
        services: Services[String],
        engine: StubEngine,
        nickname: String,
        externalId: String,
        gameExternalId: String,
        otherExternalId: String,
        prevMoveAt: Instant
    ): IO[(Fixture, Match, Participant, Participant)] =
        for {
            fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
            found <- opponent(
              services = services,
              fixture = fixture,
              nickname = s"other-$nickname",
              externalId = otherExternalId
            )
            (other, otherCharacter) = found
            started <- begin(
              fixture = fixture,
              terms = challengeFor(fixture = fixture, timeLimit = Some(Duration.ofMinutes(10))),
              other = Some(found)
            )
            participants <- participantsOf(started)
            mine = participants.find(_.playerId == fixture.owner.playerId).get
            theirs = participants.find(_.playerId == other.playerId).get
            // The other player moved at `prevMoveAt`, so it is now the challenger's turn and their
            // deadline is that plus the match's ten minutes. Recorded through the move callback rather
            // than through a refresh, because a refresh is one of the two places the clock is
            // enforced — it would decide the very thing these tests are set up to ask.
            _ <- services.engine.recordMove(
              gameId = fixture.game.gameId,
              matchId = started.matchId,
              moved = theirs.participantId,
              next = List(mine.participantId),
              takenAt = prevMoveAt,
              // The move before, which for the first move of a match is the match's own start: this is
              // the ordinary turn-taking engine, spelling out what it used to leave to be inferred.
              startedAt = started.start.get,
              callerExternalId = gameExternalId
            )
            // What the engine will say when it is asked: the same thing, so that by default the
            // status call confirms the deadline rather than overturning it.
            _ <- IO {
                engine.status = GameStatusResponse(
                  isCompleted = false,
                  participants = List(
                    EngineParticipantStatus(
                      participantId = mine.participantId.value,
                      isPending = true,
                      isCompleted = false,
                      prevMoveAt = Some(prevMoveAt)
                    ),
                    EngineParticipantStatus(
                      participantId = theirs.participantId.value,
                      isPending = false,
                      isCompleted = false,
                      prevMoveAt = None
                    )
                  )
                )
            }
        } yield (fixture, started, mine, theirs)

    private def turnsOf(m: Match): IO[List[Turn]] =
        TestSession.resource.use(session => new TurnRepo(session).listForMatch(m.gameId, m.matchId))

    /** A begun match with two seats: the owner's, and another player's in the second role. */
    private def twoSeats(
        services: Services[String],
        nickname: String,
        externalId: String,
        gameExternalId: String,
        otherExternalId: String,
        challenge: Fixture => Terms = challengeFor(_)
    ): IO[(Fixture, Match, Participant, Participant)] =
        for {
            fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
            found <- opponent(
              services = services,
              fixture = fixture,
              nickname = s"other-$nickname",
              externalId = otherExternalId
            )
            (other, _) = found
            started <- begin(fixture = fixture, terms = challenge(fixture), other = Some(found))
            participants <- participantsOf(started)
            mine = participants.find(_.playerId == fixture.owner.playerId).get
            theirs = participants.find(_.playerId == other.playerId).get
        } yield (fixture, started, mine, theirs)

    /* A two-seat match under a clock of a stated kind, started at a stated time, with two moves
     * already played through the move callback.
     *
     * The two moves are the point: a total limit is only different from a per-turn one once
     * somebody has spent part of their budget, so a fixture with no history behind it cannot tell
     * the two apart. `firstMoveAt` is when the challenger moved and `secondMoveAt` when the other
     * player replied, after which it is the challenger's turn again and their deadline is whatever
     * the match's kind of limit makes it. */
    private def playedTwice(
        services: Services[String],
        engine: StubEngine,
        nickname: String,
        externalId: String,
        gameExternalId: String,
        otherExternalId: String,
        matchStart: Instant,
        timeLimit: Duration,
        kind: TimeLimitKind,
        firstMoveAt: Instant,
        secondMoveAt: Instant,
        // What each mover's clock started at. `None` is the turn-taking case — the match's start
        // for the first move and the move before for the second — and a simultaneous game states
        // the match's own start for both seats instead.
        startedAt: Option[Instant] = None
    ): IO[(Fixture, Match, Participant, Participant)] =
        for {
            seated <- twoSeats(
              services = services,
              nickname = nickname,
              externalId = externalId,
              gameExternalId = gameExternalId,
              otherExternalId = otherExternalId,
              challenge = fixture =>
                  challengeFor(
                    fixture = fixture,
                    timeLimit = Some(timeLimit),
                    timeLimitKind = kind,
                    start = Some(matchStart)
                  )
            )
            (fixture, started, mine, theirs) = seated
            // The challenger moves first, spending firstMoveAt - matchStart of their own budget...
            _ <- services.engine.recordMove(
              gameId = fixture.game.gameId,
              matchId = started.matchId,
              moved = mine.participantId,
              next = List(theirs.participantId),
              takenAt = firstMoveAt,
              startedAt = startedAt.getOrElse(matchStart),
              callerExternalId = gameExternalId
            )
            // ...and the other player replies, which puts the challenger back on the clock.
            _ <- services.engine.recordMove(
              gameId = fixture.game.gameId,
              matchId = started.matchId,
              moved = theirs.participantId,
              next = List(mine.participantId),
              takenAt = secondMoveAt,
              startedAt = startedAt.getOrElse(firstMoveAt),
              callerExternalId = gameExternalId
            )
            // What the engine confirms when asked: exactly this, so a recheck upholds the deadline
            // rather than overturning it.
            _ <- IO {
                engine.status = GameStatusResponse(
                  isCompleted = false,
                  participants = List(
                    EngineParticipantStatus(
                      participantId = mine.participantId.value,
                      isPending = true,
                      isCompleted = false,
                      prevMoveAt = Some(secondMoveAt)
                    ),
                    EngineParticipantStatus(
                      participantId = theirs.participantId.value,
                      isPending = false,
                      isCompleted = false,
                      prevMoveAt = None
                    )
                  )
                )
            }
        } yield (fixture, started, mine, theirs)

    private def resultsOf(m: Match): IO[List[model.Result]] =
        TestSession.resource.use { session =>
            val repo = new ResultRepo(session)
            new ParticipantRepo(session)
                .listForMatch(m.gameId, m.matchId)
                .flatMap(_.traverse((p, _, _) => repo.read(m.gameId, p.participantId)))
                .map(_.flatten)
        }

    // The whole point of the feature: a clock that ran out ends the match, the player who ran out
    // loses, and the other one wins by forfeit — recorded as such, so a completed-match list can
    // say which kind of win it was.
    property("a turn that has run out ends the match by forfeit") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    // An hour ago, against a ten-minute limit: fifty minutes over.
                    prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { long_ago =>
                        startedWithClock(
                          services = services,
                          engine = engine,
                          nickname = nickname,
                          externalId = externalId,
                          gameExternalId = gameExternalId,
                          otherExternalId = otherExternalId,
                          prevMoveAt = long_ago
                        )
                    }
                    (fixture, started, mine, theirs) = prepared
                    // Asked by the player who is *not* out of time — the clock is enforced for whoever
                    // looks, not only against the person who looks.
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = otherExternalId
                    )
                    results <- resultsOf(started)
                    loser = results.find(_.participantId == mine.participantId)
                    winner = results.find(_.participantId == theirs.participantId)
                    after <- participantsOf(started)
                } yield refreshed.isCompleted &&
                    results.forall(_.isForfeit) &&
                    loser.exists(r => !r.isWinner && r.rank == 2) &&
                    winner.exists(r => r.isWinner && r.rank == 1) &&
                    after.forall(p => p.isCompleted && !p.isPending && p.due.isEmpty)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The verification step: matchmaker's copy of whose turn it is arrives by callback, and a
    // callback can be lost. A turn that looks overdue here but that the engine says has been taken
    // must not cost anybody the match.
    property("a turn the engine says was taken is not forfeited, however overdue it looked") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { long_ago =>
                        startedWithClock(
                          services = services,
                          engine = engine,
                          nickname = nickname,
                          externalId = externalId,
                          gameExternalId = gameExternalId,
                          otherExternalId = otherExternalId,
                          prevMoveAt = long_ago
                        )
                    }
                    (fixture, started, mine, theirs) = prepared
                    // The move landed after all: it is now the other player's turn, and their clock has
                    // only just started.
                    justNow <- IO.realTimeInstant
                    _ <- IO {
                        engine.status = GameStatusResponse(
                          isCompleted = false,
                          participants = List(
                            EngineParticipantStatus(
                              participantId = mine.participantId.value,
                              isPending = false,
                              isCompleted = false,
                              prevMoveAt = None
                            ),
                            EngineParticipantStatus(
                              participantId = theirs.participantId.value,
                              isPending = true,
                              isCompleted = false,
                              prevMoveAt = Some(justNow)
                            )
                          )
                        )
                    }
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    results <- resultsOf(started)
                } yield !refreshed.isCompleted && results.isEmpty
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The engine is the only witness to whether a turn was actually taken, so a status call that
    // fails is not evidence that it was not: ending somebody's match on it would be ending it on
    // nothing. The player keeps a turn they may have run out of, which is the recoverable error of
    // the two — the next successful check settles it either way.
    property("a turn is not forfeited while the engine cannot be reached to confirm it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    prepared <- IO.realTimeInstant.map(_.minusSeconds(3600)).flatMap { longAgo =>
                        startedWithClock(
                          services = services,
                          engine = engine,
                          nickname = nickname,
                          externalId = externalId,
                          gameExternalId = gameExternalId,
                          otherExternalId = otherExternalId,
                          prevMoveAt = longAgo
                        )
                    }
                    (fixture, started, _, _) = prepared
                    // The status call now fails, with the deadline long past and matchmaker's own copy
                    // still saying it is the challenger's turn.
                    unreachable = TestServices.servicesWith(engine = new GameEngineClient {
                        def createGame(
                            gameUrl: String,
                            apiKey: Option[String],
                            request: CreateGameRequest
                        ): IO[CreateGameResponse] =
                            IO.raiseError(GameEngineError("engine is down"))
                        def status(
                            statusUrl: String,
                            apiKey: Option[String],
                            since: Option[Instant] = None
                        ): IO[GameStatusResponse] =
                            IO.raiseError(GameEngineError("engine is down"))
                    })
                    // `read` is the path that rechecks — `refresh` would fail on the status call itself.
                    seen <- unreachable.engine.read(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    results <- resultsOf(started)
                    after <- participantsOf(started)
                } yield !seen.isCompleted &&
                    results.isEmpty &&
                    after.exists(p => p.isPending && !p.isCompleted)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The turn table is what a total limit is charged against, so a move that is not recorded is
    // time nobody is billed for. Both moves of the fixture are checked, since the first one's cost
    // is measured from the match's start rather than from a turn before it.
    property("a move callback records the turn it reports, and what it cost") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    start <- IO.realTimeInstant.map(_.minusSeconds(3600))
                    first = start.plusSeconds(240)
                    second = first.plusSeconds(180)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (_, started, mine, theirs) = prepared
                    turns <- turnsOf(started)
                } yield turns.map(t => (t.participantId, t.takenAt, t.elapsed)) == List(
                  // Four minutes from the match's start, then three from the move before.
                  (mine.participantId, first, Duration.ofMinutes(4)),
                  (theirs.participantId, second, Duration.ofMinutes(3))
                )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The same two moves, from an engine that says when each mover's clock started.
     *
     * This is the shape a game with no turn order produces: both players were on the clock from
     * the match's start, so the second to move spent all seven minutes of it rather than the three
     * since the other player moved. Matchmaker cannot work that out — the move before is all it can
     * see — which is why the engine is able to say so. */
    property("a move callback that states when the mover's clock started is charged from that") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    start <- IO.realTimeInstant.map(_.minusSeconds(3600))
                    first = start.plusSeconds(240)
                    second = first.plusSeconds(180)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second,
                      startedAt = Some(start)
                    )
                    (_, started, mine, theirs) = prepared
                    turns <- turnsOf(started)
                } yield turns.map(t => (t.participantId, t.takenAt, t.elapsed)) == List(
                  // Four minutes for the first mover, as before — and seven for the second, who was
                  // thinking for all of them rather than for the three since the first mover finished.
                  (mine.participantId, first, Duration.ofMinutes(4)),
                  (theirs.participantId, second, Duration.ofMinutes(7))
                )
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // What a chess clock is: the deadline for the turn in front of a player is what is left of
    // their budget, so the four minutes they spent earlier are four minutes they do not get now.
    property("under a total limit a deadline is the budget less what that player has already spent") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    start <- IO.realTimeInstant.map(_.minusSeconds(3600))
                    first = start.plusSeconds(240)
                    second = first.plusSeconds(180)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (_, started, mine, theirs) = prepared
                    after <- participantsOf(started)
                    challenger = after.find(_.participantId == mine.participantId).get
                    opponent = after.find(_.participantId == theirs.participantId).get
                } yield challenger.isPending &&
                    // Ten minutes less the four already spent, from the moment this turn began.
                    challenger.due.contains(second.plus(Duration.ofMinutes(6))) &&
                    // Not on the clock, so no deadline at all — their three minutes are spent but only
                    // count against them once it is their move again.
                    opponent.due.isEmpty
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The same two moves under the other kind of limit, which is the control for the property
    // above: per turn, the four minutes already spent buy nothing and cost nothing.
    property("under a per-turn limit a deadline is the whole limit, whatever was spent before") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    start <- IO.realTimeInstant.map(_.minusSeconds(3600))
                    first = start.plusSeconds(240)
                    second = first.plusSeconds(180)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.PerTurn,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (_, started, mine, _) = prepared
                    after <- participantsOf(started)
                    challenger = after.find(_.participantId == mine.participantId).get
                } yield challenger.due.contains(second.plus(Duration.ofMinutes(10)))
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The decision the whole thing exists for: a player loses on time without any single turn
     * having been long.
     *
     * Nine of the ten minutes went on the first move, the reply took forty-nine, and the turn now
     * in front of the challenger has one minute on it — which ran out a minute ago. Under a
     * per-turn limit nothing here is overdue at all, which is the next property. */
    property("a player whose total budget runs out forfeits, though no single turn was long") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    start = now.minusSeconds(3600)
                    first = start.plusSeconds(540)
                    second = now.minusSeconds(120)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (fixture, started, mine, theirs) = prepared
                    // Asked by the player who is not out of time, as a real opponent would.
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = otherExternalId
                    )
                    results <- resultsOf(started)
                } yield refreshed.isCompleted &&
                    results.forall(_.isForfeit) &&
                    results.find(_.participantId == mine.participantId).exists(r => !r.isWinner && r.rank == 2) &&
                    results.find(_.participantId == theirs.participantId).exists(r => r.isWinner && r.rank == 1)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("the same match under a per-turn limit is not forfeited at all") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    start = now.minusSeconds(3600)
                    first = start.plusSeconds(540)
                    second = now.minusSeconds(120)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.PerTurn,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (fixture, started, _, _) = prepared
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = otherExternalId
                    )
                    results <- resultsOf(started)
                } yield !refreshed.isCompleted && results.isEmpty
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The repair path: a move whose callback never arrived is recovered from the status call, with
    // its cost, rather than only as a corrected deadline. Without the turn the engine reports here,
    // the challenger's budget would look untouched and they would get the whole ten minutes.
    property("turns the engine reports on a status call are recorded, and are charged for") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    // Counted back from now, so that the deadline the last of these moves implies is
                    // still ahead of us: this property is about what was recorded and charged, and a
                    // fixture that had already run out of time would be answered by the forfeit instead.
                    now <- IO.realTimeInstant
                    fourth = now.minusSeconds(10)
                    third = fourth.minusSeconds(10)
                    second = third.minusSeconds(300)
                    first = second.minusSeconds(180)
                    start = first.minusSeconds(240)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (fixture, started, mine, theirs) = prepared
                    // A third and fourth move happened that matchmaker never heard about: the challenger
                    // spent five more minutes, and the opponent replied at once.
                    _ <- IO {
                        engine.status = GameStatusResponse(
                          isCompleted = false,
                          participants = List(
                            EngineParticipantStatus(
                              participantId = mine.participantId.value,
                              isPending = true,
                              isCompleted = false,
                              prevMoveAt = Some(fourth)
                            ),
                            EngineParticipantStatus(
                              participantId = theirs.participantId.value,
                              isPending = false,
                              isCompleted = false,
                              prevMoveAt = None
                            )
                          ),
                          turns = List(
                            EngineTurn(
                              participantId = mine.participantId.value,
                              takenAt = third,
                              startedAt = Some(second)
                            ),
                            EngineTurn(
                              participantId = theirs.participantId.value,
                              takenAt = fourth,
                              startedAt = Some(third)
                            )
                          )
                        )
                    }
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = otherExternalId
                    )
                    turns <- turnsOf(started)
                    after <- participantsOf(started)
                    challenger = after.find(_.participantId == mine.participantId).get
                } yield !refreshed.isCompleted &&
                    turns.map(_.takenAt) == List(first, second, third, fourth) &&
                    // Four minutes and then five: one minute of the ten is left.
                    challenger.due.contains(fourth.plus(Duration.ofMinutes(1)))
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The "Current Matches" list is read by somebody who cannot move in the match: what it owes
    // them is who it is waiting for and how long that player has, neither of which is derivable
    // from their own seat's row.
    property("an active match names whose turn it is, and when that turn runs out") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    second = now.minusSeconds(60)
                    first = second.minusSeconds(180)
                    start = first.minusSeconds(240)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (fixture, started, mine, _) = prepared
                    // Asked by the opponent, who has just moved and is waiting: it is the challenger's
                    // turn, and their deadline is the six minutes left of their budget from `second`.
                    theirs <- services.matches.active(otherExternalId)
                    row = theirs.find(_.matchId == started.matchId).get
                    // And by the challenger, whose own row says the same thing about the same turn — the
                    // seat it is read from differs, the answer does not.
                    ours <- services.matches.active(externalId)
                    mineRow = ours.find(_.matchId == started.matchId).get
                    participants <- participantsOf(started)
                    challengerDue = participants.find(_.participantId == mine.participantId).get.due
                } yield row.whoseTurn == Seq(nickname) &&
                    row.turnDue == challengerDue &&
                    row.turnDue.contains(second.plus(Duration.ofMinutes(6))) &&
                    // Not this caller's turn, so their own deadline is empty while the match's is not.
                    row.due.isEmpty && !row.isPending &&
                    mineRow.whoseTurn == row.whoseTurn && mineRow.turnDue == row.turnDue
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The chess clock made visible: what each player has left to last the rest of the match on,
    // which the turn's deadline does not say and cannot be worked out from.
    property("an active match under a total limit carries every player's remaining time") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    second = now.minusSeconds(60)
                    first = second.minusSeconds(180)
                    start = first.minusSeconds(240)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (_, started, mine, _) = prepared
                    rows <- services.matches.active(otherExternalId)
                    row = rows.find(_.matchId == started.matchId).get
                    byName = row.clocks.map(c => c.nickname -> c).toMap
                    participants <- participantsOf(started)
                    challengerDue = participants.find(_.participantId == mine.participantId).get.due
                } yield row.clocks.sizeIs == 2 &&
                    // The challenger spent four minutes on the opening and is on the clock now, so their
                    // balance is six and it comes with the deadline it is being spent against.
                    byName
                        .get(nickname)
                        .exists(c => c.remaining == Duration.ofMinutes(6) && c.deadline == challengerDue) &&
                    // The opponent took three minutes and is waiting: seven left, and no deadline, since
                    // nothing is being spent while it is not their move.
                    byName
                        .get(s"other-$nickname")
                        .exists(c => c.remaining == Duration.ofMinutes(7) && c.deadline.isEmpty)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // A per-turn limit hands the whole limit back on every move, so there is no balance to run
    // down and nothing for a reader to draw.
    property("a match under a per-turn limit carries no clocks at all") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    second = now.minusSeconds(60)
                    first = second.minusSeconds(180)
                    start = first.minusSeconds(240)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.PerTurn,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (_, started, _, _) = prepared
                    rows <- services.matches.active(otherExternalId)
                } yield rows.find(_.matchId == started.matchId).exists(_.clocks.isEmpty)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // What the completed list says about a match once it is over: the clocks are gone (nobody can
    // spend them now), and what is left is what each player spent getting there.
    property("a finished match reports how long each player spent over their turns") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    now <- IO.realTimeInstant
                    second = now.minusSeconds(60)
                    first = second.minusSeconds(180)
                    start = first.minusSeconds(240)
                    prepared <- playedTwice(
                      services = services,
                      engine = engine,
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId,
                      otherExternalId = otherExternalId,
                      matchStart = start,
                      timeLimit = Duration.ofMinutes(10),
                      kind = TimeLimitKind.Total,
                      firstMoveAt = first,
                      secondMoveAt = second
                    )
                    (fixture, started, mine, theirs) = prepared
                    // The engine ends it, the ordinary way a match finishes.
                    _ <- services.engine.recordResults(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      results = List(
                        ReportedResult(
                          participantId = mine.participantId,
                          rank = 1,
                          scores = Map.empty,
                          isWinner = true
                        ),
                        ReportedResult(
                          participantId = theirs.participantId,
                          rank = 2,
                          scores = Map.empty,
                          isWinner = false
                        )
                      ),
                      callerExternalId = gameExternalId
                    )
                    rows <- services.matches.results(externalId)
                    mineRow = rows.find(_.participantId == mine.participantId).get
                    theirRow = rows.find(_.participantId == theirs.participantId).get
                } yield mineRow.timeTaken == Duration.ofMinutes(4) &&
                    theirRow.timeTaken == Duration.ofMinutes(3)
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The clock a challenge's match is created under: its round duration, spent as the game spends
     * time. This game is one of turns, so each of the two players has a chess clock of half of it. Read
     * both on the match row and on the summary the lists are drawn from, since they are read back by
     * different queries. */
    property("a challenge's match is created under the clock its round duration gives it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val services = TestServices.servicesWith(engine = StubEngine())
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      roundDuration = Some(Duration.ofHours(2))
                    )
                    (started, _) = made
                    listed <- services.matches.active(externalId)
                    row = listed.find(_.matchId == started.matchId).get
                } yield started.timeLimit.contains(Duration.ofHours(1)) &&
                    started.timeLimitKind == TimeLimitKind.Total &&
                    row.timeLimit.contains(Duration.ofHours(1)) &&
                    row.timeLimitKind == TimeLimitKind.Total
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a match records its first turn as it is created, so the player who moves first is told straight away") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = new FirstTurnEngine
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(services = services, fixture = fixture, otherExternalId = otherExternalId)
                    (started, other) = made
                    participants <- participantsOf(started)
                    // The lists the main page's "waiting on you" is drawn from, asked for with no refresh in
                    // between — this is the state a player finds when the page loads after a start.
                    mine <- services.matches.due(externalId)
                    theirs <- services.matches.due(otherExternalId)
                } yield participants.count(_.isPending) == 1 &&
                    (mine ++ theirs).map(_.matchId) == List(started.matchId)
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    // The match is created by the time the engine is asked whose turn it is, so an engine that cannot answer must
    // not turn a created match into a failure: it leaves the seats as they were written, which `refresh` corrects.
    property("a match is still created when the engine will not say whose turn it is") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = new GameEngineClient {
                    def createGame(
                        gameUrl: String,
                        apiKey: Option[String],
                        request: CreateGameRequest
                    ): IO[CreateGameResponse] =
                        IO.pure(
                          CreateGameResponse(
                            statusUrl = "https://engine/status/1",
                            playUrl = "https://engine/play/1",
                            publicUrl = None
                          )
                        )
                    def status(
                        statusUrl: String,
                        apiKey: Option[String],
                        since: Option[Instant] = None
                    ): IO[GameStatusResponse] =
                        IO.raiseError(GameEngineError("status is down"))
                }
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(services = services, fixture = fixture, otherExternalId = otherExternalId)
                    (started, _) = made
                    participants <- participantsOf(started)
                } yield started.playUrl.contains("https://engine/play/1") && participants.forall(!_.isPending)
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    private def liveChallenge(fixture: Fixture): Terms =
        challengeFor(
          fixture = fixture,
          timeLimit = Some(Duration.ofSeconds(30)),
          timeLimitUnit = TimeLimitUnit.Seconds,
          live = true
        )

    property("a live challenge's match is live, and the engine is told so with its clock") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services =
                    TestServices.servicesWith(engine = engine, callbackBaseUrl = Some("https://matchmaker.example.com"))
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      roundDuration = Some(Duration.ofMinutes(1)),
                      live = true
                    )
                    (started, _) = made
                } yield {
                    val request = engine.lastRequest.get
                    // A game of turns: each of the two players has half the minute, as a chess clock.
                    started.isLive &&
                    request.live.contains(LiveTerms(30L, "TOTAL")) &&
                    // Still sent: the engine sends no moves to it, but boxing finds matchmaker by it.
                    request.moveCallbackUrl.isDefined && request.resultsCallbackUrl.isDefined
                }
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a match that is not live does not tell the engine it is") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                val engine = StubEngine()
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      roundDuration = Some(Duration.ofMinutes(10))
                    )
                    (started, _) = made
                } yield !started.isLive && engine.lastRequest.exists(_.live.isEmpty)
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a live challenge needs a time to play") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                noLimit <- Challenges
                    .create(
                      tournaments = services.tournaments,
                      game = fixture.game,
                      owner = fixture.owner,
                      characterId = Some(fixture.character.characterId),
                      isLive = true
                    )
                    .attempt
            } yield noLimit.left.exists(_.isInstanceOf[ValidationError])
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // The engine runs a live match's turns and says nothing about them, so a seat matchmaker marked
    // pending would stay pending -- and on the "waiting on you" list -- for the rest of the match.
    property("matchmaker keeps no turn of a live match, and enforces no clock on it") {
        forAll(genUniqueString, genUniqueString, genUniqueString, genUniqueString) {
            (nickname, externalId, gameExternalId, otherExternalId) =>
                // Names the first seat as the one to move, which an ordinary match would record.
                val engine = new FirstTurnEngine
                val services = TestServices.servicesWith(engine = engine)
                val result = for {
                    fixture <- makeFixture(
                      nickname = nickname,
                      externalId = externalId,
                      gameExternalId = gameExternalId
                    )
                    made <- challenged(
                      services = services,
                      fixture = fixture,
                      otherExternalId = otherExternalId,
                      roundDuration = Some(Duration.ofMinutes(1)),
                      live = true
                    )
                    (started, _) = made
                    refreshed <- services.engine.refresh(
                      gameId = fixture.game.gameId,
                      matchId = started.matchId,
                      callerExternalId = externalId
                    )
                    participants <- participantsOf(started)
                    due <- services.matches.due(externalId)
                    active <- services.matches.active(externalId)
                } yield !refreshed.isCompleted &&
                    participants.forall(p => !p.isPending && p.due.isEmpty) &&
                    due.isEmpty &&
                    active.find(_.matchId == started.matchId).exists(_.isLive)
                result.timeout(30.seconds).unsafeRunSync()
        }
    }

    property("a live match the engine ends by forfeit is recorded as a forfeit") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val services = TestServices.servicesWith(engine = StubEngine())
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = liveChallenge(fixture))
                participants <- participantsOf(started)
                seat = participants.head.participantId
                reported = ReportedResult(
                  participantId = seat,
                  rank = 2,
                  scores = Map.empty,
                  isWinner = false,
                  isForfeit = true
                )
                _ <- services.engine.recordResults(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  results = List(reported),
                  callerExternalId = gameExternalId
                )
                results <- resultsOf(started)
                reread <- matchOf(fixture.game.gameId, started.matchId)
            } yield results.exists(r => r.participantId == seat && r.isForfeit && !r.isWinner) &&
                reread.exists(_.isCompleted)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }

    // A live match's seats are never pending, so the status call is the only thing that can retire them
    // when the engine's results callback was lost -- and once the match is complete, nothing asks again.
    property("a live match that Refresh finds over has every seat retired, and leaves the active list") {
        forAll(genUniqueString, genUniqueString, genUniqueString) { (nickname, externalId, gameExternalId) =>
            val engine = StubEngine()
            val services = TestServices.servicesWith(engine = engine)
            val result = for {
                fixture <- makeFixture(nickname = nickname, externalId = externalId, gameExternalId = gameExternalId)
                started <- begin(fixture = fixture, terms = liveChallenge(fixture))
                _ <- IO { engine.status = GameStatusResponse(isCompleted = true, participants = Nil) }
                refreshed <- services.engine.refresh(
                  gameId = fixture.game.gameId,
                  matchId = started.matchId,
                  callerExternalId = externalId
                )
                participants <- participantsOf(started)
                active <- services.matches.active(externalId)
            } yield refreshed.isCompleted &&
                participants.nonEmpty &&
                participants.forall(p => p.isCompleted && !p.isPending && p.due.isEmpty) &&
                !active.exists(_.matchId == started.matchId)
            result.timeout(15.seconds).unsafeRunSync()
        }
    }
}
