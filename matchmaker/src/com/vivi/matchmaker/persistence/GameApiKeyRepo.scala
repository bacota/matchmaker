package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.auth.ApiKeys
import com.vivi.matchmaker.model._
import java.net.URI
import scala.util.Try

/** The key each game's engine and matchmaker authenticate each other with (V34).
  *
  * Kept apart from [[GameRepo]] because nothing that reads a game should be able to carry its key along: a key is
  * written when an admin saves a game, and read only by the two things that authenticate with it — and the two read it
  * filed under different names, because they know different things about the engine (see [[ApiKeys]]).
  */
class GameApiKeyRepo(session: Session[IO]) {

    private val gameId = SkunkIdCodecs.gameId

    private val upsert: Command[(GameId, String)] =
        sql"""INSERT INTO game_api_key (game_id, api_key) VALUES ($gameId, $text)
          ON CONFLICT (game_id) DO UPDATE SET api_key = EXCLUDED.api_key, update_date = now()""".command

    private val selectByIdentity: Query[Void, (String, String)] =
        sql"""SELECT g.external_id, k.api_key
          FROM game_api_key k
          JOIN game g ON g.game_id = k.game_id
          ORDER BY g.game_id""".query(text *: text)

    private val selectByUrl: Query[Void, (String, String)] =
        sql"""SELECT g.url, k.api_key
          FROM game_api_key k
          JOIN game g ON g.game_id = k.game_id
          ORDER BY g.game_id""".query(text *: text)

    /** Sets the game's key, replacing whatever it had. */
    def set(id: GameId, apiKey: String): IO[Unit] = session.execute(upsert)((id, apiKey)).void

    /** Every key, filed under the engine identity (`game.external_id`) of the game it belongs to: what an engine's
      * callback is authenticated against. Games sharing an identity each contribute their own key, so either one
      * identifies the engine.
      */
    def byIdentity: IO[ApiKeys] = session.execute(selectByIdentity).map(ApiKeys(_))

    /** Every key, filed under the host of its game's url: what matchmaker presents when it calls that host, which is
      * all an engine call knows about the engine it is making. A url that does not parse has no host to file under, and
      * is left out.
      */
    def byHost: IO[ApiKeys] =
        session
            .execute(selectByUrl)
            .map(rows =>
                ApiKeys(rows.flatMap((url, key) => Try(Option(URI.create(url).getHost)).toOption.flatten.map(_ -> key)))
            )
}
