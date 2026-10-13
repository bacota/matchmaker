package com.vivi.matchmaker.model.tournament

/** A participant's place in a (sub)tournament as it stands: their seed, their stratum, and whether they are out of it.
  * `initialSeed` is the seed they started it with, for the `Constant` seed policy.
  */
case class Member(entrant: Long, seed: Int, stratum: Int, initialSeed: Int, eliminated: Boolean = false)

/** A member of a pool: the seed and stratum they entered it with, and once it is complete, how they came out of it.
  * `played` is false for a pool that could play nothing, whose members neither rise nor fall.
  */
case class PoolMember(
    entrant: Long,
    seedIn: Int,
    stratumIn: Int,
    rank: Option[Int] = None,
    points: Option[Double] = None,
    scoreDiff: Option[Double] = None,
    totalScore: Option[Double] = None,
    stratumOut: Option[Int] = None,
    eliminated: Boolean = false
)

/** A match of a round-robin pool: what was planned, whether it may be drawn, the round it was played in once it has
  * begun, and how it came out once it has finished.
  */
case class PoolMatch(
    planned: PlannedMatch,
    tiesAllowed: Boolean,
    round: Option[Int] = None,
    outcome: Option[List[Placing]] = None
) {
    def finished: Boolean = outcome.isDefined
}

/** One pool of a stage run. A round-robin pool has `matches`; a pool that plays a design has `sub`, the tournament it
  * is playing. `id` is unique across the whole tournament, which is how a match is found again: by its pool's id and
  * its number in the pool.
  */
case class Pool(
    id: Int,
    stratum: Int,
    members: List[PoolMember],
    matches: List[PoolMatch] = Nil,
    sub: Option[Node] = None,
    completed: Boolean = false
) {

    /** Whether anything was or will be played in this pool. */
    def plays: Boolean = matches.nonEmpty || sub.isDefined
}

/** One playing of a stage: its pools, and the members it left out — those of a stratum too small to fill a match. */
case class StageRun(
    stage: Int,
    repetition: Int,
    pools: List[Pool],
    sittingOut: List[Long] = Nil,
    completed: Boolean = false
)

/** A (sub)tournament being played. `path` is the stage indices, from the root design, of the stages whose pools it is
  * played in: empty for the tournament itself. `ranking` is set once it is over.
  */
case class Node(
    path: List[Int],
    members: List[Member],
    stage: Int = 0,
    repetition: Int = 0,
    runs: List[StageRun] = Nil,
    ranking: Option[Map[Long, Int]] = None
) {
    def finished: Boolean = ranking.isDefined
    def active: List[Member] = members.filterNot(_.eliminated)
}

/** A whole tournament's state: the root of its tree of (sub)tournaments, the round being played, and the few things
  * that are the tournament's rather than any one part's.
  *
  * `withdrawn` are entrants who have left: they are put in no new pool, and a match they are still seated in is theirs
  * to forfeit. `stopRequested` is the owner having asked a stage that repeats until stopped to stop.
  */
case class TournamentState(
    root: Node,
    round: Int = 0,
    nextPoolId: Int = 1,
    stopRequested: Boolean = false,
    withdrawn: Set[Long] = Set.empty
) {
    def finished: Boolean = root.finished
}

/** What advancing a tournament needs that is not in its state: the design, the game, and everyone's rating now.
  *
  * `defaultPoolSize` is the tournament's (V52), for a stage that sets no pool size; `None`, as in a test that has no
  * tournament, is the game's R. `concurrentRounds` is the tournament's: set, every round-robin pool may play all of its
  * matches in one round, as a stage that says so itself may.
  */
case class Env(
    design: TournamentDesign,
    game: TournamentGame,
    ratings: Map[Long, Int],
    defaultPoolSize: Option[Int] = None,
    concurrentRounds: Boolean = false
) {
    def rating(entrant: Long): Int = ratings.getOrElse(entrant, 0)

    /** The size of `stage`'s pools. */
    def poolSize(stage: StageDesign): Int = stage.poolSizeOr(defaultPoolSize.getOrElse(game.poolSeats))

    /** The most matches a member of one of `stage`'s pools plays in a round. */
    def concurrent(stage: StageDesign): Int = if (concurrentRounds) Int.MaxValue else stage.concurrent
}

