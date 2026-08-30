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
  private val gameRoleId = SkunkIdCodecs.gameRoleId
  private val instant = SkunkCodecs.instant
  private val settings: Codec[String] = SkunkCodecs.jsonb

  private val insertAcceptance: Command[(ChallengeId, PlayerId, GameType, GameId, GameRoleId)] =
    sql"""INSERT INTO acceptance (challenge_id, player_id, game_type, game_id, game_role_id)
          VALUES ($challengeId, $playerId, $gameType, $gameId, $gameRoleId)""".command

  // Atomic insert for a CharacterAcceptance: inserts both acceptance and character_acceptance in one statement.
  private val insertCharacterAcceptance: Command[(ChallengeId, PlayerId, GameId, GameRoleId, CharacterId)] =
    sql"""WITH ins AS (
          INSERT INTO acceptance (challenge_id, player_id, game_type, game_id, game_role_id)
          VALUES ($challengeId, $playerId, 'C', $gameId, $gameRoleId)
          RETURNING game_id, challenge_id, game_role_id
        )
        INSERT INTO character_acceptance (game_id, challenge_id, game_type, game_role_id, character_id)
        SELECT game_id, challenge_id, 'C', game_role_id, $characterId FROM ins""".command

  private def toAcceptance(
      challengeId: ChallengeId,
      playerId: PlayerId,
      gameId: GameId,
      gameType: GameType,
      roleId: GameRoleId,
      characterIdValue: Option[Long]
  ): Acceptance =
    gameType match {
      case GameType.Character =>
        val cid = characterIdValue.getOrElse(
          throw new IllegalStateException(s"acceptance ($challengeId, $playerId) is game_type 'C' but has no character_acceptance row")
        )
        CharacterAcceptance(challengeId, playerId, gameId, CharacterId(cid), roleId)
      case GameType.Plain =>
        PlainAcceptance(challengeId, playerId, gameId, roleId)
    }

  // acceptance's primary key is the composite (game_id, challenge_id, game_role_id), so game_id
  // is required in every lookup below rather than challenge_id alone. Looking a row up by player
  // instead, as this one does, is not a key lookup at all: it holds only because the application
  // refuses a player a second seat in one challenge (see OpenChallengeService.accept). If that
  // rule is ever relaxed, this is one of the places that has to stop assuming one row.
  private val selectAcceptance: Query[(GameId, ChallengeId, PlayerId), (GameType, GameRoleId, Option[Long])] =
    sql"""SELECT a.game_type, a.game_role_id, ca.character_id
          FROM acceptance a
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.game_role_id = a.game_role_id
          WHERE a.game_id = $gameId AND a.challenge_id = $challengeId AND a.player_id = $playerId"""
      .query(gameType *: gameRoleId *: int8.opt)

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

  // character_acceptance is keyed by role rather than by player, so the row to delete is reached
  // through the acceptance it extends rather than named directly.
  private val deleteCharacterAcceptanceOne: Command[(GameId, ChallengeId, PlayerId)] =
    sql"""DELETE FROM character_acceptance ca
          USING acceptance a
          WHERE a.game_id = ca.game_id AND a.challenge_id = ca.challenge_id AND a.game_role_id = ca.game_role_id
            AND a.game_id = $gameId AND a.challenge_id = $challengeId AND a.player_id = $playerId""".command

  private val deleteOne: Command[(GameId, ChallengeId, PlayerId)] =
    sql"DELETE FROM acceptance WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId".command

  def delete(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Unit] =
    for {
      _ <- session.execute(deleteCharacterAcceptanceOne)((gameId, challengeId, playerId))
      _ <- session.execute(deleteOne)((gameId, challengeId, playerId))
    } yield ()

  private val selectHasAccepted: Query[(GameId, ChallengeId, PlayerId), Boolean] =
    sql"""SELECT EXISTS (
            SELECT 1 FROM acceptance
             WHERE game_id = $gameId AND challenge_id = $challengeId AND player_id = $playerId
          )""".query(bool)

  /** Whether this player has already accepted this challenge.
    *
    * Since V5 the primary key is (game_id, challenge_id, game_role_id), so the database no longer
    * refuses a player a second seat in one challenge — `OpenChallengeService.accept` does, and
    * this is what it asks. `EXISTS` rather than reading the row: the question is only whether
    * there is one, and unlike [[read]] this stays a straight answer if the rule is ever relaxed
    * and a player does hold two.
    */
  def hasAccepted(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Boolean] =
    session.unique(selectHasAccepted)((gameId, challengeId, playerId))

  private val selectRolesForChallenge: Query[(GameId, ChallengeId), GameRoleId] =
    sql"""SELECT game_role_id FROM acceptance
          WHERE game_id = $gameId AND challenge_id = $challengeId
          ORDER BY game_role_id""".query(gameRoleId)

  /** The roles already claimed by the acceptances of one challenge.
    *
    * Two questions are answered from this and nothing else: whether a role a player is asking for
    * is still free, and whether every required role of the game has been taken -- which is what a
    * start waits for. Cheaper than [[listForChallenge]], which joins in players and characters
    * that neither question needs.
    */
  def rolesForChallenge(gameId: GameId, challengeId: ChallengeId): IO[List[GameRoleId]] =
    session.execute(selectRolesForChallenge)((gameId, challengeId))

  private val countByChallenge: Query[(GameId, ChallengeId), Long] =
    sql"SELECT count(*) FROM acceptance WHERE game_id = $gameId AND challenge_id = $challengeId".query(int8)

  def countForChallenge(gameId: GameId, challengeId: ChallengeId): IO[Long] =
    session.unique(countByChallenge)((gameId, challengeId))

  private val playerRow: Codec[(String, Boolean, String)] = text *: bool *: text

  // gameId is a query parameter here, not a selected column — the caller already knows it (it's
  // how the row is looked up), so there's no need to round-trip it back out.
  private val acceptanceWithChallengeAndPlayersRow: Codec[
    (GameType, PlayerId, String, Option[Instant], Option[Double], String, Boolean, GameRoleId, Option[Long],
      (String, Boolean, String), String, Boolean, String)
  ] =
    gameType *: playerId *: text *: instant.opt *: float8.opt *: settings *: bool *: gameRoleId *: int8.opt *:
      playerRow *: text *: bool *: text

  // `a` is the acceptance being read; `challenger_acceptance` is the challenger's own, which is
  // where a challenge's gameRoleId lives (there is no such column on open_challenge — see
  // OpenChallengeRepo). The join is an inner one: every challenge has a challenger's acceptance,
  // created with it, and that acceptance names a role.
  private val selectAcceptanceWithChallengeAndPlayers = sql"""
    SELECT a.game_type, oc.challenger, oc.message, oc.start,
           EXTRACT(EPOCH FROM oc.time_limit)::float8, oc.settings, oc.public,
           challenger_acceptance.game_role_id, cc.character_id,
           acceptor.nickname, acceptor.is_admin, acceptor.external_id,
           challenger.nickname, challenger.is_admin, challenger.external_id
    FROM acceptance a
    JOIN open_challenge oc ON oc.game_id = a.game_id AND oc.challenge_id = a.challenge_id
    LEFT JOIN character_open_challenge cc ON cc.game_id = oc.game_id AND cc.challenge_id = oc.challenge_id
    JOIN acceptance challenger_acceptance
           ON challenger_acceptance.game_id = oc.game_id
          AND challenger_acceptance.challenge_id = oc.challenge_id
          AND challenger_acceptance.player_id = oc.challenger
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
            start,
            timeLimitSeconds,
            settings,
            isPublic,
            challengerRoleId,
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
            CharacterOpenChallenge(
              challengeId, challenger, message, start, timeLimit, settings, gameId, CharacterId(cid), isPublic, challengerRoleId
            )
          case GameType.Plain =>
            PlainOpenChallenge(
              challengeId, challenger, message, start, timeLimit, settings, gameId, isPublic, challengerRoleId
            )
        }
        val acceptor = Player(playerId, acceptorNickname, acceptorIsAdmin, acceptorExternalId)
        val challengerPlayer = Player(challenger, challengerNickname, challengerIsAdmin, challengerExternalId)
        (challengeModel, acceptor, challengerPlayer)
    })

  private val selectAcceptancesForPlayer
      : Query[PlayerId, (ChallengeId, GameType, GameId, GameRoleId, Option[Long], PlayerId, Boolean)] =
    sql"""SELECT a.challenge_id, a.game_type, a.game_id, a.game_role_id, ca.character_id,
                 oc.challenger,
                 -- Ready to start: no required role of the game is still unclaimed. Computed here
                 -- rather than by counting acceptances, because a challenge is full when its
                 -- roles are taken, and optional roles are not ones a start waits for. This is
                 -- the same rule GameEngineService.start enforces, asked of the row already in
                 -- hand rather than of a second query per challenge.
                 NOT EXISTS (SELECT 1 FROM game_role gr
                              WHERE gr.game_id = a.game_id AND NOT gr.optional
                                AND NOT EXISTS (SELECT 1 FROM acceptance taken
                                                 WHERE taken.game_id = a.game_id
                                                   AND taken.challenge_id = a.challenge_id
                                                   AND taken.game_role_id = gr.game_role_id))
          FROM acceptance a
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.game_role_id = a.game_role_id
          JOIN open_challenge oc ON oc.game_id = a.game_id AND oc.challenge_id = a.challenge_id
          WHERE a.player_id = $playerId AND oc.started_match_id IS NULL
          ORDER BY a.challenge_id"""
      .query(challengeId *: gameType *: gameId *: gameRoleId *: int8.opt *: playerId *: bool)

  /** Every acceptance this player has outstanding, each with the two facts about its challenge
    * that decide what the UI can offer on it: who may start it, and whether it is ready to be.
    *
    *
    * "Outstanding" means the player has not backed out and the challenge has not been started.
    * The acceptance rows of a started challenge are kept — they are the roster the match was
    * made from, and the challenge that owns them is kept too — but they are no longer anything
    * the player can act on, so they are excluded here rather than lingering in the UI's list of
    * things to back out of. This is what "what have I said yes to?" means — the UI needs it to
    * offer backing out without first knowing which challenge to look under. Scoped by player_id alone rather than the full
    * composite key deliberately: a player may have acceptances across many games, and listing
    * "everything I've accepted" is exactly the case where the game isn't known ahead of time.
    */
  def listForPlayer(playerId: PlayerId): IO[List[PendingAcceptance]] =
    session.execute(selectAcceptancesForPlayer)(playerId).map(_.map {
      case (challenge, gt, gameId, roleId, characterIdValue, challenger, ready) =>
        PendingAcceptance(
          toAcceptance(challenge, playerId, gameId, gt, roleId, characterIdValue),
          challenger,
          ready
        )
    })

  // As ParticipantRepo.listForMatch: the external id and role name are what the game engine is
  // told when the challenge becomes a match, so they are fetched in the same join rather than
  // one player lookup per acceptance.
  private val selectAcceptancesForChallenge: Query[
    (GameId, ChallengeId),
    (PlayerId, GameType, String, GameRoleId, String, Option[Long])
  ] =
    sql"""SELECT a.player_id, a.game_type, pl.external_id, a.game_role_id, r.name, ca.character_id
          FROM acceptance a
          JOIN player pl ON pl.player_id = a.player_id
          JOIN game_role r ON r.game_id = a.game_id AND r.game_role_id = a.game_role_id
          LEFT JOIN character_acceptance ca
                 ON ca.game_id = a.game_id AND ca.challenge_id = a.challenge_id AND ca.game_role_id = a.game_role_id
          WHERE a.game_id = $gameId AND a.challenge_id = $challengeId
          ORDER BY a.player_id"""
      .query(playerId *: gameType *: text *: gameRoleId *: text *: int8.opt)

  /** Every acceptance of one challenge, with each accepting player's external id and role name.
    *
    * This is the roster a challenge turns into when it is started: one participant per acceptance,
    * and one entry in the game engine's create-game request.
    */
  def listForChallenge(gameId: GameId, challengeId: ChallengeId): IO[List[(Acceptance, String, String)]] =
    session.execute(selectAcceptancesForChallenge)((gameId, challengeId)).map(_.map {
      case (playerId, gt, externalId, roleId, roleName, characterIdValue) =>
        (toAcceptance(challengeId, playerId, gameId, gt, roleId, characterIdValue), externalId, roleName)
    })

  // A CharacterAcceptance's insert already writes both the acceptance and character_acceptance
  // rows in one statement (see insertCharacterAcceptance's CTE), so it must not also go through
  // insertAcceptance — doing both would insert into acceptance twice and hit its primary key.
  def create(a: Acceptance): IO[Acceptance] =
    a match {
      case ca: CharacterAcceptance =>
        session.execute(insertCharacterAcceptance)((a.challengeId, a.playerId, a.gameId, a.gameRoleId, ca.characterId)).as(a)
      case _: PlainAcceptance =>
        session.execute(insertAcceptance)((a.challengeId, a.playerId, GameType.Plain, a.gameId, a.gameRoleId)).as(a)
    }

  def read(gameId: GameId, challengeId: ChallengeId, playerId: PlayerId): IO[Option[Acceptance]] =
    session
      .option(selectAcceptance)((gameId, challengeId, playerId))
      .map(_.map { case (gt, roleId, characterIdValue) => toAcceptance(challengeId, playerId, gameId, gt, roleId, characterIdValue) })

  // Acceptance's only fields are the composite key (plus gameId/characterId, which are fixed
  // at creation), so there is nothing mutable to update. Provided for interface symmetry.
  def update(a: Acceptance): IO[Unit] = IO.unit
}
