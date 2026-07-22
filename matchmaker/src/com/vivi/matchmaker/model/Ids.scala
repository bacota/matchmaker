package com.vivi.matchmaker.model

opaque type PlayerId = Long
object PlayerId {
  def apply(value: Long): PlayerId = value
  extension (id: PlayerId) def value: Long = id
}

opaque type GameId = Int
object GameId {
  def apply(value: Int): GameId = value
  extension (id: GameId) def value: Int = id
}

opaque type MatchId = String
object MatchId {
  def apply(value: String): MatchId = value
  extension (id: MatchId) def value: String = id
}

opaque type CharacterId = Long
object CharacterId {
  def apply(value: Long): CharacterId = value
  extension (id: CharacterId) def value: Long = id
}

opaque type GameRoleId = Int
object GameRoleId {
  def apply(value: Int): GameRoleId = value
  extension (id: GameRoleId) def value: Int = id
}

opaque type GameParameterId = Int
object GameParameterId {
  def apply(value: Int): GameParameterId = value
  extension (id: GameParameterId) def value: Int = id
}

opaque type ParticipantId = Long
object ParticipantId {
  def apply(value: Long): ParticipantId = value
  extension (id: ParticipantId) def value: Long = id
}

opaque type ChallengeId = Long
object ChallengeId {
  def apply(value: Long): ChallengeId = value
  extension (id: ChallengeId) def value: Long = id
}
