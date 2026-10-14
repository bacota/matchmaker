package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.Duration
import scala.concurrent.duration._
import munit.FunSuite
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.standard.StandardModels
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    EloRatingRepo,
    GameRepo,
    Generators,
    TestSession,
    TournamentModelRepo
}

/** Creating, entering and starting tournaments, against the local database. */
class TournamentServiceSpec extends FunSuite with QuietTests {

    private val services = TestServices.services
    private val tournaments = services.tournaments

    private def unique(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

    private case class Fixture(game: Game, owner: Player, players: List[Player], models: Map[String, TournamentModel]) {

        /** The game's model of a standard format, by the format's key. */
        def model(key: String): TournamentModel =
            models.getOrElse(key, throw new IllegalArgumentException(s"no model of $key"))
    }

    /** A plain two-player game offering the standard formats as models, its owner, and `n` other registered players. */
    private def fixture(n: Int = 4, gameType: GameType = GameType.Plain): IO[Fixture] =
        for {
            game <- TestSession.resource.use(new GameRepo[String](_).create(Generators.genGame(gameType).sample.get))
            // Each in a transaction of its own, as the services write one: a model and its stages name each other.
            offered <- TestSession.resource.use(session =>
                StandardModels.all.traverse(m => TournamentFixtures.model(session, game, m.id))
            )
            byKey = StandardModels.all.map(_.id).zip(offered).toMap
            owner <- services.registration.register(unique("owner"), unique("sub"))
            players <- (1 to n).toList.traverse(_ => services.registration.register(unique("p"), unique("sub")))
        } yield Fixture(game, owner, players, byKey)

    private def request(
        f: Fixture,
        standard: String = "single-elimination",
        settings: TournamentSettings =
            TournamentSettings(invitational = false, roundDuration = Some(Duration.ofDays(2)))
    ) = TournamentRequest("Open", f.model(standard).tournamentModelId, "an open", settings = settings)

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    private def refusal[A](io: IO[A]): Option[Throwable] = run(io.attempt).left.toOption

    test("a tournament is created in draft from one of the game's models, its stages copied, owned by its creator") {
        val (f, t, detail) = run(for {
            f <- fixture(0)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            detail <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
        } yield (f, t, detail))
        assertEquals(t.status, TournamentStatus.Draft)
        assertEquals(t.owner, f.owner.playerId)
        assertEquals(t.tournamentModelId, f.model("single-elimination").tournamentModelId)
        assertEquals(detail.design, f.model("single-elimination").design)
        assertEquals(detail.gameType, GameType.Plain)
        // The model suggests no pool size, so the default is the game's R.
        assertEquals(t.defaultPoolSize, f.game.poolSeats)
    }

    test("a model the game does not offer, or a pool size that cannot be played, is refused") {
        val unknown = refusal(fixture(0).flatMap { f =>
            tournaments.create(
              f.game.gameId,
              request(f).copy(tournamentModelId = TournamentModelId("no-such-model")),
              f.owner.externalId
            )
        })
        assert(unknown.exists(_.isInstanceOf[NotFoundError]), unknown)
        // Every game's, but not offered in this one.
        val withdrawn = refusal(fixture(0).flatMap { f =>
            TestSession.resource.use(new TournamentModelRepo(_).withdraw(f.game.gameId, TournamentModelId("ladder"))) *>
                tournaments.create(f.game.gameId, request(f, "ladder"), f.owner.externalId)
        })
        assert(withdrawn.exists(_.isInstanceOf[NotFoundError]), withdrawn)
        val empty = refusal(fixture(0).flatMap { f =>
            tournaments.create(f.game.gameId, request(f).copy(defaultPoolSize = Some(0)), f.owner.externalId)
        })
        assert(empty.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("pool")), empty)
    }

