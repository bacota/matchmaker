package com.vivi.matchmaker.model.tournament

import com.vivi.matchmaker.ErrorText

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
  * `stage_model` row of a model.
  *
  * `design` is the tournament each pool plays: set, the stage is a nesting stage; unset, an atomic one, whose pools
  * play a round robin: every `R`-member subset of the pool plays one match, `R` times over with the roles turned when
  * `isRotatingRoles` is set. A pool of `R` therefore plays a single match, which is all an *Elimination Match* is.
  * Whether a match may end in a draw follows from its pool unless the stage says ([[Ties]]): not in a pool that plays a
  * single match, which must decide its pool; yes in one that plays more.
  *
  * `poolSize` is how many a pool holds, `None` for the tournament's default pool size. `maxStratum` and `minStratum`
  * are `None` for no bound. `demote` and `promote` are ranks: at or below `demote` a participant moves down a stratum,
  * at or above `promote` up one. `hasConcurrentRounds` lets a round-robin pool play all of its matches in one round,
  * rather than each member one match a round. `hasRubberMatch` breaks a tie on points with one more match, in a game of
  * two roles; otherwise ties go to scores. `canTie` overrides whether a match of an atomic stage may be drawn, `None`
  * for the rule by the pool's matches ([[Ties]]).
  */
case class StageDesign(
    repeat: Repetition = Repetition.Fixed(1),
    hasBrackets: Boolean = true,
    demote: Option[Int] = Some(2),
    promote: Option[Int] = None,
    maxStratum: Option[Int] = Some(0),
    minStratum: Option[Int] = Some(0),
    points: Int = 2,
    pointsForDraw: Int = 1,
    hasPointsBonus: Boolean = false,
    poolSize: Option[Int] = None,
    hasConcurrentRounds: Boolean = false,
    isRotatingRoles: Boolean = false,
    hasStrictStrata: Boolean = true,
    design: Option[TournamentDesign] = None,
    hasTieBreakerStage: Boolean = false,
    seedPolicy: SeedPolicy = SeedPolicy.WinnerTakesSeed,
    hasRubberMatch: Boolean = false,
    canTie: Option[Boolean] = None,
    name: String = "",
    description: String = ""
) {

    /** How many advance from a pool: everyone ranked above `demote`. `None` when nobody is demoted. */
    def advancing: Option[Int] = demote.map(_ - 1)

    /** The size of this stage's pools, given the tournament's default for a stage that sets none. */
    def poolSizeOr(default: Int): Int = poolSize.getOrElse(default)

    /** The most matches a member of one of this stage's pools plays in a round. */
    def concurrent: Int = if (hasConcurrentRounds) Int.MaxValue else 1
}

/** Whether a match in a pool that plays `matches` matches may end in a draw: as its stage says, if it says (`stage`);
  * otherwise not if it is the pool's only match, which has to decide the pool by itself -- an elimination match sending
  * one of two on -- and yes in a pool that plays more, where the other matches can separate a draw's players. A rubber
  * match never may, whatever its pool or stage: it exists to break a tie.
  */
object Ties {
    def isAllowed(matches: Int, rubber: Boolean = false, stage: Option[Boolean] = None): Boolean =
        !rubber && stage.getOrElse(matches > 1)
}

/** One of a game's roles, as a tournament needs to know it. */
case class TournamentRole(id: Int, roleType: com.vivi.matchmaker.model.RoleType, preferenceLevel: Option[Int])

/** How a game without roles seats its matches, as a tournament plays it: the game's range of players, and the
  * tournament's `playersPerMatch` — `None` seats each pool in one match.
  */
case class OpenSeats(minPlayers: Int, maxPlayers: Option[Int], playersPerMatch: Option[Int])

/** What a tournament needs to know about its game.
  *
  * `roles` are all the game's roles in the order the game lists them, empty for a game without roles, which has `open`
  * instead. `hasRoleChoice` is the tournament's, not the game's: every match's seats choose their roles, whatever the
  * game says about them — in a game that has roles to choose.
  */
