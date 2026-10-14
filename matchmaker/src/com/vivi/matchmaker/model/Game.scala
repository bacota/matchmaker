package com.vivi.matchmaker.model

case class GameParameterValue[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    value: T
)

/** A setting a challenge or tournament picks for the game's engine — how many rounds a bout is, say.
  *
  * `name` is what the engine is sent: the key in a tournament's `settings`, and so in what the engine receives as a
  * match is created. `displayName` is what a player is shown for it — "Rounds" for `rounds`, "Board size" for
  * `board_size`. Often the two are the same, but both are always there.
  */
case class GameParameter[T](
    gameId: GameId,
    gameParameterId: GameParameterId,
    name: String,
    defaultValue: Option[T],
    values: Seq[GameParameterValue[T]],
    displayName: String
)

/** Whether a match needs a role filled.
  *
  * `Recommended` is a role a match can go without, as an optional one can, but which counts toward the size of a
  * tournament pool: a game's "R" is its required and recommended roles together. Outside tournaments the two are the
  * same, so everything that asks whether a start must wait for a role asks [[GameRole.optional]].
  */
enum RoleType(val code: String, val label: String) {
    case Required extends RoleType("required", "Required")
    case Recommended extends RoleType("recommended", "Recommended")
    case Optional extends RoleType("optional", "Optional")
}

object RoleType {
    def fromCode(code: String): RoleType =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown game_role.type '$code'"))
}

/** How a game's players take their turns: one after another, or all at once. A tournament spends a round's time
  * differently for each — a chess clock per seat for `Sequential`, a limit per turn for `Simultaneous`.
  */
enum MoveType(val code: String, val label: String) {
    case Sequential extends MoveType("seq", "Sequential")
    case Simultaneous extends MoveType("sim", "Simultaneous")
}

object MoveType {
    def fromCode(code: String): MoveType =
        values.find(_.code == code).getOrElse(throw new IllegalArgumentException(s"unknown game.move_type '$code'"))
}

/** A seat in a match. `name` is what the engine is told the seat is — the role it is sent with each player at a start.
  * `displayName` is what a player is shown for it, wherever matchmaker itself names the seat. Often the two are the
  * same, but both are always there.
  *
  * `preferenceLevel` ranks the roles a tournament hands out by seed: the highest level goes to the highest seed. `None`
  * for a role with no preference, which is every role until an admin gives it one.
  */
case class GameRole(
    gameRoleId: GameRoleId,
    gameId: GameId,
    name: String,
    roleType: RoleType,
    displayName: String,
    preferenceLevel: Option[Int] = None
) {

    /** Whether a match may start with this role empty: true of a recommended role as well as an optional one. */
    def optional: Boolean = roleType != RoleType.Required
}

/** Whether a game's entries and seats require an attached character. Mirrors the `game_type` discriminator column
  * (`'C'`/`'P'`) that `game` and every table split into a `character_*` sibling (`entrant`, `participant`) carry.
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
  * A property of the game, set by the admin who registers it: the tournament decides how long a turn may take, and the
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
    isActive: Boolean,
    roles: Seq[GameRole],
    parameters: Seq[GameParameter[_]],
    // The engine's identity. A request made on the game's behalf (a move, a result, a character
    // it made) is authorized by the API key it carries being one stored with a game of this
    // identity.
    externalId: String,
    // What happens when a player's turn runs out. Defaulted rather than required, because every
    // game had this behaviour decided for it by the migration that added the column, and
    // Forfeit is what it decided.
    timeoutAction: TimeoutAction = TimeoutAction.Forfeit,
    /* Where a player makes a character for this game, for a character game whose engine offers
     * a page for it. Characters are made in their engine, which then reports each one to matchmaker,
     * so this is where matchmaker's UI sends a player who has none. `None` for a plain game, and for
     * a character game nobody has given one. */
    characterUrl: Option[String] = None,
    /* Whether the game has an engine API key stored — never the key itself, which no read of a
     * game carries. What the admin form shows in its place, so that an admin can tell a game whose
     * engine will refuse every call from one that is ready. Read-only: a save sets the key, not this. */
    hasApiKey: Boolean = false,
    /* The admin saying that no role of the game has any advantage in winning it. Unticked, a
     * rated match moves its players' ratings in the roles they played as well as overall, and each
     * role has a leaderboard of its own. */
    isUnimportantRoles: Boolean = false,
    /* Whether players move in turn or all at once. Sequential for every game until an admin says
     * otherwise. */
    moveType: MoveType = MoveType.Sequential,
    /* How many turns a simultaneous game lasts, for a tournament to divide a round's time among.
     * `None` where the game does not say, in which case a tournament must. */
    maxTurns: Option[Int] = None
) {

    /** The game's "R": how many seats a tournament pool is sized by — its required and recommended roles. */
    def poolSeats: Int = roles.count(_.roleType != RoleType.Optional)

    /** How many seats must be filled for a match to start. */
    def requiredSeats: Int = roles.count(_.roleType == RoleType.Required)
}
