package com.vivi.matchmaker.model

/** The kinds of thing matchmaker writes to a player about.
  *
  * One case per event that is worth an email, and the list is closed on purpose: each case is a column on the three
  * levels a player states an opinion at — `player`, `player_game` and `game` (see V13) — and a paragraph in somebody's
  * inbox, so adding one is a migration and a decision, not a string a caller can invent. The `code` is what travels on
  * the wire and, with `notify_` in front of it in lower case, names the column.
  *
  * `participant` is the exception, and holds only [[onSeat]] (V24): a seat is asked about the match it is in, and every
  * other kind is about a challenge that has already become one.
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
          "My challenge is accepted",
          "Also when someone backs out of one."
        )

    /** (2) Every role in a challenge you offered is filled, so you can start the match. */
    case ChallengeReady
        extends NotificationType(
          "CHALLENGE_READY",
          "My challenge can start",
          "Every role in it has been taken."
        )

    /** (3) Somebody else accepted a challenge you had accepted, or backed out of one. */
    case AcceptanceChanged
        extends NotificationType(
          "ACCEPTANCE_CHANGED",
          "Someone joins a challenge",
          "Also when one of them backs out."
        )

    /** (4) Every role in a challenge you accepted is filled, so its challenger can start it. */
    case AcceptedChallengeReady
        extends NotificationType(
          "ACCEPTED_CHALLENGE_READY",
          "A challenge can start",
          "You are waiting on whoever offered it."
        )

    /** (5) Somebody has invited you to a challenge of theirs (V22).
      *
      * The only notification here that is the first a player hears of the thing it is about: every other kind is news
      * about a challenge or a match they are already in. So it is also the only one whose recipient may have nothing on
      * screen to act on yet, which is why its mail links to the home page rather than to a challenge.
      */
    case InvitationReceived
        extends NotificationType(
          "INVITATION_RECEIVED",
          "Someone invites me to a challenge",
          "A challenge offered to you in particular."
        )

    /** (6) Somebody has accepted an invitation you sent. */
    case InvitationAccepted
        extends NotificationType(
          "INVITATION_ACCEPTED",
          "My invitation is accepted",
          "Someone you invited has joined your challenge."
        )

    /** (7) Somebody has turned down an invitation you sent.
      *
      * Worth its own kind rather than silence: the challenge stays as it was, so nothing else would tell the challenger
      * that a seat they were holding is not going to be taken.
      */
    case InvitationRejected
        extends NotificationType(
          "INVITATION_REJECTED",
          "My invitation is turned down",
          "Also when they simply say no."
        )

    /** (8) A match you are a participant in has begun. */
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

    /** The column this kind is stored in, on `player`, `player_game` and `game` alike — and on `participant` for the
      * [[onSeat]] kinds, which are the only ones it has a column for. Derived from the code rather than stated twice,
      * so the two cannot drift.
      */
    def column: String = s"notify_${code.toLowerCase}"

    /** Whether this kind of news can still arrive about a match that has already started.
      *
      * The first five are all about a challenge, or about the start itself: by the time there is a match to have an
      * opinion about, every one of them has either happened or can no longer happen. Only playing and finishing are
      * still ahead.
      *
      * A seat carries an answer for [[NotificationType.onSeat]] and no more (V24): these three plus `MatchStarted`,
      * which fires while the seat is new. The rest have no column on `participant` to be stamped into, which is why
      * asking a seat about one answers `false` rather than anything a player said. [[NotificationType.duringMatch]] is
      * the list the per-match form uses — `MatchStarted` is stored but not offered, since a match that has started
      * cannot start again.
      */
    def inProgress: Boolean = this match {
        case NotificationType.TurnTaken | NotificationType.YourTurn | NotificationType.MatchEnded => true
        case _                                                                                    => false
    }
}

object NotificationType {

    /** The kinds a match already under way can still produce, in the order [[NotificationType.values]] gives them.
      *
      * What the per-match form asks about. Derived from [[NotificationType.inProgress]] rather than written out, so a
      * kind added to the enum has to say for itself which side of the start it falls on.
      */
    val duringMatch: Seq[NotificationType] = values.toSeq.filter(_.inProgress)

