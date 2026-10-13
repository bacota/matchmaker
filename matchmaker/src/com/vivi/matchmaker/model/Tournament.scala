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
  * `roundDuration` is how long a round lasts, which its owner may change for a round started by hand; `maxTurns` is for
  * a game whose players move at once and that has no maximum of its own. `concurrentRounds` lets every round-robin pool
  * play all of its matches in one round. `isPublic` is whether anybody may see it listed.
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
    maxTurns: Option[Int] = None,
    concurrentRounds: Boolean = false,
    isPublic: Boolean = true
)

/** A design a game's admin offers (V52): a tournament is made from one, copying its stages. `classification` is the
  * group it is listed under where a tournament is created; a model with none is not offered on its own, being the inner
  * design of another's nesting stage. `poolSize` is the default pool size to suggest, `None` for the game's R. A model
  * that is not `active` has been deactivated by an admin, and is offered no longer.
  */
case class TournamentModel(
    gameId: GameId,
    tournamentModelId: TournamentModelId,
    name: String,
    description: String,
    classification: Option[String],
    poolSize: Option[Int],
    lateEntries: Boolean,
    design: TournamentDesign,
    active: Boolean = true
)

/** What became of a model an admin removed: `deleted`, with the `tournamentsDeleted` made from it, when nobody had
  * entered a tournament made from it; otherwise only deactivated, its tournaments left as they were.
  */
case class TournamentModelRemoval(deleted: Boolean, tournamentsDeleted: Int)

/** What an admin creates or edits a model with. */
case class TournamentModelRequest(
    name: String,
    description: String = "",
    classification: Option[String] = None,
    poolSize: Option[Int] = None,
    lateEntries: Boolean = false,
    design: TournamentDesign
)

/** A tournament, without its design or its state (V52): those are rows of their own.
  *
  * `owner` may edit it and run its rounds. `tournamentModelId` is the model it was made from, whose stages were copied
  * into it. `defaultPoolSize` is the size of a pool of any stage that sets none. `roundStartedAt` and `roundDeadline`
  * are the round being played.
  */
case class Tournament(
    gameId: GameId,
    tournamentId: TournamentId,
    name: String,
    description: String,
    owner: PlayerId,
    tournamentModelId: TournamentModelId,
    defaultPoolSize: Int,
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

/** Where a tournament match is: its seats choosing roles, due to be created, created, or over -- `match.status` `C`,
  * `D`, `P` and `F` (V52).
  */
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

/** A seat of a tournament match (V52), which is a `participant` row from the moment its pool is formed: its entrant,
  * the role it plays (`None` while still to be chosen), its turn to choose, what choosing cost it, and whether it
  * forfeits before play.
  */
case class TournamentSeat(
    participantId: ParticipantId,
    entrantId: EntrantId,
    gameRoleId: Option[GameRoleId],
    chooseOrder: Option[Int],
    chooseStartedAt: Option[Instant] = None,
    chosenAt: Option[Instant] = None,
    forfeit: Boolean = false
)

/** A match of a tournament's round (V52): a `match` row of one of its atomic pools, from the round it is played in
  * until it is over. Its status is the match's, from choosing roles to done.
  */
case class TournamentMatch(
    gameId: GameId,
    tournamentId: TournamentId,
    matchId: MatchId,
    poolId: Int,
    round: Int,
    status: TournamentMatchStatus,
    tiesAllowed: Boolean,
    seats: List[TournamentSeat]
)

/** What a tournament is created or edited with: the model it is made from, its default pool size (`None` for the
  * model's suggestion), and its settings. `owner` hands an existing tournament to another player; a new one is its
  * creator's.
  */
case class TournamentRequest(
    name: String,
    tournamentModelId: TournamentModelId,
    description: String = "",
    defaultPoolSize: Option[Int] = None,
    settings: TournamentSettings = TournamentSettings(),
    owner: Option[PlayerId] = None
)

/** A tournament as a list shows it: the tournament, its game's name, its owner's nickname, how many have entered,
  * whether the viewer owns it, has entered it, or is invited, and whether it is under way with every match of its round
  * over — so that, unless its rounds start themselves, it waits on its owner to start the next.
  */
case class TournamentSummary(
    tournament: Tournament,
    gameName: String,
    ownerNickname: String,
    entrants: Int,
    isOwner: Boolean,
    entered: Boolean,
    invited: Boolean,
    roundOver: Boolean = false
)

/** A seat of a match being played in a tournament's round, as its page shows it: the entrant, the role (once chosen),
  * whether it is this seat's turn to choose, and by when.
  */
case class TournamentSeatView(
    entrantId: EntrantId,
    role: Option[GameRoleId],
    choosing: Boolean,
    chooseBy: Option[Instant]
)

/** A match of the round being played: its pool, the match itself, where it is, and its seats. */
case class TournamentMatchView(
    poolId: Int,
    matchId: MatchId,
    status: TournamentMatchStatus,
    seats: List[TournamentSeatView]
)

/** Everything a tournament's page shows: the tournament and its design, who has entered and who is invited, its state
  * once it has started, the matches of the round being played, and what the viewer may do.
  */
case class TournamentDetail(
    summary: TournamentSummary,
    design: com.vivi.matchmaker.model.tournament.TournamentDesign,
    entries: List[Entrant],
    invited: List[PlayerName],
    state: Option[com.vivi.matchmaker.model.tournament.TournamentState],
    round: List[TournamentMatchView] = Nil
)

/** Where a player stands in a tournament they entered (V52): the entry (by its character, in a character game), its
  * seed and stratum as they stand, whether it is out, how many are still in, and its final place once it is over.
  */
case class TournamentStanding(
    gameId: GameId,
    tournamentId: TournamentId,
    tournamentName: String,
    gameName: String,
    status: TournamentStatus,
    entrantId: EntrantId,
    characterName: Option[String],
    seed: Option[Int],
    stratum: Option[Int],
    eliminated: Boolean,
    entrants: Int,
    finalRank: Option[Int]
)

/** A player by name, for a list that shows people. */
case class PlayerName(playerId: PlayerId, nickname: String)

/** A seat whose turn it is to choose a role, for the player who is to: where, and by when. */
case class TournamentChoice(
    gameId: GameId,
    tournamentId: TournamentId,
    tournamentName: String,
    gameName: String,
    matchId: MatchId,
    chooseBy: Option[Instant]
)

/** A rank the owner gives one seat of a match they decide by hand. */
case class EntrantRank(entrantId: EntrantId, rank: Int)
