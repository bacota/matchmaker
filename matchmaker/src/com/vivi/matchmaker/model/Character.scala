package com.vivi.matchmaker.model

case class Character[T](
    characterId: CharacterId,
    gameId: GameId,
    name: String,
    description: String,
    state: T,
    playerId: Option[PlayerId]
)

/** A character as its page shows it to anybody: its name, its game, what its owner says about it, and who owns it now
  * -- `None` for one nobody holds. Never its state, which is its owner's business (see [[CharacterName]]).
  */
case class CharacterProfile(
    character: CharacterName,
    gameName: String,
    description: String,
    owner: Option[PublicPlayer]
)
