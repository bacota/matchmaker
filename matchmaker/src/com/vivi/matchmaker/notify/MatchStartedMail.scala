package com.vivi.matchmaker.notify

import java.time.Instant
import com.vivi.matchmaker.model.{Game, Player}

/** What a player is told when a match they accepted into begins.
  *
  * Rendering is separated from deciding who to write to, and is a pure function of its arguments, so that what the mail
  * actually says can be tested without a database, a queue or a match. The deciding half is
  * `GameEngineService.notifyStarted`.
  *
  * Plain text, no HTML alternative. Every line of this is a fact and a link; there is nothing to lay out, and a text
  * part is the one thing every client renders the same way.
  */
object MatchStartedMail {

    /** One line per thing the player needs, in the order they need it: what started, who is in it, whether they are the
      * one holding everyone up, by when, and where to go.
      *
      * `None` when the player has no address, which is the only reason there is nothing to send: the rule that a player
      * with nowhere to write to is skipped lives here rather than in each caller, so that a second kind of notification
      * cannot forget it.
      *
      * @param others
      *   the other players' nicknames, in seat order
      * @param yourTurn
      *   whether the engine has already said this seat moves first
      * @param due
      *   when this player's first turn runs out, if the match is played on a clock
      * @param playUrl
      *   the engine's own link for this match, when it gave one
      */
    def compose(
        sender: String,
        uiBaseUrl: String,
        game: Game,
        description: String,
        recipient: Player,
        others: Seq[String],
        yourTurn: Boolean,
        due: Option[Instant],
        playUrl: Option[String]
    ): Option[MailMessage] = recipient.email.map { address =>
        val opponents =
            if (others.isEmpty) "You are the only player."
            else s"Playing with you: ${others.mkString(", ")}."

        val turn =
            if (yourTurn)
                due.fold("It is your turn.")(by => s"It is your turn, and it is due by ${MailText.at(by)}.")
            else "You will be told when it is your turn."

        MailMessage(
          sender = sender,
          recipient = address,
          subject = s"Your ${game.name} match has started",
          // Laid out by `MailText.letter`, like every other notification. Only the layout is
          // shared: this template keeps a `compose` of its own rather than extending
          // `NotificationMail`, because it has no `NotificationType` to switch on and its own list
          // of arguments — it introduces a match rather than reporting an event in one.
          body = MailText.letter(
            recipient,
            s"""${opening(game, description)}
             |
             |$opponents
             |$turn""".stripMargin,
            uiBaseUrl,
            playUrl
          )
        )
    }

    /* The challenger's own words when they wrote any, since that is what a player recognises their
     * challenge by, and the game's name alone when they did not. */
    private def opening(game: Game, description: String): String =
        if (description.trim.isEmpty) s"Your match of ${game.name} has started."
        else s"""Your match of ${game.name} has started: "${description.trim}"."""
}
