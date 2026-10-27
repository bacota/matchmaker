package com.vivi.matchmaker.service

import cats.effect.IO
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import java.time.Duration
import scala.concurrent.duration._
import munit.FunSuite
import com.vivi.matchmaker.QuietTests
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.standard.{RoundRobin, StandardModels}
import com.vivi.matchmaker.persistence.{
    CharacterRepo,
    EloRatingRepo,
    GameAdminRepo,
    GameRepo,
    Generators,
    TestSession,
    TournamentModelRepo,
    TournamentRepo
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

    test(
      "a tournament is created open for entries from one of the game's models, its stages copied, owned by its creator"
    ) {
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
        assertEquals(t.status, TournamentStatus.Open)
        assertEquals(t.owner, f.owner.playerId)
        assertEquals(t.tournamentModelId, f.model("single-elimination").tournamentModelId)
        assertEquals(detail.design, f.model("single-elimination").design)
        assertEquals(detail.gameType, GameType.Plain)
        // The model suggests no pool size, so the default is the game's R.
        assertEquals(t.defaultPoolSize, Some(f.game.poolSeats))
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

    test("no pool may be smaller than the game's required roles; a round robin's may be larger than all its roles") {
        val (tooSmall, larger) = run(for {
            // A generated game has two roles, both required.
            f <- fixture(0)
            tooSmall <- tournaments
                .create(
                  gameId = f.game.gameId,
                  request = request(f = f, standard = RoundRobin.id).copy(defaultPoolSize = Some(1)),
                  callerExternalId = f.owner.externalId
                )
                .attempt
            // More than the game has roles: a round robin rather than one match.
            larger <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f, standard = RoundRobin.id).copy(defaultPoolSize = Some(3)),
              callerExternalId = f.owner.externalId
            )
        } yield (tooSmall, larger))
        assert(tooSmall.left.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("required roles")))
        assertEquals(larger.defaultPoolSize, Some(3))
    }

    test("only an admin of the game may make a competition rated, whether creating one or editing a friendly one") {
        val (asPlayer, editedAsPlayer, asAdmin) = run(for {
            f <- fixture(1)
            rated = request(f = f).copy(settings = request(f = f).settings.copy(isFriendly = false))
            asPlayer <- tournaments
                .create(gameId = f.game.gameId, request = rated, callerExternalId = f.owner.externalId)
                .attempt
            friendly <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            editedAsPlayer <- tournaments
                .update(
                  gameId = f.game.gameId,
                  tournamentId = friendly.tournamentId,
                  request = rated,
                  callerExternalId = f.owner.externalId
                )
                .attempt
            admin = f.players.head
            _ <- TestSession.resource.use(session =>
                new GameAdminRepo(session)
                    .grant(player = admin.playerId, game = f.game.gameId, grantedBy = admin.playerId)
            )
            asAdmin <- tournaments.create(gameId = f.game.gameId, request = rated, callerExternalId = admin.externalId)
        } yield (asPlayer, editedAsPlayer, asAdmin))
        assert(asPlayer.left.exists(_.isInstanceOf[UnauthorizedError]), asPlayer)
        assert(editedAsPlayer.left.exists(_.isInstanceOf[UnauthorizedError]), editedAsPlayer)
        assert(!asAdmin.settings.isFriendly, asAdmin)
    }

    test("only the owner may edit, start or stop a tournament") {
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
            start <- tournaments
                .start(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = stranger)
                .attempt
            stop <- services.rounds
                .stop(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = stranger)
                .attempt
        } yield List(edit, start, stop))
        assert(outcome.forall(_.left.exists(_.isInstanceOf[UnauthorizedError])), outcome.toString)
    }

    test("a player can enter an open tournament once") {
        val (entered, again) = run(for {
            f <- fixture(1)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            p = f.players.head.externalId
            entered <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = p
            )
            again <- tournaments
                .enter(gameId = f.game.gameId, tournamentId = t.tournamentId, characterId = None, callerExternalId = p)
                .attempt
        } yield (entered, again))
        assertEquals(entered.ratingAtEntry, EloRating.initial)
        assert(again.left.exists(e => e.isInstanceOf[ConflictError] && e.getMessage.contains("already entered")))
    }

    test("a tournament's creator can enter it as it is made") {
        val (made, detail) = run(for {
            f <- fixture(1)
            made <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f).copy(entry = Some(TournamentEntry())),
              callerExternalId = f.owner.externalId
            )
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = made.tournamentId,
              callerExternalId = f.owner.externalId
            )
        } yield (made, detail))
        assertEquals(made.status, TournamentStatus.Open)
        assertEquals(detail.entries.flatMap(_.playerId), List(made.owner))
        assertEquals(detail.summary.entrants, 1)
    }

    test("a creator's entry that is refused makes no tournament") {
        val (refused, listed) = run(for {
            f <- fixture(0)
            refused <- tournaments
                .create(
                  gameId = f.game.gameId,
                  request = request(
                    f = f,
                    settings = TournamentSettings(
                      roundDuration = Some(Duration.ofDays(1)),
                      minRating = Some(EloRating.initial + 100)
                    )
                  ).copy(entry = Some(TournamentEntry())),
                  callerExternalId = f.owner.externalId
                )
                .attempt
            listed <- tournaments.listForGame(f.game.gameId, f.owner.externalId)
        } yield (refused, listed))
        assert(refused.left.exists(e => e.isInstanceOf[ValidationError] && e.getMessage.contains("rating")), refused)
        assert(listed.isEmpty, listed)
    }

    test("in a character game a tournament's creator enters it with a character of theirs as it is made") {
        val (character, detail) = run(for {
            f <- fixture(0, GameType.Character)
            character <- characterOf(f, f.owner)
            made <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f).copy(entry = Some(TournamentEntry(Some(character.characterId)))),
              callerExternalId = f.owner.externalId
            )
            detail <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = made.tournamentId,
              callerExternalId = f.owner.externalId
            )
        } yield (character, detail))
        assertEquals(detail.entries.flatMap(_.characterId), List(character.characterId))
    }

    test("an invitational tournament takes only those invited, and its owner") {
        val (uninvited, invited, owner) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f, settings = TournamentSettings(roundDuration = Some(Duration.ofDays(1)))),
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

    test("a private invitational's page is not found by a player with no part in it, and is seen by one invited") {
        val (stranger, invited, owner) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(
                f = f,
                settings = TournamentSettings(isPublic = false, roundDuration = Some(Duration.ofDays(1)))
              ),
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.invite(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              playerId = f.players(1).playerId,
              callerExternalId = f.owner.externalId
            )
            look = (p: Player) =>
                tournaments
                    .detail(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = p.externalId)
                    .attempt
            stranger <- look(f.players.head)
            invited <- look(f.players(1))
            owner <- look(f.owner)
        } yield (stranger, invited, owner))
        assert(stranger.left.exists(_.isInstanceOf[NotFoundError]), stranger)
        assert(invited.isRight && owner.isRight)
    }

    test("a stranger sees the page of a public invitational, and of a private one open to all while it takes entries") {
        val (publicInvitational, privateOpen) = run(for {
            f <- fixture(1)
            create = (settings: TournamentSettings) =>
                tournaments.create(
                  gameId = f.game.gameId,
                  request = request(f = f, settings = settings),
                  callerExternalId = f.owner.externalId
                )
            look = (t: Tournament) =>
                tournaments
                    .detail(
                      gameId = f.game.gameId,
                      tournamentId = t.tournamentId,
                      callerExternalId = f.players.head.externalId
                    )
                    .attempt
            invitational <- create(TournamentSettings(roundDuration = Some(Duration.ofDays(1))))
            open <- create(
              TournamentSettings(isInvitational = false, isPublic = false, roundDuration = Some(Duration.ofDays(1)))
            )
            publicInvitational <- look(invitational)
            privateOpen <- look(open)
        } yield (publicInvitational, privateOpen))
        assert(publicInvitational.isRight, publicInvitational)
        assert(privateOpen.isRight, privateOpen)
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
        def attempt(
            game: Option[Game],
            playersPerMatch: Option[Int],
            poolSize: Option[Int] = None,
            standard: String = RoundRobin.id
        ) =
            run(fixture(0, roleless = game).flatMap { f =>
                tournaments
                    .create(
                      gameId = f.game.gameId,
                      request = request(
                        f = f,
                        standard = standard,
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
        // A round robin's pool is everyone entered, so it has no size until it starts.
        assertEquals(fine.defaultPoolSize, None)
        // Elsewhere its R is the players per match, which is the pool size a format with no suggestion has.
        val eliminating =
            attempt(game = Some(open), playersPerMatch = Some(3), standard = "single-elimination").toOption.get
        assertEquals(eliminating.defaultPoolSize, Some(3))
    }

    test("a game without roles, everyone in the pool, its pool set to the entries before the start, is one match") {
        val (pools, round) = run(
          for {
              f <- fixture(5, roleless = Some(Generators.genRolelessGame(least = 2, most = None)))
              t <- tournaments.create(
                gameId = f.game.gameId,
                request = request(f = f, standard = RoundRobin.id).copy(defaultPoolSize = Some(2)),
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
              // What the form's "one pool of everyone entered" sends.
              _ <- tournaments.update(
                gameId = f.game.gameId,
                tournamentId = t.tournamentId,
                request = request(f = f, standard = RoundRobin.id).copy(defaultPoolSize = Some(f.players.size)),
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
            // Stopped before its owner begins a round, so it finishes at once.
            _ <- services.rounds.stop(
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

    test("a competition that is over leaves a player's and a game's lists, and its owner's ended list holds it") {
        // A challenge's status as a finished or cancelled one's: the lists read nothing else of how it ended.
        def ended(t: Tournament, status: TournamentStatus): IO[Unit] =
            TestSession.resource.use { session =>
                val repo = new TournamentRepo(session)
                repo.read(t.gameId, t.tournamentId).flatMap(found => repo.update(found.get.copy(status = status)))
            }
        case class Listed(
            mine: Set[TournamentId],
            standings: Set[TournamentId],
            game: Set[TournamentId],
            ended: Set[TournamentId]
        )
        def listed(f: Fixture, p: Player): IO[Listed] =
            (
              tournaments.mine(p.externalId).map(_.map(_.tournament.tournamentId)),
              tournaments.standings(p.externalId).map(_.map(_.tournamentId)),
              tournaments.listForGame(f.game.gameId, p.externalId).map(_.map(_.tournament.tournamentId)),
              tournaments.endedOwned(p.externalId, CompletedFrame.Day).map(_.map(_.tournament.tournamentId))
            ).mapN((a, b, c, d) => Listed(mine = a.toSet, standings = b.toSet, game = c.toSet, ended = d.toSet))
        val (ids, open, over) = run(for {
            f <- fixture(2)
            entrant = f.players.head
            // Each reaches the player by a different road: entered, invited to, and the player's own.
            entered <- Challenges.create(tournaments = tournaments, game = f.game, owner = f.owner, poolSize = Some(3))
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = entered.tournamentId,
              characterId = None,
              callerExternalId = entrant.externalId
            )
            invited <- Challenges.create(
              tournaments = tournaments,
              game = f.game,
              owner = f.owner,
              invitations = List(entrant.playerId)
            )
            owned <- Challenges.create(tournaments = tournaments, game = f.game, owner = entrant)
            // A draft, which only its owner sees listed in the game.
            tournament <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = entrant.externalId
            )
            open <- listed(f, entrant)
            _ <- ended(entered, TournamentStatus.Finished)
            _ <- ended(invited, TournamentStatus.Cancelled)
            _ <- ended(owned, TournamentStatus.Finished)
            _ <- ended(tournament, TournamentStatus.Finished)
            over <- listed(f, entrant)
        } yield (List(entered, invited, owned, tournament).map(_.tournamentId), open, over))
        val List(entered, invited, owned, tournament) = ids: @unchecked
        val challenges = Set(entered, invited, owned)
        assertEquals(open.mine, challenges + tournament)
        assertEquals(open.standings, Set(entered, owned))
        // Only those it could still enter: not the challenge it entered, nor its own, which it entered as it made it --
        // each takes one entry from each player.
        assertEquals(open.game, Set(invited, tournament))
        assertEquals(open.ended, Set.empty[TournamentId])
        // Over, finished or cancelled: gone from every list but the owner's ended one, which holds only their own.
        assertEquals(
          over,
          Listed(mine = Set.empty, standings = Set.empty, game = Set.empty, ended = Set(owned, tournament))
        )
    }

    test("a game lists its caller's own competitions not yet started first, then the open ones they could enter") {
        val (listed, ids) = run(for {
            f <- fixture(2)
            viewer = f.players.head
            _ <- Challenges.create(
              tournaments = tournaments,
              game = f.game,
              owner = f.owner,
              invitations = List(f.players(1).playerId)
            )
            anyone <- Challenges.create(tournaments = tournaments, game = f.game, owner = f.owner)
            others <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = f.owner.externalId
            )
            own <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
              callerExternalId = viewer.externalId
            )
            listed <- tournaments.listForGame(f.game.gameId, viewer.externalId)
        } yield (listed.map(_.tournament.tournamentId), List(own, others, anyone).map(_.tournamentId)))
        // Not the invitational one the viewer is not invited to; somebody else's is listed as soon as it is made.
        assertEquals(listed, ids)
    }

    test("a game no longer lists to a player a competition whose entries they have all used, nor while it is full") {
        val (entrant, other, comeBack) = run(for {
            f <- fixture(2)
            t <- Challenges.create(tournaments = tournaments, game = f.game, owner = f.owner, poolSize = Some(3))
            p = f.players.head
            entry <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              characterId = None,
              callerExternalId = p.externalId
            )
            listedTo = (viewer: Player) =>
                tournaments.listForGame(f.game.gameId, viewer.externalId).map(_.map(_.tournament.tournamentId))
            entrant <- listedTo(p)
            other <- listedTo(f.players(1))
            _ <- tournaments.withdraw(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              entrantId = entry.entrantId,
              callerExternalId = p.externalId
            )
            comeBack <- listedTo(p)
        } yield (entrant.contains(t.tournamentId), other.contains(t.tournamentId), comeBack.contains(t.tournamentId)))
        assert(!entrant, "listed to a player with no entry left in it")
        assert(other, "not listed to a player who could still enter")
        assert(comeBack, "not listed again once the entry was withdrawn")
    }

    test("a game lists to a player only the competitions they could enter: none full, none outside their rating") {
        val (listed, ids) = run(for {
            f <- fixture(2)
            p = f.players.head
            made = (settings: TournamentSettings) =>
                tournaments.create(
                  gameId = f.game.gameId,
                  request = request(f = f, settings = settings),
                  callerExternalId = f.owner.externalId
                )
            full <- made(
              TournamentSettings(
                isInvitational = false,
                roundDuration = Some(Duration.ofDays(2)),
                maxEntrants = Some(1)
              )
            )
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = full.tournamentId,
              characterId = None,
              callerExternalId = f.players(1).externalId
            )
            outOfRange <- made(
              TournamentSettings(
                isInvitational = false,
                roundDuration = Some(Duration.ofDays(2)),
                minRating = Some(EloRating.initial + 100)
              )
            )
            inRange <- made(
              TournamentSettings(
                isInvitational = false,
                roundDuration = Some(Duration.ofDays(2)),
                maxEntrants = Some(2),
                minRating = Some(EloRating.initial - 100)
              )
            )
            listed <- tournaments.listForGame(f.game.gameId, p.externalId)
        } yield (listed.map(_.tournament.tournamentId), (full, outOfRange, inRange)))
        val (full, outOfRange, inRange) = ids
        assert(!listed.contains(full.tournamentId), "listed while full")
        assert(!listed.contains(outOfRange.tournamentId), "listed to a player rated below its least")
        assert(listed.contains(inRange.tournamentId), "not listed to a player who could enter it")
    }

    test("in a character game a competition is listed to a player only while a character of theirs could enter it") {
        case class Listed(open: Boolean, invitational: Boolean)
        val (without, free, allIn, invited) = run(for {
            f <- fixture(2, GameType.Character)
            p = f.players.head
            open <- tournaments.create(
              gameId = f.game.gameId,
              request = request(
                f = f,
                settings = TournamentSettings(
                  isInvitational = false,
                  roundDuration = Some(Duration.ofDays(2)),
                  entriesPerPlayer = 2
                )
              ),
              callerExternalId = f.owner.externalId
            )
            closed <- invitational(f)
            listedTo = () =>
                tournaments
                    .listForGame(f.game.gameId, p.externalId)
                    .map(_.map(_.tournament.tournamentId))
                    .map(ids =>
                        Listed(open = ids.contains(open.tournamentId), invitational = ids.contains(closed.tournamentId))
                    )
            without <- listedTo()
            character <- characterOf(f, p)
            free <- listedTo()
            _ <- tournaments.enter(
              gameId = f.game.gameId,
              tournamentId = open.tournamentId,
              characterId = Some(character.characterId),
              callerExternalId = p.externalId
            )
            // An entry left to the player, but no character left to make it with.
            allIn <- listedTo()
            _ <- tournaments.inviteCharacter(
              gameId = f.game.gameId,
              tournamentId = closed.tournamentId,
              characterId = character.characterId,
              callerExternalId = f.owner.externalId
            )
            invited <- listedTo()
        } yield (without, free, allIn, invited))
        assertEquals(without, Listed(open = false, invitational = false))
        assertEquals(free, Listed(open = true, invitational = false))
        assertEquals(allIn, Listed(open = false, invitational = false))
        assertEquals(invited, Listed(open = false, invitational = true))
    }

    /* The round a tournament that starts itself has begun, waited for: the ending queue begins it on a fiber of its
     * own, which the call that started the tournament does not wait for. Empty if it never began. */
    private def begunRound(f: Fixture, t: Tournament): IO[List[TournamentMatchView]] = {
        def step(remaining: Int): IO[List[TournamentMatchView]] =
            tournaments
                .detail(gameId = f.game.gameId, tournamentId = t.tournamentId, callerExternalId = f.owner.externalId)
                .flatMap(d =>
                    if (d.round.nonEmpty || remaining <= 0) IO.pure(d.round)
                    else IO.sleep(50.millis) *> step(remaining - 1)
                )
        step(200)
    }

    test("a round robin's pool is everyone entered: one that starts itself starts and begins its round as it fills") {
        // As the competition form sends it: the format leaves out the pool size.
        def roundRobin(f: Fixture, maxEntrants: Option[Int]) =
            request(
              f = f,
              standard = RoundRobin.id,
              settings = TournamentSettings(
                isInvitational = false,
                roundDuration = Some(Duration.ofDays(2)),
                isAutoStarted = maxEntrants.isDefined,
                maxEntrants = maxEntrants
              )
            ).copy(entry = Some(TournamentEntry()))
        val (automatic, round, manual, started) = run(for {
            f <- fixture(2)
            made <- List(Some(2), None).traverse(maxEntrants =>
                tournaments.create(
                  gameId = f.game.gameId,
                  request = roundRobin(f, maxEntrants = maxEntrants),
                  callerExternalId = f.owner.externalId
                )
            )
            List(automatic, manual) = made: @unchecked
            enter = (t: Tournament, p: Player) =>
                tournaments.enter(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  characterId = None,
                  callerExternalId = p.externalId
                )
            _ <- enter(automatic, f.players.head)
            // With no most, it takes everybody who comes until its owner starts it.
            _ <- f.players.traverse_(enter(manual, _))
            round <- begunRound(f, automatic)
            stood <- made.traverse(t =>
                tournaments
                    .detail(
                      gameId = f.game.gameId,
                      tournamentId = t.tournamentId,
                      callerExternalId = f.owner.externalId
                    )
                    .map(_.summary.tournament)
            )
            started <- tournaments.start(
              gameId = f.game.gameId,
              tournamentId = manual.tournamentId,
              callerExternalId = f.owner.externalId
            )
        } yield (stood.head, round, stood(1), started))
        assertEquals(automatic.status, TournamentStatus.Running)
        assertEquals(automatic.defaultPoolSize, Some(2))
        assertEquals(round.size, 1, "its round was not begun")
        assertEquals(manual.status, TournamentStatus.Open)
        assertEquals(manual.defaultPoolSize, None)
        // Its owner and both players.
        assertEquals(started.defaultPoolSize, Some(3))
    }

    test("a tournament that starts itself may be started before it is full, or without a most, and begins its round") {
        def startedEarly(maxEntrants: Option[Int]) =
            for {
                f <- fixture(4)
                t <- tournaments.create(
                  gameId = f.game.gameId,
                  request = request(
                    f = f,
                    settings = TournamentSettings(
                      isInvitational = false,
                      roundDuration = Some(Duration.ofDays(2)),
                      isAutoStarted = true,
                      maxEntrants = maxEntrants
                    )
                  ),
                  callerExternalId = f.owner.externalId
                )
                _ <- f.players
                    .take(2)
                    .traverse_(p =>
                        tournaments.enter(
                          gameId = f.game.gameId,
                          tournamentId = t.tournamentId,
                          characterId = None,
                          callerExternalId = p.externalId
                        )
                    )
                running <- tournaments.start(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  callerExternalId = f.owner.externalId
                )
                round <- begunRound(f, t)
            } yield (running.status, round.size)
        val (capped, uncapped) = run(startedEarly(Some(4)).product(startedEarly(None)))
        assertEquals(capped, (TournamentStatus.Running, 1))
        assertEquals(uncapped, (TournamentStatus.Running, 1))
    }

    test("a tournament started by hand begins its round once its owner turns its rounds to run themselves") {
        val (before, round) = run(for {
            f <- fixture(2)
            settings = TournamentSettings(isInvitational = false, roundDuration = Some(Duration.ofDays(2)))
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f, settings = settings),
              callerExternalId = f.owner.externalId
            )
            _ <- f.players.traverse_(p =>
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
            before <- tournaments.detail(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              callerExternalId = f.owner.externalId
            )
            _ <- tournaments.update(
              gameId = f.game.gameId,
              tournamentId = t.tournamentId,
              request = request(f = f, settings = settings.copy(isAutoStarted = true)),
              callerExternalId = f.owner.externalId
            )
            round <- begunRound(f, t)
        } yield (before.round.size, round.size))
        assertEquals(before, 0, "a round was begun before the owner asked for one")
        assertEquals(round, 1, "its round was not begun")
    }

    test("a tournament its owner leaves full and starting itself starts, and begins its round") {
        // Made as `from`, entered by two, and edited to `to`.
        def leftFull(from: TournamentSettings, to: TournamentSettings) =
            for {
                f <- fixture(2)
                t <- tournaments.create(
                  gameId = f.game.gameId,
                  request = request(f = f, settings = from),
                  callerExternalId = f.owner.externalId
                )
                _ <- f.players.traverse_(p =>
                    tournaments.enter(
                      gameId = f.game.gameId,
                      tournamentId = t.tournamentId,
                      characterId = None,
                      callerExternalId = p.externalId
                    )
                )
                edited <- tournaments.update(
                  gameId = f.game.gameId,
                  tournamentId = t.tournamentId,
                  request = request(f = f, settings = to),
                  callerExternalId = f.owner.externalId
                )
                round <- begunRound(f, t)
            } yield (edited.status, round.size)
        val settings = TournamentSettings(
          isInvitational = false,
          roundDuration = Some(Duration.ofDays(2)),
          maxEntrants = Some(2)
        )
        val (turnedOn, lowered) = run(
          leftFull(from = settings.copy(maxEntrants = None), to = settings.copy(isAutoStarted = true)).product(
            leftFull(
              from = settings.copy(isAutoStarted = true, maxEntrants = Some(4)),
              to = settings.copy(isAutoStarted = true)
            )
          )
        )
        assertEquals(turnedOn, (TournamentStatus.Running, 1))
        assertEquals(lowered, (TournamentStatus.Running, 1))
    }

    test("a player's standings say where they stand in each tournament they entered, by their seed while it runs") {
        val (before, after, entrants) = run(for {
            f <- fixture(2)
            t <- tournaments.create(
              gameId = f.game.gameId,
              request = request(f = f),
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
