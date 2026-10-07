package com.vivi.matchmaker.model

import java.time.{Duration, Instant}

/** Which of the design's three families a tournament is: a ladder, an elimination (pools and rounds to a final), or a
  * cyclic one (elimination repeated, cycle after cycle). Stored as `code` in `tournament.tournament_class`.
  */
enum TournamentClass(val code: String, val label: String) {
    case Ladder extends TournamentClass("LADDER", "Ladder")
    case Elimination extends TournamentClass("ELIM", "Elimination")
    case Cyclic extends TournamentClass("CYCLIC", "Cyclic")
}

object TournamentClass {
    def fromCode(code: String): TournamentClass =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown tournament_class '$code'"))
}

/** How an elimination or cyclic tournament is run. Stored as `code` in `elimination_tournament.tournament_type`. */
enum TournamentType(val code: String, val label: String) {
    case SingleElim extends TournamentType("SingleElim", "Single elimination")
    case DoubleElim extends TournamentType("DoubleElim", "Double elimination")
    case ZeroElim extends TournamentType("ZeroElim", "Zero elimination")
    case Repechage extends TournamentType("Repechage", "Repechage")
    case RoundRobin extends TournamentType("RoundRobin", "Round robin")
    case Playoff extends TournamentType("Playoff", "Playoff")
}

object TournamentType {
    def fromCode(code: String): TournamentType =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown tournament_type '$code'"))
}

/** How players level on points in a pool are told apart: by score differential, or by playing again with no tie
  * allowed. Stored as `code`.
  */
enum Tiebreaker(val code: String, val label: String) {
    case Score extends Tiebreaker("SCORE", "Score")
    case Rematch extends Tiebreaker("REMATCH", "Rematch")
}

object Tiebreaker {
    def fromCode(code: String): Tiebreaker =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown tiebreaker '$code'"))
}

/** The settings an elimination or cyclic tournament has and a ladder does not (`elimination_tournament`).
  *
  * `poolSize` is how many players a pool holds, and `minPoolAdvance` how many of them go through at least.
  * `eliminationRotations` is a playoff's rotations in its elimination rounds.
  */
case class EliminationSettings(
    tournamentType: TournamentType,
    poolSize: Int,
    minPoolAdvance: Int = 1,
    eliminationRotations: Int = 0,
    tiebreaker: Tiebreaker = Tiebreaker.Score
)

/** A tournament: a field of entries, seeded, played in rounds.
  *
  * `owner` runs it — its creator at first, and whoever a game's admin hands it to after. `live` is whether its matches
  * are live, read onto each round as the round starts, so an edit takes effect from the next round. `roundDuration` is
  * how long a round lasts unless the round says otherwise; each player's chess clock in a match is half of it.
  * `elimination` is present for an elimination or cyclic tournament and absent for a ladder.
  *
  * `maxEntriesPerPlayer` is 1 for now: the database holds it to that.
  */
case class Tournament(
    gameId: GameId,
    tournamentId: TournamentId,
    tournamentClass: TournamentClass,
    name: String,
    owner: PlayerId,
    invitational: Boolean,
    roundDuration: Duration,
    elimination: Option[EliminationSettings] = None,
    isPublic: Boolean = false,
    friendly: Boolean = true,
    live: Boolean = false,
    maxEntriesPerPlayer: Int = 1,
    minRating: Option[Int] = None,
    maxRating: Option[Int] = None,
    rotations: Int = 0,
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None
) {
    def started: Boolean = startedAt.isDefined
    def ended: Boolean = endedAt.isDefined
}

/** A sign-up: who entered, and — in a character game — the character entered, whose owner when each match is created
  * plays it.
  */
case class TournamentEntry(
    gameId: GameId,
    tournamentId: TournamentId,
    entryId: EntryId,
    playerId: PlayerId,
    characterId: Option[CharacterId] = None
)

/** An entry as the tournament plays it, seeded. `seed` is overwritten as the tournament goes (each round's reseeding);
  * `initialSeed` is the one it started with, and never changes.
  */
case class TournamentParticipant(
    gameId: GameId,
    tournamentId: TournamentId,
    tournamentParticipantId: TournamentParticipantId,
    entryId: EntryId,
    seed: Int,
    initialSeed: Int,
    withdrawn: Boolean = false,
    finalRank: Option[Int] = None,
    ladderRank: Option[Int] = None
)

/** One round. The optional settings override the tournament's for this round; `None` is "as the tournament". `live` is
  * the tournament's setting as it stood when the round started.
  */
