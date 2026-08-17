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

  // Atomic insert for a CharacterAcceptance: inserts both acceptance and character_acceptance in one statement.
  private val insertCharacterAcceptance: Command[(ChallengeId, PlayerId, GameId, CharacterId)] =
    sql"""WITH ins AS (
          INSERT INTO acceptance (challenge_id, player_id, game_type, game_id)
          VALUES ($challengeId, $playerId, 'C', $gameId)
          RETURNING game_id, challenge_id, player_id
        )
        INSERT INTO character_acceptance (game_id, challenge_id, game_type, player_id, character_id)
        SELECT game_id, challenge_id, 'C', player_id, $characterId FROM ins""".command

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

  // acceptance's primary key is the composite (game_id, challenge_id, player_id) — challenge_id
  // and player_id alone do not identify a row — so game_id is required in every lookup below,
  // not just challenge_id and player_id.
  private val selectAcceptance: Query[(GameId, ChallengeId, PlayerId), (GameType, Option[Long])] =
    sql"""SELECT a.game_type, ca.character_id
          FROM acceptance a
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.player_id = a.player_id
          WHERE a.game_id = $gameId AND a.challenge_id = $challengeId AND a.player_id = $playerId"""
      .query(gameType *: int8.opt)

  // character_acceptance has a FK to acceptance, so its rows must go first — deleting the
  // parent row while a character_acceptance row still references it is a FK violation.
  private val deleteCharacterAcceptancesByChallenge: Command[(GameId, ChallengeId)] =
    sql"DELETE FROM character_acceptance WHERE game_id = $gameId AND challenge_id = $challengeId".command

  private val deleteByChallenge: Command[(GameId, ChallengeId)] =
    sql"DELETE FROM acceptance WHERE game_id = $gameId AND challenge_id = $challengeId".command

  def deleteAllForChallenge(gameId: GameId, challengeId: ChallengeId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterAcceptancesByChallenge)((gameId, challengeId))
      _ <- session.execute(deleteByChallenge)((gameId, challengeId))
    } yield ()

  private val deleteCharacterAcceptanceOne: Command[(GameId, ChallengeId, PlayerId)] =
    sql"DELETE FROM character_acceptance WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId".command

  private val deleteOne: Command[(GameId, ChallengeId, PlayerId)] =
    sql"DELETE FROM acceptance WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId".command

  def delete(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterAcceptanceOne)((gameId, challengeId, playerId))
      _ <- session.execute(deleteOne)((gameId, challengeId, playerId))
    } yield ()

  private val countByChallenge: Query[(GameId, ChallengeId), Long] =
    sql"SELECT count(*) FROM acceptance WHERE game_id = $gameId AND challenge_id = $challengeId".query(int8)

  def countForChallenge(gameId: GameId, challengeId: ChallengeId): IO[Long] =
    session.unique(countByChallenge)((gameId, challengeId))

  private val playerRow: Codec[(String, Boolean, String)] = text *: bool *: text

  // gameId is a query parameter here, not a selected column — the caller already knows it (it's
  // how the row is looked up), so there's no need to round-trip it back out.
  private val acceptanceWithChallengeAndPlayersRow: Codec[
    (GameType, PlayerId, String, Short, Option[Instant], Option[Double], String, Option[Long],
      (String, Boolean, String), String, Boolean, String)
  ] =
    gameType *: playerId *: text *: int2 *: instant.opt *: float8.opt *: settings *: int8.opt *:
      playerRow *: text *: bool *: text

  private val selectAcceptanceWithChallengeAndPlayers = sql"""
    SELECT a.game_type, oc.challenger, oc.message, oc.number_of_players, oc.start,
           EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, cc.character_id,
           acceptor.nickname, acceptor.is_admin, acceptor.external_id,
           challenger.nickname, challenger.is_admin, challenger.external_id
    FROM acceptance a
    JOIN open_challenge oc ON oc.game_id = a.game_id AND oc.challenge_id = a.challenge_id
    LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
    JOIN player acceptor ON acceptor.player_id = a.player_id
    JOIN player challenger ON challenger.player_id = oc.challenger
    WHERE a.game_id = $gameId AND a.challenge_id = $challengeId AND a.player_id = $playerId"""
    .query(acceptanceWithChallengeAndPlayersRow)

  /** Reads, in one join query, everything needed to authorize deleting an acceptance: the
    * challenge it belongs to, the accepting player, and the challenger (the player who owns
    * the challenge).
    */
  def readWithChallengeAndPlayers(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Option[(OpenChallenge, Player, Player)]] =
    session.option(selectAcceptanceWithChallengeAndPlayers)((gameId, challengeId, playerId)).map(_.map {
      case (
            gameType,
            challenger,
            message,
            numberOfPlayers,
            start,
            timeLimitSeconds,
            settings,
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
    * first knowing which challenge to look under. Scoped by player_id alone rather than the full
    * composite key deliberately: a player may have acceptances across many games, and listing
    * "everything I've accepted" is exactly the case where the game isn't known ahead of time.
    */
  def listForPlayer(playerId: PlayerId): IO[List[Acceptance]] =
    session.execute(selectAcceptancesForPlayer)(playerId).map(_.map {
      case (challenge, gt, gameId, characterIdValue) => toAcceptance(challenge, playerId, gameId, gt, characterIdValue)
    })

  // A CharacterAcceptance's insert already writes both the acceptance and character_acceptance
  // rows in one statement (see insertCharacterAcceptance's CTE), so it must not also go through
  // insertAcceptance — doing both would insert into acceptance twice and hit its primary key.
  def create(a: Acceptance): IO[Acceptance] =
    a match {
      case ca: CharacterAcceptance =>
        session.execute(insertCharacterAcceptance)((a.challengeId, a.playerId, a.gameId, ca.characterId)).as(a)
      case _: PlainAcceptance =>
        session.execute(insertAcceptance)((a.challengeId, a.playerId, GameType.Plain, a.gameId)).as(a)
    }

  def read(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Option[Acceptance]] =
    session
      .option(selectAcceptance)((gameId, challengeId, playerId))
      .map(_.map { case (gt, characterIdValue) => toAcceptance(challengeId, playerId, gameId, gt, characterIdValue) })

  // Acceptance's only fields are the composite key (plus gameId/characterId, which are fixed
  // at creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
