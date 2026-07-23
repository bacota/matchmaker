package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

class AcceptanceRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

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

  private val playerRow: Codec[(String, Boolean, String)] = text *: bool *: text

  // GameId/CharacterId are opaque types from Ids.scala, so (as elsewhere in this file) the
  // trailing pair is decoded via the underlying int4/int8 codecs and mapped afterward rather
  // than chained directly, to avoid the skunk twiddle-list match-type resolution failure.
  private val acceptanceWithChallengeAndPlayersRow
      : Codec[
          (PlayerId, String, Short, Option[Instant], Option[Double], String, (Int, Long), (String, Boolean, String), String, Boolean, String)
        ] =
    playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: (int4 *: int8) *: playerRow *: playerRow

  private val selectAcceptanceWithChallengeAndPlayers
      : Query[
          (ChallengeId, PlayerId),
          (PlayerId, String, Short, Option[Instant], Option[Double], String, (Int, Long), (String, Boolean, String), String, Boolean, String)
        ] =
    sql"""SELECT oc.challenger, oc.message, oc.number_of_players, oc.start,
                 EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.game_id, oc.character_id,
                 acceptor.nickname, acceptor.is_admin, acceptor.external_id,
                 challenger.nickname, challenger.is_admin, challenger.external_id
          FROM acceptance a
          JOIN open_challenge oc ON oc.challenge_id = a.challenge_id
          JOIN player acceptor ON acceptor.player_id = a.player_id
          JOIN player challenger ON challenger.player_id = oc.challenger
          WHERE a.challenge_id = $challengeId AND a.player_id = $playerId"""
      .query(acceptanceWithChallengeAndPlayersRow)

  /** Reads, in one join query, everything needed to authorize deleting an acceptance: the
    * challenge it belongs to, the accepting player, and the challenger (the player who owns
    * the challenge).
    */
  def readWithChallengeAndPlayers(challengeId: ChallengeId, playerId: PlayerId): IO[Option[(OpenChallenge, Player, Player)]] =
    session.option(selectAcceptanceWithChallengeAndPlayers)((challengeId, playerId)).map(_.map {
      case (
            challenger,
            message,
            numberOfPlayers,
            start,
            timeLimitSeconds,
            settings,
            (gameIdValue, characterIdValue),
            (acceptorNickname, acceptorIsAdmin, acceptorExternalId),
            challengerNickname,
            challengerIsAdmin,
            challengerExternalId
          ) =>
        val gameId = GameId(gameIdValue)
        val characterId = CharacterId(characterIdValue)
        val challengeModel = OpenChallenge(
          challengeId,
          challenger,
          message,
          numberOfPlayers,
          start,
          timeLimitSeconds.map(v => Duration.ofSeconds(v.toLong)),
          settings,
          gameId,
          characterId
        )
        val acceptor = Player(playerId, acceptorNickname, acceptorIsAdmin, acceptorExternalId)
        val challengerPlayer = Player(challenger, challengerNickname, challengerIsAdmin, challengerExternalId)
        (challengeModel, acceptor, challengerPlayer)
    })

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