case class TournamentRound(
    gameId: GameId,
    tournamentId: TournamentId,
    round: Int,
    cycle: Int = 1,
    reseed: Boolean = false,
    live: Boolean = false,
    duration: Option[Duration] = None,
    rotations: Option[Int] = None,
    poolSize: Option[Int] = None,
    minPoolAdvance: Option[Int] = None,
    tiebreaker: Option[Tiebreaker] = None,
    startedAt: Option[Instant] = None,
    completedAt: Option[Instant] = None,
    checkedAt: Option[Instant] = None
) {
    def started: Boolean = startedAt.isDefined
    def completed: Boolean = completedAt.isDefined
}

/** A pool in a round: a group of slots whose occupants play each other. `position` is its place in the round, from 1 —
  * what the page calls it, since its id is never shown.
  */
case class Fixture(
    gameId: GameId,
    tournamentId: TournamentId,
    fixtureId: FixtureId,
    round: Int,
    position: Int
)

/** How a slot is filled: by nobody, by the holder of a seed when its round starts, or by whoever finished at `rank` in
  * a pool of an earlier round.
  */
enum SlotSource {
    case Bye
    case Seed(seed: Int)
    case Winner(prevFixture: FixtureId, rank: Int)
}

/** A place in a pool. `occupant` is who fills it, settled when its round starts and never changed after — `None` before
  * then, and for a bye.
  */
case class FixtureSlot(
    gameId: GameId,
    tournamentId: TournamentId,
    fixtureId: FixtureId,
    slotId: SlotId,
    source: SlotSource,
    occupant: Option[TournamentParticipantId] = None
)

/** Where a tournament match comes from: its pool, and its number among the pool's matches. */
case class MatchFixture(tournamentId: TournamentId, fixtureId: FixtureId, matchNo: Int)

/** A tournament seat: the slot it was filled from, and the seed its player held as they sat down. */
case class TournamentSeat(tournamentId: TournamentId, fixtureId: FixtureId, slotId: SlotId, seed: Int)

/** An entrant as the tournament page shows it: who entered, the character entered in a character game, and — once the
  * tournament has started — its seeded place in the field.
  */
case class TournamentEntrant(
    entryId: EntryId,
    player: PublicPlayer,
    character: Option[CharacterName] = None,
    participant: Option[TournamentParticipant] = None
)

/** A pool and its slots. */
case class TournamentPool(fixture: Fixture, slots: List[FixtureSlot])

/** Everything the tournament page shows: the tournament and its owner, the field, the rounds and their pools, and — for
  * its owner — who has been invited.
  */
case class TournamentDetail(
    tournament: Tournament,
    owner: PublicPlayer,
    entrants: List[TournamentEntrant],
    rounds: List[TournamentRound] = Nil,
    pools: List[TournamentPool] = Nil,
    invitedPlayers: List[PublicPlayer] = Nil,
    invitedCharacters: List[CharacterName] = Nil,
    progress: TournamentProgress = TournamentProgress()
)

/** A tournament in a player's own list, with what it is to them. */
case class TournamentSummary(tournament: Tournament, owned: Boolean, entered: Boolean, invited: Boolean)

/** What the owner may set for one round as they start it, over the tournament's own settings. A pool size can be set
  * only for the first round, which lays the tournament out again; the rest apply to the round they are set for.
  */
case class RoundOverrides(
    duration: Option[Duration] = None,
    rotations: Option[Int] = None,
    poolSize: Option[Int] = None,
    minPoolAdvance: Option[Int] = None,
    tiebreaker: Option[Tiebreaker] = None
)

/** What starting, checking or resuming a round set going: how many matches were queued to be made or checked. */
case class RoundWork(queued: Int)

/** A seat of a tournament match as the tournament page shows it: whose it is, and how it finished — `manual` when the
  * tournament's owner set the rank on cancelling the match (D12).
  */
case class TournamentSeatView(
    participantId: ParticipantId,
    entrant: Option[TournamentParticipantId],
    rank: Option[Int] = None,
    manual: Boolean = false
)

/** A match made for a pool: its number there, and how it stands. */
case class TournamentMatchView(
    fixtureId: FixtureId,
    matchNo: Int,
    matchId: MatchId,
    completed: Boolean,
    cancelled: Boolean,
    seats: List[TournamentSeatView]
)

/** One entrant's line in a pool's standings: points from every match's ranks, and the score differential. */
case class StandingLine(entrant: TournamentParticipantId, points: Int, differential: Double)

/** A pool's standings, best first. */
case class PoolStandings(fixtureId: FixtureId, lines: List[StandingLine])

/** How a tournament's started rounds stand: every match made for them, and every pool's standings. */
case class TournamentProgress(matches: List[TournamentMatchView] = Nil, standings: List[PoolStandings] = Nil)