case class TournamentGame(
    roles: List[TournamentRole],
    moveType: com.vivi.matchmaker.model.MoveType,
    maxTurns: Option[Int],
    hasRoleChoice: Boolean = false,
    open: Option[OpenSeats] = None
) {
    import com.vivi.matchmaker.model.RoleType

    /** Whether the game is played without roles: its seats are all alike, and the tournament says how many a match has.
      */
    def isRoleless: Boolean = open.isDefined

    /** The game's "R", which is what a pool is sized by: its required and recommended roles, or for a game without
      * roles the tournament's players per match. A tournament that seats each pool in one match has no R of its own;
      * the fewest players the game takes stands in for it.
      */
    def poolSeats: Int =
        open.fold(roles.count(_.roleType != RoleType.Optional))(o => o.playersPerMatch.getOrElse(o.minPlayers))

    /** How many members a match needs. */
    def requiredSeats: Int = open.fold(roles.count(_.roleType == RoleType.Required))(_.minPlayers)

    /** How many seats a match of a pool of `n` has, when the pool is small enough to play as one match: everyone, if
      * the game has a seat for each of them. `None` when the pool is too big and plays its `R`-member subsets instead.
      */
    def wholePoolSeats(n: Int): Option[Int] =
        open match {
            case Some(o) => Option.when(o.playersPerMatch.forall(n <= _))(n)
            case None    => Option.when(n <= roles.size)(n)
        }

    /** The roles a match seats, required first, then recommended, then optional, each in the game's own order. A match
      * of `n` seats plays the first `n`, so it has the optional roles only when it has more seats than the game's R.
      * Empty for a game without roles.
      */
    def seatingRoles: List[TournamentRole] =
        roles.filter(_.roleType == RoleType.Required) ++ roles.filter(_.roleType == RoleType.Recommended) ++
            roles.filter(_.roleType == RoleType.Optional)

    /** Whether a rubber match can break a tie: only in a game of two seats a match (Q7) — exactly two roles, or two
      * players per match.
      */
    def isRubberApplicable: Boolean = open.fold(roles.sizeIs == 2)(_.playersPerMatch.contains(2))
}

object TournamentGame {

    /** `playersPerMatch` is the tournament's, for a game whose matches seat no roles; a game with roles ignores it. A
      * game whose roles are all optional is played as one without roles, up to one player per role.
      */
    def of(game: com.vivi.matchmaker.model.Game, playersPerMatch: Option[Int] = None): TournamentGame =
        TournamentGame(
          // None for a game whose matches seat no roles, even one with optional roles.
          roles =
              if (game.isSeatedWithoutRoles) Nil
              else
                  game.roles.toList
                      .sortBy(_.gameRoleId.value)
                      .map(r =>
                          TournamentRole(
                            id = r.gameRoleId.value,
                            roleType = r.roleType,
                            preferenceLevel = r.preferenceLevel
                          )
                      ),
          moveType = game.moveType,
          maxTurns = game.maxTurns,
          open = game.fewestPlayers.map(least =>
              OpenSeats(minPlayers = least, maxPlayers = game.mostSeats, playersPerMatch = playersPerMatch)
          )
        )
}

/** Something wrong with a design, found before a tournament is created from it. A warning is something the creator
  * should know but may go ahead with.
  */
