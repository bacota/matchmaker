package com.vivi.matchmaker.model.tournament

/** How many times a stage is played (`tournament-design.md`, "Tournament Design"). */
enum Repetition {

    /** A fixed number of times, often once. */
    case Fixed(times: Int)

    /** Again and again until no more than `count` participants remain in it. */
    case UntilRemaining(count: Int)

    /** Again and again until the tournament's owner stops it. */
    case UntilStopped
}

/** How seeds are worked out after a stage (`tournament-design.md`, "Seed Policy"). */
enum SeedPolicy(val code: String, val label: String) {

    /** The seeds the participants had when the tournament started. */
    case Constant extends SeedPolicy("CONST", "Constant")

    /** From the participants' ratings as they stand now. */
    case Reinitialize extends SeedPolicy("ELO", "Re-initialize")

    /** From how they did in the stage: stratum, rank, points, score differential, total score, then rating. */
    case Previous extends SeedPolicy("PREV", "Previous")

    /** The seeds that entered each pool, handed back out in the order its members finished. */
    case WinnerTakesSeed extends SeedPolicy("WINNER", "Winner takes seed")
}

object SeedPolicy {
    def fromCode(code: String): SeedPolicy =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown seed policy '$code'"))
}

/** A tournament's design: its stages, in the order they are played. A stage's pools may themselves play a design, which
  * is how a tournament nests.
  */
case class TournamentDesign(stages: List[StageDesign])

/** One stage of a design, with the defaults `tournament-design.md` gives each property: a `stage` row, or a
  * `stage_model` row of a model (V52).
  *
  * `design` is the tournament each pool plays: set, the stage is a nesting stage; unset, an atomic one, whose pools
  * play a round robin: every `R`-member subset of the pool plays one match, `R` times over with the roles turned when
  * `rotateRoles` is set. A pool of `R` therefore plays a single match, which is all an *Elimination Match* is. Whether
  * a match may end in a draw follows from its pool: not in a pool of two, which is an elimination match; otherwise yes.
  *
  * `poolSize` is how many a pool holds, `None` for the tournament's default pool size. `maxStratum` and `minStratum`
  * are `None` for no bound. `demote` and `promote` are ranks: at or below `demote` a participant moves down a stratum,
  * at or above `promote` up one. `concurrentRounds` lets a round-robin pool play all of its matches in one round,
  * rather than each member one match a round. `rubberMatch` breaks a tie on points with one more match, in a game of
  * two roles; otherwise ties go to scores.
  */
case class StageDesign(
    repeat: Repetition = Repetition.Fixed(1),
    brackets: Boolean = true,
    demote: Option[Int] = Some(2),
    promote: Option[Int] = None,
    maxStratum: Option[Int] = Some(0),
    minStratum: Option[Int] = Some(0),
    points: Int = 2,
    pointsForDraw: Int = 1,
    pointsBonus: Boolean = false,
    poolSize: Option[Int] = None,
    concurrentRounds: Boolean = false,
    rotateRoles: Boolean = false,
    strictStrata: Boolean = true,
    design: Option[TournamentDesign] = None,
    tieBreakerStage: Boolean = false,
    seedPolicy: SeedPolicy = SeedPolicy.WinnerTakesSeed,
    rubberMatch: Boolean = false,
    name: String = "",
    description: String = ""
) {

    /** How many advance from a pool: everyone ranked above `demote`. `None` when nobody is demoted. */
    def advancing: Option[Int] = demote.map(_ - 1)

    /** The size of this stage's pools, given the tournament's default for a stage that sets none. */
    def poolSizeOr(default: Int): Int = poolSize.getOrElse(default)

    /** The most matches a member of one of this stage's pools plays in a round. */
    def concurrent: Int = if (concurrentRounds) Int.MaxValue else 1
}

/** Whether a match in a pool of `members` may end in a draw: not in a pool of two, which is an elimination match that
  * must send one of them on; otherwise yes. A rubber match never may, whatever its pool: it exists to break a tie.
  */
object Ties {
    def allowed(members: Int, rubber: Boolean = false): Boolean = !rubber && members > 2
}

/** One of a game's roles, as a tournament needs to know it. */
case class TournamentRole(id: Int, roleType: com.vivi.matchmaker.model.RoleType, preferenceLevel: Option[Int])

/** What a tournament needs to know about its game.
  *
  * `roles` are all the game's roles in the order the game lists them. `significantRoles` is the negation of the game's
  * `unimportant_roles`: whether it matters who plays which.
  */
case class TournamentGame(
    roles: List[TournamentRole],
    significantRoles: Boolean,
    moveType: com.vivi.matchmaker.model.MoveType,
    maxTurns: Option[Int]
) {
    import com.vivi.matchmaker.model.RoleType

    /** The game's "R": its required and recommended roles, which is what a pool is sized by. */
    def poolSeats: Int = roles.count(_.roleType != RoleType.Optional)

    /** How many members a match needs. */
    def requiredSeats: Int = roles.count(_.roleType == RoleType.Required)

    /** The roles a match seats, required first and then recommended, each in the game's own order. */
    def seatingRoles: List[TournamentRole] =
        roles.filter(_.roleType == RoleType.Required) ++ roles.filter(_.roleType == RoleType.Recommended)

    /** Whether a rubber match can break a tie: only in a game of exactly two roles (Q7). */
    def rubberApplies: Boolean = roles.sizeIs == 2
}

object TournamentGame {
    def of(game: com.vivi.matchmaker.model.Game): TournamentGame =
        TournamentGame(
          game.roles.toList
              .sortBy(_.gameRoleId.value)
              .map(r => TournamentRole(r.gameRoleId.value, r.roleType, r.preferenceLevel)),
          significantRoles = !game.unimportantRoles,
          moveType = game.moveType,
          maxTurns = game.maxTurns
        )
}

