package com.vivi.matchmaker.service

/* Every cross-game list, narrowed to one game. A new route that lists rows from more than one game
 * belongs here too: nothing else checks that a game site's players are not shown the other games.
 * See design/game-sites-plan.md. */

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import java.time.Instant
import munit.FunSuite
import skunk.Session
import skunk.implicits._
import skunk.codec.all.{int4, int8}
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence.{GameRepo, Generators, TestMatches, TestSession}

class GameScopeSpec extends FunSuite {

    private val services = TestServices.services

    // A DB-backed fixture of two games with a dozen rows in each: generous, as CLAUDE.md asks of these timeouts.
    private def run[A](io: IO[A]): A = io.timeout(120.seconds).unsafeRunSync()

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    /** Two games, and a player who in each has: a match that is their turn (public, in a running tournament they own
      * and have an entry in), a public match finished just now (in a tournament of theirs that has ended), another
      * finished days ago (so each completed list has an older window), a match waiting on their role choice, and
      * settings of their own for the game. `watcher` is somebody else, for the lists on the player's page.
      */
    private case class Fixture(first: Game, second: Game, player: Player, watcher: Player) {
        def both: Set[GameId] = Set(first.gameId, second.gameId)
        def onlyFirst: GameScope = GameScope.only(first.gameId)
        def noGames: GameScope = GameScope.Only(Set.empty)
    }

    private lazy val fixture: Fixture = run(for {
        player <- services.registration.register(nickname = unique("p"), externalId = unique("sub"))
        watcher <- services.registration.register(nickname = unique("w"), externalId = unique("sub"))
        first <- gameWithRows(player)
        second <- gameWithRows(player)
    } yield Fixture(first = first, second = second, player = player, watcher = watcher))

    private def gameWithRows(player: Player): IO[Game] =
        TestSession.resource.use { session =>
            for {
                game <- new GameRepo[String](session).create(Generators.genGame(GameType.Plain).sample.get)
                role = game.roles.headOption.map(_.gameRoleId)
                now = Instant.now()
                // Their turn, in a running tournament of theirs.
                _ <- matchFor(session = session, game = game, player = player, role = role, isPending = true)
                // Finished a minute ago, in a tournament of theirs that has then ended.
                recent <- matchFor(
                  session = session,
                  game = game,
                  player = player,
                  role = role,
                  completedAt = Some(now.minusSeconds(60))
                )
                _ <- session.execute(sql"""UPDATE tournament SET status = 'finished', ended_at = now()
                    WHERE game_id = $int4 AND tournament_id = $int8""".command)(
                  (game.gameId.value, recent.tournamentId.value)
                )
                // Finished days ago: what makes a window of the completed lists have an older one.
                older <- matchFor(
                  session = session,
                  game = game,
                  player = player,
                  role = role,
                  completedAt = Some(now.minusSeconds(3 * 24 * 3600))
                )
                _ <- session.execute(sql"""UPDATE participant SET completed_at = now() - interval '3 days'
                    WHERE game_id = $int4 AND match_id = $int8""".command)((game.gameId.value, older.matchId.value))
                // Waiting on them to choose a role: not begun, and their seat has none.
                choosing <- matchFor(
                  session = session,
                  game = game,
                  player = player,
                  role = None,
                  status = MatchStatus.Choosing,
                  start = None
                )
                _ <- session.execute(sql"""UPDATE participant SET choose_order = 1
                    WHERE game_id = $int4 AND match_id = $int8""".command)((game.gameId.value, choosing.matchId.value))
                _ <- services.notifications.updateForGame(
                  callerExternalId = player.externalId,
                  gameId = game.gameId,
                  preferences = NotificationPreferences(isNotifiedOfMatchEnded = Some(false))
                )
            } yield game
        }

    private def matchFor(
        session: Session[IO],
        game: Game,
        player: Player,
        role: Option[GameRoleId],
        isPending: Boolean = false,
        completedAt: Option[Instant] = None,
        status: MatchStatus = MatchStatus.InProgress,
        start: Option[Instant] = Some(Instant.now().minusSeconds(3600))
    ): IO[Match] =
        for {
            written <- TestMatches.writeMatch(
              session = session,
              m = Match(
                gameId = game.gameId,
                matchId = Generators.newMatchId(),
                tournamentId = TournamentId.unassigned,
                description = "description",
                completedAt = completedAt,
                start = start,
                timeLimit = None,
                settings = "{}",
                isPublic = true,
                status = status
              ),
              owner = player.playerId
            )
            _ <- TestMatches.writeSeat(
              session = session,
              p = PlainParticipant(
                participantId = ParticipantId(0),
                gameId = game.gameId,
                matchId = written.matchId,
                playerId = player.playerId,
                isPending = isPending,
                isCompleted = completedAt.isDefined,
                due = None,
                gameRoleId = role
              )
            )
        } yield written