case class Problem(where: String, message: String, isWarning: Boolean = false) {
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
                Problem(where = "", message = ErrorText.design.noRequiredRole)
              ),
              game.open.flatMap(o =>
                  o.playersPerMatch.collect {
                      case k if k < o.minPlayers =>
                          Problem(
                            where = "",
                            message =
                                ErrorText.design.matchTooSmall(players = k.toString, least = o.minPlayers.toString)
                          )
                      case k if o.maxPlayers.exists(k > _) =>
                          Problem(
                            where = "",
                            message =
                                ErrorText.design.matchTooBig(players = k.toString, most = o.maxPlayers.get.toString)
                          )
                  }
              ),
              Option.when(
                game.moveType == com.vivi.matchmaker.model.MoveType.Simultaneous && game.maxTurns.isEmpty &&
                    maxTurns.isEmpty
              )(
                Problem(
                  where = "",
                  message = ErrorText.design.needsMaxTurns
                )
              )
            ).flatten
        gameProblems ++ designProblems(design = design, game = game, defaultPoolSize = defaultPoolSize)
    }

    /** Everything wrong with a design itself, as a model holds it: before any tournament gives it a default pool size,
      * a stage that sets none is checked as pools of the game's R.
      */
    def designProblems(design: TournamentDesign, game: TournamentGame, defaultPoolSize: Option[Int]): List[Problem] =
        check(
          design = design,
          game = game,
          path = "",
          defaultPoolSize = defaultPoolSize.getOrElse(game.poolSeats),
          depth = 1
        )

    private def check(
        design: TournamentDesign,
        game: TournamentGame,
        path: String,
        defaultPoolSize: Int,
        depth: Int
    ): List[Problem] = {
        val here = if (path.isEmpty) "" else path
        if (depth > MaxDepth) List(Problem(where = here, message = ErrorText.design.tooDeep(MaxDepth.toString)))
        else if (design.stages.isEmpty) List(Problem(where = here, message = ErrorText.design.noStages))
        // Every match of a round is played in one stage (round.stage_id), and the pools of a nesting stage play
        // their designs side by side: with more than one stage, one pool could be in another stage than the next.
        else if (depth > 1 && design.stages.sizeIs > 1)
            List(Problem(where = here, message = ErrorText.design.nestedStages))
        else
            design.stages.zipWithIndex.flatMap { (stage, i) =>
                val where = (if (path.isEmpty) "" else s"$path, ") + ErrorText.design.stage((i + 1).toString)
                stageProblems(stage = stage, game = game, where = where, defaultPoolSize = defaultPoolSize) ++
                    stage.design.toList.flatMap(
                      check(_, game, ErrorText.design.poolsOf(where), defaultPoolSize, depth + 1)
                    )
            }
    }

    private def stageProblems(
        stage: StageDesign,
        game: TournamentGame,
        where: String,
        defaultPoolSize: Int
    ): List[Problem] = {
        val n = stage.poolSizeOr(defaultPoolSize)
        def p(message: String) = Problem(where = where, message = message)
        def w(message: String) = Problem(where = where, message = message, isWarning = true)
        List(
          stage.repeat match {
              case Repetition.Fixed(k) if k < 1 => Some(p(ErrorText.design.neverPlayed))
              case Repetition.UntilRemaining(c) if c < 1 =>
                  Some(p(ErrorText.design.noneLeft))
              case _ => None
          },
          Option.when(n < 1)(p(ErrorText.design.emptyPool)),
          Option.when(stage.demote.exists(_ <= 1))(p(ErrorText.design.demoteTooLow)),
          Option.when(stage.promote.exists(_ < 1))(p(ErrorText.design.promoteTooLow)),
          Option.when(stage.promote.zip(stage.demote).exists((pr, de) => pr >= de))(
            p(ErrorText.design.promoteBelowDemote)
          ),
          Option.when(stage.minStratum.zip(stage.maxStratum).exists((lo, hi) => lo > hi))(
            p(ErrorText.design.strataCrossed)
          ),
          Option.when(stage.points < 0 || stage.pointsForDraw < 0)(p(ErrorText.design.negativePoints)),
          // Brackets: the pools are a power of n/a, which has to be a whole number above 1.
          if (!stage.hasBrackets) None
          else
              stage.advancing.filter(_ > 0) match {
                  case None => None // Brackets without demote: the pools are counted as without brackets.
                  case Some(a) =>
                      if (a >= n)
                          Some(p(ErrorText.design.bracketsAllAdvance(pool = n.toString, demote = (a + 1).toString)))
                      else if (n % a != 0)
                          Some(p(ErrorText.design.bracketsUneven(advancing = a.toString, pool = n.toString)))
                      else None
              },
          if (stage.design.isEmpty) {
              val matches = Schedule.matchCount(n = n, game = game, rotateRoles = stage.isRotatingRoles)
              Option.when(matches > MaxMatchesPerPool)(
                p(
                  ErrorText.design.tooManyMatches(
                    pool = n.toString,
                    matches = matches.toString,
                    most = MaxMatchesPerPool.toString
                  )
                )
              )
          } else None,
          Option.when(stage.design.isEmpty && n < game.requiredSeats)(
            p(
              if (game.isRoleless)
                  ErrorText.design.poolBelowPlayers(pool = n.toString, least = game.requiredSeats.toString)
              else
                  ErrorText.design.poolBelowRoles(pool = n.toString, roles = game.requiredSeats.toString)
            )
          ),
          // Each pool one match: a pool bigger than the game takes is a match its engine cannot play.
          game.open
              .filter(o => stage.design.isEmpty && o.playersPerMatch.isEmpty)
              .flatMap(_.maxPlayers)
              .filter(n > _)
              .map(most => p(ErrorText.design.poolAboveMost(pool = n.toString, most = most.toString))),
          Option.when(
            stage.design.isDefined && (stage.hasPointsBonus || stage.isRotatingRoles || stage.hasConcurrentRounds)
          )(
            w(ErrorText.design.settingsIgnored)
          ),
          Option.when(stage.hasRubberMatch && !game.isRubberApplicable)(
            w(ErrorText.design.rubberInapplicable)
          ),
          Option.when(stage.hasBrackets && stage.advancing.isDefined && stage.seedPolicy != SeedPolicy.WinnerTakesSeed)(
            w(ErrorText.design.bracketsNeedWinnerSeed)
          )
        ).flatten
    }
}
