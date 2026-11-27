package com.vivi.matchmaker.api

import java.nio.file.{Files, Path, Paths}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import upickle.default.{ReadWriter, macroRW, read}
import com.vivi.matchmaker.archive.ArchiveStore
import com.vivi.matchmaker.model.Game
import com.vivi.matchmaker.notify.Notifier
import com.vivi.matchmaker.persistence.TextCodec.given
import com.vivi.matchmaker.service.{DbConfig, GameRegistration, Services}
import Json.given

/** Registers the bundled engines' games in a matchmaker database: what `init-games.sh` runs.
  *
  * Each game is saved through [[com.vivi.matchmaker.service.GameService.createOrUpdate]], as the admin page's "Add a
  * Game" saves one, so a game registered here is checked the same way, gets its API key in the same transaction, and is
  * offered every tournament model it can play. A fresh database has no models to offer, so when none is stored they are
  * synced first, as an admin's "sync" would; a database that has some is left to its admins, since a sync can delete a
  * model's tournaments.
  *
  * A game whose engine identity (`external_id`) is already registered is skipped rather than saved again: it is a real
  * game by then, its admins may have changed it, and a second game for the same engine would split its players. The
  * look and the save are one transaction under the identity's lock
  * ([[com.vivi.matchmaker.service.GameService.registerUnlessPresent]]), so two runs at once cannot both register it. A
  * moved url is `update-engine-urls.sh`'s to fix.
  *
  * {{{
  * DB_HOST=… DB_NAME=… DB_USER=… DB_PASSWORD=… ADMIN_EXTERNAL_ID=… \
  *   mill matchmaker.api.runMain com.vivi.matchmaker.api.RegisterGames /path/to/engines.json
  * }}}
  *
  * The one argument is a file of [[RegisterGames.Engine]]s. It is read from a file, not the command line or the
  * environment, because it carries the API keys, and the script that writes it makes it readable by its owner alone.
  */
object RegisterGames {

    /** One engine to register: its game's own facts, from `definition` (the engine's `game.json`), and the facts of
      * this deployment of it.
      */
    case class Engine(
        definition: String,
        url: String,
        externalId: String,
        characterUrl: Option[String] = None,
        apiKey: Option[String] = None
    )

    given ReadWriter[Engine] = macroRW

    def main(args: Array[String]): Unit = {
        val manifest = args match {
            case Array(path) => Paths.get(path)
            case _ =>
                Console.err.println("usage: RegisterGames <engines.json>")
                sys.exit(2)
        }
        val admin = sys.env.get("ADMIN_EXTERNAL_ID").map(_.trim).filter(_.nonEmpty).getOrElse {
            Console.err.println("ADMIN_EXTERNAL_ID is not set; expected an admin player's external id")
            sys.exit(2)
        }
        val engines = read[List[Engine]](Files.readString(manifest))

        Services
            .resource[String](
              config = DbConfig.fromEnvironment(),
              poolSize = 1,
              poolMax = 1,
              notifier = Notifier.disabled,
              archiveStore = ArchiveStore.Unavailable
            )
            .use(services => register(services = services, admin = admin, engines = engines))
            .unsafeRunSync()
    }

    private def register(services: Services[String], admin: String, engines: List[Engine]): IO[Unit] =
        for {
            models <- services.tournamentModels.listAll(admin)
            _ <-
                if (models.nonEmpty) IO.println(s"    tournament models: ${models.size} stored; left as they are")
                else
                    services.tournamentModels
                        .sync(admin)
                        .flatMap(done => IO.println(s"    tournament models: none stored; synced ${done.added.size}"))
            _ <- engines.traverse_(engine =>
                services.games
                    .registerUnlessPresent(externalUserId = admin, game = gameOf(engine), apiKey = engine.apiKey)
                    .flatMap {
                        case GameRegistration.AlreadyRegistered(found) =>
                            IO.println(
                              s"    ${engine.externalId}: already registered as '${found.displayName}'; skipped"
                            )
                        case GameRegistration.Registered(saved) =>
                            IO.println(
                              s"    ${engine.externalId}: registered '${saved.displayName}' at ${saved.url}" +
                                  (if (engine.apiKey.isDefined) ", with its API key" else ", with no API key")
                            )
                    }
            )
        } yield ()

    /** The engine's `game.json`, with this deployment's url, identity and character page put in. */
    private def gameOf(engine: Engine): Game = {
        val json = ujson.read(Files.readString(Path.of(engine.definition)))
        json("url") = engine.url
        json("externalId") = engine.externalId
        engine.characterUrl.foreach(url => json("characterUrl") = url)
        read[Game](json)
    }
}
