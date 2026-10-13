package com.vivi.matchmaker.model.tournament
package standard

object SingleElimination
    extends StandardModel(
      "single-elimination",
      1,
      "Single Elimination",
      "Lose once and you are out, until one is left.",
      "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(brackets = false, rotateRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(StageDesign(repeat = Repetition.UntilRemaining(1), brackets = true, design = Some(eliminationMatch)))
    )
}
