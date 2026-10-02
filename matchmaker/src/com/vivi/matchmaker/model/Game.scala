package com.vivi.matchmaker.model

case class GameParameterValue[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    value: T
)

/** A setting a challenger picks for the game's engine — how many rounds a bout is, say.
  *
  * `name` is what the engine is sent: the key in a challenge's `settings`, and so in what the engine receives at a
  * start. `displayName` is what a player is shown for it — "Rounds" for `rounds`, "Board size" for `board_size`. Often
  * the two are the same, but both are always there.
  */
case class GameParameter[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    name: String,
    defaultValue: Option[T],
    values: Seq[GameParameterValue[T]],
    displayName: String
)

/** A seat in a match. `name` is what the engine is told the seat is — the role it is sent with each player at a start.
  * `displayName` is what a player is shown for it, wherever matchmaker itself names the seat. Often the two are the
  * same, but both are always there.
  */
case class GameRole(
    gameRoleId: GameRoleId,
    gameId: GameId,
    name: String,
    optional: Boolean,
    displayName: String
)

/** Whether a game's challenges/acceptances/participants require an attached character. Mirrors the `game_type`
  * discriminator column (`'C'`/`'P'`) that `game` and every table split into a `character_*` sibling (`challenge`,
  * `acceptance`, `participant`) carry.
  */
enum GameType(val code: Char) {
    case Character extends GameType('C')
    case Plain extends GameType('P')
}
object GameType {
    def fromCode(c: Char): GameType =
        values.find(_.code == c).getOrElse(throw new IllegalArgumentException(s"unknown game_type code '$c'"))
}

/** What matchmaker does when a player's turn runs out.
  *
  * A property of the game, set by the admin who registers it: the challenge decides how long a turn may take, and the
  * game decides what happens when one takes longer. Stored as the `code` in `game.timeout_action`.
  *
  * `Forfeit` is the only action so far, and the only one the enforcement in `GameEngineService` knows how to carry out
  * — the match ends and whoever was still playing wins. The enum exists ahead of the second value because the column,
  * the API and the admin's dropdown all have to name the choice, and a boolean would have to be replaced the moment
  * there was one.
  */
enum TimeoutAction(val code: String, val label: String) {

    /** The player who ran out loses; everyone else wins by forfeit. */
    case Forfeit extends TimeoutAction("FORFEIT", "Forfeit the match")
}

object TimeoutAction {
    def fromCode(code: String): TimeoutAction =
        values
            .find(_.code == code)
            .getOrElse(throw new IllegalArgumentException(s"unknown timeout_action '$code'"))
}

case class Game(
    gameId: GameId,
    gameType: GameType,
    // The game's stable handle: what the engine is told at a start, and what the local
    // registration scripts and migrations find it by. Players are shown `displayName`, which an admin may change without detaching
    // the game from any of those.
    name: String,
    displayName: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    // The engine's identity: the name its API key is filed under in ENGINE_API_KEYS. A request
    // made on the game's behalf (a move, a result, a character it made) is authorized by its key
    // resolving to this name.
    externalId: String,
    // What happens when a player's turn runs out. Defaulted rather than required, because every
    // game had this behaviour decided for it by the migration that added the column, and
    // Forfeit is what it decided.
    timeoutAction: TimeoutAction = TimeoutAction.Forfeit,
    /* Where a player makes a character for this game (V28), for a character game whose engine offers
     * a page for it. Characters are made in their engine, which then reports each one to matchmaker,
     * so this is where matchmaker's UI sends a player who has none. `None` for a plain game, and for
     * a character game nobody has given one. */
    characterUrl: Option[String] = None
)