/** A match of the round being played, with what creating it needs to know. */
case class RoundMatch(poolId: Int, nodePath: List[Int], stage: Int, planned: PlannedMatch, tiesAllowed: Boolean)

/** A tournament's progress, as pure functions of its state: starting it, starting each round, recording results, and
  * advancing past what they finish. Matchmaker persists the state between calls and does what it says — creating the
  * round's matches — but decides none of it.
  */
object Tournament {

    /** The design a node plays: the root design for the tournament, and for a sub-tournament the design of the stage
      * whose pools it is played in.
      */
    def designAt(root: TournamentDesign, path: List[Int]): TournamentDesign =
        path.foldLeft(root)((d, s) => d.stages(s).design.getOrElse(d))

    /** A new tournament, seeded by rating, strongest first, ties to whoever entered first (`entrants` is in order of
      * entry), and advanced to the start of its first round. Its pools are numbered from `firstPoolId`: a store that
      * gives pools ids of its own numbers them from where it can tell a new one from one it holds.
      */
    def start(entrants: List[Long], env: Env, firstPoolId: Int = 1): TournamentState = {
        val members = seedByRating(entrants, env).zipWithIndex.map((e, i) => Member(e, i + 1, 0, i + 1))
        advance(TournamentState(Node(Nil, members), nextPoolId = firstPoolId), env)
    }

    private def seedByRating(entrants: List[Long], env: Env): List[Long] =
        entrants.zipWithIndex.sortBy((e, i) => (-env.rating(e), i)).map(_._1)

    // ---- rounds -----------------------------------------------------------------------------

    /** Every match of the round being played, wherever in the tree it is. */
    def roundMatches(state: TournamentState): List[RoundMatch] = matchesWhere(state, _.round.contains(state.round))

    /** Whether every match of the round being played has finished. True of a tournament that has played no round. */
    def roundComplete(state: TournamentState): Boolean =
        allPools(state.root).forall(_.matches.forall(m => !m.round.contains(state.round) || m.finished))

    /** Starts the next round: advances past everything the last one finished, then puts the next slot of every
      * round-robin pool still playing into the new round. The matches are what matchmaker is to create. None, for a
      * tournament that is over.
      */
    def nextRound(state: TournamentState, env: Env): (TournamentState, List[RoundMatch]) = {
        val advanced = advance(state, env)
        if (advanced.finished) (advanced, Nil)
        else {
            val round = advanced.round + 1
            val root = mapPools(advanced.root) { (pool, _, _) =>
                if (
                  pool.completed || pool.matches.isEmpty || pool.matches.exists(m => m.round.isDefined && !m.finished)
                )
                    pool
                else {
                    val waiting = pool.matches.filter(_.round.isEmpty)
                    waiting.map(_.planned.slot).minOption match {
                        case None => pool
                        case Some(slot) =>
                            pool.copy(matches =
                                pool.matches.map(m =>
                                    if (m.round.isEmpty && m.planned.slot == slot) m.copy(round = Some(round)) else m
                                )
                            )
                    }
                }
            }
            val next = advanced.copy(root = root, round = round)
            (next, roundMatches(next))
        }
    }

    /** Records how a match came out. A match already recorded keeps its first result: a result is final. */
    def record(state: TournamentState, poolId: Int, matchNo: Int, placings: List[Placing]): TournamentState =
        state.copy(root = mapPools(state.root) { (pool, _, _) =>
            if (pool.id != poolId) pool
            else
                pool.copy(matches =
                    pool.matches.map(m =>
                        if (m.planned.no == matchNo && m.outcome.isEmpty) m.copy(outcome = Some(placings)) else m
                    )
                )
        })

    /** Finds a match by its pool and number, with where it is in the tree. */
    def findMatch(state: TournamentState, poolId: Int, matchNo: Int): Option[RoundMatch] =
        matchesWhere(state, _ => true).find(m => m.poolId == poolId && m.planned.no == matchNo)