/** Something wrong with a design, found before a tournament is created from it. A warning is something the creator
  * should know but may go ahead with.
  */
case class Problem(where: String, message: String, warning: Boolean = false) {
    def describe: String = if (where.isEmpty) message else s"$where: $message"
}

object Validation {

    /** The most matches one round-robin pool may schedule (`tournament-design.md`, "Round Robin Tournaments"). */
    val MaxMatchesPerPool = 1000

    /** How deep designs may nest: a stage's pools playing a design whose stages' pools play a design, and so on. */
    val MaxDepth = 4

    /** Everything wrong with a tournament's design for this game: the design's problems, and the game's own. `maxTurns`
      * is the tournament's, for a game whose players move at once; `defaultPoolSize` is the tournament's, for a stage
      * that sets no pool size.
      */
    def validate(
        design: TournamentDesign,
        game: TournamentGame,
        maxTurns: Option[Int],
        defaultPoolSize: Option[Int]
    ): List[Problem] = {
        val gameProblems =
            List(
              Option.when(game.requiredSeats == 0)(
                Problem("", "the game has no required role, so no match could start")
              ),
              Option.when(
                game.moveType == com.vivi.matchmaker.model.MoveType.Simultaneous && game.maxTurns.isEmpty &&
                    maxTurns.isEmpty
              )(
                Problem(
                  "",
                  "this game's players move at once and it has no maximum number of turns, so the " +
                      "tournament must give one"
                )
              )
            ).flatten
        gameProblems ++ designProblems(design, game, defaultPoolSize)
    }

    /** Everything wrong with a design itself, as a model holds it: before any tournament gives it a default pool size,
      * a stage that sets none is checked as pools of the game's R.
      */
    def designProblems(design: TournamentDesign, game: TournamentGame, defaultPoolSize: Option[Int]): List[Problem] =
        check(design, game, "", defaultPoolSize.getOrElse(game.poolSeats), 1)

    private def check(
        design: TournamentDesign,
        game: TournamentGame,
        path: String,
        defaultPoolSize: Int,
        depth: Int
    ): List[Problem] = {
        val here = if (path.isEmpty) "" else path
        if (depth > MaxDepth) List(Problem(here, s"designs may nest at most $MaxDepth deep"))
        else if (design.stages.isEmpty) List(Problem(here, "a design needs at least one stage"))
        else
            design.stages.zipWithIndex.flatMap { (stage, i) =>
                val where = (if (path.isEmpty) "" else s"$path, ") + s"stage ${i + 1}"
                stageProblems(stage, game, where, defaultPoolSize) ++
                    stage.design.toList.flatMap(check(_, game, s"$where's pools", defaultPoolSize, depth + 1))
            }
    }

    private def stageProblems(
        stage: StageDesign,
        game: TournamentGame,
        where: String,
        defaultPoolSize: Int
    ): List[Problem] = {
        val n = stage.poolSizeOr(defaultPoolSize)
        def p(message: String) = Problem(where, message)
        def w(message: String) = Problem(where, message, warning = true)
        List(
          stage.repeat match {
              case Repetition.Fixed(k) if k < 1 => Some(p("a stage must be played at least once"))
              case Repetition.UntilRemaining(c) if c < 1 =>
                  Some(p("a stage must stop with at least one participant left"))
              case _ => None
          },
          Option.when(n < 1)(p("a pool must hold at least one participant")),
          Option.when(stage.demote.exists(_ <= 1))(p("demote must be greater than 1")),
          Option.when(stage.promote.exists(_ < 1))(p("promote must be at least 1")),
          Option.when(stage.promote.zip(stage.demote).exists((pr, de) => pr >= de))(
            p("promote must be a better rank than demote")
          ),
          Option.when(stage.minStratum.zip(stage.maxStratum).exists((lo, hi) => lo > hi))(
            p("the minimum stratum cannot be above the maximum")
          ),
          Option.when(stage.points < 0 || stage.pointsForDraw < 0)(p("points cannot be negative")),
          // Brackets: the pools are a power of n/a, which has to be a whole number above 1.
          if (!stage.brackets) None
          else
              stage.advancing.filter(_ > 0) match {
                  case None => None // Brackets without demote: the pools are counted as without brackets.
                  case Some(a) =>
                      if (a >= n)
                          Some(p(s"with brackets, fewer than the pool size ($n) must advance; demote is ${a + 1}"))
                      else if (n % a != 0)
                          Some(p(s"with brackets, the number advancing (${a}) must divide the pool size ($n)"))
                      else None
              },
          if (stage.design.isEmpty) {
              val matches = Schedule.matchCount(n, game, stage.rotateRoles)
              Option.when(matches > MaxMatchesPerPool)(
                p(s"a pool of $n would play $matches matches; the most a pool may play is $MaxMatchesPerPool")
              )
          } else None,
          Option.when(stage.design.isEmpty && n < game.requiredSeats)(
            w(s"a pool of $n cannot fill the game's ${game.requiredSeats} required roles, so it would play nothing")
          ),
          Option.when(stage.design.isDefined && (stage.pointsBonus || stage.rotateRoles || stage.concurrentRounds))(
            w("points, rotation and concurrent rounds are set, but this stage's pools play a design, which ranks them")
          ),
          Option.when(stage.rubberMatch && !game.rubberApplies)(
            w("a rubber match only breaks ties in a game of two roles; scores will be used instead")
          ),
          Option.when(stage.brackets && stage.advancing.isDefined && stage.seedPolicy != SeedPolicy.WinnerTakesSeed)(
            w("brackets keep their shape only when the winner takes the seed")
          )
        ).flatten
    }
}
