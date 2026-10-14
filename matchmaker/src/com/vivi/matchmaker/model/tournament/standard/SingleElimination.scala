package com.vivi.matchmaker.model.tournament
package standard

object SingleElimination
    extends StandardModel(
      id = "single-elimination",
      version = 1,
      name = "Single Elimination",
      description = "Lose once and you are out, until one is left.",
      classification = "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(StageDesign(repeat = Repetition.UntilRemaining(1), hasBrackets = true, design = Some(eliminationMatch)))
    )
}