    /** Whether a match has been recorded. */
    def isRecorded(state: TournamentState, poolId: Int, matchNo: Int): Boolean =
        allPools(state.root).exists(p => p.id == poolId && p.matches.exists(m => m.planned.no == matchNo && m.finished))

    // ---- entrants ----------------------------------------------------------------------------

    /** An entrant leaving: they are put in no pool from now on. Matches they are already seated in stay theirs. */
    def withdraw(state: TournamentState, entrant: Long): TournamentState =
        state.copy(withdrawn = state.withdrawn + entrant)

    /** An entrant coming back after withdrawing, with the stratum they had (`tournament-design.md`, "Late Entries"). */
    def reenter(state: TournamentState, entrant: Long): TournamentState =
        state.copy(withdrawn = state.withdrawn - entrant)

    /** An entrant joining a tournament already under way: in stratum 0, seeded by rating among those there, playing
      * from the next stage run that begins (`tournament-design.md`, "Late Entries"). Every active member's seed is
      * renumbered to make room, which keeps their order.
      */
    def lateEntry(state: TournamentState, entrant: Long, env: Env): TournamentState =
        if (state.root.members.exists(_.entrant == entrant)) reenter(state, entrant)
        else {
            val root = state.root
            val active = root.members.filterNot(_.eliminated).sortBy(_.seed)
            // The first member of stratum 0 the newcomer outrates, or the end: they go just before it.
            val at = active.indexWhere(m => m.stratum == 0 && env.rating(m.entrant) < env.rating(entrant)) match {
                case -1 => active.size
                case i  => i
            }
            // Those eliminated are numbered after everyone still in it, so that seeds stay one number per member.
            val placed =
                (active.take(at) :+ Member(entrant, 0, 0, 0)) ++ active.drop(at) ++
                    root.members.filter(_.eliminated).sortBy(m => (m.seed, m.entrant))
            val renumbered = placed.zipWithIndex.map((m, i) =>
                if (m.entrant == entrant) m.copy(seed = i + 1, initialSeed = root.members.size + 1)
                else m.copy(seed = i + 1)
            )
            state.copy(root = root.copy(members = renumbered))
        }

    /** The owner asking a stage that repeats until stopped to stop, after the run being played. */
    def requestStop(state: TournamentState): TournamentState = state.copy(stopRequested = true)

    // ---- advancing ---------------------------------------------------------------------------

    /** Moves the tournament on as far as its finished matches take it, without starting a round: completes every pool
      * whose matches are all done (asking for rubbers where a tie wants one), every run whose pools are all complete,
      * and every (sub)tournament whose stages are all played; and begins whatever run comes next.
      */
    def advance(state: TournamentState, env: Env): TournamentState = {
        var current = state
        var changed = true
        // Each pass can only complete or begin things; a pass that changes nothing is where it stops.
        while (changed) {
            val (root, nextId) = step(current.root, env, current, current.nextPoolId)
            changed = root != current.root
            current = current.copy(root = root, nextPoolId = nextId)
        }
        current
    }

    private def step(node: Node, env: Env, state: TournamentState, nextId: Int): (Node, Int) =
        if (node.finished) (node, nextId)
        else {
            val design = designAt(env.design, node.path)
            node.runs.lastOption.filterNot(_.completed) match {
                case Some(run) =>
                    // Advance the run's pools: each sub-tournament, then each pool whose play is done.
                    val (pools, afterSubs) = run.pools.foldLeft((List.empty[Pool], nextId)) { case ((acc, id), pool) =>
                        val (stepped, id2) = pool.sub match {
                            case Some(sub) =>
                                val (s, i) = step(sub, env, state, id)
                                (pool.copy(sub = Some(s)), i)
                            case None => (pool, id)
                        }
                        (acc :+ completePool(stepped, design.stages(run.stage), env), id2)
                    }
                    val updated = run.copy(pools = pools)
                    if (pools.forall(_.completed)) (finishRun(node, updated, design, env, state), afterSubs)
                    else (node.copy(runs = node.runs.init :+ updated), afterSubs)
                case None =>
                    if (node.stage >= design.stages.size) (finishNode(node, design), nextId)
                    else {
                        val stage = design.stages(node.stage)
                        val active = node.active.filterNot(m => state.withdrawn(m.entrant))
                        stage.repeat match {
                            // Already down to the count it plays until: the stage is not played at all.
                            case Repetition.UntilRemaining(c) if node.repetition == 0 && active.sizeIs <= c =>
                                (node.copy(stage = node.stage + 1, repetition = 0), nextId)
                            case _ => beginRun(node, stage, active, env, nextId)
                        }
                    }
            }
        }

