package com.vivi.matchmaker.model

/** The kinds of thing matchmaker writes to a player about.
  *
  * One case per event that is worth an email, and the list is closed on purpose: each case is a column on four tables
  * (see V13) and a paragraph in somebody's inbox, so adding one is a migration and a decision, not a string a caller
  * can invent. The `code` is what travels on the wire and, with `notify_` in front of it in lower case, names the
  * column.
  *
  * The order of `values` is the order the columns are bound in (`SkunkCodecs.notificationPreferences`) and the order
  * the forms list them in, which is why it is the order a player meets these events in rather than alphabetical:
  * offering a challenge, filling it, accepting someone else's, playing, finishing.
  */
enum NotificationType(val code: String, val label: String, val detail: String) {

    /** (1) Somebody accepted a challenge you offered, or backed out of one. */
    case ChallengeAccepted
        extends NotificationType(
          "CHALLENGE_ACCEPTED",
          "Someone accepts my challenge",
          "Also when someone backs out of one."
        )

    /** (2) Every role in a challenge you offered is filled, so you can start the match. */
    case ChallengeReady
        extends NotificationType(
          "CHALLENGE_READY",
          "My challenge is ready to start",
          "Every role in it has been taken."
        )

    /** (3) Somebody else accepted a challenge you had accepted, or backed out of one. */
    case AcceptanceChanged
        extends NotificationType(
          "ACCEPTANCE_CHANGED",
          "Someone joins a challenge I accepted",
          "Also when one of them backs out."
        )

    /** (4) Every role in a challenge you accepted is filled, so its challenger can start it. */
    case AcceptedChallengeReady
        extends NotificationType(
          "ACCEPTED_CHALLENGE_READY",
          "A challenge I accepted is ready to start",
          "You are waiting on whoever offered it."
        )

    /** (5) A match you are a participant in has begun. */
    case MatchStarted
        extends NotificationType("MATCH_STARTED", "A match I am in starts", "With whose turn it is first.")

    /** (7) Somebody took a turn in a match you are playing. */
    case TurnTaken
        extends NotificationType("TURN_TAKEN", "Someone takes a turn", "Every move in every match you are playing.")

    /** (8) It is your turn. */
    case YourTurn extends NotificationType("YOUR_TURN", "It is my turn", "Including when a turn is nearly due.")

    /** (9) A match you are in has ended or been called off. */
    case MatchEnded
        extends NotificationType("MATCH_ENDED", "A match I am in ends", "However it ends, including cancellation.")

    /** The column this kind is stored in, on `player`, `participant`, `player_game` and `game` alike. Derived from the
      * code rather than stated twice, so the two cannot drift.
      */
    def column: String = s"notify_${code.toLowerCase}"
}

/** What a player has said about each kind of notification, at one level of the chain.
  *
  * `None` is "I have not said", not "no" — which is what makes the chain in [[NotificationPolicy]] a chain, and what
  * the forms' third option ("Use Default") writes. Held as named fields rather than a map keyed by [[NotificationType]]
  * so that a kind added to the enum is a compile error here and in the three matches below, rather than a key nothing
  * ever looks up.
  */
case class NotificationPreferences(
    challengeAccepted: Option[Boolean] = None,
    challengeReady: Option[Boolean] = None,
    acceptanceChanged: Option[Boolean] = None,
    acceptedChallengeReady: Option[Boolean] = None,
    matchStarted: Option[Boolean] = None,
    turnTaken: Option[Boolean] = None,
    yourTurn: Option[Boolean] = None,
    matchEnded: Option[Boolean] = None
) {

    def apply(kind: NotificationType): Option[Boolean] = kind match {
        case NotificationType.ChallengeAccepted      => challengeAccepted
        case NotificationType.ChallengeReady         => challengeReady
        case NotificationType.AcceptanceChanged      => acceptanceChanged
        case NotificationType.AcceptedChallengeReady => acceptedChallengeReady
        case NotificationType.MatchStarted           => matchStarted
        case NotificationType.TurnTaken              => turnTaken
        case NotificationType.YourTurn               => yourTurn
        case NotificationType.MatchEnded             => matchEnded
    }

    /** This, with one kind answered differently. `None` for `choice` is the "Use Default" the forms offer. */
    def updated(kind: NotificationType, choice: Option[Boolean]): NotificationPreferences = kind match {
        case NotificationType.ChallengeAccepted      => copy(challengeAccepted = choice)
        case NotificationType.ChallengeReady         => copy(challengeReady = choice)
        case NotificationType.AcceptanceChanged      => copy(acceptanceChanged = choice)
        case NotificationType.AcceptedChallengeReady => copy(acceptedChallengeReady = choice)
        case NotificationType.MatchStarted           => copy(matchStarted = choice)
        case NotificationType.TurnTaken              => copy(turnTaken = choice)
        case NotificationType.YourTurn               => copy(yourTurn = choice)
        case NotificationType.MatchEnded             => copy(matchEnded = choice)
    }

    /** The kinds this level has nothing to say about, i.e. the ones that fall through to the next. */
    def unsaid: Seq[NotificationType] = NotificationType.values.toSeq.filter(apply(_).isEmpty)

    /** Every kind answered, or `None` if any is still unsaid. What turns the game form's eight controls into the eight
      * NOT NULL columns of `game`, and the reason the form cannot be submitted with one left blank.
      */
    def complete: Option[NotificationDefaults] =
        if (unsaid.nonEmpty) None
        else
            Some(
              NotificationDefaults(
                challengeAccepted.get,
                challengeReady.get,
                acceptanceChanged.get,
                acceptedChallengeReady.get,
                matchStarted.get,
                turnTaken.get,
                yourTurn.get,
                matchEnded.get
              )
            )
}

