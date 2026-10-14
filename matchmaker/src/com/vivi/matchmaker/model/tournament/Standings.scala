package com.vivi.matchmaker.model.tournament

/** Standings while pools are being played (`design/new-tournament-plan.md`, Phase 7): a pool's ranks and points are set
  * when it completes, and until then a page shows them as its finished matches stand.
  */
object Standings {

    /** `state` with every round-robin pool still being played given provisional ranks and points, from the matches it
      * has finished: what its table would say if it ended now. For showing only; nothing that decides a tournament
      * reads it, and it is never written.
      */
    def provisional(state: TournamentState, design: TournamentDesign, game: TournamentGame): TournamentState = {
        def node(n: Node): Node =
            n.copy(runs = n.runs.map { run =>
                val stage = Tournament.designAt(design, n.path).stages.lift(run.stage)
                run.copy(pools = run.pools.map { pool =>
                    val withSub = pool.copy(sub = pool.sub.map(node))
                    stage match {
                        case Some(s) if !pool.isCompleted && pool.sub.isEmpty && pool.matches.exists(_.isFinished) =>
                            val regular = pool.matches.filterNot(_.planned.isRubber).flatMap(_.outcome)
                            val seedIn = pool.members.map(m => m.entrant -> m.seedIn).toMap
                            val ranking = Ranking.rankPool(
                              seedIn = seedIn,
                              matches = regular,
                              rubbers = Map.empty,
                              stage = s,
                              game = game
                            )
                            withSub.copy(members = pool.members.map { m =>
                                val st = ranking.standings(m.entrant)
                                m.copy(
                                  rank = ranking.ranks.get(m.entrant),
                                  points = Some(st.points),
                                  scoreDiff = Some(st.scoreDiff),
                                  totalScore = Some(st.totalScore)
                                )
                            })
                        case _ => withSub
                    }
                })
            })
        state.copy(root = node(state.root))
    }
}
