package com.vivi.matchmaker.persistence

import skunk._
import skunk.codec.all._
import com.vivi.matchmaker.model._

object SkunkIdCodecs {
    val playerId: Codec[PlayerId] = int8.imap(PlayerId.apply)(_.value)
    val gameId: Codec[GameId] = int4.imap(GameId.apply)(_.value)
    val matchId: Codec[MatchId] = text.imap(MatchId.apply)(_.value)
    val characterId: Codec[CharacterId] = int8.imap(CharacterId.apply)(_.value)
    val gameRoleId: Codec[GameRoleId] = int4.imap(GameRoleId.apply)(_.value)
    val gameParameterId: Codec[GameParameterId] = int4.imap(GameParameterId.apply)(_.value)
    val participantId: Codec[ParticipantId] = int8.imap(ParticipantId.apply)(_.value)
    val challengeId: Codec[ChallengeId] = int8.imap(ChallengeId.apply)(_.value)
    val tournamentId: Codec[TournamentId] = int8.imap(TournamentId.apply)(_.value)
    val entryId: Codec[EntryId] = int8.imap(EntryId.apply)(_.value)
    val tournamentParticipantId: Codec[TournamentParticipantId] = int8.imap(TournamentParticipantId.apply)(_.value)
    val fixtureId: Codec[FixtureId] = int8.imap(FixtureId.apply)(_.value)
    val slotId: Codec[SlotId] = int8.imap(SlotId.apply)(_.value)
}
