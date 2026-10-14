package com.vivi.matchmaker.model

/** One of a game's parameters as a match is being played under it: the name players are shown for it, and its value.
  *
  * The value is its tournament's choice where it made one the game still allows, and the game's default otherwise —
  * what `ChallengeSettings.resolve` tells the engine as the match is created. Resolved when the list is read, against
  * the game as it is now, so a value an admin has since removed reads as the default rather than as what the engine was
  * sent.
  */
case class MatchParameter(
    displayName: String,
    value: String
)
