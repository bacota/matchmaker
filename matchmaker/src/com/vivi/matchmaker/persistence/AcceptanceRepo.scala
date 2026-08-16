package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

class AcceptanceRepo(session: Session[IO]) {
  private val challengeId = SkunkIdCodecs.challengeId
  private val playerId = SkunkIdCodecs.playerId
  private val gameId = SkunkIdCodecs.gameId
  private val characterId = SkunkIdCodecs.characterId
  private val gameType = SkunkCodecs.gameType
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  private val insertAcceptance: Command[(ChallengeId, PlayerId, GameType, GameId)] =
    sql"INSERT INTO acceptance (challenge_id, player_id, game_type, game_id) VALUES ($challengeId, $playerId, $gameType, $gameId)".command

  private val insertCharacterAcceptance: Command[(GameId, ChallengeId, PlayerId, CharacterId)] =
    sql"""INSERT INTO character_acceptance (game_id, challenge_id, game_type, player_id, character_id)
          VALUES ($gameId, $challengeId, 'C', $playerId, $characterId)""".command

  private def toAcceptance(challengeId: ChallengeId, playerId: PlayerId, gameId: GameId, gameType: GameType, characterIdValue: Option[Long]): Acceptance =
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"acceptance ($challengeId, $playerId) is game_type 'C' but has no character_acceptance row")
        )
        CharacterAcceptance(challengeId, playerId, gameId, CharacterId(cid))
      case GameType.Plain =>
        PlainAcceptance(challengeId, playerId, gameId)
    }

  // A trailing opaque-typed codec (GameId/CharacterId are opaque types defined in Ids.scala)
  // defeats skunk's twiddle-list match-type resolution from outside that file, so the
  // character_id column is decoded via the underlying int8 codec and mapped afterward instead.
  private val selectAcceptance: Query[(ChallengeId, PlayerId), (GameType, GameId, Option[Long])] =
    sql"""SELECT a.game_type, a.game_id, ca.character_id
          FROM acceptance a
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
          WHERE a.challenge_id = $challengeId AND a.player_id = $playerId"""
      .query(gameType *: gameId *: int8.opt)

  // character_acceptance has a FK to acceptance, so its rows must go first — deleting the
  // parent row while a character_acceptance row still references it is a FK violation.
  private val deleteCharacterAcceptancesByChallenge: Command[ChallengeId] =
    sql"DELETE FROM character_acceptance WHERE challenge_id = $challengeId".command

  private val deleteByChallenge: Command[ChallengeId] =
    sql"DELETE FROM acceptance WHERE challenge_id = $challengeId".command

  def deleteAllForChallenge(challengeId: ChallengeId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterAcceptancesByChallenge)(challengeId)
      _ <- session.execute(deleteByChallenge)(challengeId)
    } yield ()

  private val deleteCharacterAcceptanceOne: Command[(ChallengeId, PlayerId)] =
    sql"DELETE FROM character_acceptance WHERE challenge_id = $challengeId AND player_id = $playerId".command

  private val deleteOne: Command[(ChallengeId, PlayerId)] =
    sql"DELETE FROM acceptance WHERE challenge_id = $challengeId AND player_id = $playerId".command

  def delete(challengeId: ChallengeId, playerId: PlayerId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterAcceptanceOne)((challengeId, playerId))
      _ <- session.execute(deleteOne)((challengeId, playerId))
    } yield ()

  private val countByChallenge: Query[ChallengeId, Long] =
    sql"SELECT count(*) FROM acceptance WHERE challenge_id = $challengeId".query(int8)

  def countForChallenge(challengeId: ChallengeId): IO[Long] =
    session.unique(countByChallenge)(challengeId)

  private val playerRow: Codec[(String, Boolean, String)] = text *: bool *: text

  private val acceptanceWithChallengeAndPlayersRow: Codec[
    (GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, GameId, Option[Long],
      (String, Boolean, String), String, Boolean, String)
  ] =
    gameType *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: gameId *: int8.opt *:
      playerRow *: text *: bool *: text

  private val selectAcceptanceWithChallengeAndPlayers = sql"""
    SELECT a.game_type, oc.challenger, oc.message, oc.number_of_players, oc.start,
           EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.game_id, cc.character_id,
           acceptor.nickname, acceptor.is_admin, acceptor.external_id,
           challenger.nickname, challenger.is_admin, challenger.external_id
    FROM acceptance a
    JOIN open_challenge oc ON oc.challenge_id = a.challenge_id
    LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
    JOIN player acceptor ON acceptor.player_id = a.player_id
    JOIN player challenger ON challenger.player_id = oc.challenger
    WHERE a.challenge_id = $challengeId AND a.player_id = $playerId"""
    .query(acceptanceWithChallengeAndPlayersRow)

  /** Reads, in one join query, everything needed to authorize deleting an acceptance: the
    * challenge it belongs to, the accepting player, and the challenger (the player who owns
    * the challenge).
    */
  def readWithChallengeAndPlayers(challengeId: ChallengeId, playerId: PlayerId): IO[Option[(OpenChallenge, Player, Player)]] =
    session.option(selectAcceptanceWithChallengeAndPlayers)((challengeId, playerId)).map(_.map {
      case (
            gameType,
            challenger,
            message,
            numberOfPlayers,
            start,
            timeLimitSeconds,
            settings,
            gameId,
            characterIdValue,
            (acceptorNickname, acceptorIsAdmin, acceptorExternalId),
            challengerNickname,
            challengerIsAdmin,
            challengerExternalId
          ) =>
        val timeLimit = timeLimitSeconds.map(v => Duration.ofSeconds(v.toLong))
        val challengeModel: OpenChallenge = gameType match {
          case GameType.Character =>
            val cid = characterIdValue.getOrElse(
              throw new IllegalStateException(s"challenge ${challengeId.value} is game_type 'C' but has no character_open_challenge row")
            )
            CharacterOpenChallenge(challengeId, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId, CharacterId(cid))
          case GameType.Plain =>
            PlainOpenChallenge(challengeId, challenger, message, numberOfPlayers, start, timeLimit, settings, gameId)
        }
        val acceptor = Player(playerId, acceptorNickname, acceptorIsAdmin, acceptorExternalId)
        val challengerPlayer = Player(challenger, challengerNickname, challengerIsAdmin, challengerExternalId)
        (challengeModel, acceptor, challengerPlayer)
    })

  private val selectAcceptancesForPlayer: Query[PlayerId, (ChallengeId, GameType, GameId, Option[Long])] =
    sql"""SELECT a.challenge_id, a.game_type, a.game_id, ca.character_id
          FROM acceptance a
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
          WHERE a.player_id = $playerId
          ORDER BY a.challenge_id"""
      .query(challengeId *: gameType *: gameId *: int8.opt)

  /** Every acceptance this player has outstanding.
    *
    * An acceptance survives until the challenge becomes a match or the player backs out, so this
    * is what "what have I said yes to?" means — the UI needs it to offer backing out without
    * first knowing which challenge to look under.
    */
  def listForPlayer(playerId: PlayerId): IO[List[Acceptance]] =
    session.execute(selectAcceptancesForPlayer)(playerId).map(_.map {
      case (challenge, gt, gameId, characterIdValue) => toAcceptance(challenge, playerId, gameId, gt, characterIdValue)
    })

  // Deliberately not wrapped in its own session.transaction.use: OpenChallengeService.accept —
  // the only caller that needs this insert's two statements to be atomic — already holds an
  // ambient transaction across the whole accept flow (for the FOR UPDATE capacity check), and
  // skunk transactions do not nest on one session. A caller with no ambient transaction (e.g. a
  // test creating an acceptance directly) gets two auto-committed statements instead, which is
  // fine since nothing here observes a partial write.
  def create(a: Acceptance): IO[Acceptance] = {
    val gt = a match {
      case _: CharacterAcceptance => GameType.Character
      case _: PlainAcceptance     => GameType.Plain
    }
    for {
      _ <- session.execute(insertAcceptance)((a.challengeId, a.playerId, gt, a.gameId))
      _ <- a match {
        case ca: CharacterAcceptance => session.execute(insertCharacterAcceptance)((a.gameId, a.challengeId, a.playerId, ca.characterId)).void
        case _: PlainAcceptance      => IO.unit
      }
    } yield a
  }

  def read(challengeId: ChallengeId, playerId: PlayerId): IO[Option[Acceptance]] =
    session
      .option(selectAcceptance)((challengeId, playerId))
      .map(_.map { case (gt, gameId, characterIdValue) => toAcceptance(challengeId, playerId, gameId, gt, characterIdValue) })

  // Acceptance's only fields are the composite key (plus gameId/characterId, which are fixed
  // at creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
