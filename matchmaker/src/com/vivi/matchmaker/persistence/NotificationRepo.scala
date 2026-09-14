package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import com.vivi.matchmaker.model._

/** One seat's answer to "who wants to hear about this?", still unresolved.
  *
  * `levels` carries the game's defaults along with the three specific levels so that a caller has everything
  * [[com.vivi.matchmaker.model.NotificationPolicy]] needs in one value; the game itself is read once by the caller, not
  * once per seat.
  */
case class SeatNotifications(participantId: ParticipantId, playerId: PlayerId, levels: NotificationLevels)

/** The `notify_*` columns of `player`, `participant` and `player_game`.
  *
  * A repo of its own rather than eight more columns on `PlayerRepo` and `ParticipantRepo`, for the same reason
  * `player.email` has exactly one writer: this is a concern that touches three tables and is read by two callers — the
  * settings screens and whatever is about to send a mail — while every other query against those tables is about
  * playing the game and would be carrying eight columns it never looks at. The game's own defaults are the exception
  * and live on `Game`: they are part of what an admin registers, so they travel with the rest of the game's definition.
  *
  * Nothing here decides anything. It reads what each level said and writes what a player said; which of them wins is
  * [[com.vivi.matchmaker.model.NotificationPolicy]], in the model, where it can be exercised without a database.
  */
class NotificationRepo(session: Session[IO]) {
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val matchId = SkunkIdCodecs.matchId
    private val participantId = SkunkIdCodecs.participantId
    private val preferences = SkunkCodecs.notificationPreferences