    /** Forms a run's pools and begins it. */
    private def beginRun(node: Node, stage: StageDesign, active: List[Member], env: Env, nextId: Int): (Node, Int) = {
        val n = env.poolSize(stage)
        val formed = Pools.form(
          active.map(m => Seeded(m.entrant, m.seed, m.stratum)),
          n,
          env.game.requiredSeats,
          stage.advancing,
          stage.brackets,
          stage.strictStrata
        )
        val pooled = formed.flatMap(_.members.map(_.entrant)).toSet
        val pools = formed.zipWithIndex.map { (f, i) =>
            val members = f.members.map(s => PoolMember(s.entrant, s.seed, s.stratum))
            val id = nextId + i
            if (f.members.sizeIs < env.game.requiredSeats) Pool(id, f.stratum, members)
            else
                stage.design match {
                    case Some(_) =>
                        val subMembers = f.members.zipWithIndex.map((s, j) => Member(s.entrant, j + 1, 0, j + 1))
                        Pool(id, f.stratum, members, sub = Some(Node(node.path :+ node.stage, subMembers)))
                    case None =>
                        val planned =
                            Schedule.roundRobin(
                              f.members.map(_.entrant),
                              env.game,
                              stage.rotateRoles,
                              env.concurrent(stage)
                            )
                        // Ties by the pool's size (V52): none in a pool of two, an elimination match.
                        Pool(id, f.stratum, members, matches = planned.map(PoolMatch(_, Ties.allowed(f.members.size))))
                }
        }
        val run = StageRun(node.stage, node.repetition, pools, active.map(_.entrant).filterNot(pooled))
        (node.copy(runs = node.runs :+ run), nextId + pools.size)
    }

    /** Completes a pool whose play is done, ranking its members — or, where a tie wants a rubber, schedules it instead
      * and leaves the pool open. A pool that is still playing, or already complete, is returned as it is.
      */
    private def completePool(pool: Pool, stage: StageDesign, env: Env): Pool =
        if (pool.completed) pool
        else if (!pool.plays)
            // Nothing could be played: everyone ranks first, and nobody rises or falls.
            pool.copy(completed = true, members = pool.members.map(_.copy(rank = Some(1))))
        else
            pool.sub match {
                case Some(sub) if sub.finished =>
                    val ranks = sub.ranking.get
                    pool.copy(completed = true, members = pool.members.map(m => m.copy(rank = ranks.get(m.entrant))))
                case Some(_) => pool
                case None if pool.matches.forall(_.finished) =>
                    val regular = pool.matches.filterNot(_.planned.rubber).flatMap(_.outcome)
                    val rubbers = pool.matches
                        .filter(_.planned.rubber)
                        .flatMap(m => m.outcome.map(o => m.planned.seats.map(_.entrant).toSet -> o))
                        .toMap
                    val seedIn = pool.members.map(m => m.entrant -> m.seedIn).toMap
                    val ranking = Ranking.rankPool(seedIn, regular, rubbers, stage, env.game)
                    if (ranking.rubbers.nonEmpty) {
                        val slot = pool.matches.map(_.planned.slot).max + 1
                        val first = pool.matches.map(_.planned.no).max + 1
                        val added = ranking.rubbers.zipWithIndex.map { case ((a, b), i) =>
                            val seats = Schedule.roles(List(a, b), env.game.seatingRoles.take(2), env.game)
                            PoolMatch(PlannedMatch(first + i, slot, seats, rubber = true), tiesAllowed = false)
                        }
                        pool.copy(matches = pool.matches ++ added)
                    } else
                        pool.copy(
                          completed = true,
                          members = pool.members.map { m =>
                              val s = ranking.standings(m.entrant)
                              m.copy(
                                rank = ranking.ranks.get(m.entrant),
                                points = Some(s.points),
                                scoreDiff = Some(s.scoreDiff),
                                totalScore = Some(s.totalScore)
                              )
                          }
                        )
                case None => pool
            }

