package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

class AcceptanceRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val characterId = SkunkIdCodecs.characterId

  private val insertAcceptance: Command[(ChallengeId, PlayerId, CharacterId)] =
    sql"INSERT INTO acceptance (challenge_id, player_id, character_id) VALUES ($challengeId, $playerId, $characterId)".command

  private val selectAcceptance: Query[(ChallengeId, PlayerId), CharacterId] =
    sql"""SELECT character_id FROM acceptance WHERE challenge_id = $challengeId AND player_id = $playerId"""
      .query(characterId)

  def create(a: Acceptance): IO[Acceptance] =
    session.execute(insertAcceptance)((a.challengeId, a.playerId, a.characterId)).as(a)

  def read(challengeId: ChallengeId, playerId: PlayerId): IO[Option[Acceptance]] =
    session
      .option(selectAcceptance)((challengeId, playerId))
      .map(_.map(characterId => Acceptance(challengeId, playerId, characterId)))

  // Acceptance's only fields are the composite key (plus characterId, which is fixed at
  // creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
