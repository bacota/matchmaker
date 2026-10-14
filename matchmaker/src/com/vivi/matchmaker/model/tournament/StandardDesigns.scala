package com.vivi.matchmaker.model.tournament

/** A standard tournament format (`tournament-design.md`, "Standard Tournament Designs"), as a design built for a game.
  *
  * `hasTwoRolesOnly` formats can only be played in a game of two required roles. `hasPoolSize` formats take their first
  * stage's pool size from the tournament's default pool size, which whoever creates it may change. `classification` is
  * the group a model of it is listed under where a tournament is created.
  */
case class StandardDesign(
    key: String,
    name: String,
    description: String,
    hasTwoRolesOnly: Boolean = false,
    hasPoolSize: Boolean = false,
    classification: String = "Elimination"
)

object StandardDesigns {

    val all: List[StandardDesign] = List(
      StandardDesign(
        key = "single-elimination",
        name = "Single Elimination",
        description = "Lose once and you are out, until one is left."
      ),
      StandardDesign(
        key = "single-elimination-consolation",
        name = "Single Elimination with Consolation",
        description = "Single elimination, with a match for third place.",
        hasTwoRolesOnly = true
      ),
      StandardDesign(
        key = "modified-double-elimination",
        name = "Modified Double Elimination",
        description = "Lose twice and you are out, until a winner of each bracket is left to meet.",
        hasTwoRolesOnly = true
      ),
      StandardDesign(
        key = "double-elimination",
        name = "Double Elimination",
        description = "Lose twice and you are out; the two brackets' winners meet in a final.",
        hasTwoRolesOnly = true
      ),
      StandardDesign(
        key = "heats",
        name = "Heats with Single Elimination",
        description = "Round-robin heats seed a single elimination.",
        hasPoolSize = true,
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        key = "ladder",
        name = "Ladder",
        description = "Play repeatedly; win to climb a rung, lose to drop one. Runs until stopped.",
        classification = "Ladder"
      ),
      StandardDesign(
        key = "brazilian-repechage",
        name = "Brazilian Repechage",
        description = "Single elimination down to eight, then modified double elimination.",
        hasTwoRolesOnly = true
      ),
      StandardDesign(
        key = "world-cup",
        name = "Soccer World Cup",
        description = "Group round robins, then single elimination.",
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        key = "fencing",
        name = "Fencing",
        description = "Pools whose winners go through to single elimination.",
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        key = "ncaa",
        name = "NCAA Football Style",
        description = "Large round-robin conferences, then single elimination.",
        classification = "Pools, then elimination"
      )
    )

    def find(key: String): Option[StandardDesign] = all.find(_.key == key)

    /** One match, and the loser drops a stratum: the building block of the elimination formats. It is played in a pool
      * of two, so it may not be drawn ([[Ties]]).
      */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(hasBrackets = false, isRotatingRoles = false, demote = Some(2))))

    private def singleEliminationStage(until: Int): StageDesign =
        StageDesign(repeat = Repetition.UntilRemaining(until), hasBrackets = true, design = Some(eliminationMatch))

    private def singleElimination: List[StageDesign] = List(singleEliminationStage(1))

    private def withConsolation: List[StageDesign] = List(
      StageDesign(
        repeat = Repetition.UntilRemaining(4),
        hasBrackets = true,
        poolSize = Some(2),
        design = Some(eliminationMatch)
      ),
      StageDesign(
        hasBrackets = true,
        poolSize = Some(2),
        minStratum = Some(-1),
        hasStrictStrata = true,
        design = Some(eliminationMatch)
      ),
      StageDesign(
        hasBrackets = false,
        poolSize = Some(2),
        minStratum = Some(-1),
        hasStrictStrata = true,
        demote = None,
        design = Some(TournamentDesign(List(StageDesign(hasBrackets = false, demote = None))))
      )
    )

    private def modifiedDoubleStage: StageDesign =
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          hasBrackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          hasStrictStrata = true,
          design = Some(eliminationMatch)
        )

    /** Single elimination as the second half of a format: with a third-place match in a two-role game. */
    private def knockout(r: Int): List[StageDesign] = if (r == 2) withConsolation else singleElimination

    /** `stage`, and every stage nested in it, with an explicit pool size of `r` wherever it set none. */
    private def sized(stage: StageDesign, r: Int): StageDesign =
        stage.copy(
          poolSize = stage.poolSize.orElse(Some(r)),
          design = stage.design.map(d => TournamentDesign(d.stages.map(sized(_, r))))
        )

    /** The design of a standard format for a game whose R is `r`, or why it cannot be built. A format that takes its
      * pool size from the tournament is built with `poolSize` as its first stage's, `None` for the tournament's
      * default.
      */
    def build(key: String, r: Int, poolSize: Option[Int]): Either[String, TournamentDesign] =
        find(key) match {
            case None                                       => Left(s"there is no standard design called '$key'")
            case Some(std) if std.hasTwoRolesOnly && r != 2 => Left(s"${std.name} needs a game of exactly two players")
            case Some(std) if std.hasPoolSize && poolSize.exists(_ < 1) => Left(s"${std.name} needs a pool size")
            case Some(_) =>
                Right(TournamentDesign(key match {
                    case "single-elimination"             => singleElimination
                    case "single-elimination-consolation" => withConsolation
                    case "modified-double-elimination"    => List(modifiedDoubleStage)
                    case "double-elimination" =>
                        List(
                          modifiedDoubleStage,
                          StageDesign(
                            design = Some(eliminationMatch),
                            promote = Some(1),
                            demote = Some(2),
                            hasStrictStrata = false
                          )
                        )
                    case "heats" =>
                        // A round robin rather than a bracket: its pools are as many as the field needs. The
                        // knockout after it is sized for the game, since the heats take the tournament's default.
                        StageDesign(
                          poolSize = poolSize,
                          hasBrackets = false,
                          seedPolicy = SeedPolicy.Previous
                        ) :: knockout(r).map(sized(_, r))
                    case "ladder" =>
                        List(
                          StageDesign(
                            repeat = Repetition.UntilStopped,
                            hasBrackets = false,
                            maxStratum = None,
                            minStratum = None,
                            poolSize = Some(r),
                            promote = Some(1),
                            demote = Some(r),
                            hasStrictStrata = false,
                            seedPolicy = SeedPolicy.Reinitialize
                          )
                        )
                    case "brazilian-repechage" => List(singleEliminationStage(8), modifiedDoubleStage)
                    case "world-cup" =>
                        StageDesign(
                          poolSize = Some(r + 2),
                          hasBrackets = false,
                          demote = Some((r + 4) / 2),
                          points = 2,
                          hasPointsBonus = true,
                          pointsForDraw = 1,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "fencing" =>
                        StageDesign(
                          poolSize = Some(r + 3),
                          hasBrackets = false,
                          demote = Some((r + 3) / 2),
                          points = 1,
                          pointsForDraw = 0,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "ncaa" =>
                        StageDesign(
                          poolSize = Some(r + 8),
                          hasBrackets = false,
                          demote = Some(2),
                          points = 2,
                          pointsForDraw = 1,
                          isRotatingRoles = true,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                }))
        }
}
