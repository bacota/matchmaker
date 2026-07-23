package com.vivi.matchmaker.persistence

import org.scalacheck.Gen
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

  def genPlayer: Gen[Player] =
    for {
      nickname <- genUniqueString
      isAdmin <- Gen.oneOf(true, false)
      externalId <- genUniqueString
    } yield Player(PlayerId.unassigned, nickname, isAdmin, externalId)

  def genGame: Gen[Game] =
    for {
      name <- genString
      description <- genString
      url <- genString
      active <- Gen.oneOf(true, false)
      externalId <- genUniqueString
    } yield Game(GameId(0), name, description, url, active, Seq.empty, Seq.empty, externalId)

  def genGameWithRole: Gen[Game] =
    for {
      base <- genGame
      roleName <- genString
      optional <- Gen.oneOf(true, false)
    } yield {
      val role = GameRole(GameRoleId(0), GameId(0), roleName, optional)
      base.copy(roles = Seq(role))
    }

  def genCharacter(gameId: GameId, playerId: Option[PlayerId]): Gen[Character[String]] =
    for {
      name <- genString
      description <- genString
      state <- genString
    } yield Character(CharacterId(0), gameId, name, description, state, playerId)

  def genMatch(gameId: GameId, matchId: MatchId): Gen[Match] =
    for {
      description <- genString
      completed <- Gen.oneOf(true, false)
      start <- genInstant
      timeLimit <- Gen.option(genDuration)
    } yield Match(gameId, matchId, description, completed, start, timeLimit, "{}")

  def genParticipant(gameId: GameId, matchId: MatchId, playerId: PlayerId, characterId: CharacterId): Gen[Participant] =
    for {
      pending <- Gen.oneOf(true, false)
      completed <- Gen.oneOf(true, false)
      due <- Gen.option(genInstant)
    } yield Participant(ParticipantId(0), gameId, matchId, playerId, pending, completed, due, characterId)

  def genResult(participantId: ParticipantId): Gen[Result] =
    for {
      rank <- Gen.choose(1, 100)
      score <- Gen.choose(0.0, 1000.0)
    } yield Result(participantId, rank, score)

  def genOpenChallenge(challenger: PlayerId, gameId: GameId, characterId: CharacterId): Gen[OpenChallenge] =
    for {
      message <- genString
      numberOfPlayers <- Gen.choose(1, 10)
      start <- Gen.option(genInstant)
      timeLimit <- Gen.option(genDuration)
    } yield OpenChallenge(ChallengeId(0), challenger, message, numberOfPlayers.toShort, start, timeLimit, "{}", gameId, characterId)
}
