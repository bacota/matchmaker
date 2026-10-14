package com.vivi.matchmaker.model

/** The kinds of thing matchmaker writes to a player about.
  *
  * One case per event that is worth an email, and the list is closed on purpose: each case is a column on the three
  * levels a player states an opinion at — `player` and `player_game` — and a paragraph in somebody's inbox, so adding
  * one is a migration and a decision, not a string a caller can invent. The `code` is what travels on the wire and,
  * with `is_notify_` in front of it in lower case, names the column.
  *
  * `participant` is the exception, and holds only [[onSeat]]: a seat is asked about the match it is in, and the first
  * seven kinds are about the entries of a challenge or tournament before it starts.
  *
  * The order of `values` is the order the columns are bound in (`SkunkCodecs.notificationPreferences`) and the order
  * the forms list them in, which is why it is the order a player meets these events in rather than alphabetical:
  * offering a challenge, filling it, entering someone else's, playing, finishing. The codes, and so the columns, keep
  * the names they had when a challenge was accepted rather than entered.
  */
enum NotificationType(val code: String, val label: String, val detail: String) {

    /** (1) Somebody entered a challenge or tournament you own, or withdrew from one. */
    case ChallengeAccepted
        extends NotificationType(
          "CHALLENGE_ACCEPTED",
          "Someone enters my challenge",
          "Or my tournament. Also when someone withdraws."
        )

    /** (2) A challenge or tournament you own is full, so you can start it. */
    case ChallengeReady
        extends NotificationType(
          "CHALLENGE_READY",
          "My challenge can start",
          "Or my tournament: it is full."
        )

    /** (3) Somebody else entered a challenge or tournament you had entered, or withdrew from one. */
    case AcceptanceChanged
        extends NotificationType(
          "ACCEPTANCE_CHANGED",
          "Someone joins a challenge",
          "Or a tournament you entered. Also when one of them withdraws."
        )

    /** (4) A challenge or tournament you entered is full, so its owner can start it. */
    case AcceptedChallengeReady
        extends NotificationType(
          "ACCEPTED_CHALLENGE_READY",
          "A challenge can start",
          "Or a tournament you entered: you are waiting on its owner."
        )

    /** (5) Somebody has invited you to a challenge or tournament of theirs.
      *
      * The only notification here that is the first a player hears of the thing it is about: every other kind is news
      * about a challenge, tournament or match they are already in. So it is also the only one whose recipient may have
      * nothing on screen to act on yet, which is why its mail links to the home page.
      */
    case InvitationReceived
        extends NotificationType(
          "INVITATION_RECEIVED",
          "Someone invites me to a challenge",
          "Or to a tournament, offered to you in particular."
        )

    /** (6) Somebody has accepted an invitation you sent, by entering. */
    case InvitationAccepted
        extends NotificationType(
          "INVITATION_ACCEPTED",
          "My invitation is accepted",
          "Someone you invited has entered."
        )

    /** (7) Somebody has turned down an invitation you sent.
      *
      * Worth its own kind rather than silence: the challenge or tournament stays as it was, so nothing else would tell
      * its owner that a player they were waiting on is not going to enter.
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

    /** (10) Every match of a round of a tournament you own is over, and the next waits on you to start it.
      *
      * Not about a match, so a seat holds no answer for it; and sent only for a tournament whose rounds its owner
      * starts, since one that starts its own rounds waits on nobody.
      */
    case TournamentRoundDone
        extends NotificationType(
          "TOURNAMENT_ROUND_DONE",
          "A round of my tournament ends",
          "When you start each round yourself."
        )

    /** The column this kind is stored in, on `player` and `player_game` alike — and on `participant` for the [[onSeat]]
      * kinds, which are the only ones it has a column for. Derived from the code rather than stated twice, so the two
      * cannot drift.
      */
    def column: String = s"is_notify_${code.toLowerCase}"