    /** Completes a run whose pools are all complete: moves its members between strata, eliminates those who fall below
      * the bottom one, re-seeds by the stage's policy, and decides whether the stage is played again.
      */
    private def finishRun(
        node: Node,
        run: StageRun,
        design: TournamentDesign,
        env: Env,
        state: TournamentState
    ): Node = {
        val stage = design.stages(run.stage)
        // Each pool member's new stratum, and whether it eliminates them.
        val moved: Map[Long, (Int, Boolean)] = run.pools.flatMap { pool =>
            pool.members.map { m =>
                val rank = m.rank.getOrElse(Int.MaxValue)
                val up = pool.plays && stage.promote.exists(rank <= _)
                val down = pool.plays && !up && stage.demote.exists(rank >= _)
                val stratum =
                    if (up)
                        stage.maxStratum.fold(m.stratumIn + 1)(max =>
                            math.min(m.stratumIn + 1, math.max(max, m.stratumIn))
                        )
                    else if (down) m.stratumIn - 1
                    else m.stratumIn
                // Withdrawing is not elimination: an entrant who comes back keeps the stratum they had.
                val out = stage.minStratum.exists(stratum < _)
                m.entrant -> (stratum, out)
            }
        }.toMap
        val pools = run.pools.map(p =>
            p.copy(members =
                p.members.map(m =>
                    moved.get(m.entrant).fold(m)((s, out) => m.copy(stratumOut = Some(s), eliminated = out))
                )
            )
        )
        val finished = run.copy(pools = pools, completed = true)
        val members = node.members.map(m =>
            moved.get(m.entrant).fold(m)((s, out) => m.copy(stratum = s, eliminated = m.eliminated || out))
        )
        val reseeded = reseed(members, finished, stage, env)
        val after = node.copy(members = reseeded, runs = node.runs.init :+ finished)

        val active = after.active.filterNot(m => state.withdrawn(m.entrant))
        // A run that played nothing and moved nobody would only be played again the same way.
        val stuck = !runPlayed(finished) && members == node.members
        val again = !stuck && (stage.repeat match {
            case Repetition.Fixed(k)          => run.repetition + 1 < k
            case Repetition.UntilRemaining(c) => active.sizeIs > c
            case Repetition.UntilStopped      => !state.stopRequested
        })
        if (again) after.copy(repetition = run.repetition + 1)
        else after.copy(stage = run.stage + 1, repetition = 0)
    }

    private def runPlayed(run: StageRun): Boolean = run.pools.exists(_.plays)