    /** The kinds a seat in a match can answer at all, which is what `participant` stores (V24).
      *
      * [[duringMatch]] plus [[MatchStarted]]: a seat exists by the time a match starts, and the mail announcing the
      * start reads the seat's own answer. Everything else here is about a challenge — an acceptance, an invitation, a
      * roster filling up — and none of it can happen once the match exists, so a seat has nothing to say about them and
      * no column to say it in.
      *
      * Derived rather than written out, so that a kind added to the enum joins this list only if it says it belongs
      * there.
      */
    val onSeat: Seq[NotificationType] = values.toSeq.filter(kind => kind.inProgress || kind == MatchStarted)
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
    invitationReceived: Option[Boolean] = None,
    invitationAccepted: Option[Boolean] = None,
    invitationRejected: Option[Boolean] = None,
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
        case NotificationType.InvitationReceived     => invitationReceived
        case NotificationType.InvitationAccepted     => invitationAccepted
        case NotificationType.InvitationRejected     => invitationRejected
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
        case NotificationType.InvitationReceived     => copy(invitationReceived = choice)
        case NotificationType.InvitationAccepted     => copy(invitationAccepted = choice)
        case NotificationType.InvitationRejected     => copy(invitationRejected = choice)
        case NotificationType.MatchStarted           => copy(matchStarted = choice)
        case NotificationType.TurnTaken              => copy(turnTaken = choice)
        case NotificationType.YourTurn               => copy(yourTurn = choice)
        case NotificationType.MatchEnded             => copy(matchEnded = choice)
    }

    /** The kinds this and `other` answer differently: what a save actually changed.
      *
      * What a cascade is allowed to touch. A player who changes one question and asks for it to reach the matches they
      * are playing has asked about that question, not about the seven they left alone — and a seat may well be holding
      * an answer of its own to one of those.
      *
      * A kind that went from an answer to unsaid counts as changed: "I no longer have a view on this" is a change like
      * any other, and what it now falls through to is very likely not what it used to say.
      */
    def differences(other: NotificationPreferences): Set[NotificationType] =
        NotificationType.values.toSet.filter(kind => apply(kind) != other(kind))

    /** The kinds this level has nothing to say about, i.e. the ones that fall through to the next. */
    def unsaid: Seq[NotificationType] = NotificationType.values.toSeq.filter(apply(_).isEmpty)

    /** The four a seat holds, or `None` if any of those is unsaid. What the per-match form saves.
      *
      * Separate from [[complete]] because the two forms ask different questions: the game's asks all eleven and every
      * one of them is a column, while a match's asks the four its seat can answer and must not be blocked by the seven
      * it never shows.
      */
    def completeForSeat: Option[SeatNotifications] =
        for {
            started <- matchStarted
            taken <- turnTaken
            yours <- yourTurn
            ended <- matchEnded
        } yield SeatNotifications(started, taken, yours, ended)

    /** Every kind answered, or `None` if any is still unsaid. What turns the game form's eleven controls into the
      * eleven NOT NULL columns of `game`, and the reason the form cannot be submitted with one left blank.
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
                invitationReceived.get,
                invitationAccepted.get,
                invitationRejected.get,
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

/** What one seat in one match says it wants to hear about that match (V24).
  *
  * Four kinds, not eleven. A seat used to carry a column per kind for uniformity with the three levels behind it, and
  * seven of them could never fire: by the time there is a seat there is a match, and a challenge that has become a
  * match cannot be accepted, filled or invited to again. The columns were written at creation, re-stamped by every
  * cascade, and read by nothing.
  *
  * No chain behind it, which is what V14 established and this keeps: a seat's row is the answer, so a player who mutes
  * one match stays muted there however they later change the game's settings or their own.
  */
case class SeatNotifications(matchStarted: Boolean, turnTaken: Boolean, yourTurn: Boolean, matchEnded: Boolean) {

    /** `false` for every kind a seat does not answer. Not "they said no" — a seat is never asked about a challenge, and
      * a caller that asks is asking about an event that cannot happen to a match.
      */
    def apply(kind: NotificationType): Boolean = kind match {
        case NotificationType.MatchStarted => matchStarted
        case NotificationType.TurnTaken    => turnTaken
        case NotificationType.YourTurn     => yourTurn
        case NotificationType.MatchEnded   => matchEnded
        case _                             => false
    }

    /** These four as choices a form can edit, the seven it does not hold left unsaid. */
    def asPreferences: NotificationPreferences =
        NotificationType.onSeat.foldLeft(NotificationPreferences.unset)((preferences, kind) =>
            preferences.updated(kind, Some(apply(kind)))
        )
}

object SeatNotifications {

    /** One answer for all four, which is what a match already being played was stamped with when V24 narrowed the row.
      */
    def all(enabled: Boolean): SeatNotifications = SeatNotifications(enabled, enabled, enabled, enabled)
}

/** A game's answer for every kind — the end of the chain, and so the one level that cannot say "I have not said".
  *
  * A separate type from [[NotificationPreferences]] rather than one with a convention that all eleven are `Some`,
  * because the difference is the whole point: this is what `game`'s eleven NOT NULL columns hold, and it is what
  * [[NotificationPolicy]] can finish on.
  */
case class NotificationDefaults(
    challengeAccepted: Boolean,
    challengeReady: Boolean,
    acceptanceChanged: Boolean,
    acceptedChallengeReady: Boolean,
    invitationReceived: Boolean,
    invitationAccepted: Boolean,
    invitationRejected: Boolean,
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
        case NotificationType.InvitationReceived     => invitationReceived
        case NotificationType.InvitationAccepted     => invitationAccepted
        case NotificationType.InvitationRejected     => invitationRejected
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
          Some(invitationReceived),
          Some(invitationAccepted),
          Some(invitationRejected),
          Some(matchStarted),
          Some(turnTaken),
          Some(yourTurn),
          Some(matchEnded)
        )
}

