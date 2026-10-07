package com.vivi.matchmaker.tournament

import scala.util.Random

/** A role of the game, as scheduling sees it: its name, and whether a higher seed is simply given it (D14). */
case class RoleSpec(name: String, preferred: Boolean = false)

/** How a seat comes by its role in a planned match. */
enum SeatRole {

    /** Settled before the match: by seed, by rotation, by chance, or a preferred role taken by a high seed. */
    case Assigned(role: String)

    /** Chosen by the player in the engine, in the match's `chooseOrder`. */
    case ToChoose
}

/** One seat of a planned match: the pool member in it, and how it gets its role. */
case class PlannedSeat[K](member: K, role: SeatRole)

/** A match a pool will play: its number in the pool from 1, its seats, the order the seats with no role yet choose in —
  * best seed first — and whether it is a tie-break that must end with somebody ahead.
  */
case class PlannedMatch[K](matchNo: Int, seats: List[PlannedSeat[K]], chooseOrder: List[K], noTie: Boolean = false)

/** How roles are given out in a pool's matches. */
enum RoleMode {

    /** Players choose in seed order, in the engine, after the high seeds have taken any preferred role (D9, D14). */
    case Choose

    /** As `Choose`, but the game's engine cannot run the choosing: the roles left after the preferred ones go by seed.
      */
    case BySeed

    /** Every player plays every role `rotations` times over, against each opponent. */
    case Rotate(rotations: Int)

    /** The game's roles are unimportant: they are dealt at random. */
    case AtRandom
}

/** The matches a pool plays: every way of seating `roles.size` of its members together, once — or, with rotations, once
  * per rotation of the roles, so each player plays each role.
  *
  * The pool's members are given best seed first. A pool with fewer members than a match has seats plays nothing: a lone
  * member goes through as it stands.
  */
object PoolSchedule {

    def matches[K](members: List[K], roles: List[RoleSpec], mode: RoleMode, random: Random): List[PlannedMatch[K]] = {
        val seats = roles.size
        if (seats == 0 || members.sizeIs < seats) Nil
        else {
            val groups = members.combinations(seats).toList
            val planned = mode match {
                case RoleMode.Rotate(n) =>
                    // A group's every rotation, n times over: the group plays seats × n matches. Played round by round
                    // across the groups, so one pair does not meet in matches back to back where that can be helped.
                    (for {
                        _ <- 1 to math.max(1, n)
                        shift <- 0 until seats
                        group <- groups
                    } yield seated(group, roles, shift)).toList
                case other => groups.map(group => assign(group, roles, other, random))
            }
            spread(planned).zipWithIndex.map((m, i) => m.copy(matchNo = i + 1))
        }
    }

    /** A tie-break among `members`, level on points: each way of seating them together once more, roles handed out as
      * the round's are, and no tie allowed. Numbered on from `after`, the pool's last match.
      */
    def rematches[K](
        members: List[K],
        roles: List[RoleSpec],
        mode: RoleMode,
        random: Random,
        after: Int
    ): List[PlannedMatch[K]] = {
        // Rotations do not apply to one more game: roles are chosen, or dealt, as a single match's are.
        val single = mode match {
            case RoleMode.Rotate(_) => RoleMode.BySeed
            case m                  => m
        }
        matches(members, roles, single, random).map(m => m.copy(matchNo = after + m.matchNo, noTie = true))
    }

    /** One match of `group`, best seed first, with its roles given out by `mode` (anything but a rotation). */
    private def assign[K](group: List[K], roles: List[RoleSpec], mode: RoleMode, random: Random): PlannedMatch[K] =
        mode match {
            case RoleMode.AtRandom =>
                PlannedMatch(
                  0,
                  group.zip(random.shuffle(roles)).map((k, r) => PlannedSeat(k, SeatRole.Assigned(r.name))),
                  Nil
                )
            case RoleMode.Choose | RoleMode.BySeed =>
                // The high seeds take the preferred roles while any are free, in the game's order of roles.
                val preferred = roles.filter(_.preferred)
                val (takers, others) = group.splitAt(preferred.size)
                val taken = takers.zip(preferred).map((k, r) => k -> r.name)
                val left = roles.filterNot(r => taken.exists(_._2 == r.name))
                val rest =
                    if (mode == RoleMode.BySeed || others.sizeIs <= 1)
                        others.zip(left).map((k, r) => k -> SeatRole.Assigned(r.name))
                    else others.map(_ -> SeatRole.ToChoose)
                val seats = taken.map((k, r) => k -> SeatRole.Assigned(r)) ++ rest
                PlannedMatch(
                  0,
                  group.map(k => PlannedSeat(k, seats.find(_._1 == k).map(_._2).get)),
                  rest.collect { case (k, SeatRole.ToChoose) => k }
                )
            case RoleMode.Rotate(_) => seated(group, roles, 0)
        }

    /** `group` with the roles shifted `shift` places round: seat i plays role i + shift. */
    private def seated[K](group: List[K], roles: List[RoleSpec], shift: Int): PlannedMatch[K] =
        PlannedMatch(
          0,
          group.zipWithIndex.map((k, i) => PlannedSeat(k, SeatRole.Assigned(roles((i + shift) % roles.size).name))),
          Nil
        )

    /** The matches reordered, greedily: each next match is one sharing no player with the match before if there is one,
      * and failing that one of a different group of players, so the same players do not meet in matches back to back
      * where another match could go between.
      */
    private def spread[K](planned: List[PlannedMatch[K]]): List[PlannedMatch[K]] = {
        def group(m: PlannedMatch[K]): Set[K] = m.seats.map(_.member).toSet
        @annotation.tailrec
        def go(left: List[PlannedMatch[K]], last: Set[K], acc: List[PlannedMatch[K]]): List[PlannedMatch[K]] =
            left match {
                case Nil => acc.reverse
                case _ =>
                    val next = left
                        .find(m => group(m).intersect(last).isEmpty)
                        .orElse(left.find(m => group(m) != last))
                        .getOrElse(left.head)
                    go(left.diff(List(next)), group(next), next :: acc)
            }
        go(planned, Set.empty, Nil)
    }
}
