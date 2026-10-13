package com.vivi.matchmaker.model.tournament

/** A standard tournament format (`tournament-design.md`, "Standard Tournament Designs"), as a design built for a game.
  *
  * `twoRolesOnly` formats can only be played in a game of two required roles. `needsPoolSize` formats take their first
  * stage's pool size from the tournament's default pool size, which whoever creates it may change. `classification` is
  * the group a model of it is listed under where a tournament is created.
  */
case class StandardDesign(
    key: String,
    name: String,
    description: String,
    twoRolesOnly: Boolean = false,
    needsPoolSize: Boolean = false,
    classification: String = "Elimination"
)

/** A model to offer a game (V52): a standard format built for it, with the default pool size to suggest (`None`, the
  * game's R) and the group it is listed under.
  */
case class ModelSeed(
    key: String,
    name: String,
    description: String,
    classification: String,
    poolSize: Option[Int],
    design: TournamentDesign
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
        needsPoolSize = true,
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
        twoRolesOnly = true
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
    val eliminationMatch: TournamentDesign =
        TournamentDesign(List(StageDesign(brackets = false, rotateRoles = false, demote = Some(2))))

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
        design = Some(TournamentDesign(List(StageDesign(brackets = false, demote = None))))
      )
    )

    private def modifiedDoubleStage: StageDesign =
        StageDesign(
          repeat = Repetition.UntilRemaining(2),
          poolSize = Some(2),
          brackets = true,
          demote = Some(2),
          minStratum = Some(-1),
          strictStrata = true,
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

    /** The models a game whose R is `r` is offered to begin with: every standard format that can be played in it. A
      * format that takes its pool size from the tournament leaves its first stage unsized, and suggests a pool of `r +
      * 2`; every other format suggests the game's R.
      */
    def models(r: Int): List[ModelSeed] =
        all.flatMap { std =>
            build(std.key, r, None).toOption.map { design =>
                ModelSeed(
                  std.key,
                  std.name,
                  std.description,
                  std.classification,
                  Option.when(std.needsPoolSize)(r + 2),
                  design
                )
            }
        }

    /** The design of a standard format for a game whose R is `r`, or why it cannot be built. A format that takes its
      * pool size from the tournament is built with `poolSize` as its first stage's, `None` for the tournament's
      * default.
      */
    def build(key: String, r: Int, poolSize: Option[Int]): Either[String, TournamentDesign] =
        find(key) match {
            case None                                    => Left(s"there is no standard design called '$key'")
            case Some(std) if std.twoRolesOnly && r != 2 => Left(s"${std.name} needs a game of exactly two players")
            case Some(std) if std.needsPoolSize && poolSize.exists(_ < 1) => Left(s"${std.name} needs a pool size")
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
                        // A round robin rather than a bracket: its pools are as many as the field needs. The
                        // knockout after it is sized for the game, since the heats take the tournament's default.
                        StageDesign(
                          poolSize = poolSize,
                          brackets = false,
                          seedPolicy = SeedPolicy.Previous
                        ) :: knockout(r).map(sized(_, r))
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
