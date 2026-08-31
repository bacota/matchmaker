package com.vivi.matchmaker.model

case class GameParameterValue[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    value: T
)

case class GameParameter[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    name: String,
    defaultValue: Option[T],
    values: Seq[GameParameterValue[T]]
)

case class GameRole(
    gameRoleId: GameRoleId,
    gameId: GameId,
    name: String,
    optional: Boolean
)

/** Whether a game's challenges/acceptances/participants require an attached character.
  * Mirrors the `game_type` discriminator column (`'C'`/`'P'`) that `game` and every table
  * split into a `character_*` sibling (`open_challenge`, `acceptance`, `participant`) carry.
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
  * A property of the game, set by the admin who registers it: the challenge decides how long a
  * turn may take, and the game decides what happens when one takes longer. Stored as the `code`
  * in `game.timeout_action`.
  *
  * `Forfeit` is the only action so far, and the only one the enforcement in `GameEngineService`
  * knows how to carry out — the match ends and whoever was still playing wins. The enum exists
  * ahead of the second value because the column, the API and the admin's dropdown all have to
  * name the choice, and a boolean would have to be replaced the moment there was one.
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
    name: String,
    description: String,
    url: String,
    active: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    // Shared secret identifying the game itself, used to authorize requests made on the
    // game's behalf (e.g. creating or updating a character).
    externalId: String,
    // What happens when a player's turn runs out. Defaulted rather than required, because every
    // game had this behaviour decided for it by the migration that added the column, and
    // Forfeit is what it decided.
    timeoutAction: TimeoutAction = TimeoutAction.Forfeit
)
