package com.vivi.matchmaker.model.tournament

/** A standard tournament format (`tournament-design.md`, "Standard Tournament Designs"), as a design built for a game.
  *
  * `twoRolesOnly` formats can only be played in a game of two required roles. `needsPoolSize` formats take a pool size
  * from whoever creates the tournament.
  */
case class StandardDesign(
    key: String,
    name: String,
    description: String,
    twoRolesOnly: Boolean = false,
    needsPoolSize: Boolean = false
)

object StandardDesigns {

    val all: List[StandardDesign] = List(
      StandardDesign("single-elimination", "Single Elimination", "Lose once and you are out, until one is left."),
      StandardDesign(
        "single-elimination-consolation",
        "Single Elimination with Consolation",
        "Single elimination, with a match for third place.",
        twoRolesOnly = true
      ),
      StandardDesign(
        "modified-double-elimination",
        "Modified Double Elimination",
        "Lose twice and you are out, until a winner of each bracket is left to meet.",
        twoRolesOnly = true
      ),
      StandardDesign(
        "double-elimination",
        "Double Elimination",
        "Lose twice and you are out; the two brackets' winners meet in a final.",
        twoRolesOnly = true
      ),
      StandardDesign(
        "heats",
        "Heats with Single Elimination",
        "Round-robin heats seed a single elimination.",
        needsPoolSize = true
      ),
      StandardDesign("ladder", "Ladder", "Play repeatedly; win to climb a rung, lose to drop one. Runs until stopped."),
      StandardDesign(
        "brazilian-repechage",
        "Brazilian Repechage",
        "Single elimination down to eight, then modified double elimination.",
        twoRolesOnly = true
      ),
      StandardDesign("world-cup", "Soccer World Cup", "Group round robins, then single elimination."),
      StandardDesign("fencing", "Fencing", "Pools whose winners go through to single elimination."),
      StandardDesign("ncaa", "NCAA Football Style", "Large round-robin conferences, then single elimination.")
    )

    def find(key: String): Option[StandardDesign] = all.find(_.key == key)

    /** One match, no draws, and the loser drops a stratum: the building block of the elimination formats. */
    val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(allowTies = false, brackets = false, rotateRoles = false, demote = Some(2))))

    private def singleEliminationStage(until: Int): StageDesign =
        StageDesign(repeat = Repetition.UntilRemaining(until), brackets = true, design = Some(eliminationMatch))

    private def singleElimination: List[StageDesign] = List(singleEliminationStage(1))

    private def withConsolation: List[StageDesign] = List(
      StageDesign(
        repeat = Repetition.UntilRemaining(4),
        brackets = true,
        poolSize = Some(2),
        design = Some(eliminationMatch)
      ),
      StageDesign(
        brackets = true,
        poolSize = Some(2),
        minStratum = Some(-1),
        strictStrata = true,
        design = Some(eliminationMatch)
      ),
      StageDesign(
        brackets = false,
        poolSize = Some(2),
        minStratum = Some(-1),
        strictStrata = true,
        demote = None,
        design = Some(TournamentDesign(List(StageDesign(allowTies = false, brackets = false, demote = None))))
      )
    )

    private def modifiedDoubleStage: StageDesign =
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          allowTies = false,
          brackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          strictStrata = true,
          design = Some(eliminationMatch)
        )

    /** Single elimination as the second half of a format: with a third-place match in a two-role game. */
    private def knockout(r: Int): List[StageDesign] = if (r == 2) withConsolation else singleElimination

    /** The design of a standard format for a game whose R is `r`, or why it cannot be built. */
    def build(key: String, r: Int, poolSize: Option[Int]): Either[String, TournamentDesign] =
        find(key) match {
            case None                                    => Left(s"there is no standard design called '$key'")
            case Some(std) if std.twoRolesOnly && r != 2 => Left(s"${std.name} needs a game of exactly two players")
            case Some(std) if std.needsPoolSize && poolSize.forall(_ < 1) => Left(s"${std.name} needs a pool size")
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
                            strictStrata = false
                          )
                        )
                    case "heats" =>
                        // A round robin rather than a bracket: its pools are as many as the field needs.
                        StageDesign(
                          poolSize = poolSize,
                          brackets = false,
                          seedPolicy = SeedPolicy.Previous
                        ) :: knockout(r)
                    case "ladder" =>
                        List(
                          StageDesign(
                            repeat = Repetition.UntilStopped,
                            brackets = false,
                            maxStratum = None,
                            minStratum = None,
                            poolSize = Some(r),
                            promote = Some(1),
                            demote = Some(r),
                            strictStrata = false,
                            seedPolicy = SeedPolicy.Reinitialize
                          )
                        )
                    case "brazilian-repechage" => List(singleEliminationStage(8), modifiedDoubleStage)
                    case "world-cup" =>
                        StageDesign(
                          poolSize = Some(r + 2),
                          brackets = false,
                          demote = Some((r + 4) / 2),
                          points = 2,
                          pointsBonus = true,
                          pointsForDraw = 1,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "fencing" =>
                        StageDesign(
                          poolSize = Some(r + 3),
                          brackets = false,
                          demote = Some((r + 3) / 2),
                          points = 1,
                          pointsForDraw = 0,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                    case "ncaa" =>
                        StageDesign(
                          poolSize = Some(r + 8),
                          brackets = false,
                          demote = Some(2),
                          points = 2,
                          pointsForDraw = 1,
                          rotateRoles = true,
                          seedPolicy = SeedPolicy.Reinitialize
                        ) :: knockout(r)
                }))
        }
}
