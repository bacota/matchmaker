package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{GameId, MatchId, Player, PlayerId}

class PlayerRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId

    private val playerRow: Codec[(String, Boolean, String, Option[String])] = text *: bool *: text *: text.opt

    private val insertPlayer: Query[(String, Boolean, String, Option[String]), PlayerId] =
        sql"""INSERT INTO player (nickname, is_admin, external_id, email)
          VALUES ($text, $bool, $text, ${text.opt})
          RETURNING player_id""".query(playerId)

    private val selectPlayer: Query[PlayerId, (String, Boolean, String, Option[String])] =
        sql"""SELECT nickname, is_admin, external_id, email FROM player WHERE player_id = $playerId""".query(playerRow)

    private val selectPlayerByExternalId: Query[String, (PlayerId, String, Boolean, Option[String])] =
        sql"""SELECT player_id, nickname, is_admin, email FROM player WHERE external_id = $text"""
            .query(playerId *: text *: bool *: text.opt)

    /* As GameRepo's lockGameRow: FOR SHARE, because callers reference this player from a row they
     * are inserting rather than modifying the player itself. */
    private val selectPlayerForShare: Query[PlayerId, (String, Boolean, String, Option[String])] =
        sql"""SELECT nickname, is_admin, external_id, email FROM player WHERE player_id = $playerId FOR SHARE"""
            .query(playerRow)

    private val selectPlayerByExternalIdForShare: Query[String, (PlayerId, String, Boolean, Option[String])] =
        sql"""SELECT player_id, nickname, is_admin, email FROM player WHERE external_id = $text FOR SHARE"""
            .query(playerId *: text *: bool *: text.opt)

    /* FOR UPDATE, unlike the two above: for a caller modifying the player row itself rather than
     * referencing it. `PlayerService` reads the row to build the row it writes back, and two such
     * calls at once must queue rather than both diffing against the state before either wrote. */
    private val selectPlayerByExternalIdForUpdate: Query[String, (PlayerId, String, Boolean, Option[String])] =
        sql"""SELECT player_id, nickname, is_admin, email FROM player WHERE external_id = $text FOR UPDATE"""
            .query(playerId *: text *: bool *: text.opt)

    /* Deliberately does not write `email`.
     *
     * Every caller of `update` is changing something else -- a nickname, an admin flag -- and passes
     * a whole `Player` to do it. The address is the one field on that row whose authority lives
     * outside matchmaker: Cognito owns it, and matchmaker's copy is only ever written from a token's
     * verified claim at sign-in. A general update that carried it would mean every such caller
     * quietly restating the address from whatever `Player` it happened to be holding -- a value read
     * minutes earlier, or built by a caller that never had one -- and that is precisely how a
     * confirmed change gets overwritten by a rename.
     *
     * So the column has exactly one writer, `updateEmail` below. */
    private val updatePlayer: Command[(String, Boolean, String, PlayerId)] =
        sql"""UPDATE player SET nickname = $text, is_admin = $bool, external_id = $text
          WHERE player_id = $playerId""".command

    private val updatePlayerEmail: Command[(Option[String], PlayerId)] =
        sql"UPDATE player SET email = ${text.opt} WHERE player_id = $playerId".command

    def create(player: Player): IO[Player] =
        session
            .unique(insertPlayer)((player.nickname, player.isAdmin, player.externalId, player.email))
            .map(id => player.copy(playerId = id))

    def read(id: PlayerId): IO[Option[Player]] =
        session
            .option(selectPlayer)(id)
            .map(_.map { case (nickname, isAdmin, externalId, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    def readByExternalId(externalId: String): IO[Option[Player]] =
        session
            .option(selectPlayerByExternalId)(externalId)
            .map(_.map { case (id, nickname, isAdmin, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    /** As `read`, but holding the player row against concurrent modification until the transaction ends.
      */
    def readForShare(id: PlayerId): IO[Option[Player]] =
        session
            .option(selectPlayerForShare)(id)
            .map(_.map { case (nickname, isAdmin, externalId, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    /** As `readByExternalId`, but holding the player row against concurrent modification until the transaction ends.
      */
    def readByExternalIdForShare(externalId: String): IO[Option[Player]] =
        session
            .option(selectPlayerByExternalIdForShare)(externalId)
            .map(_.map { case (id, nickname, isAdmin, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    /** As `readByExternalId`, but taking the row's exclusive lock: for a caller whose write is derived from what it
      * reads here, which is every caller that modifies the player itself.
      */
    def readByExternalIdForUpdate(externalId: String): IO[Option[Player]] =
        session
            .option(selectPlayerByExternalIdForUpdate)(externalId)
            .map(_.map { case (id, nickname, isAdmin, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    /* Everyone playing one match, in seat order.
     *
     * Joined through `participant` rather than asked for one player at a time, because the callers
     * that want this want all of them: a notification addressed to each seat, and a list of who else
     * is playing to put inside it. Seat order (participant_id) rather than nickname order, so the
     * names read in the order the game was dealt. */
    private val selectPlayersForMatch: Query[(GameId, MatchId), (PlayerId, String, Boolean, String, Option[String])] =
        sql"""SELECT pl.player_id, pl.nickname, pl.is_admin, pl.external_id, pl.email
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
            .query(playerId *: text *: bool *: text *: text.opt)

    /** The players of one match, in seat order.
      *
      * A player with two seats in one match appears twice, which is deliberate: the caller is walking seats, and
      * silently collapsing them would leave it with fewer players than participants and no way to tell which seat is
      * which.
      */
    def listForMatch(gameId: GameId, matchId: MatchId): IO[List[Player]] =
        session
            .execute(selectPlayersForMatch)((gameId, matchId))
            .map(_.map { case (id, nickname, isAdmin, externalId, email) =>
                Player(id, nickname, isAdmin, externalId, email)
            })

    /** Writes everything about a player except their address. See `updatePlayer` for why the exception, and
      * `updateEmail` for the one thing that writes it.
      */

    def update(player: Player): IO[Unit] =
        session.execute(updatePlayer)((player.nickname, player.isAdmin, player.externalId, player.playerId)).void

    /** Records where a player can be reached, and nothing else about them.
      *
      * The only writer of `player.email`. Its one caller is `PlayerService.updateEmail`, which is reached only from the
      * sign-in path -- so the address stored here always came from the `email` claim of a token Cognito had just
      * issued, which is the only statement about an address that is worth anything: Cognito owns the address, verified
      * it, and signs the player in with it.
      *
      * Takes a `PlayerId` rather than a `Player` on purpose. A whole `Player` would invite a caller to pass one it read
      * earlier and write four stale fields to correct one, which is the mistake splitting this out exists to prevent.
      */
    def updateEmail(id: PlayerId, email: Option[String]): IO[Unit] =
        session.execute(updatePlayerEmail)((email, id)).void
}
