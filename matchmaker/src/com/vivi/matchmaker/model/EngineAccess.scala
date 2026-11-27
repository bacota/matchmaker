package com.vivi.matchmaker.model

import java.time.Instant

/** A player who may play a restricted engine, and how they came to: `grantedBy` the admin who let them in, or `None`
  * when they redeemed a code, which is then `codeLabel` (`None` too if the code has since been deleted).
  */
case class EngineListedPlayer(
    player: PublicPlayer,
    grantedBy: Option[PublicPlayer],
    codeLabel: Option[String],
    grantedAt: Instant
)

/** An access code as its admins see it. The code itself is not here: it is stored only as a hash, and shown once, when
  * it is made ([[NewEngineAccessCode]]).
  */
case class EngineAccessCode(
    codeId: EngineAccessCodeId,
    label: String,
    maxUses: Option[Int],
    uses: Int,
    expiresAt: Option[Instant],
    isRevoked: Boolean,
    createdAt: Instant
) {

    /** Whether redeeming it now would let somebody in. */
    def isRedeemable(now: Instant): Boolean =
        !isRevoked && expiresAt.forall(now.isBefore) && maxUses.forall(uses < _)
}

/** A code just made: its row, and the code itself, which is never available again. */
case class NewEngineAccessCode(code: String, access: EngineAccessCode)

/** Some of an engine's codes that can still be redeemed, newest first, and whether there are older ones after them. */
case class EngineAccessCodePage(codes: List[EngineAccessCode], hasMore: Boolean)

/** Who may play a game's engine, for the game's admins: whether it is restricted, who is listed, its live codes a page
  * or more at a time, and the newest of those revoked, expired or used up.
  */
case class EngineAccess(
    isRestricted: Boolean,
    players: List[EngineListedPlayer],
    liveCodes: EngineAccessCodePage,
    endedCodes: List[EngineAccessCode]
)

/** What redeeming a code opened: the display names of the engine's games. */
case class RedeemedAccess(games: List[String])
