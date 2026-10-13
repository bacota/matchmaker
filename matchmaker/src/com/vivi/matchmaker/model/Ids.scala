package com.vivi.matchmaker.model

opaque type PlayerId = Long
object PlayerId {
    def apply(value: Long): PlayerId = value
    extension (id: PlayerId) def value: Long = id

    /** Sentinel for a player that hasn't been persisted yet (its id is DB-generated on create). */
    val unassigned: PlayerId = PlayerId(0)
}

opaque type GameId = Int
object GameId {
    def apply(value: Int): GameId = value
    extension (id: GameId) def value: Int = id

    /** Sentinel for a game that hasn't been persisted yet (its id is DB-generated on create). */
    val unassigned: GameId = GameId(0)
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

    /** Sentinel for a role that hasn't been persisted yet (its id is DB-generated on create). Updating a game tells the
      * two apart by it: a role that carries a real id is one that exists and is being edited, one that carries this is
      * being added.
      */
    val unassigned: GameRoleId = GameRoleId(0)
}

opaque type GameParameterId = Int
object GameParameterId {
    def apply(value: Int): GameParameterId = value
    extension (id: GameParameterId) def value: Int = id

    /** Sentinel for a parameter that hasn't been persisted yet (its id is DB-generated on create). */
    val unassigned: GameParameterId = GameParameterId(0)
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

opaque type TournamentId = Long
object TournamentId {
    def apply(value: Long): TournamentId = value
    extension (id: TournamentId) def value: Long = id

    /** Sentinel for a tournament that hasn't been persisted yet (its id is DB-generated on create). */
    val unassigned: TournamentId = TournamentId(0)
}

/** A tournament model (V52): a design every game may offer, which a tournament is made from. Descriptive text, chosen
  * where the model is defined: `world-cup`, say.
  */
opaque type TournamentModelId = String
object TournamentModelId {
    def apply(value: String): TournamentModelId = value
    extension (id: TournamentModelId) def value: String = id
}

/** An entry in a tournament (V52): what `tournament-design.md` calls a participant, which `ParticipantId` already means
  * a seat in a match. The tournament's state names entrants by this id's value.
  */
opaque type EntrantId = Long
object EntrantId {
    def apply(value: Long): EntrantId = value
    extension (id: EntrantId) def value: Long = id
}