    /** `list` asked of one game's scope, of every game's, and of none: the first answers rows of the first game only,
      * and at least one; the second rows of both; the last nothing.
      */
    private def check(what: String)(list: GameScope => IO[List[GameId]]): Unit = {
        val f = fixture
        val (onlyFirst, all, none) = run((list(f.onlyFirst), list(GameScope.All), list(f.noGames)).tupled)
        assert(onlyFirst.nonEmpty, s"$what: nothing for the first game")
        assertEquals(onlyFirst.toSet, Set(f.first.gameId), s"$what: narrowed to the first game")
        assertEquals(all.toSet.intersect(f.both), f.both, s"$what: every game")
        assertEquals(none, Nil, s"$what: no game")
    }

    test("GameService.list") {
        check("games")(games =>
            services.games.list(callerExternalId = fixture.player.externalId, games = games).map(_.map(_.gameId))
        )
    }

    test("MatchService.due") {
        check("due")(games => services.matches.due(fixture.player.externalId, games).map(_.map(_.gameId)))
    }

    test("MatchService.active") {
        check("active")(games => services.matches.active(fixture.player.externalId, games).map(_.map(_.gameId)))
    }

    test("MatchService.completed") {
        check("completed")(games =>
            services.matches.completed(fixture.player.externalId, games).map(_.matches.toList.map(_.gameId))
        )
    }

    test("MatchService.completed is narrowed further by the game the query names") {
        val f = fixture
        val (named, outside) = run(
          (
            services.matches.completed(
              callerExternalId = f.player.externalId,
              games = GameScope.All,
              query = CompletedQuery(gameId = Some(f.second.gameId))
            ),
            services.matches.completed(
              callerExternalId = f.player.externalId,
              games = f.onlyFirst,
              query = CompletedQuery(gameId = Some(f.second.gameId))
            )
          ).tupled
        )
        assertEquals(named.matches.map(_.gameId).toSet, Set(f.second.gameId))
        // A site's player asking for a game the site does not show is shown none of it.
        assertEquals(outside.matches.toList, Nil)
        assert(!outside.hasOlder)
    }

    test("MatchService.completed says there is an older window only of the games in scope") {
        val f = fixture
        val (inScope, none) = run(
          (
            services.matches.completed(f.player.externalId, f.onlyFirst),
            services.matches.completed(f.player.externalId, f.noGames)
          ).tupled
        )
        assert(inScope.hasOlder)
        assert(!none.hasOlder)
    }

    test("MatchService.publicActive") {
        check("publicActive")(games =>
            services.matches
                .publicActive(
                  callerExternalId = fixture.watcher.externalId,
                  playerId = fixture.player.playerId,
                  games = games
                )
                .map(_.map(_.gameId))
        )
    }

    test("MatchService.publicCompleted") {
        check("publicCompleted")(games =>
            services.matches
                .publicCompleted(
                  callerExternalId = fixture.watcher.externalId,
                  playerId = fixture.player.playerId,
                  games = games,
                  query = CompletedQuery()
                )
                .map(_.matches.toList.map(_.gameId))
        )
    }

    test("MatchService.publicCompleted says there is an older window only of the games in scope") {
        val f = fixture
        def page(games: GameScope) =
            services.matches.publicCompleted(
              callerExternalId = f.watcher.externalId,
              playerId = f.player.playerId,
              games = games,
              query = CompletedQuery()
            )
        val (inScope, none) = run((page(f.onlyFirst), page(f.noGames)).tupled)
        assert(inScope.hasOlder)
        assert(!none.hasOlder)
    }

    test("MatchService.results") {
        check("results")(games => services.matches.results(fixture.player.externalId, games).map(_.map(_.gameId)))
    }

    test("TournamentService.mine") {
        check("mine")(games =>
            services.tournaments.mine(fixture.player.externalId, games).map(_.map(_.tournament.gameId))
        )
    }

    test("TournamentService.endedOwned") {
        check("endedOwned")(games =>
            services.tournaments
                .endedOwned(fixture.player.externalId, CompletedFrame.Day, games)
                .map(_.map(_.tournament.gameId))
        )
    }

    test("TournamentService.standings") {
        check("standings")(games =>
            services.tournaments.standings(fixture.player.externalId, games).map(_.map(_.gameId))
        )
    }

    test("TournamentRounds.choicesFor") {
        check("choicesFor")(games => services.rounds.choicesFor(fixture.player.externalId, games).map(_.map(_.gameId)))
    }

    test("NotificationService.mine") {
        check("notifications")(games =>
            services.notifications.mine(fixture.player.externalId, games).map(_.games.map(_.gameId).toList)
        )
    }
}
