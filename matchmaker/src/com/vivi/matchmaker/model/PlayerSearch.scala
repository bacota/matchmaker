package com.vivi.matchmaker.model

/** A player as everybody else may see them.
  *
  * Two fields, and deliberately not the other three: `email` is an address nobody else has been given, `externalId` is
  * the Cognito identity and names the account rather than the player, and `isAdmin` says who can edit the game
  * catalogue, which is nobody else's business. A search answers with these rather than with `Player` so that widening
  * what a stranger can see has to be a deliberate edit here, not a field quietly added to `Player` for the account
  * screen and carried along.
  */
case class PublicPlayer(playerId: PlayerId, nickname: String)

/** The answer to a nickname search: who was found, and whether that is all of them.
  *
  * `more` is true when the search matched more players than the page it is showing. It is the answer to a question the
  * list itself cannot settle -- a full page might be the last one -- and without it the only honest caption would be
  * "possibly more", on every search that filled up.
  */
case class PlayerSearchResult(players: List[PublicPlayer], more: Boolean)