object NotificationPreferences {

    /** Nothing said about anything: what a player starts with, and what a row of NULLs reads as. */
    val unset: NotificationPreferences = NotificationPreferences()
}

/** A game's answer for every kind — the end of the chain, and so the one level that cannot say "I have not said".
  *
  * A separate type from [[NotificationPreferences]] rather than one with a convention that all eight are `Some`,
  * because the difference is the whole point: this is what `game`'s eight NOT NULL columns hold, and it is what
  * [[NotificationPolicy]] can finish on.
  */
case class NotificationDefaults(
    challengeAccepted: Boolean,
    challengeReady: Boolean,
    acceptanceChanged: Boolean,
    acceptedChallengeReady: Boolean,
    matchStarted: Boolean,
    turnTaken: Boolean,
    yourTurn: Boolean,
    matchEnded: Boolean
) {

    def apply(kind: NotificationType): Boolean = kind match {
        case NotificationType.ChallengeAccepted      => challengeAccepted
        case NotificationType.ChallengeReady         => challengeReady
        case NotificationType.AcceptanceChanged      => acceptanceChanged
        case NotificationType.AcceptedChallengeReady => acceptedChallengeReady
        case NotificationType.MatchStarted           => matchStarted
        case NotificationType.TurnTaken              => turnTaken
        case NotificationType.YourTurn               => yourTurn
        case NotificationType.MatchEnded             => matchEnded
    }

    /** The same answers as choices a form can edit, every one of them said. */
    def asPreferences: NotificationPreferences =
        NotificationPreferences(
          Some(challengeAccepted),
          Some(challengeReady),
          Some(acceptanceChanged),
          Some(acceptedChallengeReady),
          Some(matchStarted),
          Some(turnTaken),
          Some(yourTurn),
          Some(matchEnded)
        )
}

object NotificationDefaults {

    /** One answer for all eight. `all(true)` is what V13 gave every game that already existed, and so what a `Game`
      * built by a client that has never heard of notifications carries.
      */
    def all(enabled: Boolean): NotificationDefaults =
        NotificationDefaults(enabled, enabled, enabled, enabled, enabled, enabled, enabled, enabled)
}

/** Every level of the chain that bears on one recipient, unresolved.
  *
  * Carried as one value rather than resolved where it is read, so that the rule lives in exactly one place —
  * [[NotificationPolicy]] — and can be exercised without a database. `participant` and `playerGame` are `unset` when
  * there is no such row: a notification about a challenge concerns a player who is in no match yet, and a player who
  * has never opened a game's settings has no `player_game` row.
  */
case class NotificationLevels(
    participant: NotificationPreferences = NotificationPreferences.unset,
    playerGame: NotificationPreferences = NotificationPreferences.unset,
    player: NotificationPreferences = NotificationPreferences.unset,
    game: NotificationDefaults
)

/** What one player has said about one game, as the settings screen lists it.
  *
  * A row of `player_game`, and absent rather than empty when they have said nothing about that game — so a player who
  * has only ever adjusted their chess settings has one of these, not one per game in existence.
  */
case class GameNotificationPreferences(gameId: GameId, preferences: NotificationPreferences)

/** Everything a player's own notification settings screen needs: what they have said in general, and what they have
  * said about particular games.
  *
  * Not the per-match level, which belongs to the match it is about and is asked for there — a player in forty matches
  * would otherwise pay for forty rows to render one form.
  */
case class NotificationSettings(
    player: NotificationPreferences,
    games: Seq[GameNotificationPreferences]
)

/** Whether a particular player is to be told about a particular thing. */
object NotificationPolicy {

    /** The first level that has an answer wins; the game always has one.
      *
      * Most specific first, which is also least durable first: a mute on one match outlives that match and nothing
      * else, while what the game says outlives everyone's opinion of it. Note that the participant level is consulted
      * simply by being present — a participant row exists only once a match has been started, so "check the participant
      * row if the match has started" needs no separate test for whether it has.
      */
    def wants(kind: NotificationType, levels: NotificationLevels): Boolean =
        levels
            .participant(kind)
            .orElse(levels.playerGame(kind))
            .orElse(levels.player(kind))
            .getOrElse(levels.game(kind))
}
