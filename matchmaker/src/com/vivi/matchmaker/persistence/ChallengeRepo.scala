package com.vivi.matchmaker.persistence

import cats.effect.IO
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import natchez.Trace.Implicits.noop
import java.time.{Duration, Instant}
import com.vivi.matchmaker.model._

/** The fields read (and locked) by [[ChallengeRepo.readForUpdate]].
  *
  * `startedMatchId` is non-empty once a challenge has been claimed by `GameEngineService.start`; see
  * [[ChallengeRepo.claimForStart]].
  */
case class LockedChallenge(gameType: GameType, startedMatchId: Option[MatchId], isOpen: Boolean)

/** Reads and writes `challenge` (plus its `character_challenge` sibling).
  *
  * `Challenge.gameRoleId` has no column here — the challenger's role lives on their own acceptance, which
  * `ChallengeService.create` writes in the same transaction as the challenge. Reads join that acceptance back in, so a
  * challenge still reports the role its challenger will play without the fact being stored twice.
  */
class ChallengeRepo(session: Session[IO]) {
    private val challengeId = SkunkIdCodecs.challengeId
    private val playerId = SkunkIdCodecs.playerId
    private val gameId = SkunkIdCodecs.gameId
    private val characterId = SkunkIdCodecs.characterId
    private val gameType = SkunkCodecs.gameType
    private val gameRoleId = SkunkIdCodecs.gameRoleId
    private val matchId = SkunkIdCodecs.matchId
    private val instant = SkunkCodecs.instant
    private val settings: Codec[String] = SkunkCodecs.jsonb
    private val timeLimitKind = SkunkCodecs.timeLimitKind
    private val timeLimitUnit = SkunkCodecs.timeLimitUnit

    private def toSeconds(d: Option[Duration]): Option[Double] = d.map(_.getSeconds.toDouble)
    private def fromSeconds(s: Option[Double]): Option[Duration] = s.map(v => Duration.ofSeconds(v.toLong))

    private val insertChallenge: Query[
      (
          GameType,
          PlayerId,
          String,
          Option[Instant],
          Option[Double],
          String,
          GameId,
          Boolean,
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean
      ),
      ChallengeId
    ] =
        sql"""INSERT INTO challenge (game_type, challenger, message, start, time_limit,
                                      settings, game_id, public, time_limit_kind, time_limit_unit,
                                      auto_start, is_open)
          VALUES ($gameType, $playerId, $text, ${instant.opt}, ${float8.opt} * INTERVAL '1 second',
                  $settings, $gameId, $bool, $timeLimitKind, $timeLimitUnit, $bool, $bool)
          RETURNING challenge_id""".query(challengeId)

    private val insertCharacterChallenge: Command[(GameId, ChallengeId, CharacterId)] =
        sql"""INSERT INTO character_challenge (game_id, challenge_id, game_type, character_id)
          VALUES ($gameId, $challengeId, 'C', $characterId)""".command

    // A trailing opaque-typed codec defeats skunk's twiddle-list match-type resolution from
    // outside Ids.scala (see AcceptanceRepo's gameAndCharacterId comment); characterId is decoded
    // as a raw int8 here and wrapped afterward for the same reason.
    private val challengeRow: Codec[
      (
          GameType,
          GameId,
          PlayerId,
          String,
          Option[Instant],
          Option[Double],
          String,
          Boolean,
          GameRoleId,
          Option[Long],
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean
      )
    ] =
        gameType *: gameId *: playerId *: text *: instant.opt *: float8.opt *: settings *: bool *: gameRoleId *: int8.opt *:
            timeLimitKind *: timeLimitUnit *: bool *: bool

    private def toChallenge(
        id: ChallengeId,
        row: (
            GameType,
            GameId,
            PlayerId,
            String,
            Option[Instant],
            Option[Double],
            String,
            Boolean,
            GameRoleId,
            Option[Long],
            TimeLimitKind,
            TimeLimitUnit,
            Boolean,
            Boolean
        )
    ): Challenge = {
        val (
          gameType,
          gameId,
          challenger,
          message,
          start,
          timeLimitSeconds,
          settings,
          isPublic,
          roleId,
          characterIdValue,
          timeLimitKind,
          timeLimitUnit,
          autoStart,
          isOpen
        ) = row
        val timeLimit = fromSeconds(timeLimitSeconds)
        gameType match {
            case GameType.Character =>
                val cid = characterIdValue.getOrElse(
                  throw new IllegalStateException(
                    s"challenge ${id.value} is game_type 'C' but has no character_challenge row"
                  )
                )
                CharacterChallenge(
                  id,
                  challenger,
                  message,
                  start,
                  timeLimit,
                  settings,
                  gameId,
                  CharacterId(cid),
                  isPublic,
                  roleId,
                  timeLimitKind,
                  timeLimitUnit,
                  autoStart,
                  isOpen
                )
            case GameType.Plain =>
                PlainChallenge(
                  id,
                  challenger,
                  message,
                  start,
                  timeLimit,
                  settings,
                  gameId,
                  isPublic,
                  roleId,
                  timeLimitKind,
                  timeLimitUnit,
                  autoStart,
                  isOpen
                )
        }
    }