    test("only the owner may edit, open, start or cancel a tournament") {
        val outcome = run(for {
            f <- fixture(1)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            stranger = f.players.head.externalId
            edit <- tournaments.update(f.game.gameId, t.tournamentId, request(f), stranger).attempt
            open <- tournaments.open(f.game.gameId, t.tournamentId, stranger).attempt
            start <- tournaments.start(f.game.gameId, t.tournamentId, stranger).attempt
            cancel <- tournaments.cancel(f.game.gameId, t.tournamentId, stranger).attempt
        } yield List(edit, open, start, cancel))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[UnauthorizedError])), outcome.toString)
    }

    test("a player can enter an open tournament once, and not a draft") {
        val (draft, entered, again) = run(for {
            f <- fixture(1)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            p = f.players.head.externalId
            draft <- tournaments.enter(f.game.gameId, t.tournamentId, None, p).attempt
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            entered <- tournaments.enter(f.game.gameId, t.tournamentId, None, p)
            again <- tournaments.enter(f.game.gameId, t.tournamentId, None, p).attempt
        } yield (draft, entered, again))
        assert(draft.left.exists(_.isInstanceOf[ConflictError]))
        assertEquals(entered.ratingAtEntry, EloRating.initial)
        assert(again.left.exists(e => e.isInstanceOf[ConflictError] && e.getMessage.contains("already entered")))
    }

    test("an invitational tournament takes only those invited, and its owner") {
        val (uninvited, invited, owner) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              f.game.gameId,
              request(f, settings = TournamentSettings(roundDuration = Some(Duration.ofDays(1)))),
              f.owner.externalId
            )
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            uninvited <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players.head.externalId).attempt
            _ <- tournaments.invite(f.game.gameId, t.tournamentId, f.players(1).playerId, f.owner.externalId)
            invited <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players(1).externalId).attempt
            owner <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.owner.externalId).attempt
        } yield (uninvited, invited, owner))
        assert(uninvited.left.exists(_.isInstanceOf[UnauthorizedError]))
        assert(invited.isRight && owner.isRight)
    }

    private def characterOf(f: Fixture, owner: Player): IO[Character[String]] =
        TestSession.resource.use(
          new CharacterRepo[String](_).create(Generators.genCharacter(f.game.gameId, Some(owner.playerId)).sample.get)
        )

    private def invitational(f: Fixture): IO[Tournament] =
        for {
            t <- tournaments.create(
              f.game.gameId,
              request(f, settings = TournamentSettings(roundDuration = Some(Duration.ofDays(1)))),
              f.owner.externalId
            )
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
        } yield t

    // In a character game the character plays, so it is the character that is invited -- and whoever owns it when the
    // invitation is answered may enter with it, which is how an invitation follows a character handed on.
    test("in a character game an invitation is a character's, and follows it to a new owner") {
        val (asPlayer, otherCharacter, formerOwner, newOwner, listed) = run(for {
            f <- fixture(2, GameType.Character)
            t <- invitational(f)
            invited <- characterOf(f, f.players.head)
            other <- characterOf(f, f.players.head)
            asPlayer <- tournaments
                .invite(f.game.gameId, t.tournamentId, f.players.head.playerId, f.owner.externalId)
                .attempt
            _ <- tournaments.inviteCharacter(f.game.gameId, t.tournamentId, invited.characterId, f.owner.externalId)
            otherCharacter <- tournaments
                .enter(f.game.gameId, t.tournamentId, Some(other.characterId), f.players.head.externalId)
                .attempt
            // Handed on before the invitation is answered.
            _ <- TestSession.resource.use(
              new CharacterRepo[String](_).update(invited.copy(playerId = Some(f.players(1).playerId)))
            )
            formerOwner <- tournaments
                .enter(f.game.gameId, t.tournamentId, Some(invited.characterId), f.players.head.externalId)
                .attempt
            d <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
            newOwner <- tournaments
                .enter(f.game.gameId, t.tournamentId, Some(invited.characterId), f.players(1).externalId)
                .attempt
        } yield (asPlayer, otherCharacter, formerOwner, newOwner, d.invitedCharacters))
        assert(asPlayer.left.exists(_.isInstanceOf[ValidationError]), asPlayer)
        assert(otherCharacter.left.exists(_.isInstanceOf[UnauthorizedError]), otherCharacter)
        assert(formerOwner.isLeft, formerOwner)
        assert(newOwner.isRight, newOwner)
        assertEquals(listed.size, 1)
        assert(listed.head.owner.nonEmpty)
    }

    test("a character's invitation is turned down by its owner, or revoked by the tournament's, and by nobody else") {
        val (stranger, declined, afterDecline, revoked) = run(for {
            f <- fixture(2, GameType.Character)
            t <- invitational(f)
            first <- characterOf(f, f.players.head)
            second <- characterOf(f, f.players.head)
            _ <- tournaments.inviteCharacter(f.game.gameId, t.tournamentId, first.characterId, f.owner.externalId)
            _ <- tournaments.inviteCharacter(f.game.gameId, t.tournamentId, second.characterId, f.owner.externalId)
            stranger <- tournaments
                .revokeCharacter(f.game.gameId, t.tournamentId, first.characterId, f.players(1).externalId)
                .attempt
            declined <- tournaments
                .revokeCharacter(f.game.gameId, t.tournamentId, first.characterId, f.players.head.externalId)
                .attempt
            d <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
            revoked <- tournaments
                .revokeCharacter(f.game.gameId, t.tournamentId, second.characterId, f.owner.externalId)
                .attempt
        } yield (stranger, declined, d.invitedCharacters.map(_.character.characterId), revoked))
        assert(stranger.left.exists(_.isInstanceOf[UnauthorizedError]), stranger)
        assert(declined.isRight && revoked.isRight)
        assertEquals(afterDecline.size, 1)
    }

    test("a closed challenge of a character game invites characters, and its invitee sees it as theirs to answer") {
        val (asPlayer, mine, theirs) = run(for {
            f <- fixture(2, GameType.Character)
            own <- characterOf(f, f.owner)
            invited <- characterOf(f, f.players.head)
            asPlayer <- tournaments
                .createChallenge(
                  f.game.gameId,
                  ChallengeRequest(
                    characterId = Some(own.characterId),
                    open = false,
                    invitations = List(f.players.head.playerId)
                  ),
                  f.owner.externalId
                )
                .attempt
            made <- tournaments.createChallenge(
              f.game.gameId,
              ChallengeRequest(
                characterId = Some(own.characterId),
                open = false,
                characterInvitations = List(invited.characterId)
              ),
              f.owner.externalId
            )
            mine <- tournaments.challengesFor(f.players.head.externalId)
            theirs <- tournaments.challengesFor(f.players(1).externalId)
        } yield (asPlayer, mine.find(_.summary.tournament.tournamentId == made.tournamentId), theirs))
        assert(asPlayer.left.exists(_.isInstanceOf[ValidationError]), asPlayer)
        assert(mine.exists(c => c.summary.invited && c.invitedCharacters.nonEmpty), mine)
        assert(theirs.isEmpty, theirs)
    }

    test("a rating outside the tournament's limits is refused") {
        val refused = refusal(for {
            f <- fixture(1)
            t <- tournaments.create(
              f.game.gameId,
              request(
                f,
                settings = TournamentSettings(
                  invitational = false,
                  minRating = Some(1600),
                  roundDuration = Some(Duration.ofDays(1))
                )
              ),
              f.owner.externalId
            )
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players.head.externalId)
        } yield ())
        assert(refused.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("at least 1600")), refused)
    }

    test("a character game's entry is one of the caller's characters") {
        val (none, theirs, mine, gameType) = run(for {
            f <- fixture(2, GameType.Character)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            other <- TestSession.resource.use(
              new CharacterRepo[String](_)
                  .create(Generators.genCharacter(f.game.gameId, Some(f.players(1).playerId)).sample.get)
            )
            own <- TestSession.resource.use(
              new CharacterRepo[String](_)
                  .create(Generators.genCharacter(f.game.gameId, Some(f.players.head.playerId)).sample.get)
            )
            none <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players.head.externalId).attempt
            theirs <- tournaments
                .enter(f.game.gameId, t.tournamentId, Some(other.characterId), f.players.head.externalId)
                .attempt
            mine <- tournaments.enter(f.game.gameId, t.tournamentId, Some(own.characterId), f.players.head.externalId)
            // The page asks for a character by the game's type, which the detail carries for a client without the game.
            detail <- tournaments.detail(f.game.gameId, t.tournamentId, f.players.head.externalId)
        } yield (none, theirs, mine, detail.gameType))
        assertEquals(gameType, GameType.Character)
        assert(none.left.exists(_.isInstanceOf[ValidationError]))
        assert(theirs.left.exists(_.isInstanceOf[ValidationError]))
        assert(mine.characterId.isDefined)
    }

    test("starting seeds the entrants by rating and forms the first stage's pools; it needs two entries") {
        val (tooFew, started, detail) = run(for {
            f <- fixture(4)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players.head.externalId)
            tooFew <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId).attempt
            // The last to enter is rated highest, so takes the top seed.
            _ <- TestSession.resource.use(
              new EloRatingRepo(_).set(f.game.gameId, f.players(3).playerId, 1900, f.owner.playerId)
            )
            _ <- f.players.tail.traverse(p => tournaments.enter(f.game.gameId, t.tournamentId, None, p.externalId))
            started <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId)
            detail <- tournaments.detail(f.game.gameId, t.tournamentId, f.players.head.externalId)
        } yield (tooFew, started, detail))
        assert(tooFew.left.exists(_.isInstanceOf[ConflictError]))
        assertEquals(started.status, TournamentStatus.Running)
        val state = detail.state.getOrElse(fail("a started tournament has a state"))
        val top = state.root.members.minBy(_.seed).entrant
        assertEquals(detail.entries.find(_.entrantId.value == top).map(_.playerId), Some(detail.entries.last.playerId))
        // Single elimination of four: two pools of two.
        assertEquals(state.root.runs.head.pools.map(_.members.size), List(2, 2))
        assertEquals(detail.summary.entrants, 4)
    }

    test("withdrawing before the start removes the entry; after it, marks it and keeps it out of later pools") {
        val (before, after) = run(for {
            f <- fixture(3)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            entries <- f.players.traverse(p => tournaments.enter(f.game.gameId, t.tournamentId, None, p.externalId))
            _ <- tournaments.withdraw(f.game.gameId, t.tournamentId, entries.head.entrantId, f.players.head.externalId)
            before <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players.head.externalId)
            _ <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- tournaments.withdraw(f.game.gameId, t.tournamentId, entries(1).entrantId, f.owner.externalId)
            after <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
        } yield (before, after))
        assertEquals(before.entries.size, 2)
        assert(after.entries.exists(e => e.withdrawn))
        assert(after.state.exists(_.withdrawn.nonEmpty))
    }

    test("a running tournament with late entries takes them into its state, and refuses them otherwise") {
        val (refused, late) = run(for {
            f <- fixture(3)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- f.players.take(2).traverse(p => tournaments.enter(f.game.gameId, t.tournamentId, None, p.externalId))
            _ <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId)
            refused <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players(2).externalId).attempt
            _ <- tournaments.update(
              f.game.gameId,
              t.tournamentId,
              request(f, settings = TournamentSettings(lateEntries = true, roundDuration = Some(Duration.ofDays(2)))),
              f.owner.externalId
            )
            entry <- tournaments.enter(f.game.gameId, t.tournamentId, None, f.players(2).externalId)
            detail <- tournaments.detail(f.game.gameId, t.tournamentId, f.owner.externalId)
        } yield (refused, detail.state.exists(_.root.members.exists(_.entrant == entry.entrantId.value))))
        assert(refused.left.exists(_.isInstanceOf[ConflictError]))
        assert(late)
    }

    test("a running tournament's design cannot be changed, nor anything once it is over") {
        val (designKept, overRefused) = run(for {
            f <- fixture(2)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- f.players.traverse(p => tournaments.enter(f.game.gameId, t.tournamentId, None, p.externalId))
            _ <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId)
            edited <- tournaments.update(
              f.game.gameId,
              t.tournamentId,
              request(f, "world-cup").copy(name = "Renamed"),
              f.owner.externalId
            )
            _ <- tournaments.cancel(f.game.gameId, t.tournamentId, f.owner.externalId)
            over <- tournaments.update(f.game.gameId, t.tournamentId, request(f), f.owner.externalId).attempt
        } yield (edited.tournamentModelId == t.tournamentModelId && edited.name == "Renamed", over))
        assert(designKept)
        assert(overRefused.left.exists(_.isInstanceOf[ConflictError]))
    }

    test("a player's tournaments list those they own, entered or were invited to") {
        val (mine, theirs) = run(
          for {
              f <- fixture(2)
              t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
              _ <- tournaments.invite(f.game.gameId, t.tournamentId, f.players.head.playerId, f.owner.externalId)
              mine <- tournaments.mine(f.owner.externalId)
              theirs <- tournaments.mine(f.players.head.externalId)
          } yield (
            mine.find(_.tournament.tournamentId == t.tournamentId),
            theirs.find(_.tournament.tournamentId == t.tournamentId)
          )
        )
        assert(mine.exists(_.isOwner))
        assert(theirs.exists(s => s.invited && !s.isOwner))
    }

    test("a player's standings say where they stand in each tournament they entered, by their seed while it runs") {
        val (before, after, entrants) = run(for {
            f <- fixture(2)
            t <- tournaments.create(f.game.gameId, request(f), f.owner.externalId)
            _ <- tournaments.open(f.game.gameId, t.tournamentId, f.owner.externalId)
            _ <- f.players.traverse(p => tournaments.enter(f.game.gameId, t.tournamentId, None, p.externalId))
            before <- tournaments.standings(f.players.head.externalId)
            _ <- tournaments.start(f.game.gameId, t.tournamentId, f.owner.externalId)
            after <- tournaments.standings(f.players.head.externalId)
        } yield (before, after, f.players.size))
        assertEquals(before.map(_.status), List(TournamentStatus.Open))
        assertEquals(before.flatMap(_.seed), Nil)
        assertEquals(after.map(_.status), List(TournamentStatus.Running))
        assert(after.head.seed.exists(s => s >= 1 && s <= entrants), after)
        assertEquals(after.head.entrants, entrants)
        assertEquals(after.head.finalRank, None)
    }
}
