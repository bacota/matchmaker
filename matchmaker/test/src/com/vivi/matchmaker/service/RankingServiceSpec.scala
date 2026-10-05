package com.vivi.matchmaker.service

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import org.scalacheck.Gen
import org.scalacheck.Prop._
import skunk._
import skunk.implicits._
import skunk.codec.all.{bool, int4, int8}
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.{PropertySuite, TestMigration}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, TestSession}

/** A game's leaderboard (V45): whatever order ratings are moved in, and however many move before the leaderboard is put
  * in order, it ends up in the order the ratings are in — equal ratings sharing a place, and the places after them
  * skipped — and a placing that cannot have a lock gives up and is tried again rather than waiting.
  */
class RankingServiceSpec extends PropertySuite {
    TestMigration.ensure()

    /* A ceiling on a case that has hung: see CLAUDE.md. */
    private val caseTimeout = 60.seconds

    private val services = TestServices.services
    private val ranking = new RankingService(TestServices.pool)

    private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

    private def makeGame(): IO[Game] =
        TestSession.resource.use { session =>
            new GameRepo[String](session).create(
              Game(
                GameId.unassigned,
                GameType.Plain,
                "Ladder",
                "Ladder",
                "description",
                "https://engine.example.com/games",
                active = true,
                Seq(GameRole(GameRoleId(0), GameId.unassigned, "only", optional = false, displayName = "Only")),
                Seq.empty,
                unique("ladder")
              )
            )
        }

    private def players(n: Int): IO[List[Player]] =
        List.fill(n)(()).traverse(_ => services.registration.register(unique("rank"), unique("rank-sub")))

    /** A player's rating as play or an admin leaves it: `rated` is whether the leaderboard shows it — a match has moved
      * it — or not, as for a row a match's start made; and how many rated matches stand behind it, which tell equal
      * ratings apart.
      */
    private case class Rating(rating: Int, rated: Boolean, matches: Int = 1)

    private val upsert: Command[(Int, Long, Int, Int)] =
        sql"""INSERT INTO elo_rating (game_id, player_id, rating, matches) VALUES ($int4, $int8, $int4, $int4)
          ON CONFLICT (game_id, player_id) DO UPDATE SET rating = EXCLUDED.rating, matches = EXCLUDED.matches""".command

    /** Moves the ratings the way a completion does: the rating and the matches, and nothing about places. */
    private def write(game: Game, ratings: Map[Player, Rating]): IO[Unit] =
        TestSession.resource.use { session =>
            ratings.toList.traverse_((player, r) =>
                session.execute(upsert)(
                  (game.gameId.value, player.playerId.value, r.rating, if (r.rated) r.matches else 0)
                )
            )
        }

    private val selectPlaces: Query[Int, (Long, Option[Int], Option[(Int, Int)], (Int, Int))] =
        sql"""SELECT player_id, rank, ranked_rating, ranked_matches, rating, matches FROM elo_rating
          WHERE game_id = $int4"""
            .query(int8 *: int4.opt *: (int4 *: int4).opt *: int4 *: int4)
            .map((player, rank, rankedBy, rating, matches) => (player, rank, rankedBy, (rating, matches)))

    private def places(game: Game): IO[Map[Long, Option[Int]]] =
        TestSession.resource.use(session =>
            session.execute(selectPlaces)(game.gameId.value).map { rows =>
                rows.foreach((player, rank, rankedBy, standing) =>
                    assert(rank.isEmpty || rankedBy.contains(standing), s"$player is placed by a stale standing")
                )
                rows.map((player, rank, _, _) => player -> rank).toMap
            }
        )

    /** Where everybody belongs: one more than how many rated players stand higher — by rating, then by matches — for
      * the rated; none for the rest.
      */
    private def expected(ratings: Map[Player, Rating]): Map[Long, Option[Int]] = {
        import scala.math.Ordering.Implicits._
        def standing(r: Rating) = (r.rating, r.matches)
        val rated = ratings.values.filter(_.rated).map(standing).toList
        ratings.map((player, r) => player.playerId.value -> Option.when(r.rated)(rated.count(_ > standing(r)) + 1))
    }

