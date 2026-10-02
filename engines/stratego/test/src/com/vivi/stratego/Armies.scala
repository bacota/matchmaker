package com.vivi.stratego

/** Setups for tests that need to know where things are. */
object Armies {

    /** A legal setup for `side` with `fixed` on the squares named, and the rest of the army filled in from the back:
      * the bombs and the flag first, so that nothing a test did not place is in the way at the front.
      */
    def setup(side: Side, fixed: Map[Int, Rank] = Map.empty): List[Rank] = {
        val home = side.homeSquares.toList
        require(fixed.keySet.subsetOf(home.toSet), s"$fixed is not all on $side's home squares")
        val left = fixed.values.foldLeft(Rank.army)((army, r) => army.updated(r, army(r) - 1))
        require(left.values.forall(_ >= 0), s"$fixed places more of some rank than an army has")
        val pool = Rank.values.toList.filterNot(_.movable).flatMap(r => List.fill(left(r))(r)) ++
            Rank.values.toList.filter(_.movable).reverse.flatMap(r => List.fill(left(r))(r))
        val backFirst = if (side == Side.Red) home else home.reverse
        val filled = backFirst.filterNot(fixed.contains).zip(pool).toMap ++ fixed
        home.map(filled)
    }

    def names(ranks: List[Rank]): List[String] = ranks.map(_.toString)
}
