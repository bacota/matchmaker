package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** One seat's answer to "who wants to hear about this?".
  *
  * Already resolved, because since V14 there is nothing to resolve: the seat's own eleven columns are NOT NULL and are
  * what it was stamped with when it was created (or what its player has said about this match since). No other level is
  * read, and so none is carried here.
  */
case class SeatNotifications(participantId: ParticipantId, player: Player, preferences: NotificationDefaults)

/** A game as the notification path needs it: what to call it, and what it says its players should hear.
  *
  * Not a `Game`, which cannot be read without naming the type of its parameter values — and the services that send mail
  * have no business naming that. These are the two facts about a game that writing to its players actually uses.
  */
case class GameNotice(gameId: GameId, name: String, notifications: NotificationDefaults)

/** One acceptor of a challenge, with everything that bears on writing to them about it.
  *
  * The whole `Player` rather than an id, because the caller is composing mail: it needs the nickname to address them by
  * and the address to send to, and a challenge's roster is small enough that fetching them with the preferences costs
  * one join rather than a query per recipient.
  *
  * Two levels and the game's, which is all there is to ask: nobody is a participant in anything until the challenge is
  * started, so the level that would otherwise answer does not exist yet.
  */
case class AcceptorNotifications(player: Player, roleName: String, levels: NotificationLevels)

/** The `notify_*` columns of `player`, `participant` and `player_game`.
  *
  * A repo of its own rather than eleven more columns on `PlayerRepo` and `ParticipantRepo`, for the same reason
  * `player.email` has exactly one writer: this is a concern that touches three tables and is read by two callers — the
  * settings screens and whatever is about to send a mail — while every other query against those tables is about
  * playing the game and would be carrying eleven columns it never looks at. The game's own defaults are the exception
  * and live on `Game`: they are part of what an admin registers, so they travel with the rest of the game's definition.
  *
  * Nothing here decides who hears about what; that is a seat's own eleven columns, or
  * [[com.vivi.matchmaker.model.NotificationLevels.resolve]] for an audience that has no seat yet. What this does hold
  * is the two statements that carry a player's answers downwards when they ask for it — see `alignGamesWithPlayer` and
  * `applyToMatches`, which resolve the same chain `resolve` describes, in SQL, because they resolve it for many rows at
  * once.
  */
class NotificationRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId
    private val participantId = SkunkIdCodecs.participantId
    private val challengeId = SkunkIdCodecs.challengeId
    private val preferences = SkunkCodecs.notificationPreferences
    private val defaults = SkunkCodecs.notificationDefaults

    private val selectPlayerPreferences: Query[PlayerId, NotificationPreferences] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player
          WHERE player_id = $playerId""".query(preferences)

    /* The same eleven columns, read under the row's own lock -- the repo-wide rule for a read whose
     * answer decides a write, and here the read decides more than one: what this save changed is what
     * its cascades are allowed to carry into `player_game` and `participant`. Two saves that both read
     * the old answers would each compute a change the other had already made, and the row would end up
     * disagreeing with the rows beneath it about which of them had happened.
     *
     * The `player` row is the lock for all three levels, which is why `lockSettings` exists for the
     * paths that do not want these columns back. */
    private val selectPlayerPreferencesForUpdate: Query[PlayerId, NotificationPreferences] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player
          WHERE player_id = $playerId
          FOR UPDATE""".query(preferences)

    private val lockPlayer: Query[PlayerId, PlayerId] =
        sql"SELECT player_id FROM player WHERE player_id = $playerId FOR UPDATE".query(playerId)

    /* As GameRepo's update: a SET list needs a placeholder per column, so the value is taken apart
     * here rather than at the call site. */
    private val updatePlayerPreferences: Command[(NotificationPreferences, PlayerId)] =
        sql"""UPDATE player SET
            notify_challenge_accepted = ${bool.opt}, notify_challenge_ready = ${bool.opt},
            notify_acceptance_changed = ${bool.opt}, notify_accepted_challenge_ready = ${bool.opt}, notify_invitation_received = ${bool.opt}, notify_invitation_accepted = ${bool.opt}, notify_invitation_rejected = ${bool.opt},
            notify_match_started = ${bool.opt}, notify_turn_taken = ${bool.opt},
            notify_your_turn = ${bool.opt}, notify_match_ended = ${bool.opt},
            update_date = now()
          WHERE player_id = $playerId""".command
            .contramap { case (p, id) =>
                (
                  p.challengeAccepted,
                  p.challengeReady,
                  p.acceptanceChanged,
                  p.acceptedChallengeReady,
                  p.invitationReceived,
                  p.invitationAccepted,
                  p.invitationRejected,
                  p.matchStarted,
                  p.turnTaken,
                  p.yourTurn,
                  p.matchEnded,
                  id
                )
            }

    private val selectPlayerGames: Query[PlayerId, (GameId, NotificationPreferences)] =
        sql"""SELECT game_id,
                 notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player_game
          WHERE player_id = $playerId
          ORDER BY game_id""".query(gameId *: preferences)

    private val selectPlayerGame: Query[(PlayerId, GameId), NotificationPreferences] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player_game
          WHERE player_id = $playerId AND game_id = $gameId""".query(preferences)

    /* One statement for both cases, because a player editing one game's settings does not know or
     * care whether they have edited them before. EXCLUDED restates nothing: it is the row this
     * insert would have written, so the eleven values are bound once. */
    private val upsertPlayerGame: Command[(PlayerId, GameId, NotificationPreferences)] =
        sql"""INSERT INTO player_game (player_id, game_id,
              notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
              notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
              notify_your_turn, notify_match_ended)
          VALUES ($playerId, $gameId, $preferences)
          ON CONFLICT (player_id, game_id) DO UPDATE SET
              notify_challenge_accepted = EXCLUDED.notify_challenge_accepted,
              notify_challenge_ready = EXCLUDED.notify_challenge_ready,
              notify_acceptance_changed = EXCLUDED.notify_acceptance_changed,
              notify_accepted_challenge_ready = EXCLUDED.notify_accepted_challenge_ready, notify_invitation_received = EXCLUDED.notify_invitation_received, notify_invitation_accepted = EXCLUDED.notify_invitation_accepted, notify_invitation_rejected = EXCLUDED.notify_invitation_rejected,
              notify_match_started = EXCLUDED.notify_match_started,
              notify_turn_taken = EXCLUDED.notify_turn_taken,
              notify_your_turn = EXCLUDED.notify_your_turn,
              notify_match_ended = EXCLUDED.notify_match_ended,
              update_date = now()""".command

    /* Every seat in a match with what it says about itself.
     *
     * One table, and no COALESCE: since V14 a seat's eleven columns are NOT NULL and are the answer,
     * which is the whole point of the change -- deciding whether to write to a seat no longer depends
     * on what its player has said since about the game in general. Carrying that into a match they
     * are already in is something they ask for; see `applyToMatches`.
     *
     * `player` is joined rather than read per seat for the same reason it is in PlayerRepo's own
     * listForMatch: one player may hold two seats, and each seat is asked about separately. */
    private val selectSeatPreferences: Query[
      (GameId, MatchId),
      (ParticipantId, PlayerId, String, Boolean, String, Option[String], NotificationDefaults)
    ] =
        sql"""SELECT p.participant_id, p.player_id, pl.nickname, pl.is_admin, pl.external_id, pl.email,
                 p.notify_challenge_accepted, p.notify_challenge_ready, p.notify_acceptance_changed,
                 p.notify_accepted_challenge_ready, p.notify_invitation_received, p.notify_invitation_accepted, p.notify_invitation_rejected, p.notify_match_started, p.notify_turn_taken,
                 p.notify_your_turn, p.notify_match_ended
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
            .query(participantId *: playerId *: text *: bool *: text *: text.opt *: defaults)

    /* Everyone who has accepted one challenge, with the two levels that can speak for them.
     *
     * The challenge-stage counterpart of `selectSeatPreferences`, and a chain rather than an answer
     * for a reason that is not an omission: a participant row is what a start creates, so there is
     * nothing more specific than the player's settings for the game to ask while a challenge is still
     * a challenge.
     *
     * The challenger is in here too, because creating a challenge inserts their own acceptance --
     * see `ChallengeService.create`. So this one query is the whole audience for anything that
     * happens to a challenge, and which of them is the challenger is a comparison the caller makes.
     */
    private val selectLevelsForChallenge: Query[
      (GameId, ChallengeId),
      (PlayerId, String, Boolean, String, Option[String], String, NotificationPreferences, NotificationPreferences)
    ] =
        sql"""SELECT pl.player_id, pl.nickname, pl.is_admin, pl.external_id, pl.email, r.name,
                 pg.notify_challenge_accepted, pg.notify_challenge_ready, pg.notify_acceptance_changed,
                 pg.notify_accepted_challenge_ready, pg.notify_invitation_received, pg.notify_invitation_accepted, pg.notify_invitation_rejected, pg.notify_match_started, pg.notify_turn_taken,
                 pg.notify_your_turn, pg.notify_match_ended,
                 pl.notify_challenge_accepted, pl.notify_challenge_ready, pl.notify_acceptance_changed,
                 pl.notify_accepted_challenge_ready, pl.notify_invitation_received, pl.notify_invitation_accepted, pl.notify_invitation_rejected, pl.notify_match_started, pl.notify_turn_taken,
                 pl.notify_your_turn, pl.notify_match_ended
          FROM acceptance a
          JOIN player pl ON pl.player_id = a.player_id
          JOIN game_role r ON r.game_id = a.game_id AND r.game_role_id = a.game_role_id
          LEFT JOIN player_game pg ON pg.player_id = a.player_id AND pg.game_id = a.game_id
          WHERE a.game_id = $gameId AND a.challenge_id = $challengeId
          ORDER BY a.player_id"""
            .query(playerId *: text *: bool *: text *: text.opt *: text *: preferences *: preferences)

    /* The caller's own seats in one match. Plural: a player may hold two, and they are not two
     * settings -- so the read takes the first and the write covers all of them. */
    private val selectParticipantPreferences: Query[(GameId, MatchId, PlayerId), NotificationDefaults] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM participant
          WHERE game_id = $gameId AND match_id = $matchId AND player_id = $playerId
          ORDER BY participant_id""".query(defaults)

    private val updateParticipantPreferences: Command[(NotificationDefaults, GameId, MatchId, PlayerId)] =
        sql"""UPDATE participant SET
            notify_challenge_accepted = $bool, notify_challenge_ready = $bool,
            notify_acceptance_changed = $bool, notify_accepted_challenge_ready = $bool, notify_invitation_received = $bool, notify_invitation_accepted = $bool, notify_invitation_rejected = $bool,
            notify_match_started = $bool, notify_turn_taken = $bool,
            notify_your_turn = $bool, notify_match_ended = $bool,
            update_date = now()
          WHERE game_id = $gameId AND match_id = $matchId AND player_id = $playerId""".command
            .contramap { case (p, game, matchId, player) =>
                (
                  p.challengeAccepted,
                  p.challengeReady,
                  p.acceptanceChanged,
                  p.acceptedChallengeReady,
                  p.invitationReceived,
                  p.invitationAccepted,
                  p.invitationRejected,
                  p.matchStarted,
                  p.turnTaken,
                  p.yourTurn,
                  p.matchEnded,
                  game,
                  matchId,
                  player
                )
            }

    /* Carrying a player's answers down into the rows that inherited from them.
     *
     * Two statements, because the two levels below `player` are shaped differently. This one copies
     * what the player has just changed into every game they have said something about, so that those
     * games stop disagreeing with them about it -- including a question they have gone back to leaving
     * unsaid, which is written as the NULL it now is rather than frozen at what it used to say.
     *
     * Only what they changed: the questions this save left alone are left alone here too, because a
     * game they deliberately answered differently on some other question is still answering it. Hence
     * the `CASE WHEN` per column, as in `restampParticipants` and for the same reason.
     *
     * Only the games they already have a row for. Creating one per game in existence would record an
     * opinion about games they have never played, and a game with no row already answers from the
     * player level, which is what they just set. */
    private val alignPlayerGames: Command[(Set[NotificationType], NotificationPreferences, PlayerId)] =
        sql"""UPDATE player_game SET
            notify_challenge_accepted = CASE WHEN $bool THEN ${bool.opt} ELSE notify_challenge_accepted END,
            notify_challenge_ready = CASE WHEN $bool THEN ${bool.opt} ELSE notify_challenge_ready END,
            notify_acceptance_changed = CASE WHEN $bool THEN ${bool.opt} ELSE notify_acceptance_changed END,
            notify_accepted_challenge_ready = CASE WHEN $bool THEN ${bool.opt} ELSE notify_accepted_challenge_ready END,
            notify_invitation_received = CASE WHEN $bool THEN ${bool.opt} ELSE notify_invitation_received END,
            notify_invitation_accepted = CASE WHEN $bool THEN ${bool.opt} ELSE notify_invitation_accepted END,
            notify_invitation_rejected = CASE WHEN $bool THEN ${bool.opt} ELSE notify_invitation_rejected END,
            notify_match_started = CASE WHEN $bool THEN ${bool.opt} ELSE notify_match_started END,
            notify_turn_taken = CASE WHEN $bool THEN ${bool.opt} ELSE notify_turn_taken END,
            notify_your_turn = CASE WHEN $bool THEN ${bool.opt} ELSE notify_your_turn END,
            notify_match_ended = CASE WHEN $bool THEN ${bool.opt} ELSE notify_match_ended END,
            update_date = now()
          WHERE player_id = $playerId""".command
            // A flag and a value per column, interleaved in `NotificationType.values` order: the flag
            // says whether this save touched that question at all, and the value is what it now says.
            .contramap { case (changed, p, id) =>
                (
                  changed(NotificationType.ChallengeAccepted),
                  p.challengeAccepted,
                  changed(NotificationType.ChallengeReady),
                  p.challengeReady,
                  changed(NotificationType.AcceptanceChanged),
                  p.acceptanceChanged,
                  changed(NotificationType.AcceptedChallengeReady),
                  p.acceptedChallengeReady,
                  changed(NotificationType.InvitationReceived),
                  p.invitationReceived,
                  changed(NotificationType.InvitationAccepted),
                  p.invitationAccepted,
                  changed(NotificationType.InvitationRejected),
                  p.invitationRejected,
                  changed(NotificationType.MatchStarted),
                  p.matchStarted,
                  changed(NotificationType.TurnTaken),
                  p.turnTaken,
                  changed(NotificationType.YourTurn),
                  p.yourTurn,
                  changed(NotificationType.MatchEnded),
                  p.matchEnded,
                  id
                )
            }

    /* And this one re-stamps the player's seats in the matches they are still playing, from the chain
     * as it now stands -- the same expression `ParticipantRepo.create` stamps a new seat with, which
     * is why it is SQL in both places rather than a resolve in Scala and a second one here.
     *
     * `COALESCE($gameId.opt, g.game_id)` is how one statement serves both offers: one game, when the
     * player has just changed that game's settings, or every game, when they have changed their
     * defaults. A NULL there matches every game rather than none.
     *
     * "Matches they are still playing" is `NOT p.completed`, and the seat answers that itself: every
     * way a match ends retires its seats -- results, forfeit, and (since V15) cancellation. A match
     * that is over will not send anything again, and rewriting its seats would edit the record of what
     * it did send.
     *
     * No join to `match`, which is what this needed before V15: cancelling marked the match and left
     * the seats reading `completed = false`, so the only way to tell a called-off match from a live one
     * was to go and look. Now that a seat knows, asking it is both cheaper and the right question --
     * this is about seats, and `participant.completed` is per-seat, which is where an engine that
     * retires one player from a match that carries on would say so.
     *
     * `CASE WHEN` per column, rather than eleven plain assignments, is what keeps this to what the
     * player actually changed. A seat holds answers its player may have set on that one match, and
     * nothing distinguishes those from what the seat was stamped with at creation -- that is what
     * making the columns NOT NULL costs. So the request says which kinds it changed, and every other
     * column is assigned from itself and left exactly as it was: changing what a game says about
     * turns does not undo a mute somebody put on one match's results. */
    private val restampParticipants: Command[(Set[NotificationType], PlayerId, Option[GameId])] =
        sql"""UPDATE participant p SET
            notify_challenge_accepted = CASE WHEN $bool THEN r.notify_challenge_accepted ELSE p.notify_challenge_accepted END,
            notify_challenge_ready = CASE WHEN $bool THEN r.notify_challenge_ready ELSE p.notify_challenge_ready END,
            notify_acceptance_changed = CASE WHEN $bool THEN r.notify_acceptance_changed ELSE p.notify_acceptance_changed END,
            notify_accepted_challenge_ready = CASE WHEN $bool THEN r.notify_accepted_challenge_ready ELSE p.notify_accepted_challenge_ready END,
            notify_invitation_received = CASE WHEN $bool THEN r.notify_invitation_received ELSE p.notify_invitation_received END,
            notify_invitation_accepted = CASE WHEN $bool THEN r.notify_invitation_accepted ELSE p.notify_invitation_accepted END,
            notify_invitation_rejected = CASE WHEN $bool THEN r.notify_invitation_rejected ELSE p.notify_invitation_rejected END,
            notify_match_started = CASE WHEN $bool THEN r.notify_match_started ELSE p.notify_match_started END,
            notify_turn_taken = CASE WHEN $bool THEN r.notify_turn_taken ELSE p.notify_turn_taken END,
            notify_your_turn = CASE WHEN $bool THEN r.notify_your_turn ELSE p.notify_your_turn END,
            notify_match_ended = CASE WHEN $bool THEN r.notify_match_ended ELSE p.notify_match_ended END,
            update_date = now()
          FROM (
              SELECT g.game_id,
                     COALESCE(pg.notify_challenge_accepted, pl.notify_challenge_accepted,
                              g.notify_challenge_accepted) AS notify_challenge_accepted,
                     COALESCE(pg.notify_challenge_ready, pl.notify_challenge_ready,
                              g.notify_challenge_ready) AS notify_challenge_ready,
                     COALESCE(pg.notify_acceptance_changed, pl.notify_acceptance_changed,
                              g.notify_acceptance_changed) AS notify_acceptance_changed,
                     COALESCE(pg.notify_accepted_challenge_ready, pl.notify_accepted_challenge_ready,
                              g.notify_accepted_challenge_ready) AS notify_accepted_challenge_ready,
                     COALESCE(pg.notify_invitation_received, pl.notify_invitation_received,
                              g.notify_invitation_received) AS notify_invitation_received,
                     COALESCE(pg.notify_invitation_accepted, pl.notify_invitation_accepted,
                              g.notify_invitation_accepted) AS notify_invitation_accepted,
                     COALESCE(pg.notify_invitation_rejected, pl.notify_invitation_rejected,
                              g.notify_invitation_rejected) AS notify_invitation_rejected,
                     COALESCE(pg.notify_match_started, pl.notify_match_started,
                              g.notify_match_started) AS notify_match_started,
                     COALESCE(pg.notify_turn_taken, pl.notify_turn_taken,
                              g.notify_turn_taken) AS notify_turn_taken,
                     COALESCE(pg.notify_your_turn, pl.notify_your_turn,
                              g.notify_your_turn) AS notify_your_turn,
                     COALESCE(pg.notify_match_ended, pl.notify_match_ended,
                              g.notify_match_ended) AS notify_match_ended
              FROM player pl
                  CROSS JOIN game g
                  LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = g.game_id
              WHERE pl.player_id = $playerId AND g.game_id = COALESCE(${gameId.opt}, g.game_id)
          ) r
          WHERE p.player_id = $playerId AND p.game_id = r.game_id AND NOT p.completed""".command
            // Eleven flags in `NotificationType.values` order, as everywhere else the columns are
            // bound positionally. The player is named twice in the statement -- once to resolve the
            // chain, once to pick the seats -- so it is bound twice from the one value.
            .contramap { case (changed, player, game) =>
                (
                  changed(NotificationType.ChallengeAccepted),
                  changed(NotificationType.ChallengeReady),
                  changed(NotificationType.AcceptanceChanged),
                  changed(NotificationType.AcceptedChallengeReady),
                  changed(NotificationType.InvitationReceived),
                  changed(NotificationType.InvitationAccepted),
                  changed(NotificationType.InvitationRejected),
                  changed(NotificationType.MatchStarted),
                  changed(NotificationType.TurnTaken),
                  changed(NotificationType.YourTurn),
                  changed(NotificationType.MatchEnded),
                  player,
                  game,
                  player
                )
            }

    /* Whether there is a game to have an opinion about.
     *
     * Here rather than through GameRepo because that repo is parameterized by the type of a game's
     * parameter values, which a service about notifications has no business naming, and because the
     * alternative -- letting the insert fail on the foreign key -- turns a caller's typo into a 500.
     */
    private val selectGameExists: Query[GameId, GameId] =
        sql"SELECT game_id FROM game WHERE game_id = $gameId".query(gameId)

    def gameExists(id: GameId): IO[Boolean] = session.option(selectGameExists)(id).map(_.isDefined)

    /* A game's name and its notification defaults, for the same reason as above: the notification
     * path needs a game, and `GameRepo` cannot be built without naming the type of its parameter
     * values. */
    private val selectGameNotice: Query[GameId, GameNotice] =
        sql"""SELECT game_id, name,
                 notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_invitation_received, notify_invitation_accepted, notify_invitation_rejected, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM game
          WHERE game_id = $gameId"""
            .query(gameId *: text *: SkunkCodecs.notificationDefaults)
            .map { case (id, name, defaults) => GameNotice(id, name, defaults) }

    /** What writing to a game's players needs to know about the game. */
    def gameNotice(id: GameId): IO[Option[GameNotice]] = session.option(selectGameNotice)(id)

    /** What this player has said about notifications in general. A row of NULLs reads as `unset`, which is a player who
      * has never opened the form — indistinguishable from one who opened it and chose "Use Default" for everything, and
      * deliberately so.
      */
    def readForPlayer(id: PlayerId): IO[NotificationPreferences] =
        session.option(selectPlayerPreferences)(id).map(_.getOrElse(NotificationPreferences.unset))

    /** The same, with the row locked for the rest of the transaction.
      *
      * For a save that is about to write these columns and to decide, from what they said, what its cascades may carry
      * downwards — so the answers it diffs against cannot be changed underneath it by another save. See
      * `selectPlayerPreferencesForUpdate`.
      */
    def readForPlayerForUpdate(id: PlayerId): IO[NotificationPreferences] =
        session.option(selectPlayerPreferencesForUpdate)(id).map(_.getOrElse(NotificationPreferences.unset))

    /** Takes the lock without reading the columns, for a save at one of the levels below `player`.
      *
      * The `player` row is the lock for everything this repo writes for one player, at every level. On the face of it a
      * per-game save should lock its own `player_game` row instead — but that row may not exist yet, which is precisely
      * the case two concurrent first saves for one game would race on, and `FOR UPDATE` on a row that is not there
      * locks nothing. The player always exists; the caller has just read it.
      *
      * Cheap enough to take unconditionally: one indexed row, held only for the rest of a transaction that writes a
      * handful of rows, and contended only by that same player saving twice at once.
      */
    def lockSettings(id: PlayerId): IO[Unit] = session.option(lockPlayer)(id).void

    def updateForPlayer(id: PlayerId, preferences: NotificationPreferences): IO[Unit] =
        session.execute(updatePlayerPreferences)((preferences, id)).void

    /** Every game this player has said something specific about, by game id. */
    def listForPlayer(id: PlayerId): IO[List[GameNotificationPreferences]] =
        session
            .execute(selectPlayerGames)(id)
            .map(_.map { case (game, prefs) => GameNotificationPreferences(game, prefs) })

    /** What this player has said about this one game. `unset` where they have no row, which reads the same as a row of
      * NULLs and is what a player who has never opened that game's settings has.
      *
      * Read before a save so that the save can tell what it changed, which is all a cascade may carry down — under
      * `lockSettings`, which is what keeps that comparison from going stale while it is being acted on.
      */
    def readForPlayerGame(id: PlayerId, game: GameId): IO[NotificationPreferences] =
        session.option(selectPlayerGame)((id, game)).map(_.getOrElse(NotificationPreferences.unset))

    def updateForPlayerGame(id: PlayerId, game: GameId, preferences: NotificationPreferences): IO[Unit] =
        session.execute(upsertPlayerGame)((id, game, preferences)).void

    /** What this player's seats in this match say, or `None` if they have no seat in it.
      *
      * Every kind answered, because a seat cannot leave one unsaid. `None` means they are not playing this match, which
      * is a different answer from "they are playing it and want nothing", and the caller needs both.
      *
      * The first of their seats, where a player holding two has both written together by `updateForPlayerInMatch` — so
      * the two always agree and reading either is reading the answer.
      */
    def readForPlayerInMatch(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId
    ): IO[Option[NotificationDefaults]] =
        session.execute(selectParticipantPreferences)((gameId, matchId, playerId)).map(_.headOption)

    /** Records what this player wants to hear about this match, on every seat they hold in it.
      *
      * Every seat, because the preference is about the match: a player with two seats muted one match, not one half of
      * one, and leaving the other seat as it was would keep mailing them from it.
      *
      * @return
      *   whether the player has any seat in the match at all, which is how the service tells "saved" from "you are not
      *   in this match".
      */
    def updateForPlayerInMatch(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId,
        preferences: NotificationDefaults
    ): IO[Boolean] =
        session
            .execute(updateParticipantPreferences)((preferences, gameId, matchId, playerId))
            .map {
                case skunk.data.Completion.Update(rows) => rows > 0
                // Postgres answers an UPDATE with an UPDATE tag and nothing else, so this is
                // unreachable -- but "no rows" is the safe reading of an answer we do not recognise:
                // it reports not-in-this-match rather than claiming a write nobody can see.
                case _ => false
            }

    /** Copies what this save changed about the player's own answers into every game they have said anything about, so
      * that those games stop answering that question differently.
      *
      * The offer a player accepts when they save their defaults and ask for them to be used everywhere. `changed` is
      * the questions the save actually changed, and the only columns this touches: a game answering some other question
      * differently goes on answering it. Games they have no row for need no write — those already answer from the
      * player level.
      */
    def alignGamesWithPlayer(
        id: PlayerId,
        preferences: NotificationPreferences,
        changed: Set[NotificationType]
    ): IO[Unit] =
        session.execute(alignPlayerGames)((changed, preferences, id)).void

    /** Re-stamps this player's seats in the matches they are still playing, from the chain as it now stands.
      *
      * `game` narrows it to the one game whose settings they have just changed; `None` covers every game, which is what
      * a change to their defaults offers. Either way it is the matches that are still running: a finished one will send
      * nothing more, and rewriting its seats would rewrite the record of what it did send.
      *
      * `changed` is the questions the save actually changed, and the only columns this touches — so an answer a player
      * set on one match in particular survives a change to something else. See `restampParticipants`. An empty set
      * would write nothing, so the caller does not call at all.
      */
    def applyToMatches(id: PlayerId, game: Option[GameId], changed: Set[NotificationType]): IO[Unit] =
        session.execute(restampParticipants)((changed, id, game)).void

    /** Everyone who has accepted a challenge, with what each of them wants to hear about it.
      *
      * Takes the game's defaults rather than reading them, because the caller has the `Game` in hand — it needed it to
      * compose the mail.
      */
    def levelsForChallenge(
        gameId: GameId,
        challengeId: ChallengeId,
        defaults: NotificationDefaults
    ): IO[List[AcceptorNotifications]] =
        session
            .execute(selectLevelsForChallenge)((gameId, challengeId))
            .map(_.map { case (id, nickname, isAdmin, externalId, email, roleName, perGame, overall) =>
                AcceptorNotifications(
                  Player(id, nickname, isAdmin, externalId, email),
                  roleName,
                  NotificationLevels(playerGame = perGame, player = overall, game = defaults)
                )
            })

    /** Every seat in a match with what it says about whether its player hears about it.
      *
      * Takes no defaults and joins no other level: a seat's own eleven columns are the answer, which is what V14 made
      * them for.
      */
    /* One player's two levels for one game, for a notification about somebody who is not in the
     * challenge yet.
     *
     * `levelsForChallenge` cannot answer this: it reads the acceptance rows, and an invited player has
     * no acceptance -- that is the whole difference between being invited and having accepted. So this
     * is the same chain read for one named player, joined the same way (LEFT JOIN, since a player who
     * has never opened a game's settings has no player_game row and NULLs fall through the chain
     * exactly as an unanswered question does). */
    private val selectLevelsForPlayer: Query[(PlayerId, GameId), (NotificationPreferences, NotificationPreferences)] =
        sql"""SELECT pg.notify_challenge_accepted, pg.notify_challenge_ready, pg.notify_acceptance_changed,
                 pg.notify_accepted_challenge_ready,
                 pg.notify_invitation_received, pg.notify_invitation_accepted, pg.notify_invitation_rejected,
                 pg.notify_match_started, pg.notify_turn_taken,
                 pg.notify_your_turn, pg.notify_match_ended,
                 pl.notify_challenge_accepted, pl.notify_challenge_ready, pl.notify_acceptance_changed,
                 pl.notify_accepted_challenge_ready,
                 pl.notify_invitation_received, pl.notify_invitation_accepted, pl.notify_invitation_rejected,
                 pl.notify_match_started, pl.notify_turn_taken,
                 pl.notify_your_turn, pl.notify_match_ended
          FROM player pl
          LEFT JOIN player_game pg ON pg.player_id = pl.player_id AND pg.game_id = $gameId
          WHERE pl.player_id = $playerId"""
            .query(preferences *: preferences)
            .contramap { case (player, game) => (game, player) }

    /** The chain for one player in one game, ending at `defaults`.
      *
      * For an event about a player who has not accepted anything -- an invitation made, or turned down -- where
      * [[levelsForChallenge]] has no row to find them by. `NotificationLevels.unset` throughout if the player is gone,
      * which resolves to the game's own answers: a notification is not the place to discover a missing row.
      */
    def levelsForPlayer(player: PlayerId, game: GameId, defaults: NotificationDefaults): IO[NotificationLevels] =
        session
            .option(selectLevelsForPlayer)((player, game))
            .map {
                case Some((playerGame, playerLevel)) => NotificationLevels(playerGame, playerLevel, defaults)
                case None                            => NotificationLevels(game = defaults)
            }

    def preferencesForMatch(gameId: GameId, matchId: MatchId): IO[List[SeatNotifications]] =
        session
            .execute(selectSeatPreferences)((gameId, matchId))
            .map(_.map { case (participant, id, nickname, isAdmin, externalId, email, seat) =>
                SeatNotifications(participant, Player(id, nickname, isAdmin, externalId, email), seat)
            })
}