    /** Whether this kind of news can still arrive about a match that has already started.
      *
      * The first eight are all about entries, or about the start itself: by the time there is a match to have an
      * opinion about, every one of them has either happened or can no longer happen. Only playing and finishing are
      * still ahead.
      *
      * A seat carries an answer for [[NotificationType.onSeat]] and no more: these three plus `MatchStarted`, which
      * fires while the seat is new. The rest have no column on `participant` to be stamped into, which is why asking a
      * seat about one answers `false` rather than anything a player said. [[NotificationType.duringMatch]] is the list
      * the per-match form uses — `MatchStarted` is stored but not offered, since a match that has started cannot start
      * again.
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

    /** The kinds a seat in a match can answer at all, which is what `participant` stores.
      *
      * [[duringMatch]] plus [[MatchStarted]]: a seat exists by the time a match starts, and the mail announcing the
      * start reads the seat's own answer. Everything else here is about entries — an entry, an invitation, a challenge
      * filling up — or a tournament's round, and none of it is about the match a seat is in, so a seat has nothing to
      * say about them and no column to say it in.
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
    isChallengeAccepted: Option[Boolean] = None,
    isChallengeReady: Option[Boolean] = None,
    isAcceptanceChanged: Option[Boolean] = None,
    isAcceptedChallengeReady: Option[Boolean] = None,
    isInvitationReceived: Option[Boolean] = None,
    isInvitationAccepted: Option[Boolean] = None,
    isInvitationRejected: Option[Boolean] = None,
    isMatchStarted: Option[Boolean] = None,
    isTurnTaken: Option[Boolean] = None,
    isYourTurn: Option[Boolean] = None,
    isMatchEnded: Option[Boolean] = None,
    isTournamentRoundDone: Option[Boolean] = None
) {

    def apply(kind: NotificationType): Option[Boolean] = kind match {
        case NotificationType.ChallengeAccepted      => isChallengeAccepted
        case NotificationType.ChallengeReady         => isChallengeReady
        case NotificationType.AcceptanceChanged      => isAcceptanceChanged
        case NotificationType.AcceptedChallengeReady => isAcceptedChallengeReady
        case NotificationType.InvitationReceived     => isInvitationReceived
        case NotificationType.InvitationAccepted     => isInvitationAccepted
        case NotificationType.InvitationRejected     => isInvitationRejected
        case NotificationType.MatchStarted           => isMatchStarted
        case NotificationType.TurnTaken              => isTurnTaken
        case NotificationType.YourTurn               => isYourTurn
        case NotificationType.MatchEnded             => isMatchEnded
        case NotificationType.TournamentRoundDone    => isTournamentRoundDone
    }

    /** This, with one kind answered differently. `None` for `choice` is the "Use Default" the forms offer. */
    def updated(kind: NotificationType, choice: Option[Boolean]): NotificationPreferences = kind match {
        case NotificationType.ChallengeAccepted      => copy(isChallengeAccepted = choice)
        case NotificationType.ChallengeReady         => copy(isChallengeReady = choice)
        case NotificationType.AcceptanceChanged      => copy(isAcceptanceChanged = choice)
        case NotificationType.AcceptedChallengeReady => copy(isAcceptedChallengeReady = choice)
        case NotificationType.InvitationReceived     => copy(isInvitationReceived = choice)
        case NotificationType.InvitationAccepted     => copy(isInvitationAccepted = choice)
        case NotificationType.InvitationRejected     => copy(isInvitationRejected = choice)
        case NotificationType.MatchStarted           => copy(isMatchStarted = choice)
        case NotificationType.TurnTaken              => copy(isTurnTaken = choice)
        case NotificationType.YourTurn               => copy(isYourTurn = choice)
        case NotificationType.MatchEnded             => copy(isMatchEnded = choice)
        case NotificationType.TournamentRoundDone    => copy(isTournamentRoundDone = choice)
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

    /** The four a seat holds, or `None` if any of those is unsaid. What the per-match form saves: a match's form asks
      * the four its seat can answer, and must not be blocked by the seven it never shows.
      */
    def completeForSeat: Option[SeatNotifications] =
        for {
            started <- isMatchStarted
            taken <- isTurnTaken
            yours <- isYourTurn
            ended <- isMatchEnded
        } yield SeatNotifications(started, taken, yours, ended)
}

object NotificationPreferences {

