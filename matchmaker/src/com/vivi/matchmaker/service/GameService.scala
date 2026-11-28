package com.vivi.matchmaker.service

import com.vivi.matchmaker.ErrorText
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import skunk.SqlState
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.auth.ApiKeys
import com.vivi.matchmaker.util.Site
import com.vivi.matchmaker.persistence.{GameRepo, PlayerRepo, Repos, TextCodec, TournamentRepo}

/** Creates or updates a Game, together with all of its roles, parameters, and parameter values. Only an admin may do
  * this.
  */
class GameService[T](sessionPool: SessionPool)(using codec: TextCodec[T]) {

    /** Creates `game` if it has no id yet (gameId == GameId.unassigned), otherwise updates the existing game with that
      * id. Returns the persisted state, including any database-generated role/parameter ids.
      *
      * @param externalUserId
      *   identifies the caller; must belong to an existing admin player
      * @param apiKey
      *   the key the game's engine and matchmaker authenticate each other with, replacing the one it has. `None` — or
      *   blank, which is what an untouched form field sends — leaves the key as it is, since the key is never sent back
      *   and an edit that does not mean to change it has no other way to say so. Written in the same transaction as the
      *   game, so a new game is never saved without the key it was given.
      */
    def createOrUpdate(externalUserId: String, game: Game, apiKey: Option[String] = None): IO[Game] =
        sessionPool.use { session =>
            // A game is its rows plus its roles, parameters and parameter values; the repo writes
            // them as separate statements, so the transaction that makes them one change lives here.
            session.transaction.use { _ =>
                val repos = Repos(session)
                authorize(repos.players, externalUserId) *> save(repos = repos, game = game, apiKey = apiKey)
            }
        }

    /** Creates `game` unless a game with its engine identity (`externalId`) is already registered, which is left as it
      * is: what `init-games.sh` registers a bundled engine with. The look and the insert are one transaction under the
      * identity's lock, so two runs at once, or a run and an admin adding the same game, cannot both find none and both
      * insert.
      *
      * An admin may still give two games one engine identity on purpose (see [[ApiKeys]]); only this path refuses to.
      */
    def registerUnlessPresent(externalUserId: String, game: Game, apiKey: Option[String]): IO[GameRegistration] =
        sessionPool.use { session =>
            val repos = Repos(session)
            session.transaction.use { _ =>
                val identity = normalize(game).externalId
                for {
                    _ <- authorize(repos.players, externalUserId)
                    _ <- repos.games[T].lockIdentity(identity)
                    present <- repos.games[T].readIdsByExternalId(identity)
                    registered <- present.headOption match {
                        case Some(id) =>
                            repos.games[T].read(id).flatMap {
                                case Some(found) => IO.pure(GameRegistration.AlreadyRegistered(found))
                                case None        => IO.raiseError(NotFoundError(ErrorText.game.notFound))
                            }
                        case None =>
                            save(repos = repos, game = game, apiKey = apiKey).map(GameRegistration.Registered(_))
                    }
                } yield registered
            }
        }