    private val selectPlayerPreferences: Query[PlayerId, NotificationPreferences] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player
          WHERE player_id = $playerId""".query(preferences)

    /* As GameRepo's update: a SET list needs a placeholder per column, so the value is taken apart
     * here rather than at the call site. */
    private val updatePlayerPreferences: Command[(NotificationPreferences, PlayerId)] =
        sql"""UPDATE player SET
            notify_challenge_accepted = ${bool.opt}, notify_challenge_ready = ${bool.opt},
            notify_acceptance_changed = ${bool.opt}, notify_accepted_challenge_ready = ${bool.opt},
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
                 notify_accepted_challenge_ready, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM player_game
          WHERE player_id = $playerId
          ORDER BY game_id""".query(gameId *: preferences)

    /* One statement for both cases, because a player editing one game's settings does not know or
     * care whether they have edited them before. EXCLUDED restates nothing: it is the row this
     * insert would have written, so the eight values are bound once. */
    private val upsertPlayerGame: Command[(PlayerId, GameId, NotificationPreferences)] =
        sql"""INSERT INTO player_game (player_id, game_id,
              notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
              notify_accepted_challenge_ready, notify_match_started, notify_turn_taken,
              notify_your_turn, notify_match_ended)
          VALUES ($playerId, $gameId, $preferences)
          ON CONFLICT (player_id, game_id) DO UPDATE SET
              notify_challenge_accepted = EXCLUDED.notify_challenge_accepted,
              notify_challenge_ready = EXCLUDED.notify_challenge_ready,
              notify_acceptance_changed = EXCLUDED.notify_acceptance_changed,
              notify_accepted_challenge_ready = EXCLUDED.notify_accepted_challenge_ready,
              notify_match_started = EXCLUDED.notify_match_started,
              notify_turn_taken = EXCLUDED.notify_turn_taken,
              notify_your_turn = EXCLUDED.notify_your_turn,
              notify_match_ended = EXCLUDED.notify_match_ended,
              update_date = now()""".command

    /* Every seat in a match with the three levels that could speak for it.
     *
     * One query rather than three, because the answer is wanted for every seat at once and the
     * joins are what a notification is: this seat, this player's settings for this game, this
     * player's settings in general. The `player_game` join is outer -- a player who has said
     * nothing about the game has no row, which decodes to `unset` and falls straight through.
     *
     * `player` is joined rather than read per seat for the same reason it is in PlayerRepo's own
     * listForMatch: one player may hold two seats, and each seat is asked about separately. */
    private val selectLevelsForMatch: Query[
      (GameId, MatchId),
      (ParticipantId, PlayerId, NotificationPreferences, NotificationPreferences, NotificationPreferences)
    ] =
        sql"""SELECT p.participant_id, p.player_id,
                 p.notify_challenge_accepted, p.notify_challenge_ready, p.notify_acceptance_changed,
                 p.notify_accepted_challenge_ready, p.notify_match_started, p.notify_turn_taken,
                 p.notify_your_turn, p.notify_match_ended,
                 pg.notify_challenge_accepted, pg.notify_challenge_ready, pg.notify_acceptance_changed,
                 pg.notify_accepted_challenge_ready, pg.notify_match_started, pg.notify_turn_taken,
                 pg.notify_your_turn, pg.notify_match_ended,
                 pl.notify_challenge_accepted, pl.notify_challenge_ready, pl.notify_acceptance_changed,
                 pl.notify_accepted_challenge_ready, pl.notify_match_started, pl.notify_turn_taken,
                 pl.notify_your_turn, pl.notify_match_ended
          FROM participant p
          JOIN player pl ON pl.player_id = p.player_id
          LEFT JOIN player_game pg ON pg.player_id = p.player_id AND pg.game_id = p.game_id
          WHERE p.game_id = $gameId AND p.match_id = $matchId
          ORDER BY p.participant_id"""
            .query(participantId *: playerId *: preferences *: preferences *: preferences)

    /* The caller's own seats in one match. Plural: a player may hold two, and they are not two
     * settings -- so the read takes the first and the write covers all of them. */
    private val selectParticipantPreferences: Query[(GameId, MatchId, PlayerId), NotificationPreferences] =
        sql"""SELECT notify_challenge_accepted, notify_challenge_ready, notify_acceptance_changed,
                 notify_accepted_challenge_ready, notify_match_started, notify_turn_taken,
                 notify_your_turn, notify_match_ended
          FROM participant
          WHERE game_id = $gameId AND match_id = $matchId AND player_id = $playerId
          ORDER BY participant_id""".query(preferences)

    private val updateParticipantPreferences: Command[(NotificationPreferences, GameId, MatchId, PlayerId)] =
        sql"""UPDATE participant SET
            notify_challenge_accepted = ${bool.opt}, notify_challenge_ready = ${bool.opt},
            notify_acceptance_changed = ${bool.opt}, notify_accepted_challenge_ready = ${bool.opt},
            notify_match_started = ${bool.opt}, notify_turn_taken = ${bool.opt},
            notify_your_turn = ${bool.opt}, notify_match_ended = ${bool.opt},
            update_date = now()
          WHERE game_id = $gameId AND match_id = $matchId AND player_id = $playerId""".command
            .contramap { case (p, game, matchId, player) =>
                (
                  p.challengeAccepted,
                  p.challengeReady,
                  p.acceptanceChanged,
                  p.acceptedChallengeReady,
                  p.matchStarted,
                  p.turnTaken,
                  p.yourTurn,
                  p.matchEnded,
                  game,
                  matchId,
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

    /** What this player has said about notifications in general. A row of NULLs reads as `unset`, which is a player who
      * has never opened the form — indistinguishable from one who opened it and chose "Use Default" for everything, and
      * deliberately so.
      */
    def readForPlayer(id: PlayerId): IO[NotificationPreferences] =
        session.option(selectPlayerPreferences)(id).map(_.getOrElse(NotificationPreferences.unset))

    def updateForPlayer(id: PlayerId, preferences: NotificationPreferences): IO[Unit] =
        session.execute(updatePlayerPreferences)((preferences, id)).void

    /** Every game this player has said something specific about, by game id. */
    def listForPlayer(id: PlayerId): IO[List[GameNotificationPreferences]] =
        session
            .execute(selectPlayerGames)(id)
            .map(_.map { case (game, prefs) => GameNotificationPreferences(game, prefs) })

    def updateForPlayerGame(id: PlayerId, game: GameId, preferences: NotificationPreferences): IO[Unit] =
        session.execute(upsertPlayerGame)((id, game, preferences)).void

    /** What this player has said about this one match, or `None` if they have no seat in it.
      *
      * `Some(unset)` and `None` are different answers and the caller needs both: the first is a player in the match who
      * has said nothing, the second is somebody who is not playing it. A row of NULLs is the former.
      *
      * The first of their seats, where a player holding two has both written together by `updateForPlayerInMatch` — so
      * the two always agree and reading either is reading the answer.
      */
    def readForPlayerInMatch(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId
    ): IO[Option[NotificationPreferences]] =
        session.execute(selectParticipantPreferences)((gameId, matchId, playerId)).map(_.headOption)

    /** Records what this player wants to hear about this match, on every seat they hold in it.
      *
      * Every seat, because the preference is about the match: a player with two seats muted one match, not one half of
      * one, and leaving the other seat unset would keep mailing them from it.
      *
      * @return
      *   whether the player has any seat in the match at all, which is how the service tells "saved" from "you are not
      *   in this match".
      */
    def updateForPlayerInMatch(
        gameId: GameId,
        matchId: MatchId,
        playerId: PlayerId,
        preferences: NotificationPreferences
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

    /** Every seat in a match with everything that bears on whether its player hears about it.
      *
      * Takes the game's defaults rather than reading them, because the caller has the `Game` in hand — it needed it to
      * compose the mail — and reading it again per match would be a query for a value it is already holding.
      */
    def levelsForMatch(gameId: GameId, matchId: MatchId, defaults: NotificationDefaults): IO[List[SeatNotifications]] =
        session
            .execute(selectLevelsForMatch)((gameId, matchId))
            .map(_.map { case (participant, player, seat, perGame, overall) =>
                SeatNotifications(participant, player, NotificationLevels(seat, perGame, overall, defaults))
            })
}