object NotificationDefaults {

    /** One answer for all eleven. `all(true)` is what V13 gave every game that already existed, and so what a `Game`
      * built by a client that has never heard of notifications carries.
      */
    def all(enabled: Boolean): NotificationDefaults =
        NotificationDefaults(
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled,
          enabled
        )
}

/** The levels a new seat inherits from, unresolved.
  *
  * Carried as one value rather than resolved where it is read, so that the rule lives in exactly one place —
  * [[resolve]] — and can be exercised without a database. `playerGame` is `unset` when there is no such row: a player
  * who has never opened a game's settings has none.
  *
  * Note what is *not* here: the participant level. Since V14 a seat's own answers are NOT NULL and are the whole answer
  * for anything about a match, so a chain is only ever walked in the two places one still has to be — creating a seat,
  * and writing to somebody about a challenge, which nobody is a participant in yet.
  *
  * [[resolve]] still answers every kind, and has to: the second of those places is an audience of acceptors, who are
  * asked about challenge kinds. Only the seat-shaped subset of it is ever written to `participant`.
  */
case class NotificationLevels(
    playerGame: NotificationPreferences = NotificationPreferences.unset,
    player: NotificationPreferences = NotificationPreferences.unset,
    game: NotificationDefaults
) {

    /** The chain collapsed: the most specifically stated answer for each kind, ending at the game, which always has
      * one.
      *
      * Most specific first, which is also least durable first: what a player says about one game outlives their opinion
      * of that afternoon, while what the game says outlives everyone's opinion of it.
      *
      * This is what a seat is stamped with when it is created, and the same expression the database writes there —
      * `ParticipantRepo.create` does it in SQL so that a seat cannot exist unstamped. Here so that the rule can be read
      * and tested as itself.
      */
    def resolve: NotificationDefaults =
        NotificationDefaults(
          answer(NotificationType.ChallengeAccepted),
          answer(NotificationType.ChallengeReady),
          answer(NotificationType.AcceptanceChanged),
          answer(NotificationType.AcceptedChallengeReady),
          answer(NotificationType.InvitationReceived),
          answer(NotificationType.InvitationAccepted),
          answer(NotificationType.InvitationRejected),
          answer(NotificationType.MatchStarted),
          answer(NotificationType.TurnTaken),
          answer(NotificationType.YourTurn),
          answer(NotificationType.MatchEnded)
        )

    private def answer(kind: NotificationType): Boolean =
        playerGame(kind).orElse(player(kind)).getOrElse(game(kind))
}

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
    games: Seq[GameNotificationPreferences],
    /* Why mail to this player is being held back, when it is.
     *
     * On this object rather than behind a route of its own because it is the same screen and the
     * same moment: the form that says what a player wants to hear about is exactly where "we have
     * stopped writing to you" belongs, and a second fetch would let the screen render the form
     * before it knew the form was moot.
     *
     * `None` is the ordinary answer, and also the answer for a failure that has been recorded and
     * not acted on -- one transient delay is not news. Defaulted, so the many places that build
     * settings without a database do not have to mention it. */
    suppressed: Option[EmailSuppression.Notice] = None
)

/** Whether a particular player is to be told about a particular thing.
  *
  * Over one recipient's answers, already resolved: a seat's own [[SeatNotifications]], or
  * [[NotificationLevels.resolve]] for an audience that has no seat yet.
  */
/** Which notification a recipient gets, given what they have said they want.
  *
  * `answers` is a function rather than a type, so that a game's [[NotificationDefaults]] and a seat's
  * [[SeatNotifications]] can both be asked without sharing a supertype. They must not share one: both travel on the
  * wire, and upickle tags a case class that has a parent with a `$type` discriminator — which would silently change the
  * shape of every game payload. Pass either one's `apply`.
  */
object NotificationPolicy {

    def wants(kind: NotificationType, answers: NotificationType => Boolean): Boolean = answers(kind)

    /** The one notification a recipient gets for an event that is several kinds of news at once.
      *
      * One thing that happens is often two reasons to write: the acceptance that fills the last role is both "somebody
      * accepted" and "you can start it now", and a move is both "somebody moved" and "it is your turn". A player is
      * owed one email about one event, so `kinds` is those reasons in the order of how much they say — the fullest
      * first — and this takes the first one the player actually wants to hear.
      *
      * Which means a player who has turned off "it is my turn" but left "someone takes a turn" on still hears that the
      * move happened, in the plainer mail. That is the point of asking in order rather than of picking the most
      * specific reason and then testing it: the mail a player gets is the best one they have not refused.
      *
      * `None` when they have refused all of them, which is the only case that sends nothing.
      */
    def choose(kinds: Seq[NotificationType], answers: NotificationType => Boolean): Option[NotificationType] =
        kinds.find(wants(_, answers))
}