    // challenge's primary key is the composite (game_id, challenge_id) — challenge_id alone
    // is not declared unique — so both columns are required here, not challenge_id alone.
    private val selectChallenge: Query[
      (GameId, ChallengeId),
      (
          GameType,
          GameId,
          PlayerId,
          String,
          Option[Instant],
          Option[Double],
          String,
          Boolean,
          GameRoleId,
          Option[Long],
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean
      )
    ] =
        sql"""SELECT ch.game_type, ch.game_id, ch.challenger, ch.message, ch.start,
                 EXTRACT(EPOCH FROM ch.time_limit)::float8, ch.settings, ch.public, a.game_role_id, cc.character_id,
                 ch.time_limit_kind, ch.time_limit_unit, ch.auto_start, ch.is_open
          FROM challenge ch
          LEFT JOIN character_challenge cc ON cc.game_id = ch.game_id AND cc.challenge_id = ch.challenge_id
          JOIN acceptance a ON a.game_id = ch.game_id AND a.challenge_id = ch.challenge_id
                           AND a.player_id = ch.challenger
          WHERE ch.game_id = $gameId AND ch.challenge_id = $challengeId"""
            .query(challengeRow)

    private val selectChallengeForUpdate: Query[(GameId, ChallengeId), (GameType, Option[MatchId], Boolean)] =
        sql"""SELECT game_type, started_match_id, is_open FROM challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId FOR UPDATE"""
            .query(gameType *: matchId.opt *: bool)

    // gameRoleId is not written here: it belongs to the challenger's acceptance, and changing the
    // role they will play means updating that row (AcceptanceRepo), not this one.
    private val updateChallenge: Command[
      (
          PlayerId,
          String,
          Option[Instant],
          Option[Double],
          String,
          Boolean,
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean,
          GameId,
          ChallengeId
      )
    ] =
        sql"""UPDATE challenge SET challenger = $playerId, message = $text,
          start = ${instant.opt}, time_limit = ${float8.opt} * INTERVAL '1 second', settings = $settings,
          public = $bool, time_limit_kind = $timeLimitKind, time_limit_unit = $timeLimitUnit,
          auto_start = $bool, is_open = $bool
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

    /** Inserts the challenge, and for a [[CharacterChallenge]] its character row too.
      *
      * Like every write in this package it opens no transaction of its own — that is the calling service's job, and
      * skunk rejects nested transactions outright anyway.
      */
    def create(c: Challenge): IO[Challenge] = {
        val gt = c match {
            case _: CharacterChallenge => GameType.Character
            case _: PlainChallenge     => GameType.Plain
        }
        for {
            id <- session.unique(insertChallenge)(
              (
                gt,
                c.challenger,
                c.message,
                c.start,
                toSeconds(c.timeLimit),
                c.settings,
                c.gameId,
                c.isPublic,
                c.timeLimitKind,
                c.timeLimitUnit,
                c.autoStart,
                c.isOpen
              )
            )
            _ <- c match {
                case cc: CharacterChallenge =>
                    session.execute(insertCharacterChallenge)((c.gameId, id, cc.characterId)).void
                case _: PlainChallenge => IO.unit
            }
        } yield c match {
            case cc: CharacterChallenge => cc.copy(challengeId = id)
            case pc: PlainChallenge     => pc.copy(challengeId = id)
        }
    }

    def read(gameId: GameId, id: ChallengeId): IO[Option[Challenge]] =
        session.option(selectChallenge)((gameId, id)).map(_.map(row => toChallenge(id, row)))

    /** Reads a challenge's game_type and start claim, taking a row lock (`FOR UPDATE`) that is held until the enclosing
      * transaction commits or rolls back. Callers use this to serialize concurrent acceptance attempts against the same
      * challenge's roles, the game_type to decide whether an acceptance must carry a characterId, startedMatchId to
      * refuse a challenge someone is already starting, and isOpen to decide whether an uninvited player may accept it
      * at all — a decision that has to be taken under the same lock as the acceptance it allows, or a challenge could
      * be closed between the two.
      */
    def readForUpdate(gameId: GameId, id: ChallengeId): IO[Option[LockedChallenge]] =
        session.option(selectChallengeForUpdate)((gameId, id)).map(_.map(LockedChallenge.apply.tupled))

    /* The claim on its own, without the lock [[readForUpdate]] takes.
     *
     * No FOR UPDATE, which is not an oversight about the rule in CLAUDE.md: nothing is written on the
     * strength of this answer. It is asked by a caller deciding what to *say* -- whether an acceptance
     * is still news about an open challenge, or news that has been overtaken by the match beginning --
     * and locking a row to decide what to put in an email would make every start wait on a mail. */
    private val selectStartedMatch: Query[(GameId, ChallengeId), Option[MatchId]] =
        sql"""SELECT started_match_id FROM challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId"""
            .query(matchId.opt)

    /** The match this challenge has been claimed for, if it has been. `None` for a challenge that is still open, and
      * for one that is not there at all — which read the same to every caller of this, since neither is a challenge
      * anybody can still start.
      */
    def startedMatch(gameId: GameId, id: ChallengeId): IO[Option[MatchId]] =
        session.option(selectStartedMatch)((gameId, id)).map(_.flatten)

    private val selectChallenger: Query[(GameId, ChallengeId), PlayerId] =
        sql"""SELECT challenger FROM challenge
          WHERE game_id = $gameId AND challenge_id = $challengeId""".query(playerId)

    /** Just the challenger of a challenge.
      *
      * Separate from [[read]] because that one joins the challenger's acceptance to read the role they are playing, and
      * the callers of this are asking a different question: a match's creator is its challenge's challenger, and that
      * is true whatever became of the acceptances.
      */
    def challengerOf(gameId: GameId, id: ChallengeId): IO[Option[PlayerId]] =
        session.option(selectChallenger)((gameId, id))

    private val claimChallengeForStart: Command[(MatchId, GameId, ChallengeId)] =
        sql"""UPDATE challenge SET started_match_id = $matchId
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

