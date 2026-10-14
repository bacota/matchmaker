package com.vivi.matchmaker.model.tournament

import java.time.Duration
import scala.util.Random
import com.vivi.matchmaker.model.{MoveType, RoleType}

/** Games, fields and result oracles for the tournament specs, and a simulator that plays a whole tournament through
  * [[Tournament]] the way matchmaker will: start, then round after round, each match's result recorded as it comes.
  */
object Fixtures {

    def game(roles: Int = 2, recommended: Int = 0): TournamentGame =
        TournamentGame(
          roles = (1 to roles).toList.map(i =>
              TournamentRole(id = i, roleType = RoleType.Required, preferenceLevel = None)
          ) ++
              (1 to recommended).toList.map(i =>
                  TournamentRole(id = roles + i, roleType = RoleType.Recommended, preferenceLevel = None)
              ),
          moveType = MoveType.Sequential,
          maxTurns = None
        )

    val twoPlayer: TournamentGame = game()

    /** A game without roles of `least` to `most` players, played `perMatch` to a match — `None` for each pool in one.
      */
    def roleless(least: Int = 2, most: Option[Int] = None, perMatch: Option[Int] = None): TournamentGame =
        TournamentGame(
          roles = Nil,
          moveType = MoveType.Simultaneous,
          maxTurns = Some(1),
          open = Some(OpenSeats(minPlayers = least, maxPlayers = most, playersPerMatch = perMatch))
        )

    val hour: Duration = Duration.ofHours(1)

    /** Entrants 1 to n, rated so that entrant 1 is the strongest and seeds follow the ids. */
    def field(n: Int): (List[Long], Map[Long, Int]) = {
        val ids = (1 to n).toList.map(_.toLong)
        (ids, ids.map(e => e -> (3000 - e.toInt)).toMap)
    }

    /** How a match comes out, given the match. */
    type Oracle = RoundMatch => List[Placing]

    /** The strongest seed — the lowest id — always wins; everyone else in order behind. */
    val favourites: Oracle = m =>
        m.planned.seats.map(_.entrant).sorted.zipWithIndex.map((e, i) => Placing(entrant = e, rank = Some(i + 1)))

    /** The weakest seed always wins. */
    val upsets: Oracle = m =>
        m.planned.seats
            .map(_.entrant)
            .sorted
            .reverse
            .zipWithIndex
            .map((e, i) => Placing(entrant = e, rank = Some(i + 1)))

    /** Every match a draw, scores level: the case only the Ties rule can settle. */
    val draws: Oracle = m => m.planned.seats.map(s => Placing(entrant = s.entrant, rank = Some(1), score = Some(10.0)))

    /** Random places, with draws only where the match allows them. */
    def random(seed: Long): Oracle = {
        val rng = new Random(seed)
        m => {
            val order = rng.shuffle(m.planned.seats.map(_.entrant))
            if (m.canTie && rng.nextInt(5) == 0) order.map(Placing(_, Some(1), Some(1.0)))
            else
                order.zipWithIndex.map((e, i) =>
                    Placing(entrant = e, rank = Some(i + 1), score = Some((10 - i).toDouble))
                )
        }
    }

    /** The outcome of simulating a tournament: its final state, every round's matches, and how many rounds it took. */
    case class Played(state: TournamentState, rounds: List[List[RoundMatch]]) {
        def ranking: Map[Long, Int] = state.root.ranking.getOrElse(Map.empty)
    }

    /** Plays a tournament to its end, or until `maxRounds`. `between` is run on the state between rounds — a
      * withdrawal, a late entry, a stop — given the number of the round just finished.
      */
    def play(
        design: TournamentDesign,
        n: Int,
        oracle: Oracle,
        g: TournamentGame = twoPlayer,
        maxRounds: Int = 500,
        between: (TournamentState, Int) => TournamentState = (s, _) => s,
        extraRatings: Map[Long, Int] = Map.empty
    ): Played = {
        val (ids, ratings) = field(n)
        val env = Env(design = design, game = g, ratings = ratings ++ extraRatings)
        var state = Tournament.start(entrants = ids, env = env)
        var rounds = List.empty[List[RoundMatch]]
        var guard = 0
        while (!state.isFinished && guard < maxRounds) {
            val (next, matches) = Tournament.nextRound(state, env)
            state = next
            if (!state.isFinished) {
                rounds = rounds :+ matches
                matches.foreach(m =>
                    state = Tournament.record(
                      state = state,
                      poolId = m.poolId,
                      matchNo = m.planned.no,
                      placings = oracle(m)
                    )
                )
                assert(Tournament.isRoundComplete(state), "every match of the round was recorded")
                state = between(state, state.round)
            }
            guard += 1
        }
        Played(Tournament.advance(state, env), rounds)
    }
}