    /** [[createOrUpdate]]'s work, inside the caller's transaction, once the caller has been authorized. */
    private def save(repos: Repos, game: Game, apiKey: Option[String]): IO[Game] = {
        // Trimmed before it is checked, so that "  attacker  " and "attacker" are refused as the
        // duplicate they are rather than stored as two roles that read identically.
        val trimmed = normalize(game)
        for {
            _ <- validate(trimmed)
            newKey = apiKey.map(_.trim).filter(_.nonEmpty)
            // The same floor the engine module puts on the key it is given: this is the only thing
            // protecting the callback routes, and a short one is a typo or a guessable token.
            _ <- newKey.traverse_(key =>
                IO.raiseWhen(key.length < GameService.MinApiKeyLength)(
                  ValidationError(
                    ErrorText.game
                        .shortApiKey(least = GameService.MinApiKeyLength.toString, length = key.length.toString)
                  )
                )
            )
            result <-
                if (trimmed.gameId == GameId.unassigned)
                    // Under its identity's lock, so that `registerUnlessPresent` looking for a game with it sees this
                    // one or waits for it.
                    repos.games[T].lockIdentity(trimmed.externalId) *> repos.games[T].create(trimmed)
                else
                    // Locked before it is read: `checkRoles` below decides what may be written
                    // from what is there now, and the update rewrites the roles and parameters it
                    // was checked against. Two admins saving the same game at once would
                    // otherwise both diff against the state before either of them wrote.
                    repos
                        .games[T]
                        .lockForUpdate(trimmed.gameId) *> repos.games[T].read(trimmed.gameId).flatMap {
                        case None           => IO.raiseError(NotFoundError(ErrorText.game.notFound))
                        case Some(existing) =>
                            // An edit that moves the game to another engine identity takes that identity's lock, as a
                            // new game does: otherwise `registerUnlessPresent` could find no game with it, this edit
                            // commit, and the registration insert a second game for the same engine. After the row
                            // lock, never before it; nothing takes an identity lock and then a game's row lock.
                            IO.whenA(existing.externalId != trimmed.externalId)(
                              repos.games[T].lockIdentity(trimmed.externalId)
                            ) *> checkRoles(
                              existing = existing,
                              incoming = trimmed,
                              tournaments = repos.tournaments
                            ) *> repos
                                .games[T]
                                .update(trimmed) *> repos.games[T].read(trimmed.gameId).flatMap {
                                case Some(updated) => IO.pure(updated)
                                case None =>
                                    IO.raiseError(NotFoundError(ErrorText.game.notFound))
                            }
                    }
            // A new game offers every tournament model it can play but the challenge, which its admins may then change.
            _ <- IO.whenA(trimmed.gameId == GameId.unassigned)(
              TournamentModelService.offerPlayable(repos, result).void
            )
            // Unique across games. A clash rolls the whole save back, game and all.
            _ <- newKey.traverse_(key =>
                repos.gameApiKeys.set(result.gameId, key).recoverWith { case SqlState.UniqueViolation(_) =>
                    IO.raiseError(ConflictError(ErrorText.game.apiKeyTaken))
                }
            )
        } yield result.copy(hasApiKey = result.hasApiKey || newKey.isDefined)
    }

    /** Every stored engine key, filed under the engine identity of its game: what a game engine's callback is
      * authenticated against. Asked by the authenticator before there is any caller to authorize, which is why it takes
      * none.
      */
    def engineKeys: IO[ApiKeys] = sessionPool.use(session => Repos(session).gameApiKeys.byIdentity)

    /** Lists games for any registered caller. Unlike `createOrUpdate` this needs no admin rights — the game catalogue
      * is what every player browses — but the caller must still be a known player.
      *
      * @param games
      *   the games a game site shows, or every game
      * @param activeOnly
      *   hide games flagged inactive
      */
    def list(callerExternalId: String, games: GameScope, activeOnly: Boolean = false): IO[List[Game]] =
        sessionPool.use { session =>
            val repos = Repos(session)
            for {
                _ <- repos.players.readByExternalId(callerExternalId).flatMap {
                    case Some(player) => IO.pure(player)
                    case None         => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
                }
                listed <- repos.games[T].list(activeOnly = activeOnly, games = games)
            } yield listed
        }

    /* Each site's scope as last looked up, and when. */
    private val siteScopes = new ConcurrentHashMap[Site, GameService.SiteScope]()

