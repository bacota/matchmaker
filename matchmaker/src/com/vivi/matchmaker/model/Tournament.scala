package com.vivi.matchmaker.model

import java.time.{Duration, Instant}
import com.vivi.matchmaker.model.tournament.TournamentDesign

/** Where a tournament is in its life. */
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
  * `roundDuration` is how long a round lasts, which its owner may change for a round started by hand, and which is also
  * every match's time limit (`Clock.forMatch`); `maxTurns` is for a game whose players move at once and that has no
  * maximum of its own. `isConcurrentRounds` lets every round-robin pool play all of its matches in one round.
  * `isPublic` is whether anybody may see it listed, and its matches watched.
  *
  * `gameSettings` is the owner's choice of each game parameter, as JSON, copied to every match and so to the engine;
  * `isLive` makes every match live, its turns and clock the engine's to run. `start` is when it is meant to be played,
  * shown and nothing more. `isChooseRoles` has every match's seats choose their roles, whatever the game's preference
  * levels. `maxEntrants` is the most entries it takes, and with `isAutoStart` it starts by itself when they are
  * reached.
  */
case class TournamentSettings(
    isAutoRounds: Boolean = false,
    isFriendly: Boolean = false,
    isInvitational: Boolean = true,
    isLateEntries: Boolean = false,
    minRating: Option[Int] = None,
    maxRating: Option[Int] = None,
    entriesPerPlayer: Int = 1,
    roundDuration: Option[Duration] = None,
    maxTurns: Option[Int] = None,
    isConcurrentRounds: Boolean = false,
    isPublic: Boolean = true,
    gameSettings: String = "{}",
    isLive: Boolean = false,
    start: Option[Instant] = None,
    isChooseRoles: Boolean = false,
    maxEntrants: Option[Int] = None,
    isAutoStart: Boolean = false
)

/** A design every game may offer, which a tournament is made from by copying its stages: one of the standard formats
  * the code defines ([[com.vivi.matchmaker.model.tournament.standard.StandardModels]]), as a site admin's sync last
  * wrote it, at `version`. `classification` is the group it is listed under where a tournament is created; a model with
  * none is never offered on its own, being the inner design of another's nesting stage. `poolSize` is the default pool
  * size to suggest, `None` for the game's R. `isChooseRoles` is what a tournament made from it starts with: every
  * match's seats choose their roles. A model that is not `isActive` is one the code no longer defines, kept for the
  * tournaments made from it.
  */
case class TournamentModel(
    tournamentModelId: TournamentModelId,
    version: Int,
    name: String,
    description: String,
    classification: Option[String],
    poolSize: Option[Int],
    isLateEntries: Boolean,
    design: TournamentDesign,
    isActive: Boolean = true,
    isChooseRoles: Boolean = false
)

/** What a site admin's sync of the models with the code did: the models it `added`, those it `updated` to a newer
  * version (or brought back), those it `deactivated` because the code no longer defines them and a tournament made from
  * them has entrants, and those it `deleted`, with the tournaments nobody had entered, because nothing needed them.
  */
case class TournamentModelSync(
    added: List[TournamentModelId],
    updated: List[TournamentModelId],
    deactivated: List[TournamentModelId],
    deleted: List[TournamentModelId]
)

/** A tournament, without its design or its state: those are rows of their own.
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

/** An entry in a tournament: a player's in a plain game, a character's in a character game, the rating it entered at,
  * and whether it has withdrawn. `playerId` is who plays it now -- the player, or the character's current owner, and
  * `None` for a character nobody owns -- and `nickname` theirs. `characterName` is for showing a character entry by.
  */
