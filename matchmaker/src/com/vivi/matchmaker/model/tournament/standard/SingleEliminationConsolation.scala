package com.vivi.matchmaker.model.tournament
package standard

object SingleEliminationConsolation
    extends StandardModel(
      "single-elimination-consolation",
      1,
      "Single Elimination with Consolation",
      "Single elimination, with a match for third place.",
      "Elimination"
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(brackets = false, rotateRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // Single elimination down to the semi-finals.
        StageDesign(
          repeat = Repetition.UntilRemaining(4),
          brackets = true,
          poolSize = Some(2),
          design = Some(eliminationMatch)
        ),
        // The final, with the semi-finals' losers kept apart a stratum below.
        StageDesign(
          brackets = true,
          poolSize = Some(2),
          minStratum = Some(-1),
          strictStrata = true,
          design = Some(eliminationMatch)
        ),
        // The third-place match, which may be drawn.
        StageDesign(
          brackets = false,
          poolSize = Some(2),
          minStratum = Some(-1),
          strictStrata = true,
          demote = None,
          design = Some(TournamentDesign(List(StageDesign(brackets = false, demote = None))))
        )
      )
    )
}
