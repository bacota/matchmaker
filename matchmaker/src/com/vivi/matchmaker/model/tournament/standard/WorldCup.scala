package com.vivi.matchmaker.model.tournament
package standard

object WorldCup
    extends StandardModel(
      id = "world-cup",
      version = 1,
      name = "Soccer World Cup",
      description = "Group round robins, then single elimination.",
      classification = Some("Pools, then elimination")
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // Groups of four, whose top two go through.
        StageDesign(
          poolSize = Some(4),
          hasBrackets = false,
          demote = Some(3),
          points = 2,
          hasPointsBonus = true,
          pointsForDraw = 1,
          seedPolicy = SeedPolicy.Reinitialize
        ),
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
