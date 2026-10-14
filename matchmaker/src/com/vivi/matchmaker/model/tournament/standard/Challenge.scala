package com.vivi.matchmaker.model.tournament
package standard

/** A challenge: the simplest tournament there is, one match of everyone who enters.
  *
  * One atomic stage, played once. Its pool size is the tournament's default, which `createChallenge` also makes the
  * most it takes (`max_entrants`); with no more of them than the game has roles, the one pool plays a single match of
  * everyone ([[Schedule.roundRobin]]), and with more it plays a round robin. Nobody is demoted, and its matches may be
  * drawn, whatever their size. Its seats choose their roles once it starts, the strongest seed first: a challenge's
  * players no longer pick a role as they join.
  */
object Challenge
    extends StandardModel(
      id = "challenge",
      version = 1,
      name = "Challenge",
      description = "One match, of everyone who enters. The players choose their roles once it starts.",
      classification = "Challenge",
      hasRoleChoice = true
    ) {

    /** The model's id, which the challenge screens find their tournaments by. */
    val modelId: com.vivi.matchmaker.model.TournamentModelId = com.vivi.matchmaker.model.TournamentModelId(id)

    val design: TournamentDesign = TournamentDesign(
      List(
        StageDesign(
          repeat = Repetition.Fixed(1),
          hasBrackets = false,
          demote = None,
          promote = None,
          canTie = Some(true),
          seedPolicy = SeedPolicy.Constant
        )
      )
    )
}
