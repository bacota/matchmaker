package com.vivi.matchmaker.model.tournament
package standard

object Ncaa
    extends StandardModel(
      id = "ncaa",
      version = 2,
      name = "NCAA Football Style",
      displayOrder = 100,
      description = "Large round-robin conferences, then single elimination.",
      classification = Some("Pools, then elimination")
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    val design: TournamentDesign = TournamentDesign(
      List(
        // Conferences of ten, whose winner goes through.
        StageDesign(
          poolSize = Some(10),
          hasBrackets = false,
          demote = Some(2),
          points = 2,
          pointsForDraw = 1,
          isRotatingRoles = true,
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
