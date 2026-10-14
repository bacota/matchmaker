package com.vivi.matchmaker.model.tournament
package standard

object BrazilianRepechage
    extends StandardModel(
      "brazilian-repechage",
      1,
      "Brazilian Repechage",
      "Single elimination down to eight, then modified double elimination.",
      "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        StageDesign(repeat = Repetition.UntilRemaining(8), hasBrackets = true, design = Some(eliminationMatch)),
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          hasBrackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          hasStrictStrata = true,
          design = Some(eliminationMatch)
        )
      )
    )
}
