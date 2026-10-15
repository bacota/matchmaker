package com.vivi.hilo

import upickle.default.{ReadWriter, macroRW}
import java.time.Instant
import com.vivi.engine.{Game, MatchLike, Outcome, ResultText, SeatLike, TurnClock, TurnLike}

/** A player's place in a match. `cognitoId` is who may pick in it; `participantId` is matchmaker's key for it.
  * `nickname` is what the player is shown as, as matchmaker sent it.
  */
case class Player(cognitoId: String, participantId: Long, nickname: Option[String] = None) extends SeatLike

/** One number picked: by whom, in which round, when, and when that player's clock started for it. A pick is this game's
  * whole move.
  */
case class Pick(participantId: Long, number: Int, takenAt: Instant, startedAt: Instant) extends TurnLike

/** The rules: every player picks a number from 1 to the number of players, all at once, and the highest number that
  * nobody else picked wins. A round with no number picked by one player alone is played again.
  */
object Rules {

    /** Who won a round: whoever picked the highest number picked once. `None` when every number was picked twice or
      * more.
      */
    def winnerOf(round: List[Pick]): Option[Pick] =
        round.groupBy(_.number).collect { case (_, only :: Nil) => only }.maxByOption(_.number)
}

/** A match: its players, the rounds played and the picks made so far in the round being played.
  *
  * `rounds` are the rounds every player picked in, oldest first; all but the last were played again, since nobody won
  * them. `current` is the round being played, whose picks stay hidden until the last player has picked.
  */
case class HiloMatch(
    matchId: String,
    players: List[Player],
    rounds: List[List[Pick]],
    current: List[Pick],
    isPublic: Boolean,
    isCompleted: Boolean,
    createdAt: Instant,
    moveCallbackUrl: Option[String],
    resultsCallbackUrl: Option[String],
    clock: Option[TurnClock] = None,
    override val gameDisplayName: Option[String] = None,
    override val description: Option[String] = None
) extends MatchLike {

    /** The highest number a player may pick: one for each player. */
    def highest: Int = players.size

    /** What a player is called: their nickname, or their place at the table. */
    def nameOf(p: Player): String =
        p.nickname.map(_.trim).filter(_.nonEmpty).getOrElse(s"Player ${players.indexOf(p) + 1}")

    def playerFor(cognitoId: String): Option[Player] = players.find(_.cognitoId == cognitoId)

    def hasPicked(p: Player): Boolean = current.exists(_.participantId == p.participantId)

    def pickOf(p: Player): Option[Pick] = current.find(_.participantId == p.participantId)

    def hasRunOut: Boolean = clock.exists(_.hasRunOut)

    /** The round that won the match, once one has. Only the last can have: a won round ends the match. */
    def winningPick: Option[Pick] = rounds.lastOption.flatMap(Rules.winnerOf)

    def isOver: Boolean = hasRunOut || winningPick.isDefined

    /** Everyone yet to pick in the round being played, and nobody once the match is over. */
    def pending: List[Player] = if (isOver) Nil else players.filterNot(hasPicked)

    /** When the round being played began: when the last one resolved, or the match was created. */
    def roundStartedAt: Instant = rounds.lastOption.flatMap(_.map(_.takenAt).maxOption).getOrElse(createdAt)

    /** Who won: whoever picked the highest number nobody else did — or, in a match the clock ended, everyone who was
      * not caught out by it.
      */
    def winners: List[Player] =
        if (hasRunOut) players.filter(p => clock.flatMap(_.outcomeOf(p.participantId)).contains(Outcome.Win))
        else winningPick.toList.flatMap(w => players.find(_.participantId == w.participantId))

    def isWinner(p: Player): Boolean = winners.exists(_.participantId == p.participantId)
}

/** Hilo as matchmaker sees it: a game without roles for two or more players, all picking at once. Nobody waits for
  * anybody, so every player's clock starts when the round does.
  */
object HiloMatch extends Game[HiloMatch, Player, Pick] {

    def create(request: Protocol.CreateGameRequest, now: Instant): Either[String, HiloMatch] =
        if (request.players.sizeIs < 2) Left(s"hilo needs at least two players; ${request.players.size} were sent")
        else if (request.players.map(_.cognitoId).distinct.sizeIs != request.players.size)
            Left("every place must belong to a different player")
        else
            Right(
              HiloMatch(
                matchId = request.matchId,
                // In the order matchmaker sends them, which is all a place is here: there are no roles.
                players = request.players.map(p => Player(p.cognitoId, p.participantId, p.nickname)),
                rounds = Nil,
                current = Nil,
                isPublic = request.isPublic,
                isCompleted = false,
                createdAt = now,
                moveCallbackUrl = request.moveCallbackUrl,
                resultsCallbackUrl = request.resultsCallbackUrl,
                gameDisplayName = request.gameDisplayName,
                description = request.description
              )
            )

    override def seatName: String = "place in this match"

    def seats(m: HiloMatch): List[Player] = m.players

    def isOver(m: HiloMatch): Boolean = m.isOver

    def markCompleted(m: HiloMatch): HiloMatch = m.copy(isCompleted = true)

    def pending(m: HiloMatch): List[Player] = m.pending

    def clockStartedAt(m: HiloMatch): Instant = m.roundStartedAt

    def turns(m: HiloMatch): List[Pick] = m.rounds.flatten ++ m.current

    def sequence(m: HiloMatch): Long = turns(m).size.toLong

    /** A win for the winner and a loss for everyone else. A round nobody wins is played again, so a match is never
      * drawn. A match the clock ended is as the clock says: a loss for whoever ran out, a win for everyone else.
      */
    def outcome(m: HiloMatch, seat: Player): Outcome =
        m.clock
            .flatMap(_.outcomeOf(seat.participantId))
            .getOrElse(if (m.isWinner(seat)) Outcome.Win else Outcome.Loss)

    def scores(m: HiloMatch, seat: Player): Map[String, ujson.Value] =
        Map(
          "pick" -> m.rounds.lastOption
              .flatMap(_.find(_.participantId == seat.participantId))
              .fold(ujson.Null)(p => ujson.Num(p.number)),
          "rounds" -> ujson.Num(m.rounds.size),
          // What a tournament's score tiebreak reads: 1 for the winner, 0 for everyone else.
          "score" -> ujson.Num(if (m.isWinner(seat)) 1 else 0)
        )

    /** "<strong>Ann</strong> wins with 3, after 2 rounds." */
    override def summary(m: HiloMatch): Option[String] =
        Option.when(m.isOver) {
            def who(p: Player) = ResultText.name(p.nickname, m.nameOf(p))
            val after = m.rounds.size match {
                case 1 => ""
                case n => s", after $n rounds"
            }
            (m.winners, m.winningPick) match {
                case (Nil, _) if m.hasRunOut      => "Nobody picked in time. Nobody wins."
                case (w :: Nil, _) if m.hasRunOut => s"${who(w)} wins: the others did not pick in time."
                case (ws, _) if m.hasRunOut => s"${ws.map(who).mkString(" and ")} win: the others did not pick in time."
                case (w :: Nil, Some(p)) =>
                    s"${who(w)} wins with ${p.number}, the highest number nobody else picked$after."
                case _ => "Nobody wins."
            }
        }

    def clock(m: HiloMatch): Option[TurnClock] = m.clock

    def withClock(m: HiloMatch, clock: TurnClock): HiloMatch = m.copy(clock = Some(clock))

    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)
    given ReadWriter[Player] = macroRW
    given ReadWriter[Pick] = macroRW
    given ReadWriter[HiloMatch] = macroRW
}
