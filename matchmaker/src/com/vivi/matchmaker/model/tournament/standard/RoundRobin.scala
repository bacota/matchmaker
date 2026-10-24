package com.vivi.matchmaker.model.tournament
package standard

/** Round Robin: the simplest tournament there is, one match of everyone who enters -- so there is no most to set, nor
  * to start on, and the form leaves both out.
  */
object RoundRobin
    extends StandardModel(
      id = "RR",
      version = 2,
      name = "Round Robin Challenge",
      description = "",
      classification = None,
      hasRoleChoice = true,
      formOverrides = Map("labels.competition.maxEntrants" -> None, "labels.competition.autoStart" -> None)
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