    /** The games a game site shows, by its games' names: a name that matches no game is skipped, and a site none of
      * whose games are registered shows nothing. See design/game-sites-plan.md.
      *
      * Asked on every list a site's page makes, so a process keeps each site's answer for
      * [[GameService.SiteScopeLifetime]] rather than asking the database each time. The names resolve to ids that
      * change only when a game of that name is registered or renamed, which is rare; the cost is that such a game
      * reaches a site's lists up to that long after it is saved.
      */
    def scopeFor(site: Site): IO[GameScope] =
        IO.monotonic.flatMap { now =>
            Option(siteScopes.get(site)).filter(kept => now - kept.lookedUpAt < GameService.SiteScopeLifetime) match {
                case Some(kept) => IO.pure(kept.scope)
                case None =>
                    sessionPool
                        .use(session => Repos(session).games[T].idsNamed(site.games))
                        .map(ids => GameScope.Only(ids.toSet))
                        .flatTap(scope =>
                            IO(siteScopes.put(site, GameService.SiteScope(scope = scope, lookedUpAt = now))).void
                        )
            }
        }

    /** What an update may do to a game's roles: rename them, make them optional or not, and add new ones. Not remove
      * one — except by doing without roles altogether.
      *
      * `participant` carries a `game_role_id`, so a role that is deleted is a played match with no record of who played
      * what. A role that has outlived its usefulness is made optional instead, which stops a challenge needing it while
      * leaving every match that used it intact.
      *
      * A role is recognised by its id: one carrying [[GameRoleId.unassigned]] is being added, and an existing id
      * missing from the update is a deletion asked for by omission.
      *
      * A game whose roles are all optional may do without them -- with a range of players, or with neither, when it
      * seats two or more a match: no match needs any of them, so none is lost. Its roles are retired rather than
      * deleted (`GameRepo.update`). Not while a competition of it is open or under way, though, since that was made
      * for, and may be seating, the roles. The game is held FOR UPDATE by then, and a tournament's creation holds it
      * FOR SHARE, so one being made meanwhile is committed, and counted, before its tournaments are read.
      */
    private def checkRoles(existing: Game, incoming: Game, tournaments: TournamentRepo): IO[Unit] =
        if (incoming.roles.isEmpty && existing.roles.nonEmpty) {
            val needed = existing.roles.filterNot(_.roleType == RoleType.Optional)
            for {
                _ <- IO.raiseUnless(needed.isEmpty)(
                  ValidationError(
                    ErrorText.game
                        .rolesNotOptional(game = existing.name, roles = needed.map(r => s"'${r.name}'").mkString(", "))
                  )
                )
                inPlay <- tournaments.hasUnfinished(existing.gameId)
                _ <- IO.raiseWhen(inPlay)(ValidationError(ErrorText.game.rolesInPlay(existing.name)))
            } yield ()
        } else checkKeptRoles(existing = existing, incoming = incoming)

    private def checkKeptRoles(existing: Game, incoming: Game): IO[Unit] = {
        val existingIds = existing.roles.map(_.gameRoleId).toSet
        val incomingIds = incoming.roles.map(_.gameRoleId).filterNot(_ == GameRoleId.unassigned).toSet
        val unknown = incomingIds -- existingIds
        val removed = existing.roles.filterNot(role => incomingIds.contains(role.gameRoleId))
        for {
            _ <- IO.raiseUnless(unknown.isEmpty)(
              ValidationError(ErrorText.game.unknownRoles(existing.name))
            )
            _ <- IO.raiseUnless(removed.isEmpty)(
              ValidationError(
                ErrorText.game
                    .rolesPlayed(roles = removed.map(r => s"'${r.name}'").mkString(", "), game = existing.name)
              )
            )
        } yield ()
    }

