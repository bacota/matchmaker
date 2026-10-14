package com.vivi.matchmaker.model.tournament
package standard

/** Round-robin heats of the tournament's default pool size -- four suggested -- seeding a knockout of pairs. */
object Heats
    extends StandardModel(
      "heats",
      1,
      "Heats with Single Elimination",
      "Round-robin heats seed a single elimination.",
      "Pools, then elimination",
      poolSize = Some(4)
    ) {

    /** One match, and the loser drops a stratum. It is played in a pool of two, so it may not be drawn. */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(
          List(StageDesign(isBrackets = false, isRotateRoles = false, demote = Some(2), poolSize = Some(2)))
        )

    val design: TournamentDesign = TournamentDesign(
      List(
        // A round robin rather than a bracket: its pools are as many as the field needs.
        StageDesign(poolSize = None, isBrackets = false, seedPolicy = SeedPolicy.Previous),
        // The knockout after it sizes every pool, since the heats take the tournament's default: single elimination
        // down to the semi-finals.
        StageDesign(
          repeat = Repetition.UntilRemaining(4),
          isBrackets = true,
          poolSize = Some(2),
          design = Some(eliminationMatch)
        ),
        // The final, with the semi-finals' losers kept apart a stratum below.
        StageDesign(
          isBrackets = true,
          poolSize = Some(2),
          minStratum = Some(-1),
          isStrictStrata = true,
          design = Some(eliminationMatch)
        ),
        // The third-place match, which may be drawn.
        StageDesign(
          isBrackets = false,
          poolSize = Some(2),
          minStratum = Some(-1),
          isStrictStrata = true,
          demote = None,
          design = Some(TournamentDesign(List(StageDesign(isBrackets = false, demote = None, poolSize = Some(2)))))
        )
      )
    )
}
