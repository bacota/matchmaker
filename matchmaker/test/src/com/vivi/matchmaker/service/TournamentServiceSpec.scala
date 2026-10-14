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

    /** A plain two-player game — or the game without roles given — offering the standard formats as models, its owner,
      * and `n` other registered players.
      */
    private def fixture(n: Int = 4, gameType: GameType = GameType.Plain, roleless: Option[Game] = None): IO[Fixture] =
        for {
            game <- TestSession.resource.use(
              new GameRepo[String](_).create(roleless.getOrElse(Generators.genGame(gameType).sample.get))
            )
            // Each in a transaction of its own, as the services write one: a model and its stages name each other.
            offered <- TestSession.resource.use(session =>
                StandardModels.all.traverse(m => TournamentFixtures.model(session = session, game = game, key = m.id))
            )
            byKey = StandardModels.all.map(_.id).zip(offered).toMap
            owner <- services.registration.register(nickname = unique("owner"), externalId = unique("sub"))
            players <- (1 to n).toList.traverse(_ =>
                services.registration.register(nickname = unique("p"), externalId = unique("sub"))
            )
        } yield Fixture(game = game, owner = owner, players = players, models = byKey)

    private def request(
        f: Fixture,
        standard: String = "single-elimination",
        settings: TournamentSettings =
            TournamentSettings(isInvitational = false, roundDuration = Some(Duration.ofDays(2)))
    ) = TournamentRequest(
      name = "Open",
      tournamentModelId = f.model(standard).tournamentModelId,
      description = "an open",
      settings = settings
    )

    private def run[A](io: IO[A]): A = io.timeout(60.seconds).unsafeRunSync()

    private def refusal[A](io: IO[A]): Option[Throwable] = run(io.attempt).left.toOption

    test("a tournament is created in draft from one of the game's models, its stages copied, owned by its creator") {
        val (f, t, detail) = run(for {
            f <- fixture(0)
            t <- tournaments
                .create(gameId = f.game.gameId, request = request(f = f), callerExternalId = f.owner.externalId)
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
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
              gameId = f.game.gameId,
              request = request(f = f).copy(tournamentModelId = TournamentModelId("no-such-model")),
              callerExternalId = f.owner.externalId
            )
        })
        assert(unknown.exists(_.isInstanceOf[NotFoundError]), unknown)
        // Every game's, but not offered in this one.
        val withdrawn = refusal(fixture(0).flatMap { f =>
            TestSession.resource.use(new TournamentModelRepo(_).withdraw(f.game.gameId, TournamentModelId("ladder"))) *>
                tournaments.create(
                  gameId = f.game.gameId,
                  request = request(f = f, standard = "ladder"),
                  callerExternalId = f.owner.externalId
                )
        })
        assert(withdrawn.exists(_.isInstanceOf[NotFoundError]), withdrawn)
        val empty = refusal(fixture(0).flatMap { f =>
            tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f).copy(defaultPoolSize = Some(0)),
              callerExternalId = f.owner.externalId
            )
        })
        assert(empty.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("pool")), empty)
    }

    test("no pool may be smaller than the game's required roles; a challenge's may be larger than all its roles") {
        val (tooSmall, tooSmallChallenge, larger) = run(for {
            // A generated game has two roles, both required.
            f <- fixture(0)
            tooSmall <- tournaments
                .create(
                  gameId = f.game.gameId,
                  request = request(f = f, standard = "challenge").copy(defaultPoolSize = Some(1)),
                  callerExternalId = f.owner.externalId
                )
                .attempt
            tooSmallChallenge <- tournaments
                .createChallenge(
                  gameId = f.game.gameId,
                  request = ChallengeRequest(poolSize = Some(1)),
                  callerExternalId = f.owner.externalId
                )
                .attempt
            // More than the game has roles: a round robin rather than one match, and it takes as many entries.
            larger <- tournaments.createChallenge(
              gameId = f.game.gameId,
              request = ChallengeRequest(poolSize = Some(3)),
              callerExternalId = f.owner.externalId
            )
        } yield (tooSmall, tooSmallChallenge, larger))
        assert(tooSmall.left.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("required roles")))
        assert(tooSmallChallenge.left.exists(_.isInstanceOf[ValidationError]), tooSmallChallenge)
        assertEquals(larger.defaultPoolSize, 3)
        assertEquals(larger.settings.maxEntrants, Some(3))
    }

    test("only the owner may edit, open, start or cancel a tournament") {
        val outcome = run(for {
            f <- fixture(1)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            stranger = f.players.head.externalId
            edit <- tournaments
                .update(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  request = request(f = f),
                  callerExternalId = stranger
                )
                .attempt
            open <- tournaments
                .open(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = stranger)
                .attempt
            start <- tournaments
                .start(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = stranger)
                .attempt
            cancel <- tournaments
                .cancel(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = stranger)
                .attempt
        } yield List(edit, open, start, cancel))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[UnauthorizedError])), outcome.toString)
    }

    test("a player can enter an open tournament once, and not a draft") {
        val (draft, entered, again) = run(for {
            f <- fixture(1)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            p = f.players.head.externalId
            draft <- tournaments
                .enter(gameId = f.game.gameId, tournamentId = t.tournamentId, characterId = None, callerExternalId = p)
                .attempt
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            entered <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = p
            )
            again <- tournaments
                .enter(gameId = f.game.gameId, tournamentId = t.tournamentId, characterId = None, callerExternalId = p)
                .attempt
        } yield (draft, entered, again))
        assert(draft.left.exists(_.isInstanceOf[ConflictError]))
        assertEquals(entered.ratingAtEntry, EloRating.initial)
        assert(again.left.exists(e => e.isInstanceOf[ConflictError] && e.getMessage.contains("already entered")))
    }

    test("an invitational tournament takes only those invited, and its owner") {
        val (uninvited, invited, owner) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f, settings = TournamentSettings(roundDuration = Some(Duration.ofDays(1)))),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            uninvited <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            _ <- tournaments.invite(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              playerId = f.players(1).playerId,
              callerExternalId = f.owner.externalId
            )
            invited <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = f.players(1).externalId
                )
                .attempt
            owner <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = f.owner.externalId
                )
                .attempt
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
              gameId = f.game.gameId,
              request = request(f = f, settings = TournamentSettings(roundDuration = Some(Duration.ofDays(1)))),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
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
                .invite(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  playerId = f.players.head.playerId,
                  callerExternalId = f.owner.externalId
                )
                .attempt
            _ <- tournaments.inviteCharacter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = invited.characterId,
              callerExternalId = f.owner.externalId
            )
            otherCharacter <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = Some(other.characterId),
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            // Handed on before the invitation is answered.
            _ <- TestSession.resource.use(
              new CharacterRepo[String](_).update(invited.copy(playerId = Some(f.players(1).playerId)))
            )
            formerOwner <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = Some(invited.characterId),
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            d <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            newOwner <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = Some(invited.characterId),
                  callerExternalId = f.players(1).externalId
                )
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
            _ <- tournaments.inviteCharacter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = first.characterId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.inviteCharacter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = second.characterId,
              callerExternalId = f.owner.externalId
            )
            stranger <- tournaments
                .revokeCharacter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = first.characterId,
                  callerExternalId = f.players(1).externalId
                )
                .attempt
            declined <- tournaments
                .revokeCharacter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = first.characterId,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            d <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            revoked <- tournaments
                .revokeCharacter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = second.characterId,
                  callerExternalId = f.owner.externalId
                )
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
                  gameId = f.game.gameId,
                  request = ChallengeRequest(
                    characterId = Some(own.characterId),
                    isOpen = false,
                    invitations = List(f.players.head.playerId)
                  ),
                  callerExternalId = f.owner.externalId
                )
                .attempt
            made <- tournaments.createChallenge(
              gameId = f.game.gameId,
              request = ChallengeRequest(
                characterId = Some(own.characterId),
                isOpen = false,
                characterInvitations = List(invited.characterId)
              ),
              callerExternalId = f.owner.externalId
            )
            mine <- tournaments.challengesFor(f.players.head.externalId)
            theirs <- tournaments.challengesFor(f.players(1).externalId)
        } yield (asPlayer, mine.find(_.summary.tournament.tournamentId == made.tournamentId), theirs))
        assert(asPlayer.left.exists(_.isInstanceOf[ValidationError]), asPlayer)
        assert(mine.exists(c => c.summary.isInvited && c.invitedCharacters.nonEmpty), mine)
        assert(theirs.isEmpty, theirs)
    }

    test("a rating outside the tournament's limits is refused") {
        val refused = refusal(for {
            f <- fixture(1)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(
                f = f,
                settings = TournamentSettings(
                  isInvitational = false,
                  minRating = Some(1600),
                  roundDuration = Some(Duration.ofDays(1))
                )
              ),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = f.players.head.externalId
            )
        } yield ())
        assert(refused.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("at least 1600")), refused)
    }

    test("a character game's entry is one of the caller's characters") {
        val (none, theirs, mine, gameType) = run(for {
            f <- fixture(2, GameType.Character)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            other <- TestSession.resource.use(
              new CharacterRepo[String](_)
                  .create(Generators.genCharacter(f.game.gameId, Some(f.players(1).playerId)).sample.get)
            )
            own <- TestSession.resource.use(
              new CharacterRepo[String](_)
                  .create(Generators.genCharacter(f.game.gameId, Some(f.players.head.playerId)).sample.get)
            )
            none <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            theirs <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = Some(other.characterId),
                  callerExternalId = f.players.head.externalId
                )
                .attempt
            mine <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = Some(own.characterId),
              callerExternalId = f.players.head.externalId
            )
            // The page asks for a character by the game's type, which the detail carries for a client without the game.
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.players.head.externalId
            )
        } yield (none, theirs, mine, detail.gameType))
        assertEquals(gameType, GameType.Character)
        assert(none.left.exists(_.isInstanceOf[ValidationError]))
        assert(theirs.left.exists(_.isInstanceOf[ValidationError]))
        assert(mine.characterId.isDefined)
    }

    test("players per match is refused for a game with roles, and outside a game without roles' range of players") {
        def attempt(game: Option[Game], playersPerMatch: Option[Int], poolSize: Option[Int] = None) =
            run(fixture(0, roleless = game).flatMap { f =>
                tournaments
                    .create(
                      gameId = f.game.gameId,
                      request = request(
                        f = f,
                        standard = "challenge",
                        settings = TournamentSettings(isInvitational = false, playersPerMatch = playersPerMatch)
                      ).copy(defaultPoolSize = poolSize),
                      callerExternalId = f.owner.externalId
                    )
                    .attempt
            })
        def refused(e: Either[Throwable, Tournament]) = e.left.exists(_.isInstanceOf[ValidationError])
        val open = Generators.genRolelessGame(least = 2, most = Some(4))
        assert(refused(attempt(game = None, playersPerMatch = Some(2))))
        assert(refused(attempt(game = Some(open), playersPerMatch = Some(5))))
        assert(refused(attempt(game = Some(open), playersPerMatch = Some(1))))
        // Everyone in a pool in one match: a pool bigger than the game takes is refused.
        assert(refused(attempt(game = Some(open), playersPerMatch = None, poolSize = Some(6))))
        val fine = attempt(game = Some(open), playersPerMatch = Some(3)).toOption.get
        assertEquals(fine.settings.playersPerMatch, Some(3))
        // Its R is the players per match, which is the pool size the format suggests.
        assertEquals(fine.defaultPoolSize, 3)
    }

    test("a game without roles, everyone in the pool, its pool set to the entries before the start, is one match") {
        val (pools, round) = run(
          for {
              f <- fixture(5, roleless = Some(Generators.genRolelessGame(least = 2, most = None)))
              t <- tournaments.create(
                gameId = f.game.gameId,
                request = request(f = f, standard = "challenge").copy(defaultPoolSize = Some(2)),
                callerExternalId = f.owner.externalId
              )
              _ <- tournaments.open(gameId = f.game.gameId, tournamentId = t.tournamentId, f.owner.externalId)
              _ <- f.players.traverse(p =>
                  tournaments.enter(
                    gameId = f.game.gameId,
                    tournamentId = t.tournamentId,
                    characterId = None,
                    callerExternalId = p.externalId
                  )
              )
              // What the form's "one pool of everyone entered" sends.
              _ <- tournaments.update(
                gameId = f.game.gameId,
                tournamentId = t.tournamentId,
                request = request(f = f, standard = "challenge").copy(defaultPoolSize = Some(f.players.size)),
                callerExternalId = f.owner.externalId
              )
              _ <- tournaments.start(gameId = f.game.gameId, tournamentId = t.tournamentId, f.owner.externalId)
              detail <- tournaments.detail(
                gameId = f.game.gameId,
                tournamentId = t.tournamentId,
                callerExternalId = f.owner.externalId
              )
          } yield (
            detail.state.get.root.runs.head.pools.map(_.members.size),
            detail.state.get.root.runs.head.pools.flatMap(_.matches).map(_.planned.seats)
          )
        )
        assertEquals(pools, List(5))
        // One match of all five, nobody given a role or choosing one.
        assertEquals(round.map(_.size), List(5))
        assert(round.flatten.forall(s => s.role.isEmpty && s.chooseOrder.isEmpty))
    }

    test("starting seeds the entrants by rating and forms the first stage's pools; it needs two entries") {
        val (tooFew, started, detail) = run(for {
            f <- fixture(4)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = f.players.head.externalId
            )
            tooFew <- tournaments
                .start(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = f.owner.externalId)
                .attempt
            // The last to enter is rated highest, so takes the top seed.
            _ <- TestSession.resource.use(
              new EloRatingRepo(_)
                  .set(game = f.game.gameId, player = f.players(3).playerId, rating = 1900, setBy = f.owner.playerId)
            )
            _ <- f.players.tail.traverse(p =>
                tournaments.enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            )
            started <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.players.head.externalId
            )
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
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            entries <- f.players.traverse(p =>
                tournaments.enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            )
            _ <- tournaments.withdraw(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              entrantId = entries.head.entrantId,
              callerExternalId = f.players.head.externalId
            )
            before <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = f.players.head.externalId
            )
            _ <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.withdraw(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              entrantId = entries(1).entrantId,
              callerExternalId = f.owner.externalId
            )
            after <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
        } yield (before, after))
        assertEquals(before.entries.size, 2)
        assert(after.entries.exists(e => e.isWithdrawn))
        assert(after.state.exists(_.withdrawn.nonEmpty))
    }

    test("a running tournament with late entries takes them into its state, and refuses them otherwise") {
        val (refused, late) = run(for {
            f <- fixture(3)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- f.players
                .take(2)
                .traverse(p =>
                    tournaments.enter(
                      gameId = f.game.gameId,
                      tournamentId = t.tournamentId,
                      characterId = None,
                      callerExternalId = p.externalId
                    )
                )
            _ <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            refused <- tournaments
                .enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = f.players(2).externalId
                )
                .attempt
            _ <- tournaments.update(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              request = request(
                f = f,
                settings = TournamentSettings(hasLateEntries = true, roundDuration = Some(Duration.ofDays(2)))
              ),
              callerExternalId = f.owner.externalId
            )
            entry <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = f.players(2).externalId
            )
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
        } yield (refused, detail.state.exists(_.root.members.exists(_.entrant == entry.entrantId.value))))
        assert(refused.left.exists(_.isInstanceOf[ConflictError]))
        assert(late)
    }

    test("a running tournament's design cannot be changed, nor anything once it is over") {
        val (designKept, overRefused) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- f.players.traverse(p =>
                tournaments.enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            )
            _ <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            edited <- tournaments.update(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              request = request(f = f, standard = "world-cup").copy(name = "Renamed"),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.cancel(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            over <- tournaments
                .update(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  request = request(f = f),
                  callerExternalId = f.owner.externalId
                )
                .attempt
        } yield (edited.tournamentModelId == t.tournamentModelId && edited.name == "Renamed", over))
        assert(designKept)
        assert(overRefused.left.exists(_.isInstanceOf[ConflictError]))
    }

    test("a player's tournaments list those they own, entered or were invited to") {
        val (mine, theirs) = run(
          for {
              f <- fixture(2)
              t <- tournaments
                  .create(gameId = f.game.gameId, request = request(f = f), callerExternalId = f.owner.externalId)
              _ <- tournaments.invite(
                gameId = f.game.gameId,
                tournamentId = t.tournamentId,
                playerId = f.players.head.playerId,
                callerExternalId = f.owner.externalId
              )
              mine <- tournaments.mine(f.owner.externalId)
              theirs <- tournaments.mine(f.players.head.externalId)
          } yield (
            mine.find(_.tournament.tournamentId == t.tournamentId),
            theirs.find(_.tournament.tournamentId == t.tournamentId)
          )
        )
        assert(mine.exists(_.isOwner))
        assert(theirs.exists(s => s.isInvited && !s.isOwner))
    }

    test("a player's standings say where they stand in each tournament they entered, by their seed while it runs") {
        val (before, after, entrants) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.open(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- f.players.traverse(p =>
                tournaments.enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            )
            before <- tournaments.standings(f.players.head.externalId)
            _ <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
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