    // Close together, so that equal ratings are common and so are ties in matches between them, and some unrated, so
    // that players come and go.
    private val genRating: Gen[Rating] =
        for {
            rating <- Gen.choose(1494, 1500)
            rated <- Gen.frequency(5 -> true, 1 -> false)
            matches <- Gen.choose(1, 3)
        } yield Rating(rating, rated, matches)

    /** Rounds of moves: in each, some of the players' ratings move, and then the leaderboard is put in order. */
    private val genRounds: Gen[(Int, List[Map[Int, Rating]])] =
        for {
            n <- Gen.choose(1, 10)
            first <- Gen.listOfN(n, genRating).map(_.zipWithIndex.map(_.swap).toMap)
            later <- Gen.listOfN(
              6,
              Gen.choose(1, n)
                  .flatMap(k =>
                      Gen.pick(k, 0 until n)
                          .flatMap(who =>
                              Gen.sequence[List[Rating], Rating](who.map(_ => genRating)).map(who.zip(_).toMap)
                          )
                  )
            )
        } yield (n, first :: later)

    property("however many ratings move before it is put in order, the leaderboard ends in the order they are in") {
        forAll(genRounds) { (n, rounds) =>
            val result = for {
                game <- makeGame()
                people <- players(n)
                checked <- rounds.foldLeft(IO.pure((Map.empty[Player, Rating], List.empty[Boolean]))) { (acc, round) =>
                    acc.flatMap { (ratings, verdicts) =>
                        val moved = round.map((i, r) => people(i) -> r)
                        val now = ratings ++ moved
                        for {
                            _ <- write(game, moved)
                            settled <- ranking.rank(game.gameId)
                            placed <- places(game)
                        } yield (now, verdicts :+ (settled == Settlement.Settled && placed == expected(now)))
                    }
                }
            } yield checked._2
            val verdicts = result.timeout(caseTimeout).unsafeRunSync()
            assert(verdicts.forall(identity), verdicts)
        }
    }

    test("a player who moves past the place above swaps with it, and a tie shares a place and skips the next") {
        val result = for {
            game <- makeGame()
            people <- players(4)
            List(a, b, c, d) = people: @unchecked
            _ <- write(game, Map(a -> Rating(1600, true), b -> Rating(1550, true), c -> Rating(1500, true)))
            _ <- ranking.rank(game.gameId)
            before <- places(game)
            // c climbs past b; d arrives level with a.
            _ <- write(game, Map(c -> Rating(1580, true), d -> Rating(1600, true)))
            _ <- ranking.rank(game.gameId)
            after <- places(game)
        } yield (List(a, b, c, d).map(_.playerId.value), before, after)
        val (List(a, b, c, d), before, after) = result.timeout(caseTimeout).unsafeRunSync(): @unchecked
        assertEquals(before, Map(a -> Some(1), b -> Some(2), c -> Some(3)))
        assertEquals(after, Map(a -> Some(1), d -> Some(1), c -> Some(3), b -> Some(4)))
    }

    test("of two players rated the same, the one with more matches is placed higher, and a match moves them") {
        val result = for {
            game <- makeGame()
            people <- players(3)
            List(a, b, c) = people: @unchecked
            _ <- write(game, Map(a -> Rating(1500, true, 3), b -> Rating(1500, true, 5), c -> Rating(1500, true, 3)))
            _ <- ranking.rank(game.gameId)
            before <- places(game)
            // A match that moves a's rating by nothing still adds one: a passes c, and draws level with nobody.
            _ <- write(game, Map(a -> Rating(1500, true, 4)))
            _ <- ranking.rank(game.gameId)
            after <- places(game)
        } yield (people.map(_.playerId.value), before, after)
        val (List(a, b, c), before, after) = result.timeout(caseTimeout).unsafeRunSync(): @unchecked
        assertEquals(before, Map(b -> Some(1), a -> Some(2), c -> Some(2)))
        assertEquals(after, Map(b -> Some(1), a -> Some(2), c -> Some(3)))
    }

