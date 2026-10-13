package com.vivi.matchmaker.model.tournament
package standard

object ModifiedDoubleElimination
    extends StandardModel(
      "modified-double-elimination",
      1,
      "Modified Double Elimination",
      "Lose twice and you are out, until a winner of each bracket is left to meet.",
      "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(brackets = false, rotateRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // A first loss drops a player into the losers' bracket, a stratum below; a second puts them out.
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          brackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          strictStrata = true,
          design = Some(eliminationMatch)
        )
      )
    )
}
