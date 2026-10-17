package com.vivi.matchmaker.model.tournament
package standard

object Ladder
    extends StandardModel(
      id = "ladder",
      version = 2,
      name = "Ladder",
      displayOrder = 110,
      description = "Play repeatedly; win to climb a rung, lose to drop one. Runs until stopped.",
      classification = "Ladder"
    ) {
    val design: TournamentDesign = TournamentDesign(
      List(
        StageDesign(
          repeat = Repetition.UntilStopped,
          hasBrackets = false,
          maxStratum = None,
          minStratum = None,
          poolSize = Some(2),
          promote = Some(1),
          demote = Some(2),
          hasStrictStrata = false,
          seedPolicy = SeedPolicy.Reinitialize
        )
      )
    )
}