    private val releaseChallengeStartClaim: Command[(GameId, ChallengeId)] =
        sql"""UPDATE challenge SET started_match_id = NULL
          WHERE game_id = $gameId AND challenge_id = $challengeId""".command

    /** Marks a challenge as being started as `matchId`, so that a second concurrent start is refused rather than
      * producing a second match.
      *
      * Must be called in the same transaction as the [[readForUpdate]] whose row lock it is guarding: the lock alone
      * only serializes two starts, it does not tell the second one that the first has already happened. This is the
      * write that does.
      */
    def claimForStart(gameId: GameId, id: ChallengeId, matchId: MatchId): IO[Unit] =
        session.execute(claimChallengeForStart)((matchId, gameId, id)).void

    /** Clears a [[claimForStart]], returning the challenge to startable. Used when the engine call that the claim was
      * taken for fails and the half-made match is undone.
      */
    def releaseStartClaim(gameId: GameId, id: ChallengeId): IO[Unit] =
        session.execute(releaseChallengeStartClaim)((gameId, id)).void

    def update(c: Challenge): IO[Unit] =
        session
            .execute(updateChallenge)(
              (
                c.challenger,
                c.message,
                c.start,
                toSeconds(c.timeLimit),
                c.settings,
                c.isPublic,
                c.timeLimitKind,
                c.timeLimitUnit,
                c.autoStart,
                c.isOpen,
                c.gameId,
                c.challengeId
              )
            )
            .void

    // character_challenge has a FK to challenge, so its row must go first — deleting
    // the parent row while a character_challenge row still references it is a FK violation.
    // challenge's primary key is the composite (game_id, challenge_id) — challenge_id alone
    // is not declared unique — so both columns are required here, not challenge_id alone.
    private val deleteCharacterChallenge: Command[(GameId, ChallengeId)] =
        sql"DELETE FROM character_challenge WHERE game_id = $gameId AND challenge_id = $challengeId".command

    private val deleteChallenge: Command[(GameId, ChallengeId)] =
        sql"DELETE FROM challenge WHERE game_id = $gameId AND challenge_id = $challengeId".command

    def delete(gameId: GameId, id: ChallengeId): IO[Unit] =
        for {
            _ <- session.execute(deleteCharacterChallenge)((gameId, id))
            _ <- session.execute(deleteChallenge)((gameId, id))
        } yield ()