    /** New seeds for a node's members after a run, by the stage's policy (`tournament-design.md`, "Seed Policy"). */
    private def reseed(members: List[Member], run: StageRun, stage: StageDesign, env: Env): List[Member] = {
        val poolOf: Map[Long, PoolMember] = run.pools.flatMap(_.members.map(m => m.entrant -> m)).toMap
        // The active members in `order`, then those eliminated, by the seed they had: seeds stay one number per member,
        // as a node's seeds are kept (V52).
        def renumber(order: List[Member]): List[Member] = {
            val all = order ++ members.filter(_.eliminated).sortBy(m => (m.seed, m.entrant))
            val seeds = all.zipWithIndex.map((m, i) => m.entrant -> (i + 1)).toMap
            members.map(m => seeds.get(m.entrant).fold(m)(s => m.copy(seed = s)))
        }
        val active = members.filterNot(_.eliminated)
        stage.seedPolicy match {
            case SeedPolicy.Constant     => members.map(m => m.copy(seed = m.initialSeed))
            case SeedPolicy.Reinitialize => renumber(active.sortBy(m => (-env.rating(m.entrant), m.initialSeed)))
            case SeedPolicy.Previous =>
                renumber(active.sortBy { m =>
                    val p = poolOf.get(m.entrant)
                    (
                      -m.stratum,
                      p.flatMap(_.rank).getOrElse(Int.MaxValue),
                      -p.flatMap(_.points).getOrElse(0.0),
                      -p.flatMap(_.scoreDiff).getOrElse(0.0),
                      -p.flatMap(_.totalScore).getOrElse(0.0),
                      -env.rating(m.entrant),
                      m.seed
                    )
                })
            case SeedPolicy.WinnerTakesSeed =>
                // In each pool, the seeds that came in are handed back out in the order its members finished.
                val handed: Map[Long, Int] = run.pools.flatMap { pool =>
                    val seeds = pool.members.map(_.seedIn).sorted
                    val finishOrder = pool.members.sortBy(m => (m.rank.getOrElse(Int.MaxValue), m.seedIn))
                    finishOrder.map(_.entrant).zip(seeds)
                }.toMap
                members.map(m => handed.get(m.entrant).fold(m)(s => m.copy(seed = s)))
        }
    }

    /** Ends a (sub)tournament whose stages have all been played, ranking it from its runs. */
    private def finishNode(node: Node, design: TournamentDesign): Node = {
        val runs = node.runs.filter(_.completed)
        val completed = runs.map { run =>
            val pooled = run.pools.flatMap(pool =>
                pool.members.map(m =>
                    m.entrant -> RunPlacing(
                      pool.stratum,
                      m.rank.getOrElse(Int.MaxValue),
                      m.stratumOut.getOrElse(m.stratumIn),
                      m.eliminated
                    )
                )
            )
            // Those the run left out stood where they were: ranked with the pools' winners, in their own stratum.
            val stratumOf = node.members.map(m => m.entrant -> m.stratum).toMap
            val sat =
                run.sittingOut.map(e => e -> RunPlacing(stratumOf.getOrElse(e, 0), 1, stratumOf.getOrElse(e, 0), false))
            CompletedRun((pooled ++ sat).toMap)
        }
        val breakers = runs.zipWithIndex.collect {
            case (run, i) if design.stages(run.stage).tieBreakerStage => i
        }.toSet
        val ranked = Ranking.rankNode(completed, breakers)
        // Anyone who never played a run — a tournament that ended before it began — ranks level, last.
        val unranked = node.members.map(_.entrant).filterNot(ranked.contains)
        node.copy(ranking = Some(ranked ++ unranked.map(_ -> (ranked.size + 1))))
    }

    // ---- walking the tree --------------------------------------------------------------------

    /** Every pool in the tree, in the runs not yet completed and those that are. */
    def allPools(node: Node): List[Pool] =
        node.runs.flatMap(_.pools).flatMap(p => p :: p.sub.toList.flatMap(allPools))

    /** The tree with `f` applied to every pool of every run not yet completed, with the node and stage it is in. */
    private def mapPools(node: Node)(f: (Pool, Node, Int) => Pool): Node =
        node.copy(runs = node.runs.map { run =>
            if (run.completed) run
            else
                run.copy(pools = run.pools.map { pool =>
                    val inner = pool.copy(sub = pool.sub.map(mapPools(_)(f)))
                    f(inner, node, run.stage)
                })
        })

    private def matchesWhere(state: TournamentState, wanted: PoolMatch => Boolean): List[RoundMatch] = {
        def walk(node: Node): List[RoundMatch] =
            node.runs.flatMap { run =>
                run.pools.flatMap { pool =>
                    pool.matches
                        .filter(wanted)
                        .map(m => RoundMatch(pool.id, node.path, run.stage, m.planned, m.tiesAllowed)) ++
                        pool.sub.toList.flatMap(walk)
                }
            }
        walk(state.root)
    }
}
