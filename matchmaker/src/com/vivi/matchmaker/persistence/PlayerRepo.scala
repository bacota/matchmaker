package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model.{GameId, MatchId, Player, PlayerId, PublicPlayer}

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

    /* Players whose nickname begins with a prefix, for the search box.
     *
     * Case insensitive, and insensitive to how the name was spaced: both sides of the comparison are
     * normalized the same way -- lowercased, every run of whitespace collapsed to one space, and
     * trimmed -- so "red  BARON" finds "Red Baron". `normalized` below is the Scala half; the SQL
     * expression here is the other, and V21 indexes exactly this expression. The three must stay
     * identical: a difference in any of them is not a wrong answer but a silent table scan, or a
     * nickname that cannot be found by the way it is written.
     *
     * `LIKE` rather than `starts_with`, because this is the form the index can serve: Postgres turns
     * `expr LIKE 'abc%'` into the range `expr ~>=~ 'abc' AND expr ~<~ 'abd'` over V21's
     * `text_pattern_ops` index. The price is that the pattern has a language, so the prefix is
     * escaped on the way in -- see `likePrefix`.
     *
     * Ordered by nickname, not by the normalized form: the page is a list somebody reads, and the
     * order they see is the order of the names as they are written. That ORDER BY is in the
     * database's collation rather than the index's byte order either way, so the plan sorts what the
     * range scan found -- the handful of rows the prefix matched, not the table.
     *
     * `LIMIT` is a parameter rather than a constant here because the caller asks for one more than
     * it means to show -- that extra row is how it knows to say "there are more". */
    private val selectPlayersByNicknamePrefix: Query[(String, Int), (PlayerId, String)] =
        sql"""SELECT player_id, nickname FROM player
          WHERE btrim(regexp_replace(lower(nickname), '[ \t\n\r\f\v]+', ' ', 'g')) LIKE $text
          ORDER BY nickname
          LIMIT $int4"""
            .query(playerId *: text)

    /** Players whose nickname begins with `prefix`, in nickname order, at most `limit` of them.
      *
      * Case insensitive and whitespace insensitive, and the prefix is text rather than a pattern: see
      * `selectPlayersByNicknamePrefix`, `normalized` and `likePrefix`. Answers with
      * [[com.vivi.matchmaker.model.PublicPlayer]] rather than `Player`, because the caller is a stranger -- the address
      * and the Cognito identity on a `Player` are not theirs to see, and the way to keep it that way is not to read
      * them.
      */
    def searchByNicknamePrefix(prefix: String, limit: Int): IO[List[PublicPlayer]] =
        session
            .execute(selectPlayersByNicknamePrefix)((likePrefix(normalized(prefix)), limit))
            .map(_.map((id, nickname) => PublicPlayer(id, nickname)))

    /* A nickname, or the start of one, in the form the search compares.
     *
     * The Scala half of V21's index expression, and it has to agree with it character for character:
     * lowercase, every run of whitespace one space, ends trimmed.
     *
     * Two deliberate details. The whitespace class is written out rather than `\\s`, because
     * Postgres's `\\s` is locale-dependent and Java's is a fixed five characters plus vertical tab --
     * naming them makes both sides the same function instead of two that agree on ASCII. And the
     * locale is `ROOT` rather than the JVM's default, because `toLowerCase` with a Turkish default
     * locale maps `I` to a dotless `i`, which would fold one way in this process and another way in
     * the database. */
    private def normalized(nickname: String): String =
        nickname.toLowerCase(java.util.Locale.ROOT).replaceAll("[ \\t\\n\\r\\f\\u000B]+", " ").trim

    /* The prefix somebody typed, as a LIKE pattern that matches it literally and then anything.
     *
     * Three characters mean something to LIKE and have to be spelled out to mean themselves: `%`
     * (any run), `_` (any one character) and the escape character itself. Unescaped, a nickname
     * search for "a_b" would find "axb" -- a wildcard the searcher did not ask for and cannot turn
     * off. The backslash is LIKE's default escape, so no ESCAPE clause is needed, and this is a
     * parameter rather than interpolated SQL, so nothing here is about quoting.
     *
     * The trailing `%` is the only wildcard in the result, and it is what makes this a prefix match
     * rather than an equality. */
    private def likePrefix(prefix: String): String = {
        val escaped = prefix.flatMap {
            case c @ ('\\' | '%' | '_') => s"\\$c"
            case c                      => c.toString
        }
        s"$escaped%"
    }

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
