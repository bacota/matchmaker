package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import org.scalacheck.Prop._
import org.scalacheck.Gen
import java.time.{Duration, Instant}
import skunk.implicits._
import skunk.codec.all.{int4, int8, text}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.TestMatches
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    Generators,
    GameRepo,
    MatchRepo,
    ParticipantRepo,
    PlayerRepo,
    ResultRepo,
    SkunkCodecs,
    TestSession,
    TournamentRepo
}

class MatchServiceSpec extends PropertySuite {
    TestMigration.ensure()

    private val matchService = TestServices.services.matches
    private val registrationService = TestServices.services.registration
    private val characterService = TestServices.services.characters

    private def genUniqueString: Gen[String] =
        Gen.choose(24, 40)
            .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))
            .map(s => s"$s-${java.util.UUID.randomUUID()}")

    /** Registers a player and puts them in one match, with control over the two flags the lists discriminate on:
      * whether the match is finished, and whether it is this player's turn.
      */
    private def makeMatch(
        nickname: String,
        externalId: String,
        mid: MatchId,
        completed: Boolean,
        pending: Boolean
    ): IO[(Player, Game, MatchId)] =
        TestSession.resource.use { session =>
            for {
                prepared <- setup(session, nickname, externalId)
                (player, game, character) = prepared
                matchId <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  mid,
                  Option.when(completed)(Instant.ofEpochSecond(3000)),
                  pending
                )
            } yield (player, game, matchId)
        }

    /** A registered player with a game to play and a character to play it with — everything a match needs except the
      * match.
      */
    private def setup(
        session: skunk.Session[IO],
        nickname: String,
        externalId: String,
        parameters: Seq[GameParameter[String]] = Seq.empty
    ): IO[(Player, Game, Character[String])] =
        for {
            player <- registrationService.register(nickname, externalId)
            game <- new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Character,
                "game",
                "game",
                "description",
                "url",
                active = true,
                // One role, because every participant names one.
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "only", roleType = RoleType.Required, displayName = "only")
                ),
                parameters,
                genUniqueString.sample.get
              )
            )
            character <- new CharacterRepo[String](session).create(
              Character(CharacterId(0), game.gameId, "character", "description", "", Some(player.playerId))
            )
        } yield (player, game, character)

    /** One more match for a player who already has a game and a character, so that a test about the order of a list can
      * put two of them in it. The player owns its tournament unless `tournamentOwner` says otherwise.
      */
    private def addMatch(
        session: skunk.Session[IO],
        player: Player,
        game: Game,
        character: Character[String],
        mid: MatchId,
        completedAt: Option[Instant],
        pending: Boolean,
        /* Whether the match may be looked at by anybody, which is what the lists on another player's
         * page select on. Defaulted false, as the column is, so the tests that predate it read the
         * same. */
        isPublic: Boolean = false,
        /* What the engine answered with when it created the game, for the tests about the Watch link.
         * Null in the database for a match that is not public, which is the engine's own rule. */
        publicUrl: Option[String] = None,
        /* Whether this player's own seat is finished, which is normally whether the match is -- but
         * not always: a player can be out of a match that is still being played, and the lists on
         * their page split on the seat. */
        seatCompleted: Option[Boolean] = None,
        /* The parameter choices, carried by the match from its tournament. */
        settings: String = "{}",
        /* Who owns the match's tournament, when it is not the player seated in it. */
        tournamentOwner: Option[PlayerId] = None
    ): IO[MatchId] =
        for {
            matchId <- IO.pure(mid)
            made = Match(
              game.gameId,
              matchId,
              TournamentId.unassigned,
              "description",
              completedAt,
              Some(Instant.ofEpochSecond(1000)),
              None,
              settings,
              isPublic = isPublic,
              publicUrl = publicUrl
            )
            _ <- TestMatches.writeMatch(session, made, tournamentOwner.getOrElse(player.playerId))
            _ <- TestMatches
                .writeSeat(
                  session,
                  CharacterParticipant(
                    ParticipantId(0),
                    game.gameId,
                    matchId,
                    player.playerId,
                    pending,
                    seatCompleted.getOrElse(completedAt.isDefined),
                    Some(Instant.ofEpochSecond(2000)),
                    character.characterId,
                    game.roles.head.gameRoleId
                  ),
                  EloRating.initial
                )
        } yield matchId

    // ---------------------------------------------------------------------------
    // Completed lists, a window of time at a time
    // ---------------------------------------------------------------------------

    /** Sets when the player's seat in a match was finished with — the column the windows are drawn on, which an
      * ordinary finish stamps with the database's now.
      */
    private def finishedAt(session: skunk.Session[IO], gameId: GameId, matchId: MatchId, at: Instant): IO[Unit] =
        session
            .execute(
              sql"""UPDATE participant SET completed_at = ${SkunkCodecs.instant}
                WHERE game_id = $int4 AND match_id = $int8""".command
            )((at, gameId.value, matchId.value))
            .void

    /** A finished match for the player, finished at `at`. */
    private def finished(
        session: skunk.Session[IO],
        player: Player,
        game: Game,
        character: Character[String],
        at: Instant,
        isPublic: Boolean = false
    ): IO[MatchId] =
        for {
            id <- addMatch(session, player, game, character, Generators.newMatchId(), Some(at), false, isPublic)
            _ <- finishedAt(session, game.gameId, id, at)
        } yield id

    /** A match the player called off at `at`: the match cancelled and its seats retired, as `MatchService.cancel` does.
      */
    private def calledOff(
        session: skunk.Session[IO],
        player: Player,
        game: Game,
        character: Character[String],
        at: Instant,
        isPublic: Boolean = false
    ): IO[MatchId] =
        for {
            id <- addMatch(session, player, game, character, Generators.newMatchId(), None, true, isPublic)
            m <- new MatchRepo(session).read(game.gameId, id).map(_.get)
            _ <- new MatchRepo(session).update(m.copy(cancelled = true))
            _ <- new ParticipantRepo(session).completeForMatch(game.gameId, id)
            _ <- finishedAt(session, game.gameId, id, at)
        } yield id

    /** Another game, and a character in it, for a player who already has one. */
    private def anotherGame(session: skunk.Session[IO], player: Player): IO[(Game, Character[String])] =
        for {
            game <- new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Character,
                "other",
                "other",
                "description",
                "url",
                active = true,
                Seq(
                  GameRole(GameRoleId(0), GameId.unassigned, "only", roleType = RoleType.Required, displayName = "only")
                ),
                Seq.empty,
                genUniqueString.sample.get
              )
            )
            character <- new CharacterRepo[String](session).create(
              Character(CharacterId(0), game.gameId, "character", "description", "", Some(player.playerId))
            )
        } yield (game, character)

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    test("a completed list shows the last 24 hours, and each Next the day before, until the oldest match is shown") {
        val now = Instant.now()
        val unique = genUniqueString.sample.get
        val (externalId, recent, yesterday, lastWeek) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                recent <- finished(session, player, game, character, now.minus(Duration.ofHours(2)))
                yesterday <- finished(session, player, game, character, now.minus(Duration.ofHours(30)))
                lastWeek <- finished(session, player, game, character, now.minus(Duration.ofHours(9 * 24 + 12)))
                // Called off an hour ago: never in a completed list, whatever the window.
                _ <- calledOff(session, player, game, character, now.minus(Duration.ofHours(1)))
            } yield (unique, recent, yesterday, lastWeek)
        })

        // The default: the last day, and something older to go back to.
        val first = run(matchService.completed(externalId))
        assertEquals(first.frame, CompletedFrame.Day)
        assertEquals(first.matches.map(_.matchId), List(recent))
        assert(first.hasOlder)
        // The day before, measured from the same moment the first window was.
        val second = run(matchService.completed(externalId, CompletedQuery(page = 1, asOf = Some(first.asOf))))
        assertEquals(second.matches.map(_.matchId), List(yesterday))
        assertEquals(second.until, first.from)
        assert(second.hasOlder)
        // Back to the most recent window from the same moment: the same window, which ends at that
        // moment. Pinned three hours back, the match finished two hours ago is after it, so is not in it.
        val again = run(matchService.completed(externalId, CompletedQuery(asOf = Some(first.asOf))))
        assertEquals(again.matches.map(_.matchId), List(recent))
        assertEquals(again.until, first.asOf)
        val earlier = first.asOf.minus(Duration.ofHours(3))
        val pinned = run(matchService.completed(externalId, CompletedQuery(asOf = Some(earlier))))
        assertEquals(pinned.matches, Nil)
        assertEquals(pinned.until, earlier)

        // A week at a time: the first holds both recent ones, the second the oldest -- and with the
        // oldest match on screen there is nothing further back.
        val week = run(matchService.completed(externalId, CompletedQuery(frame = CompletedFrame.Week)))
        assertEquals(week.matches.map(_.matchId), List(recent, yesterday))
        assert(week.hasOlder)
        val older = run(
          matchService.completed(externalId, CompletedQuery(CompletedFrame.Week, page = 1, asOf = Some(week.asOf)))
        )
        assertEquals(older.matches.map(_.matchId), List(lastWeek))
        assert(!older.hasOlder)
        assertEquals(older.frame.span, Duration.ofDays(7))
    }

    test("the oldest match a list can show is the oldest in that list: the game's own, on a game's screen") {
        val now = Instant.now()
        val unique = genUniqueString.sample.get
        val (externalId, game, other, here) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                here <- finished(session, player, game, character, now.minus(Duration.ofHours(2)))
                elsewhere <- anotherGame(session, player)
                (other, otherCharacter) = elsewhere
                _ <- finished(session, player, other, otherCharacter, now.minus(Duration.ofDays(20)))
            } yield (unique, game, other, here)
        })

        // Every game: the other game's match is older, so there is a Next.
        val everything = run(matchService.completed(externalId))
        assertEquals(everything.matches.map(_.matchId), List(here))
        assert(everything.hasOlder)
        // This game alone: nothing older, so none.
        val thisGame = run(matchService.completed(externalId, CompletedQuery(gameId = Some(game.gameId))))
        assertEquals(thisGame.matches.map(_.matchId), List(here))
        assert(!thisGame.hasOlder)
        // The other game: nothing today, but something to go back to.
        val otherGame = run(matchService.completed(externalId, CompletedQuery(gameId = Some(other.gameId))))
        assertEquals(otherGame.matches, Nil)
        assert(otherGame.hasOlder)
    }

    test("another player's completed list holds their public matches only, and never a cancelled one") {
        val now = Instant.now()
        val unique = genUniqueString.sample.get
        val watcher = s"w-$unique"
        val (player, game, shown) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                shown <- finished(session, player, game, character, now.minus(Duration.ofHours(2)), isPublic = true)
                _ <- finished(session, player, game, character, now.minus(Duration.ofHours(3)))
                _ <- calledOff(session, player, game, character, now.minus(Duration.ofHours(1)), isPublic = true)
                _ <- registrationService.register(watcher, watcher)
            } yield (player, game, shown)
        })

        val page =
            run(matchService.publicCompleted(watcher, player.playerId, CompletedQuery(gameId = Some(game.gameId))))
        assertEquals(page.matches.map(_.matchId), List(shown))
        assert(!page.hasOlder)
    }

    test("a finished match names whom each player played, says which of them won, and what it did to each rating") {
        val now = Instant.now()
        val unique = genUniqueString.sample.get
        val rival = s"r-$unique"
        val (game, matchId) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                matchId <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  Generators.newMatchId(),
                  Some(now),
                  pending = false
                )
                other <- registrationService.register(rival, rival)
                theirs <- new CharacterRepo[String](session).create(
                  Character(CharacterId(0), game.gameId, "rival", "description", "", Some(other.playerId))
                )
                _ <- TestMatches.writeSeat(
                  session,
                  CharacterParticipant(
                    ParticipantId(0),
                    game.gameId,
                    matchId,
                    other.playerId,
                    false,
                    true,
                    None,
                    theirs.characterId,
                    game.roles.head.gameRoleId
                  ),
                  EloRating.initial
                )
                _ <- finishedAt(session, game.gameId, matchId, now.minus(Duration.ofMinutes(5)))
                seats <- new ParticipantRepo(session).listForMatch(game.gameId, matchId)
                results = new ResultRepo(session)
                _ <- seats.traverse_ { case (seat, _, _) =>
                    val won = seat.playerId == player.playerId
                    results.create(
                      com.vivi.matchmaker.model
                          .Result(
                            game.gameId,
                            seat.participantId,
                            rank = if (won) 1 else 2,
                            Map.empty,
                            isWinner = won,
                            eloDelta = Some(if (won) 16 else -16)
                          )
                    )
                }
            } yield (game, matchId)
        })

        def summary(externalId: String) =
            run(matchService.completed(externalId, CompletedQuery(gameId = Some(game.gameId)))).matches
                .find(_.matchId == matchId)
                .map(s => (s.opponents.flatMap(_.player.map(_.nickname)), s.outcome, s.eloDelta))

        assertEquals(summary(unique), Some((Seq(rival), Some(MatchOutcome.Won), Some(16))))
        assertEquals(summary(rival), Some((Seq(unique), Some(MatchOutcome.Lost), Some(-16))))
    }

    test("a character's page lists its public matches, naming each opposing character and who owned it then") {
        val unique = genUniqueString.sample.get
        val rival = s"r-$unique"
        val buyer = s"b-$unique"
        val (player, game, character, theirs, other, running, done) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                running <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  Generators.newMatchId(),
                  None,
                  true,
                  isPublic = true
                )
                done <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  Generators.newMatchId(),
                  Some(Instant.now()),
                  false,
                  true
                )
                // Private: not on anybody's page but the players' own.
                _ <- addMatch(session, player, game, character, Generators.newMatchId(), None, pending = true)
                other <- registrationService.register(rival, rival)
                theirs <- new CharacterRepo[String](session).create(
                  Character(CharacterId(0), game.gameId, "rival", "description", "", Some(other.playerId))
                )
                _ <- List(running, done).traverse_ { matchId =>
                    TestMatches.writeSeat(
                      session,
                      CharacterParticipant(
                        ParticipantId(0),
                        game.gameId,
                        matchId,
                        other.playerId,
                        false,
                        matchId == done,
                        None,
                        theirs.characterId,
                        game.roles.head.gameRoleId
                      ),
                      EloRating.initial
                    )
                }
                // The rival's character changes hands afterwards: the matches were still played by `other`.
                newOwner <- registrationService.register(buyer, buyer)
                _ <- new CharacterRepo[String](session).update(theirs.copy(playerId = Some(newOwner.playerId)))
            } yield (player, game, character, theirs, other, running, done)
        })
        val mine = CharacterName(character.characterId, game.gameId, "character")
        val against =
            Opponent(
              Some(PublicPlayer(other.playerId, rival)),
              Some(CharacterName(theirs.characterId, game.gameId, "rival"))
            )

        val active = run(matchService.characterActive(buyer, game.gameId, character.characterId))
        assertEquals(active.map(_.matchId), List(running))
        assertEquals(active.map(_.opponents), List(Seq(against)))
        assertEquals(active.map(_.character), List(Some(mine)))

        val over = run(matchService.characterCompleted(buyer, game.gameId, character.characterId, CompletedQuery()))
        assertEquals(over.matches.map(_.matchId), List(done))
        assertEquals(over.matches.map(_.opponents), List(Seq(against)))

        // From the rival's side the same match, with this player's character as the opponent.
        val seen = run(matchService.characterActive(buyer, game.gameId, theirs.characterId))
        assertEquals(seen.flatMap(_.opponents), List(Opponent(Some(PublicPlayer(player.playerId, unique)), Some(mine))))

        // And the page's heading names the owner it has now.
        val profile = run(characterService.profile(game.gameId, theirs.characterId, buyer))
        assertEquals(profile.owner.map(_.nickname), Some(buyer))
        assertEquals(profile.description, "description")
        intercept[NotFoundError](
          run(characterService.profile(GameId(game.gameId.value + 1), theirs.characterId, buyer))
        )

        // Its record counts the finished match once it has results, and only once it is not friendly -- a forfeit
        // win for this player's character, a forfeit loss for the rival's.
        def record(of: CharacterId) = run(characterService.profile(game.gameId, of, buyer)).record
        run(TestSession.resource.use { session =>
            new ParticipantRepo(session)
                .listForMatch(game.gameId, done)
                .flatMap(_.traverse_ { case (seat, _, _) =>
                    val won = seat.playerId == player.playerId
                    new ResultRepo(session).create(
                      com.vivi.matchmaker.model.Result(
                        game.gameId,
                        seat.participantId,
                        rank = if (won) 1 else 2,
                        Map.empty,
                        isWinner = won,
                        forfeit = true
                      )
                    )
                })
        })
        assertEquals(record(character.characterId), MatchRecord())
        run(TestSession.resource.use { session =>
            session
                .execute(sql"UPDATE match SET friendly = false WHERE game_id = $int4 AND match_id = $int8".command)(
                  (game.gameId.value, done.value)
                )
                .void
        })
        assertEquals(record(character.characterId), MatchRecord(wins = 1, forfeitWins = 1))
        assertEquals(record(theirs.characterId), MatchRecord(losses = 1, forfeitLosses = 1))
    }

    test("a seat nobody played, its character having no owner, is still listed, as its character alone") {
        val unique = genUniqueString.sample.get
        val (game, matchId, ownerless) = run(TestSession.resource.use { session =>
            for {
                prepared <- setup(session, unique, unique)
                (player, game, character) = prepared
                matchId <- addMatch(session, player, game, character, Generators.newMatchId(), None, pending = true)
                ownerless <- new CharacterRepo[String](session).create(
                  Character(CharacterId(0), game.gameId, "ownerless", "description", "", None)
                )
                seat <- TestMatches.writeSeat(
                  session,
                  CharacterParticipant(
                    ParticipantId(0),
                    game.gameId,
                    matchId,
                    player.playerId,
                    false,
                    false,
                    None,
                    ownerless.characterId,
                    game.roles.head.gameRoleId
                  ),
                  EloRating.initial
                )
                // Nobody plays it: written as a round writes a seat whose character has no owner.
                _ <- session.execute(sql"UPDATE participant SET player_id = NULL WHERE participant_id = $int8".command)(
                  seat.participantId.value
                )
            } yield (game, matchId, ownerless)
        })
        val listed = run(matchService.active(unique)).filter(_.matchId == matchId)
        assertEquals(
          listed.map(_.opponents),
          List(Seq(Opponent(None, Some(CharacterName(ownerless.characterId, game.gameId, "ownerless")))))
        )
    }

    /* Another player's page: the two lists a stranger is shown, which are the same two lists the
     * caller sees of their own matches minus every match that was not offered as public. The rule is
     * in the query, so what these check is that the query is the one being used. */
    property("publicActive shows a public match and hides a private one") {
        forAll(genUniqueString, genUniqueString, genUniqueString, Generators.genMatchId, Generators.genMatchId) {
            (nickname, externalId, watcherId, openId, hiddenId) =>
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId)
                        (player, game, character) = prepared
                        open <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          openId,
                          None,
                          pending = true,
                          isPublic = true
                        )
                        _ <- addMatch(session, player, game, character, hiddenId, None, pending = true)
                        // A second player, because this list is read by somebody who is not in the match.
                        _ <- registrationService.register(watcherId, watcherId)
                        seen <- matchService.publicActive(watcherId, player.playerId)
                        // And the player's own list is unchanged by any of it: they see both.
                        mine <- matchService.active(externalId)
                    } yield seen.map(_.matchId) == List(open) &&
                        mine.map(_.matchId).toSet == Set(open, hiddenId)
                }
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property(
      "the public lists split a player's matches into running and finished, as the caller's own lists are split"
    ) {
        forAll(genUniqueString, genUniqueString, genUniqueString, Generators.genMatchId, Generators.genMatchId) {
            (nickname, externalId, watcherId, runningId, finishedId) =>
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId)
                        (player, game, character) = prepared
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          runningId,
                          None,
                          pending = true,
                          isPublic = true
                        )
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          finishedId,
                          Some(Instant.ofEpochSecond(5000)),
                          pending = false,
                          isPublic = true
                        )
                        _ <- registrationService.register(watcherId, watcherId)
                        running <- matchService.publicActive(watcherId, player.playerId)
                        over <- matchService
                            .publicCompleted(watcherId, player.playerId, CompletedQuery())
                            .map(_.matches.toList)
                    } yield running.map(_.matchId) == List(runningId) &&
                        over.map(_.matchId) == List(finishedId) &&
                        // The rows describe the player asked about, not the caller: it is their turn in
                        // the running one, and they created both.
                        running.forall(s => s.pending && s.isOwner)
                }
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* A game's parameters as a match is played under them: what the challenger chose where they
     * chose, the default where they did not, and a choice the game no longer allows read as the
     * default -- what the engine is told at a start. Shown by display name, in the game's order. */
    property("a summary lists each of the game's parameters with the value its match is played under") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId, Generators.genMatchId) {
            (nickname, externalId, runningId, finishedId) =>
                def parameter(name: String, displayName: String, default: String, values: String*) =
                    GameParameter(
                      GameId.unassigned,
                      GameParameterId(0),
                      name,
                      Some(default),
                      values.map(v => GameParameterValue(GameId.unassigned, GameParameterId(0), v)),
                      displayName
                    )
                val parameters = Seq(
                  parameter("rounds", "Rounds", "3", "3", "6", "12"),
                  parameter("venue", "Venue", "hall", "hall", "park")
                )
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId, parameters)
                        (player, game, character) = prepared
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          runningId,
                          None,
                          pending = true,
                          settings = """{"rounds":"12"}"""
                        )
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          finishedId,
                          Some(Instant.ofEpochSecond(5000)),
                          pending = false,
                          settings = """{"rounds":"6","venue":"moon"}"""
                        )
                        running <- matchService.active(externalId)
                        over <- matchService.completed(externalId).map(_.matches.toList)
                    } yield (running.map(_.parameters), over.map(_.parameters))
                }
                val (running, over) = result.timeout(15.seconds).unsafeRunSync()
                (running ?= List(Seq(MatchParameter("Rounds", "12"), MatchParameter("Venue", "hall")))) &&
                (over ?= List(Seq(MatchParameter("Rounds", "6"), MatchParameter("Venue", "hall"))))
        }
    }

    /* The spectator's url travels on the summary, which is what a Watch link is drawn from -- and
     * it is null for an engine that serves no board, so the absence has to survive the round trip
     * as plainly as the value does. */
    property("a summary carries the public url the engine issued, and none when there is none") {
        forAll(genUniqueString, genUniqueString, genUniqueString, Generators.genMatchId, Generators.genMatchId) {
            (nickname, externalId, watcherId, watchableId, plainId) =>
                val url = s"http://engine.test/matches/$watchableId/board"
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId)
                        (player, game, character) = prepared
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          watchableId,
                          None,
                          pending = true,
                          isPublic = true,
                          publicUrl = Some(url)
                        )
                        _ <- addMatch(session, player, game, character, plainId, None, pending = true, isPublic = true)
                        _ <- registrationService.register(watcherId, watcherId)
                        seen <- matchService.publicActive(watcherId, player.playerId)
                        // And on the player's own list, which is the same summary read by its owner.
                        mine <- matchService.active(externalId)
                    } yield seen.find(_.matchId == watchableId).flatMap(_.publicUrl).contains(url) &&
                        seen.find(_.matchId == plainId).exists(_.publicUrl.isEmpty) &&
                        mine.find(_.matchId == watchableId).flatMap(_.publicUrl).contains(url)
                }
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    /* The split is the seat's, not the match's: a player who is out of a match that is still being
     * played has finished with it, and their page should say so. */
    property("the public lists put a retired seat among the finished matches, though the match runs on") {
        forAll(genUniqueString, genUniqueString, genUniqueString, Generators.genMatchId) {
            (nickname, externalId, watcherId, mid) =>
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId)
                        (player, game, character) = prepared
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          mid,
                          // The match itself is unfinished; this player's seat is not.
                          None,
                          pending = false,
                          isPublic = true,
                          seatCompleted = Some(true)
                        )
                        _ <- registrationService.register(watcherId, watcherId)
                        running <- matchService.publicActive(watcherId, player.playerId)
                        over <- matchService
                            .publicCompleted(watcherId, player.playerId, CompletedQuery())
                            .map(_.matches.toList)
                    } yield running.isEmpty && over.map(_.matchId) == List(mid) &&
                        // Still an unfinished match, which is why the row cannot say it completed.
                        over.forall(s => !s.completed && !s.cancelled)
                }
                result.timeout(15.seconds).unsafeRunSync()
        }
    }

    property("publicActive rejects a caller who has never registered") {
        forAll(genUniqueString) { externalId =>
            matchService
                .publicActive(externalId, PlayerId(1))
                .attempt
                .timeout(10.seconds)
                .unsafeRunSync() match {
                case Left(_: UnauthorizedError) => true
                case _                          => false
            }
        }
    }

    property("due returns matches where it is the caller's turn") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, matchId) = made
                due <- matchService.due(externalId)
            } yield due.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) &&
                due.forall(_.gameName == "game")
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("due excludes matches where it is not the caller's turn") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                _ <- makeMatch(nickname, externalId, mid, completed = false, pending = false)
                due <- matchService.due(externalId)
            } yield due.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("active returns unfinished matches and completed returns finished ones") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, matchId) = made
                active <- matchService.active(externalId)
                completed <- matchService.completed(externalId).map(_.matches.toList)
            } yield active.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) && completed.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    /* The completed list is a history, so it is read from the most recent end: by when each of the
     * player's seats was finished with. A cancelled match is not in it at all. */
    property("completed matches come back most recently finished first, and a cancelled one not at all") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId, Generators.genMatchId, Generators.genMatchId) {
            (nickname, externalId, olderId, newerId, cancelledId) =>
                val result = TestSession.resource.use { session =>
                    for {
                        prepared <- setup(session, nickname, externalId)
                        (player, game, character) = prepared
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          olderId,
                          Some(Instant.ofEpochSecond(5000)),
                          pending = false
                        )
                        _ <- addMatch(
                          session,
                          player,
                          game,
                          character,
                          newerId,
                          Some(Instant.ofEpochSecond(9000)),
                          pending = false
                        )
                        cancelled <- addMatch(session, player, game, character, cancelledId, None, pending = false)
                        _ <- new MatchRepo(session).read(game.gameId, cancelled).flatMap {
                            case Some(m) => new MatchRepo(session).update(m.copy(cancelled = true))
                            case None => IO.raiseError(new IllegalStateException("the match just written is not there"))
                        }
                        over <- matchService.completed(externalId).map(_.matches.toList)
                    } yield over.map(_.matchId.value) == List(newerId, olderId)
                }
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("completed returns finished matches and active excludes them") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = true, pending = false)
                (_, game, matchId) = made
                active <- matchService.active(externalId)
                completed <- matchService.completed(externalId).map(_.matches.toList)
            } yield completed.map(s => (s.gameId, s.matchId)) == List((game.gameId, matchId)) && active.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("lists are scoped to the caller, so another player sees nothing") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId, genUniqueString, genUniqueString) {
            (nickname, externalId, mid, otherNickname, otherExternalId) =>
                val result = for {
                    _ <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                    _ <- registrationService.register(otherNickname, otherExternalId)
                    due <- matchService.due(otherExternalId)
                    active <- matchService.active(otherExternalId)
                } yield due.isEmpty && active.isEmpty
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("an unregistered caller is unauthorized") {
        forAll(genUniqueString) { externalId =>
            matchService.due(externalId).attempt.timeout(10.seconds).unsafeRunSync() match {
                case Left(_: UnauthorizedError) => true
                case _                          => false
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Cancelling
    // ---------------------------------------------------------------------------
    //
    // `makeMatch` makes the registered player the owner of the match's tournament, so the match is theirs.

    property("the tournament's owner can cancel its match") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, matchId) = made
                cancelled <- matchService.cancel(game.gameId, matchId, externalId)
                due <- matchService.due(externalId)
                active <- matchService.active(externalId)
                over <- matchService.completed(externalId).map(_.matches.toList)
            } yield cancelled.cancelled &&
                // Gone from the lists of things still to play, and not among the finished ones either: a
                // match called off was never finished, and completed lists leave it out.
                due.isEmpty && active.isEmpty && over.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    // A match is its tournament's owner's, whoever is seated in it: that alone says whose it is, on the lists and to
    // the cancel.
    /** Takes a tournament match back to before it began: `status` with no start or urls, and its seats with no role and
      * no rating, as a match is while its seats choose. `cancelled` is a match called off in that time: over, never
      * begun.
      */
    private def unbegin(session: skunk.Session[IO], gameId: GameId, matchId: MatchId, cancelled: Boolean): IO[Unit] =
        session.execute(
          sql"""UPDATE match SET status = $text, cancelled = ${skunk.codec.all.bool}, start = NULL,
                   status_url = NULL, play_url = NULL, public_url = NULL
            WHERE game_id = $int4 AND match_id = $int8""".command
        )((if (cancelled) "F" else "D", cancelled, gameId.value, matchId.value)) *>
            session
                .execute(
                  sql"""UPDATE participant SET game_role_id = NULL, elo_start = NULL
                    WHERE game_id = $int4 AND match_id = $int8""".command
                )((gameId.value, matchId.value))
                .void

    test("a tournament match that has not begun is in no list, no results, and cannot be opened or cancelled") {
        val result = TestSession.resource.use { session =>
            for {
                prepared <- setup(session, genUniqueString.sample.get, genUniqueString.sample.get)
                (player, game, character) = prepared
                // Due to be created, and the player's turn by the seat's own flag: the due list's
                // condition, apart from the match having begun.
                waiting <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  Generators.newMatchId(),
                  None,
                  pending = true
                )
                _ <- unbegin(session, game.gameId, waiting, cancelled = false)
                // Called off before it began: over, and still not a match the player played.
                calledOff <- addMatch(
                  session,
                  player,
                  game,
                  character,
                  Generators.newMatchId(),
                  None,
                  pending = false
                )
                _ <- unbegin(session, game.gameId, calledOff, cancelled = true)
                _ <- new ParticipantRepo(session).completeForMatch(game.gameId, calledOff)
            } yield (player, game, Set(waiting, calledOff), waiting)
        }
        val (player, game, unbegun, waiting) = result.timeout(30.seconds).unsafeRunSync()
        val outcome = for {
            active <- matchService.active(player.externalId)
            due <- matchService.due(player.externalId)
            completed <- matchService.completed(player.externalId)
            results <- matchService.results(player.externalId)
            read <- TestServices.services.engine.read(game.gameId, waiting, player.externalId).attempt
            cancel <- matchService.cancel(game.gameId, waiting, player.externalId).attempt
        } yield (active, due, completed, results, read, cancel)
        val (active, due, completed, results, read, cancel) = outcome.timeout(30.seconds).unsafeRunSync()

        assertEquals(active.map(_.matchId).filter(unbegun), Nil)
        assertEquals(due.map(_.matchId).filter(unbegun), Nil)
        assertEquals(completed.matches.map(_.matchId).filter(unbegun), Nil)
        assertEquals(results.map(_.matchId).filter(unbegun), Nil)
        assert(read.left.exists(_.isInstanceOf[NotFoundError]), read)
        assert(cancel.left.exists(_.isInstanceOf[ConflictError]), cancel)
    }

    test("a match is its tournament owner's to cancel, and listed as theirs and nobody else's") {
        val result = TestSession.resource.use { session =>
            for {
                prepared <- setup(session, genUniqueString.sample.get, genUniqueString.sample.get)
                (owner, game, ownerCharacter) = prepared
                ownerMatch <- addMatch(
                  session,
                  owner,
                  game,
                  ownerCharacter,
                  Generators.newMatchId(),
                  None,
                  pending = true
                )
                otherExternalId = genUniqueString.sample.get
                other <- registrationService.register(genUniqueString.sample.get, otherExternalId)
                otherCharacter <- new CharacterRepo[String](session).create(
                  Character(CharacterId(0), game.gameId, "other", "description", "", Some(other.playerId))
                )
                // Seated, but in a match the owner created for them.
                seatedMatch <- addMatch(
                  session,
                  other,
                  game,
                  otherCharacter,
                  Generators.newMatchId(),
                  None,
                  pending = true,
                  tournamentOwner = Some(owner.playerId)
                )
            } yield (owner, game, ownerMatch, otherExternalId, seatedMatch)
        }
        val (owner, game, ownerMatch, otherExternalId, seatedMatch) = result.unsafeRunSync()
        val outcome = for {
            ownersList <- matchService.active(owner.externalId)
            othersList <- matchService.active(otherExternalId)
            refused <- matchService.cancel(game.gameId, seatedMatch, otherExternalId).attempt
            cancelled <- matchService.cancel(game.gameId, ownerMatch, owner.externalId)
            cancelledSeated <- matchService.cancel(game.gameId, seatedMatch, owner.externalId)
        } yield {
            assertEquals(ownersList.map(s => (s.matchId, s.isOwner)), List((ownerMatch, true)))
            assertEquals(othersList.map(s => (s.matchId, s.isOwner)), List((seatedMatch, false)))
            assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
            assert(cancelled.cancelled)
            assert(cancelledSeated.cancelled)
        }
        outcome.timeout(30.seconds).unsafeRunSync()
    }

    // The seats, not just the match. A cancelled match is over, so nothing in it is anybody's turn and
    // no clock in it is running -- which is what the results and forfeit paths already write, and what
    // anything asking "is this seat still in play" now reads instead of joining `match`.
    property("cancelling retires every seat in the match") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, matchId) = made
                before <- TestSession.resource.use(new ParticipantRepo(_).listForMatch(game.gameId, matchId))
                _ <- matchService.cancel(game.gameId, matchId, externalId)
                after <- TestSession.resource.use(new ParticipantRepo(_).listForMatch(game.gameId, matchId))
            } yield before.nonEmpty && before.forall((p, _, _) => p.pending && !p.completed) &&
                after.forall((p, _, _) => !p.pending && p.completed && p.due.isEmpty)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("a player who did not create the match may not cancel it") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId, genUniqueString, genUniqueString) {
            (nickname, externalId, mid, otherNickname, otherExternalId) =>
                val result = for {
                    made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                    (_, game, matchId) = made
                    _ <- registrationService.register(otherNickname, otherExternalId)
                    outcome <- matchService.cancel(game.gameId, matchId, otherExternalId).attempt
                    // And the refusal is a refusal, not a silent no-op.
                    active <- matchService.active(externalId)
                } yield (outcome match {
                    case Left(_: UnauthorizedError) => true
                    case _                          => false
                }) && active.map(_.matchId) == List(matchId)
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("a completed match can no longer be cancelled") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = true, pending = false)
                (_, game, matchId) = made
                outcome <- matchService.cancel(game.gameId, matchId, externalId).attempt
            } yield outcome match {
                case Left(_: ConflictError) => true
                case _                      => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("cancelling twice is a conflict, not a second cancel") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, matchId) = made
                _ <- matchService.cancel(game.gameId, matchId, externalId)
                outcome <- matchService.cancel(game.gameId, matchId, externalId).attempt
            } yield outcome match {
                case Left(_: ConflictError) => true
                case _                      => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("a match that does not exist is not found") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, game, _) = made
                outcome <- matchService
                    .cancel(game.gameId, com.vivi.matchmaker.persistence.Generators.newMatchId(), externalId)
                    .attempt
            } yield outcome match {
                case Left(_: NotFoundError) => true
                case _                      => false
            }
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    // ---------------------------------------------------------------------------
    // Friendly
    // ---------------------------------------------------------------------------

    /** A registered player made an overall admin, as only the database can. */
    private def overallAdmin(): IO[Player] =
        for {
            player <- registrationService.register(genUniqueString.sample.get, genUniqueString.sample.get)
            _ <- TestSession.resource.use(session => new PlayerRepo(session).update(player.copy(isAdmin = true)))
        } yield player.copy(isAdmin = true)

    /** A match, its owner, and a player made an admin of its game. */
    private def friendlyFixture(completed: Boolean = false): IO[(Player, Game, MatchId, Player)] =
        for {
            made <- makeMatch(
              genUniqueString.sample.get,
              genUniqueString.sample.get,
              Generators.newMatchId(),
              completed = completed,
              pending = true
            )
            (owner, game, matchId) = made
            overall <- overallAdmin()
            gameAdmin <- registrationService.register(genUniqueString.sample.get, genUniqueString.sample.get)
            _ <- TestServices.services.gameAdmins.grant(game.gameId, gameAdmin.playerId, overall.externalId)
        } yield (owner, game, matchId, gameAdmin)

    private def friendlyOf(game: Game, matchId: MatchId): IO[Option[Boolean]] =
        TestSession.resource.use(session => new MatchRepo(session).read(game.gameId, matchId).map(_.map(_.friendly)))

    test("a game's admin says a match of the game is not friendly, and then that it is") {
        val result = for {
            f <- friendlyFixture()
            (_, game, matchId, gameAdmin) = f
            answered <- matchService.setFriendly(game.gameId, matchId, friendly = false, gameAdmin.externalId)
            stored <- friendlyOf(game, matchId)
            _ <- matchService.setFriendly(game.gameId, matchId, friendly = true, gameAdmin.externalId)
            restored <- friendlyOf(game, matchId)
        } yield (answered.friendly, stored, restored)
        assertEquals(result.timeout(30.seconds).unsafeRunSync(), (false, Some(false), Some(true)))
    }

    test("an overall admin may say it too, without administering the game") {
        val result = for {
            f <- friendlyFixture()
            (_, game, matchId, _) = f
            overall <- overallAdmin()
            _ <- matchService.setFriendly(game.gameId, matchId, friendly = false, overall.externalId)
            stored <- friendlyOf(game, matchId)
        } yield stored
        assertEquals(result.timeout(30.seconds).unsafeRunSync(), Some(false))
    }

    // What it does to ratings is EloRatingServiceSpec's business; this is about who may, and that it sticks.
    test("a completed match may still be said to be friendly or not, and saying what it already is changes nothing") {
        val result = for {
            f <- friendlyFixture(completed = true)
            (owner, game, matchId, gameAdmin) = f
            refused <- matchService.setFriendly(game.gameId, matchId, friendly = false, owner.externalId).attempt
            same <- matchService.setFriendly(game.gameId, matchId, friendly = true, gameAdmin.externalId)
            _ <- matchService.setFriendly(game.gameId, matchId, friendly = false, gameAdmin.externalId)
            changed <- friendlyOf(game, matchId)
            _ <- matchService.setFriendly(game.gameId, matchId, friendly = true, gameAdmin.externalId)
            back <- friendlyOf(game, matchId)
        } yield (refused, same.friendly, changed, back)
        val (refused, same, changed, back) = result.timeout(30.seconds).unsafeRunSync()
        assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
        assertEquals(same, true)
        assertEquals(changed, Some(false))
        assertEquals(back, Some(true))
    }

    test("a game's admin lists the game's matches, each with who plays it and whether it is friendly") {
        val result = for {
            f <- friendlyFixture()
            (owner, game, matchId, gameAdmin) = f
            _ <- matchService.setFriendly(game.gameId, matchId, friendly = false, gameAdmin.externalId)
            listed <- matchService.listForGame(game.gameId, gameAdmin.externalId)
            refused <- matchService.listForGame(game.gameId, owner.externalId).attempt
        } yield (listed, owner, matchId, refused)
        val (listed, owner, matchId, refused) = result.timeout(30.seconds).unsafeRunSync()
        assertEquals(listed.map(m => (m.matchId, m.friendly, m.players)), List((matchId, false, Seq(owner.nickname))))
        assert(refused.left.exists(_.isInstanceOf[UnauthorizedError]), refused)
    }

    test("narrowed to a player, a game's admin lists only the matches that player has a seat in") {
        val result = for {
            f <- friendlyFixture()
            (owner, game, matchId, gameAdmin) = f
            theirs <- matchService.listForGame(game.gameId, gameAdmin.externalId, Some(owner.playerId))
            // The admin has no seat in it: narrowed to them, there is nothing.
            nobodys <- matchService.listForGame(game.gameId, gameAdmin.externalId, Some(gameAdmin.playerId))
        } yield (theirs, nobodys, matchId)
        val (theirs, nobodys, matchId) = result.timeout(30.seconds).unsafeRunSync()
        assertEquals(theirs.map(_.matchId), List(matchId))
        assertEquals(nobodys, Nil)
    }

    test(
      "the match's own tournament owner may not, nor an admin of a different game, and a missing match is not found"
    ) {
        val result = for {
            f <- friendlyFixture()
            (owner, game, matchId, _) = f
            other <- friendlyFixture()
            (_, _, _, otherAdmin) = other
            byOwner <- matchService.setFriendly(game.gameId, matchId, friendly = false, owner.externalId).attempt
            byOther <- matchService.setFriendly(game.gameId, matchId, friendly = false, otherAdmin.externalId).attempt
            stored <- friendlyOf(game, matchId)
            missing <- matchService
                .setFriendly(
                  other._2.gameId,
                  com.vivi.matchmaker.persistence.Generators.newMatchId(),
                  friendly = false,
                  otherAdmin.externalId
                )
                .attempt
        } yield (byOwner, byOther, stored, missing)
        val (byOwner, byOther, stored, missing) = result.timeout(30.seconds).unsafeRunSync()
        assert(byOwner.left.exists(_.isInstanceOf[UnauthorizedError]), byOwner)
        assert(byOther.left.exists(_.isInstanceOf[UnauthorizedError]), byOther)
        assertEquals(stored, Some(true))
        assert(missing.left.exists(_.isInstanceOf[NotFoundError]), missing)
    }

    // ---------------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------------

    /** The one participant `makeMatch` creates, which is what a result has to be written against. */
    private def onlyParticipant(gameId: GameId, matchId: MatchId): IO[ParticipantId] =
        TestSession.resource.use(session =>
            new ParticipantRepo(session).listForMatch(gameId, matchId).map(_.head._1.participantId)
        )

    property("results name the player, their role, and whatever the engine scored them on") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = true, pending = false)
                (_, game, matchId) = made
                participantId <- onlyParticipant(game.gameId, matchId)
                _ <- TestSession.resource.use(session =>
                    new ResultRepo(session).create(
                      com.vivi.matchmaker.model
                          .Result(game.gameId, participantId, rank = 1, scores = Map("moves" -> 5.0), isWinner = true)
                    )
                )
                results <- matchService.results(externalId)
                mine = results.filter(_.matchId == matchId)
            } yield mine.map(r => (r.nickname, r.roleName, r.rank, r.isWinner)) ==
                List((nickname, "only", Some(1), true)) &&
                mine.head.scores == Map("moves" -> 5.0)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("a seat the engine reported no result for is still listed, with no rank") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = true, pending = false)
                (_, _, matchId) = made
                results <- matchService.results(externalId)
                mine = results.filter(_.matchId == matchId)
            } yield mine.map(r => (r.nickname, r.rank, r.isWinner)) == List((nickname, None, false)) &&
                mine.head.scores.isEmpty
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("results exclude matches still being played") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                (_, _, matchId) = made
                results <- matchService.results(externalId)
            } yield results.forall(_.matchId != matchId)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("results are scoped to the caller, so another player sees nothing of them") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId, genUniqueString, genUniqueString) {
            (nickname, externalId, mid, otherNickname, otherExternalId) =>
                val result = for {
                    _ <- makeMatch(nickname, externalId, mid, completed = true, pending = false)
                    _ <- registrationService.register(otherNickname, otherExternalId)
                    results <- matchService.results(otherExternalId)
                } yield results.isEmpty
                result.timeout(10.seconds).unsafeRunSync()
        }
    }

    property("the owner is told apart from a mere participant on every summary") {
        forAll(genUniqueString, genUniqueString, Generators.genMatchId) { (nickname, externalId, mid) =>
            val result = for {
                made <- makeMatch(nickname, externalId, mid, completed = false, pending = true)
                active <- matchService.active(externalId)
            } yield active.forall(_.isOwner)
            result.timeout(10.seconds).unsafeRunSync()
        }
    }
}
