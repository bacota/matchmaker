package com.vivi.matchmaker.model.tournament
package standard

object DoubleElimination
    extends StandardModel(
      "double-elimination",
      1,
      "Double Elimination",
      "Lose twice and you are out; the two brackets' winners meet in a final.",
      "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // Modified double elimination, until a winner of each bracket is left.
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          hasBrackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          hasStrictStrata = true,
          design = Some(eliminationMatch)
        ),
        // The final between them.
        StageDesign(design = Some(eliminationMatch), promote = Some(1), demote = Some(2), hasStrictStrata = false)
      )
    )
}
