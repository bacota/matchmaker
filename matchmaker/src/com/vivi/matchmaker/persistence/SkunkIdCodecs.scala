package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import com.vivi.matchmaker.model._

object SkunkIdCodecs {
  val playerId: Codec[PlayerId] = int8.imap(PlayerId.apply)(_.value)
  val gameId: Codec[GameId] = int4.imap(GameId.apply)(_.value)
  val matchId: Codec[MatchId] = varchar.imap(MatchId.apply)(_.value)
  val characterId: Codec[CharacterId] = int8.imap(CharacterId.apply)(_.value)
  val gameRoleId: Codec[GameRoleId] = int4.imap(GameRoleId.apply)(_.value)
  val gameParameterId: Codec[GameParameterId] = int4.imap(GameParameterId.apply)(_.value)
  val participantId: Codec[ParticipantId] = int8.imap(ParticipantId.apply)(_.value)
  val challengeId: Codec[ChallengeId] = int8.imap(ChallengeId.apply)(_.value)
}
