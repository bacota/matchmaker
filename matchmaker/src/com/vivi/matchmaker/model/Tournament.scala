package com.vivi.matchmaker.model

import java.time.{Duration, Instant}
import com.vivi.matchmaker.model.tournament.TournamentDesign

/** Where a tournament is in its life (V52). */
enum TournamentStatus(val code: String, val label: String) {

    /** Being set up by its owner: nobody may enter yet. */
    case Draft extends TournamentStatus("draft", "Draft")

    /** Taking entries. */
    case Open extends TournamentStatus("open", "Open for entries")

    /** Started: its rounds are being played. */
    case Running extends TournamentStatus("running", "Under way")

    case Finished extends TournamentStatus("finished", "Finished")

    /** Called off by its owner. */
    case Cancelled extends TournamentStatus("cancelled", "Cancelled")
}

object TournamentStatus {
    def fromCode(code: String): TournamentStatus =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown tournament status '$code'"))
}

/** A tournament's settings (`tournament-design.md`, "Other Tournament Properties"), with its defaults.
  *
  * `roundDuration` is the default a stage's round duration inherits; `maxTurns` is for a game whose players move at
  * once and that has no maximum of its own.
  */
case class TournamentSettings(
    autoRounds: Boolean = false,
    friendly: Boolean = false,
    invitational: Boolean = true,
    lateEntries: Boolean = false,
    minRating: Option[Int] = None,
    maxRating: Option[Int] = None,
    entriesPerPlayer: Int = 1,
    roundDuration: Option[Duration] = None,
    maxTurns: Option[Int] = None
)

/** A tournament, without its state (V52).
  *
  * `owner` may edit it and run its rounds. `standardDesign` is the key of the standard design it was built from, or
  * `None` for one designed by hand. `roundStartedAt` and `roundDeadline` are the round being played.
  */
case class Tournament(
    gameId: GameId,
    tournamentId: TournamentId,
    name: String,
    description: String,
    owner: PlayerId,
    design: TournamentDesign,
    standardDesign: Option[String],
    settings: TournamentSettings,
    status: TournamentStatus = TournamentStatus.Draft,
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    roundStartedAt: Option[Instant] = None,
    roundDeadline: Option[Instant] = None,
    checkedAt: Option[Instant] = None
)

/** An entry in a tournament: who entered, with which character in a character game, the rating they entered at, and
  * whether they have withdrawn. `nickname` and `characterName` are for showing it by.
  */
case class Entrant(
    gameId: GameId,
    tournamentId: TournamentId,
    entrantId: EntrantId,
    playerId: PlayerId,
    characterId: Option[CharacterId],
    ratingAtEntry: Int,
    enteredAt: Instant,
    withdrawnAt: Option[Instant] = None,
    nickname: String = "",
    characterName: Option[String] = None
) {
    def withdrawn: Boolean = withdrawnAt.isDefined
}

/** Where a tournament match is: its seats choosing roles, due to be created, created, or finished and recorded. */
enum TournamentMatchStatus(val code: String) {
    case Choosing extends TournamentMatchStatus("choosing")
    case Due extends TournamentMatchStatus("due")
    case Started extends TournamentMatchStatus("started")
    case Done extends TournamentMatchStatus("done")
}

object TournamentMatchStatus {
    def fromCode(code: String): TournamentMatchStatus =
        values
            .find(_.code == code)
            .getOrElse(throw new IllegalArgumentException(s"unknown tournament match status '$code'"))
}

/** A seat of a tournament match: its entrant, the role it plays (`None` while still to be chosen), its turn to choose,
  * what choosing cost it, whether it forfeits before play, and the match seat it became.
  */
case class TournamentSeat(
    entrantId: EntrantId,
    gameRoleId: Option[GameRoleId],
    chooseOrder: Option[Int],
    chooseStartedAt: Option[Instant] = None,
    chosenAt: Option[Instant] = None,
    forfeit: Boolean = false,
    participantId: Option[ParticipantId] = None
)

/** A match of a tournament's round (V52), found by its pool and number in the tournament's state. */
case class TournamentMatch(
    gameId: GameId,
    tournamentId: TournamentId,
    poolId: Int,
    matchNo: Int,
    round: Int,
    status: TournamentMatchStatus,
    tiesAllowed: Boolean,
    matchId: Option[MatchId],
    seats: List[TournamentSeat]
)
