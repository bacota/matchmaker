package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

class AcceptanceRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId

  private val insertAcceptance: Command[(ChallengeId, PlayerId, GameId, CharacterId)] =
    sql"INSERT INTO acceptance (challenge_id, player_id, game_id, character_id) VALUES ($challengeId, $playerId, $gameId, $characterId)".command

  // A trailing opaque-typed codec (both GameId and CharacterId are opaque types defined in
  // Ids.scala) defeats skunk's twiddle-list match-type resolution from outside that file, so
  // this pair is decoded via the underlying int4/int8 codecs and mapped afterward instead.
  private val gameAndCharacterId: Codec[(GameId, CharacterId)] =
    (int4 *: int8).imap { case (g, c) => (GameId(g), CharacterId(c)) } { case (g, c) => (g.value, c.value) }

  private val selectAcceptance: Query[(ChallengeId, PlayerId), (GameId, CharacterId)] =
    sql"""SELECT game_id, character_id FROM acceptance WHERE challenge_id = $challengeId AND player_id = $playerId"""
      .query(gameAndCharacterId)

  private val deleteByChallenge: Command[ChallengeId] =
    sql"DELETE FROM acceptance WHERE challenge_id = $challengeId".command

  def deleteAllForChallenge(challengeId: ChallengeId): IO[Unit] =
    session.execute(deleteByChallenge)(challengeId).void

  private val deleteOne: Command[(ChallengeId, PlayerId)] =
    sql"DELETE FROM acceptance WHERE challenge_id = $challengeId AND player_id = $playerId".command

  def delete(challengeId: ChallengeId, playerId: PlayerId): IO[Unit] =
    session.execute(deleteOne)((challengeId, playerId)).void

  private val countByChallenge: Query[ChallengeId, Long] =
    sql"SELECT count(*) FROM acceptance WHERE challenge_id = $challengeId".query(int8)

  def countForChallenge(challengeId: ChallengeId): IO[Long] =
    session.unique(countByChallenge)(challengeId)

  def create(a: Acceptance): IO[Acceptance] =
    session.execute(insertAcceptance)((a.challengeId, a.playerId, a.gameId, a.characterId)).as(a)

  def read(challengeId: ChallengeId, playerId: PlayerId): IO[Option[Acceptance]] =
    session
      .option(selectAcceptance)((challengeId, playerId))
      .map(_.map { case (gameId, characterId) => Acceptance(challengeId, playerId, gameId, characterId) })

  // Acceptance's only fields are the composite key (plus gameId/characterId, which are fixed
  // at creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
