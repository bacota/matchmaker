package com.vivi.matchmaker.model

/** What a match's `timeLimit` is a limit *on*.
  *
  * The number comes from the challenge either way; this says how to spend it. A per-turn limit
  * is judged one turn at a time and starts again on every move, so a long game is not itself a
  * problem. A total limit is a chess clock: it is the player's budget for the whole match, and
  * every turn they take spends part of it, so the deadline for the turn in front of them depends
  * on every turn behind them.
  *
  * The set is expected to grow — an increment per move is the obvious next member — which is why
  * this is a code rather than a boolean.
  */
enum TimeLimitKind(val code: String, val label: String) {

  /** Every turn gets the whole limit. */
  case PerTurn extends TimeLimitKind("PER_TURN", "Per turn")

  /** The limit is the player's budget for the entire match. */
  case Total extends TimeLimitKind("TOTAL", "Total per player, like a chess clock")
}

object TimeLimitKind {
  def fromCode(code: String): TimeLimitKind =
    values
      .find(_.code == code)
      .getOrElse(throw new IllegalArgumentException(s"unknown time_limit_kind '$code'"))
}
