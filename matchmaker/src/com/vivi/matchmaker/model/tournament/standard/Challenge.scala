package com.vivi.matchmaker.model.tournament
package standard

/** A challenge: the simplest tournament there is, one match of everyone who enters.
  *
  * One atomic stage, played once, in one pool. Its pool size is the tournament's default, which must be between the
  * game's required roles and all its roles (`TournamentService`), so the pool plays a single match of everyone
  * ([[Schedule.roundRobin]]), and is also the most it takes: a challenge's `max_entrants`. Nobody is demoted, and its
  * match may be drawn, whatever its size. Its seats choose their roles once it starts, the strongest seed first: a
  * challenge's players no longer pick a role as they join.
  */
object Challenge
    extends StandardModel(
      "challenge",
      1,
      "Challenge",
      "One match, of everyone who enters. The players choose their roles once it starts.",
      "Challenge",
      chooseRoles = true
    ) {

    /** The model's id, which the challenge screens find their tournaments by. */
    val modelId: com.vivi.matchmaker.model.TournamentModelId = com.vivi.matchmaker.model.TournamentModelId(id)

    val design: TournamentDesign = TournamentDesign(
      List(
        StageDesign(
          repeat = Repetition.Fixed(1),
          brackets = false,
          demote = None,
          promote = None,
          tiesAllowed = Some(true),
          seedPolicy = SeedPolicy.Constant
        )
      )
    )
}