    /** Strips the whitespace around every role name, parameter name, parameter value and default.
      *
      * All four are things an admin types and something else matches on, so the spaces around them are typing and not
      * content: " X " and "X" name the same role, and storing both would show a player two roles that look the same and
      * let a game define two parameters it cannot tell apart. For a value it is worse than cosmetic —
      * `game_parameter.default_value` is a foreign key to `game_parameter_value`, so a default of "3x3" against a value
      * stored as "3x3 " is not a default at all. Done here rather than in the form, because the form is not the only
      * thing that can post a game.
      *
      * The game's, a role's and a parameter's display names are trimmed too, and a blank one is the name: the form
      * sends what was typed, and an empty field means it is shown by its name. A parameter value's display name is
      * trimmed the same way, and a blank one is the value.
      */
    private def normalize(game: Game): Game =
        game.copy(
          // A blank url is no url: the form sends what was typed, and an empty field means there is none.
          characterUrl = game.characterUrl.map(_.trim).filter(_.nonEmpty),
          externalId = game.externalId.trim,
          name = game.name.trim,
          displayName = Option(game.displayName.trim).filter(_.nonEmpty).getOrElse(game.name.trim),
          roles = game.roles.map(role =>
              role.copy(
                name = role.name.trim,
                displayName = Option(role.displayName.trim).filter(_.nonEmpty).getOrElse(role.name.trim)
              )
          ),
          parameters = game.parameters.map { p =>
              // The same cast GameRepo makes: `parameters` is existential in its value type, and
              // TextCodec[String] is the only instance there is.
              val parameter = p.asInstanceOf[GameParameter[T]]
              parameter.copy(
                name = parameter.name.trim,
                displayName = Option(parameter.displayName.trim).filter(_.nonEmpty).getOrElse(parameter.name.trim),
                defaultValue = parameter.defaultValue.map(trimValue),
                values = parameter.values.map { value =>
                    val trimmed = trimValue(value.value)
                    value.copy(
                      value = trimmed,
                      displayName = Option(value.displayName.trim).filter(_.nonEmpty).getOrElse(codec.encode(trimmed))
                    )
                }
              )
          }
        )

    /* A parameter value is generic, so it is trimmed through the codec that decides what its text
     * form is — which for the only instance there is, TextCodec[String], is the string itself. */
    private def trimValue(value: T): T = codec.decode(codec.encode(value).trim)

