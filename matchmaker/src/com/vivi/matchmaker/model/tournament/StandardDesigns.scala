package com.vivi.matchmaker.model.tournament

/** A standard tournament format (`tournament-design.md`, "Standard Tournament Designs"), as a design built for a game.
  *
  * `isTwoRolesOnly` formats can only be played in a game of two required roles. `isNeedsPoolSize` formats take their
  * first stage's pool size from the tournament's default pool size, which whoever creates it may change.
  * `classification` is the group a model of it is listed under where a tournament is created.
  */
case class StandardDesign(
    key: String,
    name: String,
    description: String,
    isTwoRolesOnly: Boolean = false,
    isNeedsPoolSize: Boolean = false,
    classification: String = "Elimination"
)

object StandardDesigns {

    val all: List[StandardDesign] = List(
      StandardDesign("single-elimination", "Single Elimination", "Lose once and you are out, until one is left."),
      StandardDesign(
        "single-elimination-consolation",
        "Single Elimination with Consolation",
        "Single elimination, with a match for third place.",
        isTwoRolesOnly = true
      ),
      StandardDesign(
        "modified-double-elimination",
        "Modified Double Elimination",
        "Lose twice and you are out, until a winner of each bracket is left to meet.",
        isTwoRolesOnly = true
      ),
      StandardDesign(
        "double-elimination",
        "Double Elimination",
        "Lose twice and you are out; the two brackets' winners meet in a final.",
        isTwoRolesOnly = true
      ),
      StandardDesign(
        "heats",
        "Heats with Single Elimination",
        "Round-robin heats seed a single elimination.",
        isNeedsPoolSize = true,
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        "ladder",
        "Ladder",
        "Play repeatedly; win to climb a rung, lose to drop one. Runs until stopped.",
        classification = "Ladder"
      ),
      StandardDesign(
        "brazilian-repechage",
        "Brazilian Repechage",
        "Single elimination down to eight, then modified double elimination.",
        isTwoRolesOnly = true
      ),
      StandardDesign(
        "world-cup",
        "Soccer World Cup",
        "Group round robins, then single elimination.",
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        "fencing",
        "Fencing",
        "Pools whose winners go through to single elimination.",
        classification = "Pools, then elimination"
      ),
      StandardDesign(
        "ncaa",
        "NCAA Football Style",
        "Large round-robin conferences, then single elimination.",
        classification = "Pools, then elimination"
      )
    )

    def find(key: String): Option[StandardDesign] = all.find(_.key == key)

    /** One match, and the loser drops a stratum: the building block of the elimination formats. It is played in a pool
      * of two, so it may not be drawn ([[Ties]]).
      */
    private val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(isBrackets = false, isRotateRoles = false, demote = Some(2))))

    private def singleEliminationStage(until: Int): StageDesign =
        StageDesign(repeat = Repetition.UntilRemaining(until), isBrackets = true, design = Some(eliminationMatch))

    private def singleElimination: List[StageDesign] = List(singleEliminationStage(1))

    private def withConsolation: List[StageDesign] = List(
      StageDesign(
        repeat = Repetition.UntilRemaining(4),
        isBrackets = true,
        poolSize = Some(2),
        design = Some(eliminationMatch)
      ),
      StageDesign(
        isBrackets = true,
        poolSize = Some(2),
        minStratum = Some(-1),
        isStrictStrata = true,
        design = Some(eliminationMatch)
      ),
      StageDesign(
        isBrackets = false,
        poolSize = Some(2),
        minStratum = Some(-1),
        isStrictStrata = true,
        demote = None,
        design = Some(TournamentDesign(List(StageDesign(isBrackets = false, demote = None))))
      )
    )

    private def modifiedDoubleStage: StageDesign =
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          isBrackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          isStrictStrata = true,
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
            case None                                      => Left(s"there is no standard design called '$key'")
            case Some(std) if std.isTwoRolesOnly && r != 2 => Left(s"${std.name} needs a game of exactly two players")
            case Some(std) if std.isNeedsPoolSize && poolSize.exists(_ < 1) => Left(s"${std.name} needs a pool size")
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
                            isStrictStrata = false
                          )
                        )
                    case "heats" =>
                        // A round robin rather than a bracket: its pools are as many as the field needs. The
                        // knockout after it is sized for the game, since the heats take the tournament's default.
                        StageDesign(
                          poolSize = poolSize,
                          isBrackets = false,
                          seedPolicy = SeedPolicy.Previous
                        ) :: knockout(r).map(sized(_, r))
                    case "ladder" =>
                        List(
                          StageDesign(
                            repeat = Repetition.UntilStopped,
                            isBrackets = false,
                            maxStratum = None,
                            minStratum = None,
                            poolSize = Some(r),
                            promote = Some(1),
                            demote = Some(r),
                            isStrictStrata = false,
                            seedPolicy = SeedPolicy.Reinitialize
                          )
                        )
                    case "brazilian-repechage" => List(singleEliminationStage(8), modifiedDoubleStage)
                    case "world-cup" =>
                        StageDesign(
                          poolSize = Some(r + 2),
                          isBrackets = false,
                          demote = Some((r + 4) / 2),
                          points = 2,
                          isPointsBonus = true,
                          pointsForDraw = 1,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "fencing" =>
                        StageDesign(
                          poolSize = Some(r + 3),
                          isBrackets = false,
                          demote = Some((r + 3) / 2),
                          points = 1,
                          pointsForDraw = 0,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "ncaa" =>
                        StageDesign(
                          poolSize = Some(r + 8),
                          isBrackets = false,
                          demote = Some(2),
                          points = 2,
                          pointsForDraw = 1,
                          isRotateRoles = true,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                }))
        }
}
