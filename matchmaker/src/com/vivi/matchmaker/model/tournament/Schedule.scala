package com.vivi.matchmaker.model.tournament

/** A seat in a scheduled match: who sits in it and, unless they are to choose, the role they play. `chooseOrder` is
  * their place in the order of choosing, 0 first, for a seat whose role is chosen.
  */
case class SeatPlan(entrant: Long, role: Option[Int], chooseOrder: Option[Int] = None)

/** A match a pool will play: its number within the pool, the slot of the pool's schedule it is played in (every match
  * of a slot is played in the same round, and a slot is not begun until the one before it is finished), and its seats.
  */
case class PlannedMatch(no: Int, slot: Int, seats: List[SeatPlan], rubber: Boolean = false)

/** A round-robin pool's matches (`tournament-design.md`, "Round Robin Tournaments"). */
object Schedule {

    private def choose(n: Int, k: Int): Long =
        if (k < 0 || k > n) 0L else (1 to k).foldLeft(1L)((acc, i) => acc * (n - k + i) / i)

    /** How many matches a round robin of `n` plays in this game: every `R`-member subset once, `R` times over when
      * roles are rotated — or one match of everyone, when the pool is short of `R` but can fill the required roles, or
      * none when it cannot.
      */
    def matchCount(n: Int, game: TournamentGame, rotateRoles: Boolean): Long = {
        val r = game.poolSeats
        if (n < game.requiredSeats || n < 1) 0L
        else if (n < r) (if (rotateRoles && game.significantRoles) n.toLong else 1L)
        else choose(n, r) * (if (rotateRoles && game.significantRoles) r else 1)
    }

    /** The matches of a round-robin pool, members in seed order, strongest first.
      *
      * The subsets are taken in order of the members' seeds. With `rotateRoles` (and roles that matter) each subset
      * plays once per role, the roles turned one place each time, so every member plays every role in it. Otherwise
      * each subset plays once, its roles handed out by [[roles]].
      *
      * They are then packed into slots no member plays more than `concurrent` matches in: by the circle method for a
      * two-seat game at one match a round — `n − 1` slots for an even pool, `n` for an odd — and greedily otherwise.
      */
    def roundRobin(
        members: List[Long],
        game: TournamentGame,
        rotateRoles: Boolean,
        concurrent: Int
    ): List[PlannedMatch] = {
        val n = members.size
        val r = game.poolSeats
        val seating = game.seatingRoles
        if (n < game.requiredSeats || n < 1) Nil
        else {
            val size = math.min(n, r)
            val rotate = rotateRoles && game.significantRoles
            // Each subset's seats, members in seed order, with the roles of each pass.
            val subsets: List[List[Long]] =
                if (size == 2 && concurrent == 1) circle(members) else members.combinations(size).toList
            val passes = if (rotate) size else 1
            val unpacked: List[List[SeatPlan]] =
                (0 until passes).toList.flatMap { pass =>
                    subsets.map { subset =>
                        if (rotate)
                            subset.zipWithIndex.map((e, i) => SeatPlan(e, Some(seating((i + pass) % size).id)))
                        else roles(subset, seating.take(size), game)
                    }
                }
            pack(unpacked, concurrent, if (size == 2 && concurrent == 1) Some(subsets.size, n) else None)
        }
    }

    /** The roles of a match whose roles are not rotated: in seat order where they do not matter; by preference where
      * any role has a preference level, the most preferred to the strongest seed; and otherwise chosen, the strongest
      * seed choosing first (`tournament-design.md`, "Choice of Roles"). `subset` is in seed order.
      */
    def roles(subset: List[Long], seating: List[TournamentRole], game: TournamentGame): List[SeatPlan] =
        if (!game.significantRoles) subset.zip(seating).map((e, role) => SeatPlan(e, Some(role.id)))
        else if (seating.exists(_.preferenceLevel.isDefined)) {
            val preferred = seating.zipWithIndex.sortBy((role, i) => (-role.preferenceLevel.getOrElse(Int.MinValue), i))
            subset.zip(preferred).map { case (e, (role, _)) => SeatPlan(e, Some(role.id)) }
        } else if (subset.sizeIs == 1) subset.zip(seating).map((e, role) => SeatPlan(e, Some(role.id)))
        else subset.zipWithIndex.map((e, i) => SeatPlan(e, None, Some(i)))

    /** The pairs of a round robin by the circle method, round after round: `n − 1` rounds of `n / 2` pairs for an even
      * `n`, and `n` rounds with one member sitting each out for an odd one. Each pair is in seed order.
      */
    private def circle(members: List[Long]): List[List[Long]] = {
        val seats: Vector[Option[Long]] =
            (members.map(Some(_)) ++ (if (members.size % 2 == 1) List(None) else Nil)).toVector
        val m = seats.size
        val order = members.zipWithIndex.toMap
        (0 until m - 1).toList.flatMap { round =>
            // The first seat stays; the rest turn one place each round.
            val rest = seats.tail
            val turned = seats.head +: (rest.drop(rest.size - round) ++ rest.take(rest.size - round))
            (0 until m / 2).toList.flatMap { i =>
                (turned(i), turned(m - 1 - i)) match {
                    case (Some(a), Some(b)) => List(List(a, b).sortBy(order))
                    case _                  => Nil
                }
            }
        }
    }

    /** Numbers the matches and puts each in a slot. For the circle method, whose pairs come a round at a time, each
      * round of pairs is a slot, and each later pass repeats them. Otherwise each match goes in the first slot where
      * none of its members is already playing `concurrent` matches.
      */
    private def pack(
        matches: List[List[SeatPlan]],
        concurrent: Int,
        circleOf: Option[(Int, Int)]
    ): List[PlannedMatch] =
        circleOf match {
            case Some((pairsPerPass, n)) =>
                val perRound = n / 2
                matches.zipWithIndex.map { (seats, i) =>
                    val pass = i / pairsPerPass
                    val inPass = i % pairsPerPass
                    // Rounds within a pass, by the circle's own numbering; an odd pool sits one out each round.
                    val roundsPerPass = if (n % 2 == 0) n - 1 else n
                    PlannedMatch(i, pass * roundsPerPass + inPass / math.max(perRound, 1), seats)
                }
            case None =>
                val (planned, _) =
                    matches.zipWithIndex.foldLeft((List.empty[PlannedMatch], Map.empty[(Int, Long), Int])) {
                        case ((acc, load), (seats, i)) =>
                            val who = seats.map(_.entrant)
                            val slot =
                                Iterator.from(0).find(s => who.forall(e => load.getOrElse((s, e), 0) < concurrent)).get
                            (
                              acc :+ PlannedMatch(i, slot, seats),
                              load ++ who.map(e => (slot, e) -> (load.getOrElse((slot, e), 0) + 1))
                            )
                    }
                planned
        }
}
