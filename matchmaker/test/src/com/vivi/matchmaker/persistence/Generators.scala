package com.vivi.matchmaker.persistence

import cats.effect.IO
import org.scalacheck.Gen
import skunk.Session
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

object Generators {
    // Long enough that unique-key columns (nickname, external_id, ...) don't collide across
    // repeated runs against a persistent local dev database that these tests never clean up.
    def genString: Gen[String] =
        Gen.choose(24, 40).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))

    def genInstant: Gen[Instant] = Gen.choose(0L, 2000000000L).map(Instant.ofEpochSecond(_))

    def genDuration: Gen[Duration] = Gen.choose(1L, 100000L).map(Duration.ofSeconds(_))

    // ScalaCheck's default seed is deterministic, so separate suites running concurrently in
    // separate forked JVMs can independently generate the exact same "random" sequence. That's
    // fine for most fields, but nickname/external_id are unique-constrained, so they're
    // suffixed with a JVM-entropy-backed UUID to guarantee uniqueness across suites/runs.
    private def genUniqueString: Gen[String] = genString.map(s => s"$s-${java.util.UUID.randomUUID()}")

    /** A match id no other spec will make: far above anything `match_id_seq` will reach, for a match a spec writes
      * itself rather than starting, against a database the specs share.
      */
    def newMatchId(): MatchId = MatchId(
      java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40, Long.MaxValue)
    )

    def genMatchId: Gen[MatchId] = Gen.delay(Gen.const(newMatchId()))

    def genPlayer: Gen[Player] =
        for {
            nickname <- genUniqueString
            isAdmin <- Gen.oneOf(true, false)
            externalId <- genUniqueString
            // Absent half the time, which is what a player registered before the column existed looks
            // like. Not unique-suffixed as the two above are: nothing constrains the address, and two
            // generated players sharing one is a case worth generating rather than avoiding.
            local <- genString
            email <- Gen.oneOf(Some(s"$local@example.com"), None)
        } yield Player(PlayerId.unassigned, nickname, isAdmin, externalId, email)

    // Existing suites all build a character alongside the game, so Character is the default;
    // Plain-game coverage passes gameType explicitly.
    def genGame(gameType: GameType = GameType.Character): Gen[Game] =
        for {
            name <- genString
            description <- genString
            url <- genString
            active <- Gen.oneOf(true, false)
            externalId <- genUniqueString
            characterUrl <- Gen.option(genString)
            unimportantRoles <- Gen.oneOf(true, false)
            // Two roles, always: every seat names one, so a game with no roles is a game nothing
            // can be played in. Two rather than one because two players in one match may not share a
            // role.
        } yield Game(
          GameId.unassigned,
          gameType,
          name,
          name,
          description,
          url,
          active,
          Seq(
            GameRole(GameRoleId(0), GameId.unassigned, "first", roleType = RoleType.Required, displayName = "first"),
            GameRole(GameRoleId(0), GameId.unassigned, "second", roleType = RoleType.Required, displayName = "second")
          ),
          Seq.empty,
          externalId,
          characterUrl = characterUrl,
          hasUnimportantRoles = unimportantRoles
        )

    def genGameWithRole: Gen[Game] =
        for {
            base <- genGame()
            roleName <- genString
            roleType <- Gen.oneOf(RoleType.values.toSeq)
            preferenceLevel <- Gen.option(Gen.choose(0, 5))
        } yield {
            val role = GameRole(GameRoleId(0), GameId.unassigned, roleName, roleType, roleName, preferenceLevel)
            base.copy(roles = Seq(role))
        }

    def genCharacter(gameId: GameId, playerId: Option[PlayerId]): Gen[Character[String]] =
        for {
            name <- genString
            description <- genString
            state <- genString
        } yield Character(CharacterId(0), gameId, name, description, state, playerId)

    /** A match. Its tournament, and so whose it is, is the one [[TestMatches.writeMatch]] gives it. */
    def genMatch(gameId: GameId, matchId: MatchId): Gen[Match] =
        for {
            description <- genString
            completedAt <- Gen.option(genInstant)
            start <- genInstant
            timeLimit <- Gen.option(genDuration)
            isPublic <- Gen.oneOf(true, false)
            // The urls are the game engine's, so they are generated as opaque strings — matchmaker
            // stores whatever it is handed and never parses them.
            statusUrl <- Gen.option(genString.map("https://engine.example.com/status/" + _))
            playUrl <- Gen.option(genString.map("https://engine.example.com/play/" + _))
            publicUrl <- Gen.option(genString.map("https://engine.example.com/watch/" + _))
            cancelled <- Gen.oneOf(true, false)
            timeLimitKind <- Gen.oneOf(TimeLimitKind.values.toSeq)
            timeLimitUnit <- Gen.oneOf(TimeLimitUnit.values.toSeq)
            friendly <- Gen.oneOf(true, false)
        } yield Match(
          gameId,
          matchId,
          TournamentId.unassigned,
          description,
          completedAt,
          Some(start),
          timeLimit,
          "{}",
          isPublic,
          cancelled,
          statusUrl,
          playUrl,
          publicUrl,
          timeLimitKind,
          timeLimitUnit,
          isFriendly = friendly,
          // Begun, as a match is once it is created in its engine: over once it is completed or cancelled.
          status = if (completedAt.isDefined || cancelled) MatchStatus.Finished else MatchStatus.InProgress
        )

    def genParticipant(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId,
        characterId: CharacterId,
        gameRoleId: GameRoleId
    ): Gen[Participant] =
        for {
            pending <- Gen.oneOf(true, false)
            completed <- Gen.oneOf(true, false)
            due <- Gen.option(genInstant)
        } yield CharacterParticipant(
          ParticipantId(0),
          gameId,
          matchId,
          playerId,
          pending,
          completed,
          due,
          characterId,
          gameRoleId
        )

    /** As `genParticipant`, for a game that has no characters. */
    def genPlainParticipant(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId,
        gameRoleId: GameRoleId
    ): Gen[Participant] =
        for {
            pending <- Gen.oneOf(true, false)
            completed <- Gen.oneOf(true, false)
            due <- Gen.option(genInstant)
        } yield PlainParticipant(ParticipantId(0), gameId, matchId, playerId, pending, completed, due, gameRoleId)

    def genResult(gameId: GameId, participantId: ParticipantId): Gen[Result] =
        for {
            rank <- Gen.choose(1, 100)
            scores <- genScores
            isWinner <- Gen.oneOf(true, false)
            // A friendly match's result has none, a rated one's has one.
            eloDelta <- Gen.option(Gen.choose(-32, 32))
        } yield Result(gameId, participantId, rank, scores, isWinner, eloDelta = eloDelta)

    /* Values are restricted to the shapes a jsonb round trip preserves exactly: a Double comes
     * back a Double, but an Int would come back a Double too, so ints are not generated here. */
    private def genScores: Gen[Map[String, Any]] =
        Gen.mapOf(
          for {
              key <- genString
              value <- Gen.oneOf[Any](Gen.choose(0.0, 1000.0), genString, Gen.oneOf(true, false))
          } yield key -> value
        )

    /** A game, and a player to own a match in it. */
    def gameWithOwner(session: Session[IO]): IO[(Game, PlayerId)] =
        for {
            game <- new GameRepo[String](session).create(genGame().sample.get)
            owner <- ownerIn(session, game)
        } yield (game, owner)

    /** A generated match, ready for [[TestMatches.writeMatch]] to write. */
    def matchFrom(gameId: GameId, matchId: MatchId): Match =
        genMatch(gameId, matchId).sample.get

    /** A player to own a match in an existing game, with a character of theirs in it. */
    def ownerIn(session: Session[IO], game: Game): IO[PlayerId] =
        for {
            player <- new PlayerRepo(session).create(genPlayer.sample.get)
            _ <- new CharacterRepo[String](session).create(genCharacter(game.gameId, Some(player.playerId)).sample.get)
        } yield player.playerId
}
