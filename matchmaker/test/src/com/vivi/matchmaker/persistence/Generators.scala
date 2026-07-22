package com.vivi.matchmaker.persistence

import org.scalacheck.Gen
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

object Generators {
  def genString: Gen[String] =
    Gen.choose(6, 16).flatMap(n => Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString))

  def genInstant: Gen[Instant] = Gen.choose(0L, 2000000000L).map(Instant.ofEpochSecond(_))

  def genDuration: Gen[Duration] = Gen.choose(1L, 100000L).map(Duration.ofSeconds(_))

  def genPlayer: Gen[Player] =
    for {
      nickname <- genString
      isAdmin <- Gen.oneOf(true, false)
      externalId <- genString
    } yield Player(PlayerId(0), nickname, isAdmin, externalId)

  def genGame(playerGame: Boolean): Gen[Game] =
    for {
      name <- genString
      description <- genString
      url <- genString
      active <- Gen.oneOf(true, false)
    } yield
      if (playerGame) PlayerGame(GameId(0), name, description, url, active, Seq.empty, Seq.empty)
      else CharacterGame(GameId(0), name, description, url, active, Seq.empty, Seq.empty)

  def genGameWithRole(playerGame: Boolean): Gen[Game] =
    for {
      base <- genGame(playerGame)
      roleName <- genString
      optional <- Gen.oneOf(true, false)
    } yield {
      val role = GameRole(GameRoleId(0), GameId(0), roleName, optional)
      base match {
        case g: PlayerGame    => g.copy(roles = Seq(role))
        case g: CharacterGame => g.copy(roles = Seq(role))
      }
    }

  def genCharacter(gameId: GameId, playerId: Option[PlayerId]): Gen[Character[String]] =
    for {
      name <- genString
      description <- genString
      state <- genString
    } yield Character(CharacterId(0), gameId, name, description, state, playerId)

  def genMatch(gameId: GameId, matchId: MatchId, playerMatch: Boolean): Gen[Match] =
    for {
      description <- genString
      completed <- Gen.oneOf(true, false)
      start <- genInstant
      timeLimit <- Gen.option(genDuration)
    } yield
      if (playerMatch) PlayerMatch(gameId, matchId, description, completed, start, timeLimit, "{}")
      else CharacterMatch(gameId, matchId, description, completed, start, timeLimit, "{}")

  def genPlayerParticipant(gameId: GameId, matchId: MatchId, playerId: PlayerId, roleId: GameRoleId): Gen[Participant] =
    for {
      pending <- Gen.oneOf(true, false)
      completed <- Gen.oneOf(true, false)
      due <- Gen.option(genInstant)
    } yield PlayerParticipant(ParticipantId(0), gameId, matchId, playerId, pending, completed, due, roleId)

  def genCharacterParticipant(gameId: GameId, matchId: MatchId, playerId: PlayerId, characterId: CharacterId): Gen[Participant] =
    for {
      pending <- Gen.oneOf(true, false)
      completed <- Gen.oneOf(true, false)
      due <- Gen.option(genInstant)
    } yield CharacterParticipant(ParticipantId(0), gameId, matchId, playerId, pending, completed, due, characterId)

  def genResult(participantId: ParticipantId): Gen[Result] =
    for {
      rank <- Gen.choose(1, 100)
      score <- Gen.choose(0.0, 1000.0)
    } yield Result(participantId, rank, score)

  def genPlayerOpenChallenge(challenger: PlayerId, gameId: GameId): Gen[OpenChallenge] =
    for {
      message <- genString
      numberOfPlayers <- Gen.choose(1, 10)
      start <- Gen.option(genInstant)
      timeLimit <- Gen.option(genDuration)
    } yield PlayerOpenChallenge(ChallengeId(0), challenger, message, numberOfPlayers.toShort, start, timeLimit, "{}", gameId)

  def genCharacterOpenChallenge(challenger: PlayerId, gameId: GameId, characterId: CharacterId): Gen[OpenChallenge] =
    for {
      message <- genString
      numberOfPlayers <- Gen.choose(1, 10)
      start <- Gen.option(genInstant)
      timeLimit <- Gen.option(genDuration)
    } yield CharacterOpenChallenge(ChallengeId(0), challenger, message, numberOfPlayers.toShort, start, timeLimit, "{}", gameId, characterId)
}
