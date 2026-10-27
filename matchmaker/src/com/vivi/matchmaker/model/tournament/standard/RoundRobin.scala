package com.vivi.matchmaker.model.tournament
package standard

/** Round Robin: the simplest tournament there is, one match of everyone who enters -- so there is no most to set, nor
  * to start on, and the form leaves both out, with late entries. Its pool is everyone taking part, so the form calls
  * the pool size the number of participants, without the tip about pools, and says what starting by itself means here.
  */
object RoundRobin
    extends StandardModel(
      id = "RR",
      version = 7,
      name = "Round Robin Challenge",
      description = "",
      classification = None,
      hasRoleChoice = true,
      hasLateEntries = false,
      hasPoolOfEveryone = true,
      formOverrides = Map(
        "labels.competition.poolSize" -> None,
        "labels.competition.maxEntrants" -> Some("Players in competition"),
        "tips.competition.maxEntrants" -> Some(
          "Everyone who enters plays everyone else. Leave blank for no limit."
        ),
        "tips.competition.autoStart" -> Some(
          "Start as soon as everybody signs up. If there is more than one round, then start each round as soon as the " +
              "previous round ends."
        ),
        "labels.competition.lateEntries" -> None
      )
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
