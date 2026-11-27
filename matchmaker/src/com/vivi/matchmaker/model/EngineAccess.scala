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

/** Who may play a game's engine, for the game's admins: whether it is restricted, who is listed, and the codes. */
case class EngineAccess(isRestricted: Boolean, players: List[EngineListedPlayer], codes: List[EngineAccessCode])

/** What redeeming a code opened: the display names of the engine's games. */
case class RedeemedAccess(games: List[String])
