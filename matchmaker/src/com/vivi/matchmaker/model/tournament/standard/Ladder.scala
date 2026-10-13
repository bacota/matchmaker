package com.vivi.matchmaker.model.tournament
package standard

object Ladder
    extends StandardModel(
      "ladder",
      1,
      "Ladder",
      "Play repeatedly; win to climb a rung, lose to drop one. Runs until stopped.",
      "Ladder"
    ) {
    val design: TournamentDesign = TournamentDesign(
      List(
        StageDesign(
          repeat = Repetition.UntilStopped,
          brackets = false,
          maxStratum = None,
          minStratum = None,
          poolSize = Some(2),
          promote = Some(1),
          demote = Some(2),
          strictStrata = false,
          seedPolicy = SeedPolicy.Reinitialize
        )
      )
    )
}