case class Entrant(
    gameId: GameId,
    tournamentId: TournamentId,
    entrantId: EntrantId,
    playerId: Option[PlayerId],
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
  * `D`, `P` and `F`.
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

/** A seat of a tournament match, which is a `participant` row from the moment its pool is formed: its entrant, the role
  * it plays (`None` while still to be chosen), its turn to choose, what choosing cost it, and whether it forfeits
  * before play.
  */
case class TournamentSeat(
    participantId: ParticipantId,
    entrantId: EntrantId,
    gameRoleId: Option[GameRoleId],
    chooseOrder: Option[Int],
    chooseStartedAt: Option[Instant] = None,
    chosenAt: Option[Instant] = None,
    isForfeit: Boolean = false
)

/** A match of a tournament's round: a `match` row of one of its atomic pools, from the round it is played in until it
  * is over. Its status is the match's, from choosing roles to done.
  */
case class TournamentMatch(
    gameId: GameId,
    tournamentId: TournamentId,
    matchId: MatchId,
    poolId: Int,
    round: Int,
    status: TournamentMatchStatus,
    isTiesAllowed: Boolean,
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
    isEntered: Boolean,
    isInvited: Boolean,
    isRoundOver: Boolean = false
)

/** A seat of a match being played in a tournament's round, as its page shows it: the entrant, the role (once chosen),
  * whether it is this seat's turn to choose, and by when.
  */
case class TournamentSeatView(
    entrantId: EntrantId,
    role: Option[GameRoleId],
    isChoosing: Boolean,
    chooseBy: Option[Instant]
)

/** A match of the round being played: its pool, the match itself, where it is, and its seats. */
case class TournamentMatchView(
    poolId: Int,
    matchId: MatchId,
    status: TournamentMatchStatus,
    seats: List[TournamentSeatView]
)

/** Everything a tournament's page shows: the tournament and its design, who has entered and who is invited -- players
  * in a plain game, characters in a character game -- its state once it has started, the matches of the round being
  * played, and what the viewer may do.
  */
case class TournamentDetail(
    summary: TournamentSummary,
    /** Its game's type, which decides whether players or characters enter and are invited: carried here because a
      * client may not have the game to ask -- a direct link, or a game no longer active.
      */
    gameType: GameType,
    design: com.vivi.matchmaker.model.tournament.TournamentDesign,
    entries: List[Entrant],
    invited: List[PlayerName],
    state: Option[com.vivi.matchmaker.model.tournament.TournamentState],
    round: List[TournamentMatchView] = Nil,
    invitedCharacters: List[InvitedCharacter] = Nil
)

/** A character invited to a tournament of a character game, with whoever owns it now: the invitation is the
  * character's, so it is its owner at the time who may answer it. `owner` is `None` for a character nobody owns.
  */
case class InvitedCharacter(character: CharacterName, owner: Option[PlayerName])

/** Where a player stands in a tournament they entered: the entry (by its character, in a character game), its seed and
  * stratum as they stand, whether it is out, how many are still in, and its final place once it is over.
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
    isEliminated: Boolean,
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

/** What a challenge is offered with: a tournament of the challenge model, made, opened and entered in one go.
  *
  * `characterId` is the challenger's character in a character game, and `isPlays` false a game's admin offering a match
  * they will not play in, who enters nothing. `poolSize` is how many it seats, between the game's required roles and
  * all its roles, the game's R if not given, and also the most entries it takes. `roundDuration` is the match's time
  * limit (`Clock.forMatch`). `isOpen` false makes it invitational: only those invited may enter -- `invitations`,
  * players, in a plain game, and `characterInvitations`, characters, in a character game.
  */
case class ChallengeRequest(
    characterId: Option[CharacterId] = None,
    isPlays: Boolean = true,
    poolSize: Option[Int] = None,
    roundDuration: Option[Duration] = None,
    gameSettings: String = "{}",
    isLive: Boolean = false,
    isFriendly: Boolean = true,
    isPublic: Boolean = false,
    start: Option[Instant] = None,
    isAutoStart: Boolean = false,
    isOpen: Boolean = true,
    invitations: List[PlayerId] = Nil,
    characterInvitations: List[CharacterId] = Nil
)

/** A challenge as its screens list it: the tournament as a list shows it, who has entered, and who is invited --
  * players in a plain game, characters in a character game.
  */
case class ChallengeSummary(
    summary: TournamentSummary,
    entries: List[Entrant],
    invited: List[PlayerName],
    invitedCharacters: List[InvitedCharacter] = Nil
)