    // The acceptance count is a scalar subquery rather than another LEFT JOIN: this query already
    // joins the challenger's own acceptance to read their role, and counting over a second join to
    // the same table would multiply the rows rather than count them.
    private val selectChallengesByGame: Query[
      (GameId, PlayerId, PlayerId, PlayerId, PlayerId, PlayerId, PlayerId, PlayerId),
      (
          ChallengeId,
          GameType,
          PlayerId,
          String,
          Option[Instant],
          Option[Double],
          String,
          Boolean,
          GameRoleId,
          Option[Long],
          Long,
          String,
          TimeLimitKind,
          TimeLimitUnit,
          Boolean,
          Boolean
      )
    ] =
        sql"""SELECT ch.challenge_id, ch.game_type, ch.challenger, ch.message, ch.start,
                 EXTRACT(EPOCH FROM ch.time_limit)::float8, ch.settings, ch.public, a.game_role_id, cc.character_id,
                 (SELECT count(*) FROM acceptance ac
                   WHERE ac.game_id = ch.game_id AND ac.challenge_id = ch.challenge_id),
                 -- The roles already claimed, as a comma-separated list rather than an array:
                 -- one more scalar subquery beside the count, decoded as text below, which keeps
                 -- this row a flat tuple of scalars like every other query here.
                 (SELECT coalesce(string_agg(ac.game_role_id::text, ',' ORDER BY ac.game_role_id), '')
                    FROM acceptance ac
                   WHERE ac.game_id = ch.game_id AND ac.challenge_id = ch.challenge_id),
                 ch.time_limit_kind, ch.time_limit_unit, ch.auto_start, ch.is_open
          FROM challenge ch
          LEFT JOIN character_challenge cc ON cc.game_id = ch.game_id AND cc.challenge_id = ch.challenge_id
          JOIN acceptance a ON a.game_id = ch.game_id AND a.challenge_id = ch.challenge_id
                           AND a.player_id = ch.challenger
          WHERE ch.game_id = $gameId AND ch.started_match_id IS NULL
            -- A challenge nobody may accept uninvited is nobody else's business either, for the
            -- same reason the full one below is: it cannot be accepted by a passer-by, and the
            -- people it is still about are its challenger and the players it was addressed to.
            -- `is_open` first because it is the common case and the cheapest test. It does not
            -- make V22's (game_id, is_open) index usable -- an OR cannot be an index condition,
            -- so this whole clause is a filter over the rows an index on game_id already found,
            -- exactly as `started_match_id IS NULL` above it has always been. Recovering the
            -- index means splitting this into a union of three branches (open, mine, invited),
            -- measured at 15.6ms against 19.4ms on a game of 22k challenges -- at the cost of
            -- writing the select list above three times. See the note in V22 for why that trade
            -- is not taken yet.
            AND (ch.is_open
                 OR ch.challenger = $playerId
                 -- Anybody already seated in it, who needs it to see what they are waiting for and
                 -- to back out. Usually their invitation admits them too, but not always: a character
                 -- accepted and then transferred takes its invitation to the new owner, and leaves the
                 -- player whose acceptance holds the seat with nothing else to see the challenge by.
                 OR EXISTS (SELECT 1 FROM acceptance mine
                             WHERE mine.game_id = ch.game_id AND mine.challenge_id = ch.challenge_id
                               AND mine.player_id = $playerId)
                 OR EXISTS (SELECT 1 FROM invitation i
                             WHERE i.game_id = ch.game_id AND i.challenge_id = ch.challenge_id
                               AND i.player_id = $playerId)
                 -- A character game's invitation (V25) reaches whoever owns the character now --
                 -- unless the character has already accepted, which leaves its new owner (after a
                 -- transfer) nothing to answer: the seat is the previous owner's acceptance, and the
                 -- clause above still shows the challenge to them. The same exclusion as
                 -- CharacterInvitationRepo.listForOwner.
                 OR EXISTS (SELECT 1 FROM character_invitation ci
                             JOIN character c ON c.game_id = ci.game_id AND c.character_id = ci.character_id
                             WHERE ci.game_id = ch.game_id AND ci.challenge_id = ch.challenge_id
                               AND c.player_id = $playerId
                               AND NOT EXISTS (SELECT 1 FROM character_acceptance ca
                                                WHERE ca.game_id = ci.game_id
                                                  AND ca.challenge_id = ci.challenge_id
                                                  AND ca.character_id = ci.character_id)))
            -- A full challenge is nobody else's business: it cannot be accepted, and the only
            -- people it is still about are the ones already in it — who need it in order to see
            -- what they are waiting for, and who, if the challenger, need it to start the match.
            -- Full means every one of the game's roles is spoken for, optional ones included:
            -- those are seats a latecomer could still take, even though a start need not wait
            -- for them.
            --
            -- "Spoken for" means accepted or reserved (V22). A role held by an invitation to
            -- somebody else is not a seat this viewer can take -- `ChallengeService.accept`
            -- refuses it -- so a challenge whose every remaining role is reserved for other
            -- people is as good as full to them, open or not. Reserved for *this* viewer counts
            -- the other way: it is the one seat they are certain of, and a challenge held open
            -- for them is the last thing to hide from them.
            AND (EXISTS (SELECT 1 FROM game_role gr
                          WHERE gr.game_id = ch.game_id
                            AND NOT EXISTS (SELECT 1 FROM acceptance ac
                                             WHERE ac.game_id = ch.game_id
                                               AND ac.challenge_id = ch.challenge_id
                                               AND ac.game_role_id = gr.game_role_id)
                            AND NOT EXISTS (SELECT 1 FROM invitation held
                                             WHERE held.game_id = ch.game_id
                                               AND held.challenge_id = ch.challenge_id
                                               AND held.game_role_id = gr.game_role_id
                                               AND held.player_id <> $playerId)
                            AND NOT EXISTS (SELECT 1 FROM character_invitation held
                                             JOIN character c ON c.game_id = held.game_id
                                                             AND c.character_id = held.character_id
                                             WHERE held.game_id = ch.game_id
                                               AND held.challenge_id = ch.challenge_id
                                               AND held.game_role_id = gr.game_role_id
                                               AND c.player_id IS DISTINCT FROM $playerId))
                 OR EXISTS (SELECT 1 FROM acceptance ac
                             WHERE ac.game_id = ch.game_id AND ac.challenge_id = ch.challenge_id
                               AND ac.player_id = $playerId))
          ORDER BY ch.create_date DESC"""
            .query(
              challengeId *: gameType *: playerId *: text *: instant.opt *: float8.opt *: settings *: bool *:
                  gameRoleId *: int8.opt *: int8 *: text *: timeLimitKind *: timeLimitUnit *: bool *: bool
            )