    /** What the schema would refuse anyway, refused here first so that it is a 400 explaining itself rather than a
      * constraint violation surfacing as a 500.
      *
      * The rules that are not the schema's: a game has roles or a range of players, not both, which would be asking its
      * seats to be at once named and all alike. A game with neither seats its players without roles, two or more a
      * match (`Game.fewestPlayers`).
      */
    private def validate(game: Game): IO[Unit] =
        for {
            _ <- IO.raiseWhen(game.roles.nonEmpty && game.isRoleless)(
              ValidationError(ErrorText.game.rolesAndPlayers(game.name))
            )
            _ <- IO.raiseWhen(game.maxPlayers.isDefined && !game.isRoleless)(
              ValidationError(ErrorText.game.mostButNoFewest(game.name))
            )
            _ <- IO.raiseWhen(game.minPlayers.exists(_ < 1))(
              ValidationError(ErrorText.game.noPlayers(game.name))
            )
            _ <- IO.raiseWhen(game.minPlayers.zip(game.maxPlayers).exists((least, most) => most < least))(
              ValidationError(ErrorText.game.mostBelowFewest(game.name))
            )
            // Nobody goes first in a game without roles: everyone moves at once.
            _ <- IO.raiseWhen(game.isRoleless && game.moveType != MoveType.Simultaneous)(
              ValidationError(ErrorText.game.rolelessTakesTurns(game.name))
            )
            // Its display name falls back to it, and a blank one is a constraint violation rather
            // than an explanation.
            _ <- IO.raiseWhen(game.name.isEmpty)(ValidationError(ErrorText.game.blankName))
            // Opened in a tab from matchmaker's own page: a `javascript:` url would run there (`WebUrl`).
            _ <- IO.raiseWhen(game.characterUrl.exists(url => !WebUrl.isWeb(url)))(
              ValidationError(ErrorText.game.characterUrlNotWeb(game.name))
            )
            // The engine's identity: what its callbacks are matched against. A game without one can be
            // started but never heard from again.
            _ <- IO.raiseWhen(game.externalId.isEmpty)(
              ValidationError(ErrorText.game.noEngineIdentity(game.name))
            )
            _ <- IO.raiseWhen(game.roles.exists(_.name.trim.isEmpty))(
              ValidationError(ErrorText.game.unnamedRole(game.name))
            )
            _ <- IO.raiseWhen(game.roles.map(_.name).distinct.sizeIs != game.roles.size)(
              ValidationError(ErrorText.game.sameRoleName(game.name))
            )
            // A player picking a seat by its display name could not tell two seats shown alike apart.
            _ <- IO.raiseWhen(game.roles.map(_.displayName).distinct.sizeIs != game.roles.size)(
              ValidationError(ErrorText.game.sameRoleDisplayName(game.name))
            )
            _ <- IO.raiseWhen(game.parameters.exists(_.name.trim.isEmpty))(
              ValidationError(ErrorText.game.unnamedParameter(game.name))
            )
            _ <- IO.raiseWhen(game.parameters.map(_.name).distinct.sizeIs != game.parameters.size)(
              ValidationError(ErrorText.game.sameParameterName(game.name))
            )
            // Distinct names can still be shown alike — `rounds` captioned "Length" beside a `length`
            // shown as it is — and a player choosing between two pickers with one caption cannot
            // tell which is which.
            _ <- IO.raiseWhen(game.parameters.map(_.displayName).distinct.sizeIs != game.parameters.size)(
              ValidationError(ErrorText.game.sameParameterDisplayName(game.name))
            )
            _ <- game.parameters.toList.traverse_ { parameter =>
                val values = parameter.values.map(_.value)
                for {
                    _ <- IO.raiseWhen(values.distinct.sizeIs != values.size)(
                      ValidationError(ErrorText.game.repeatedValue(parameter.name))
                    )
                    // A player picking from the dropdown could not tell two values shown alike apart.
                    _ <- IO.raiseWhen(parameter.values.map(_.displayName).distinct.sizeIs != values.size)(
                      ValidationError(ErrorText.game.sameValueDisplayName(parameter.name))
                    )
                    // game_parameter.default_value is a foreign key to game_parameter_value, so a default
                    // that is not one of the parameter's values cannot be stored at all.
                    _ <- parameter.defaultValue.traverse_ { default =>
                        IO.raiseUnless(values.contains(default))(
                          ValidationError(
                            ErrorText.game.defaultNotAValue(parameter = parameter.name, default = default.toString)
                          )
                        )
                    }
                } yield ()
            }
        } yield ()

    private def authorize(playerRepo: PlayerRepo, externalUserId: String): IO[Player] =
        playerRepo.readByExternalId(externalUserId).flatMap {
            case None => IO.raiseError(UnauthorizedError(ErrorText.caller.unregistered))
            case Some(player) if !player.isAdmin =>
                IO.raiseError(UnauthorizedError(ErrorText.caller.notSiteAdmin))
            case Some(player) => IO.pure(player)
        }
}

object GameService {

    /** How long a process keeps a game site's games before looking them up again: see [[GameService.scopeFor]]. */
    val SiteScopeLifetime: FiniteDuration = 5.minutes

    /** A site's games as [[GameService.scopeFor]] last looked them up, and when, on the monotonic clock. */
    private case class SiteScope(scope: GameScope, lookedUpAt: FiniteDuration)

    /** The shortest engine API key a game may be given — the minimum `terraform/modules/engine` enforces on the key it
      * deploys an engine with, so any key that engine could have been given passes.
      */
    val MinApiKeyLength = 24
}

/** What [[GameService.registerUnlessPresent]] did: saved the game, or found one with its engine identity already. */
enum GameRegistration {
    case Registered(game: Game)
    case AlreadyRegistered(game: Game)
}