    /** Nothing said about anything: what a player starts with, and what a row of NULLs reads as. */
    val unset: NotificationPreferences = NotificationPreferences()
}

/** What one seat in one match says it wants to hear about that match.
  *
  * Four kinds, not twelve. A seat used to carry a column per kind for uniformity with the three levels behind it, and
  * the rest could never fire about the match a seat is in. The columns were written at creation, re-stamped by every
  * cascade, and read by nothing.
  *
  * No chain behind it: a seat's row is the answer, so a player who mutes one match stays muted there however they later
  * change the game's settings or their own.
  */
case class SeatNotifications(
    isMatchStarted: Boolean,
    isTurnTaken: Boolean,
    isYourTurn: Boolean,
    isMatchEnded: Boolean
) {

    /** `false` for every kind a seat does not answer. Not "they said no" — a seat is never asked about entries, and a
      * caller that asks is asking about an event that cannot happen to a match.
      */
    def apply(kind: NotificationType): Boolean = kind match {
        case NotificationType.MatchStarted => isMatchStarted
        case NotificationType.TurnTaken    => isTurnTaken
        case NotificationType.YourTurn     => isYourTurn
        case NotificationType.MatchEnded   => isMatchEnded
        case _                             => false
    }

    /** These four as choices a form can edit, the seven it does not hold left unsaid. */
    def asPreferences: NotificationPreferences =
        NotificationType.onSeat.foldLeft(NotificationPreferences.unset)((preferences, kind) =>
            preferences.updated(kind, Some(apply(kind)))
        )
}

object SeatNotifications {

    /** One answer for all four, as a seat is stamped when every kind resolves alike.
      */
    def all(enabled: Boolean): SeatNotifications = SeatNotifications(enabled, enabled, enabled, enabled)
}

/** The levels a new seat inherits from, unresolved.
  *
  * Carried as one value rather than resolved where it is read, so that the rule lives in exactly one place — [[apply]]
  * — and can be exercised without a database. `playerGame` is `unset` when there is no such row: a player who has never
  * opened a game's settings has none.
  *
  * Note what is *not* here: the participant level. A seat's own answers are NOT NULL and are the whole answer for
  * anything about a match, so a chain is only ever walked in the two places one still has to be — creating a seat, and
  * writing to somebody about entries, which nobody has a seat in yet.
  *
  * Nor the game: the chain ends at "send it", the same for every game. [[apply]] still answers every kind, and has to:
  * the second of those places is an audience of entrants, who are asked about the entry kinds. Only the seat-shaped
  * subset of it is ever written to `participant`.
  */
case class NotificationLevels(
    playerGame: NotificationPreferences = NotificationPreferences.unset,
    player: NotificationPreferences = NotificationPreferences.unset
) {

    /** The chain collapsed for one kind: the most specifically stated answer, and `true` where nobody has said.
      *
      * Most specific first, which is also least durable first: what a player says about one game outlives their opinion
      * of that afternoon.
      *
      * This is what a seat is stamped with when it is created, and the same expression the database writes there —
      * `ParticipantRepo.create` does it in SQL so that a seat cannot exist unstamped. Here so that the rule can be read
      * and tested as itself. Pass it, as `levels.apply`, wherever [[NotificationPolicy]] wants answers.
      */
    def apply(kind: NotificationType): Boolean =
        playerGame(kind).orElse(player(kind)).getOrElse(NotificationLevels.unsaid)
}

object NotificationLevels {

    /** What a kind nobody has said anything about resolves to: send it. The end of the chain, and the `TRUE` that
      * closes every `COALESCE` that resolves it in SQL.
      */
    val unsaid: Boolean = true
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

/** Which notification a recipient gets, given what they have said they want.
  *
  * Over one recipient's answers, already resolved: a seat's own [[SeatNotifications]], or [[NotificationLevels]] for an
  * audience that has no seat yet. `answers` is a function rather than a type, so that the two can both be asked without
  * sharing a supertype — `SeatNotifications` travels on the wire, and upickle tags a case class that has a parent with
  * a `$type` discriminator. Pass either one's `apply`.
  */
object NotificationPolicy {

    def wants(kind: NotificationType, answers: NotificationType => Boolean): Boolean = answers(kind)

    /** The one notification a recipient gets for an event that is several kinds of news at once.
      *
      * One thing that happens is often two reasons to write: the entry that fills a challenge is both "somebody
      * entered" and "you can start it now", and a move is both "somebody moved" and "it is your turn". A player is owed
      * one email about one event, so `kinds` is those reasons in the order of how much they say — the fullest first —
      * and this takes the first one the player actually wants to hear.
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