    /** Every challenge for a game that `viewer` may see, newest first, each with how many players have accepted it.
      *
      * A challenge that has been started is excluded: it is no longer something to accept, and since starting one no
      * longer deletes it, it would otherwise sit in the list forever offering a Start that would be refused.
      *
      * A challenge that is not open is excluded unless `viewer` offered it or was invited to it (V22). Nobody else can
      * join it, so listing it would be listing an Accept the service refuses — the same reasoning as the full challenge
      * below, and the same exception for the people it is actually about. Which invitations it carries is not read
      * here: `ChallengeService.listByGame` adds them, since they are rows in another table and this query is already
      * asking three questions.
      *
      * A challenge that is full — every role of its game either accepted or reserved for somebody other than `viewer`
      * by an invitation — but not yet started is excluded too, unless `viewer` has accepted it. It is not something
      * anyone else can join, and listing it invites a click on an Accept the service would refuse. The challenger sees
      * their own throughout, since creating a challenge writes their acceptance of it.
      *
      * The count and the claimed roles come back with the challenge rather than from a call per challenge: the UI needs
      * both for every row it draws — the count to know whether a challenge has enough acceptances to be started, the
      * roles to know which ones are still free to accept as, and together whether a start would be refused for a role
      * nobody has taken.
      */
    def listByGame(id: GameId, viewer: PlayerId): IO[List[ChallengeSummary]] =
        session
            .execute(selectChallengesByGame)((id, viewer, viewer, viewer, viewer, viewer, viewer, viewer))
            .map(_.map {
                case (
                      challengeId,
                      gt,
                      challenger,
                      message,
                      start,
                      timeLimitSeconds,
                      settings,
                      isPublic,
                      roleId,
                      characterIdValue,
                      acceptances,
                      takenRoles,
                      timeLimitKind,
                      timeLimitUnit,
                      autoStart,
                      isOpen
                    ) =>
                    ChallengeSummary(
                      toChallenge(
                        challengeId,
                        (
                          gt,
                          id,
                          challenger,
                          message,
                          start,
                          timeLimitSeconds,
                          settings,
                          isPublic,
                          roleId,
                          characterIdValue,
                          timeLimitKind,
                          timeLimitUnit,
                          autoStart,
                          isOpen
                        )
                      ),
                      acceptances.toInt,
                      takenRoles.split(',').filter(_.nonEmpty).map(v => GameRoleId(v.toInt)).toSeq
                    )
            })
}
