package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

class AcceptanceRepo(session: Session[IO]) {

  private val insertAcceptance: Command[(Long, Long)] =
    sql"INSERT INTO acceptance (challenge_id, player_id) VALUES ($int8, $int8)".command

  private val insertPlayerAcceptance: Command[(Long, Long)] =
    sql"INSERT INTO player_acceptance (challenge_id, player_id) VALUES ($int8, $int8)".command

  private val insertCharacterAcceptance: Command[(Long, Long, Long)] =
    sql"INSERT INTO character_acceptance (challenge_id, player_id, character_id) VALUES ($int8, $int8, $int8)".command

  private val selectAcceptance: Query[(Long, Long), Option[Long]] =
    sql"""SELECT ca.character_id
          FROM acceptance a
          LEFT JOIN character_acceptance ca ON ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
          WHERE a.challenge_id = $int8 AND a.player_id = $int8""".query(int8.opt)

  def create(a: Acceptance): IO[Acceptance] =
    for {
      _ <- session.execute(insertAcceptance)((a.challengeId, a.playerId))
      _ <- a match {
        case pa: PlayerAcceptance    => session.execute(insertPlayerAcceptance)((pa.challengeId, pa.playerId))
        case ca: CharacterAcceptance => session.execute(insertCharacterAcceptance)((ca.challengeId, ca.playerId, ca.characterId))
      }
    } yield a

  def read(challengeId: Long, playerId: Long): IO[Option[Acceptance]] =
    session.option(selectAcceptance)((challengeId, playerId)).map(_.map {
      case Some(characterId) => CharacterAcceptance(challengeId, playerId, characterId)
      case None               => PlayerAcceptance(challengeId, playerId)
    })

  // Acceptance's only fields are the composite key (plus characterId, which is fixed at
  // creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
