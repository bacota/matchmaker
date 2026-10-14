package com.vivi.matchmaker.model.tournament
package standard

object SingleEliminationConsolation
    extends StandardModel(
      id = "single-elimination-consolation",
      version = 1,
      name = "Single Elimination with Consolation",
      description = "Single elimination, with a match for third place.",
      classification = "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // Single elimination down to the semi-finals.
        StageDesign(
          repeat = Repetition.UntilRemaining(4),
          hasBrackets = true,
          poolSize = Some(2),
          design = Some(eliminationMatch)
        ),
        // The final, with the semi-finals' losers kept apart a stratum below.
        StageDesign(
          hasBrackets = true,
          poolSize = Some(2),
          minStratum = Some(-1),
          hasStrictStrata = true,
          design = Some(eliminationMatch)
        ),
        // The third-place match, which may be drawn.
        StageDesign(
          hasBrackets = false,
          poolSize = Some(2),
          minStratum = Some(-1),
          hasStrictStrata = true,
          demote = None,
          design = Some(TournamentDesign(List(StageDesign(hasBrackets = false, demote = None))))
        )
      )
    )
}