    test("a placing that cannot have a row's lock gives up rather than waiting, and is placed once it can".tag(Quiet)) {
        val impatient = new RankingService(TestServices.pool, attempts = 3, pause = _ => IO.sleep(10.millis))
        val lockRow: Query[(Int, Long), Boolean] =
            sql"""SELECT true FROM elo_rating WHERE game_id = $int4 AND player_id = $int8 FOR UPDATE""".query(bool)
        val result = for {
            game <- makeGame()
            people <- players(2)
            List(a, b) = people: @unchecked
            _ <- write(game, Map(a -> Rating(1600, true), b -> Rating(1500, true)))
            // Somebody else -- a completion, say -- holds b's row while the leaderboard is put in order.
            held <- TestSession.resource.use(session =>
                session.transaction.use(_ =>
                    session.unique(lockRow)((game.gameId.value, b.playerId.value)) *>
                        impatient.rank(game.gameId).timeout(10.seconds)
                )
            )
            whileHeld <- places(game)
            released <- impatient.rank(game.gameId)
            after <- places(game)
        } yield (a.playerId.value, b.playerId.value, held, whileHeld, released, after)
        val (a, b, held, whileHeld, released, after) = result.timeout(caseTimeout).unsafeRunSync()
        assert(held.isInstanceOf[Settlement.Owed], held)
        // a was placed: one player's lock does not hold up the others'.
        assertEquals(whileHeld, Map(a -> Some(1), b -> None))
        assertEquals(released, Settlement.Settled)
        assertEquals(after, Map(a -> Some(1), b -> Some(2)))
    }

    test("placings of one game run at once take turns, and leave it in order") {
        val patient = new RankingService(TestServices.pool, attempts = 100)
        val result = for {
            game <- makeGame()
            people <- players(8)
            ratings = people.zipWithIndex.map((p, i) => p -> Rating(1500 + (i % 3) * 10 + i, true)).toMap
            _ <- write(game, ratings)
            settled <- List.fill(4)(patient.rank(game.gameId)).parSequence
            placed <- places(game)
        } yield (settled, placed, expected(ratings))
        val (settled, placed, wanted) = result.timeout(caseTimeout).unsafeRunSync()
        assert(settled.forall(_ == Settlement.Settled), settled)
        assertEquals(placed, wanted)
    }

    test("the leaderboard is paged twenty places at a time, a tie is never split, and it says whether there is more") {
        val result = for {
            game <- makeGame()
            people <- players(26)
            // 1 to 18 apart, then four sharing 19, then 23 to 26 -- so the first page holds 22 players.
            _ <- write(
              game,
              people.zipWithIndex
                  .map((p, i) => p -> Rating(if (i < 18) 2000 - i else if (i < 22) 1000 else 900 - i, true))
                  .toMap
            )
            _ <- ranking.rank(game.gameId)
            first <- services.ratings.leaderboard(game.gameId, 0, people.head.externalId)
            second <- services.ratings.leaderboard(game.gameId, 1, people.head.externalId)
            third <- services.ratings.leaderboard(game.gameId, 2, people.head.externalId)
        } yield (first, second, third)
        val (first, second, third) = result.timeout(caseTimeout).unsafeRunSync()
        assertEquals(first.ratings.flatMap(_.rank), (1 to 18).toList ++ List.fill(4)(19))
        assert(first.more)
        assertEquals(second.ratings.flatMap(_.rank), (23 to 26).toList)
        assert(!second.more)
        assertEquals(third, Leaderboard(Nil, more = false))
    }
}
