package com.vivi.matchmaker.api

import upickle.default.{ReadWriter, readwriter, macroRW}
import com.vivi.matchmaker.model._
import Json.given

/** The tournament half of the wire format, beside [[Json]] rather than in it: one object holding every codec grows past
  * what the Scala.js compiler's stack will take while it expands them.
  *
  * The design and the state are the model's own codecs, which the server's services share, re-exported by name rather
  * than imported so that nothing here is ambiguous. Import `TournamentJson.given` beside `Json.given`.
  */
object TournamentJson {
    given ReadWriter[com.vivi.matchmaker.model.tournament.TournamentDesign] =
        com.vivi.matchmaker.model.tournament.TournamentCodecs.designRW
    given ReadWriter[com.vivi.matchmaker.model.tournament.TournamentState] =
        com.vivi.matchmaker.model.tournament.TournamentCodecs.stateRW
    given ReadWriter[TournamentStatus] = readwriter[String].bimap(_.code, TournamentStatus.fromCode)
    given ReadWriter[TournamentMatchStatus] = readwriter[String].bimap(_.code, TournamentMatchStatus.fromCode)
    given ReadWriter[TournamentSettings] = macroRW
    given ReadWriter[Tournament] = macroRW
    given ReadWriter[TournamentEntry] = macroRW
    given ReadWriter[TournamentRequest] = macroRW
    given ReadWriter[TournamentModel] = macroRW
    given ReadWriter[TournamentModelSync] = macroRW
    given ReadWriter[Entrant] = macroRW
    given ReadWriter[PlayerName] = macroRW
    given ReadWriter[InvitedCharacter] = macroRW
    given ReadWriter[TournamentSummary] = macroRW
    given ReadWriter[TournamentSeatView] = macroRW
    given ReadWriter[CreateFailure] = macroRW
    given ReadWriter[TournamentMatchView] = macroRW
    given ReadWriter[TournamentDetail] = macroRW
    given ReadWriter[TournamentStanding] = macroRW

    /** A challenge as it is offered, and as its screens list it. */

    /** Entering a tournament: with which character, in a character game. */
    case class EnterRequest(characterId: Option[CharacterId] = None)
    given ReadWriter[EnterRequest] = macroRW

    given ReadWriter[TournamentChoice] = macroRW
    given ReadWriter[EntrantRank] = macroRW

    /** A seat's choice of role. */
    case class ChooseRoleRequest(gameRoleId: GameRoleId)
    given ReadWriter[ChooseRoleRequest] = macroRW

    /** The owner's ranks for a match decided by hand, one per seat. */
    case class DecideRequest(ranks: List[EntrantRank])
    given ReadWriter[DecideRequest] = macroRW

    /** The round a start began, or none if it finished the tournament. */
    case class RoundStarted(round: Option[Int])
    given ReadWriter[RoundStarted] = macroRW

    /** Inviting a player to a tournament of a plain game. */
    case class TournamentInvite(playerId: PlayerId)
    given ReadWriter[TournamentInvite] = macroRW

    /** Inviting a character to a tournament of a character game. */
    case class TournamentCharacterInvite(characterId: CharacterId)
    given ReadWriter[TournamentCharacterInvite] = macroRW

}
