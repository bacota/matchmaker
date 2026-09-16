package com.vivi.matchmaker.notify

import cats.effect.IO
import cats.syntax.all._
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.persistence._

/** Everything matchmaker decides about notifications: who is told what, and when nobody is.
  *
  * A class of its own, and one method per thing that happens, because none of this is any of the services' business.
  * What a service knows is that it has accepted a challenge or recorded a move; which of eight kinds of news that is to
  * which of the players in it, whether any of them has asked not to hear about it, and what the mail then says are all
  * questions with one right answer wherever the event came from. Those answers used to be spread across four services,
  * where the same thirty lines appeared twice and the largest of them sat inline in `GameEngineService`.
  *
  * So a service calls one method, after its transaction has committed, and passes the thing that happened. Every method
  * here reads whatever else it needs for itself, which is why the call sites are one line: a notification is a report,
  * and a report can afford its own queries.
  *
  * A method per event rather than one method taking a `NotificationType`, because the kind is not the caller's to
  * choose. One event is often several kinds of news at once — the acceptance that fills a roster, the move that hands
  * over the turn — and which kind a particular player gets depends on what they have asked for (see
  * [[com.vivi.matchmaker.model.NotificationPolicy.choose]]). A `NotificationType` parameter would put that decision
  * back in the services, which is the entanglement this class exists to undo.
  *
  * Three terms hold for every method here, and are enforced once in [[dispatch]]:
  *
  *   - *Nothing without a sender and a link.* An environment given neither cannot say anything useful — a mail with no
  *     `From` is not one SES will accept, and one with no link is worse than no mail — so it says nothing, and does not
  *     even do the reads. This is also what keeps the local server and every test that has no opinion about mail
  *     silent, with no special case in either.
  *   - *Never fails the caller.* A notification reports something that has already happened. The match exists, the
  *     acceptance is recorded, the turn is taken; failing the request that did it because a queue would not take a mail
  *     would undo nothing and help nobody.
  *   - *Never silently, either.* A failure is printed with what it was about, because a queue that cannot be reached
  *     and an event nobody was owed a mail for look identical from everywhere else: nothing retries, and nothing
  *     records that a notification was owed. That is a real cost of this design, written down rather than hidden —
  *     making it durable means writing the mail beside the event in its own transaction and draining that table, which
  *     is a bigger change than this one and worth making the day a lost notification matters more than the action does.
  */
class Notifications(notifier: Notifier, mail: MailSettings) {

    // -------------------------------------------------------------------------
    // Things that happen to a challenge
    // -------------------------------------------------------------------------

    /** Somebody has accepted a challenge. Told to the challenger, and to everyone else who has accepted it.
      *
      * @param actor
      *   the player who accepted. Passed rather than read because the caller has just had it in hand, and because they
      *   are the one person not told.
      */
    def challengeAccepted(
        session: Session[IO],
        gameId: GameId,
        challengeId: ChallengeId,
        actor: Player
    ): IO[Unit] =
        rosterChanged(session, gameId, challengeId, actor, joined = true, except = None)

    /** Somebody's acceptance has gone. Told to the same audience, minus whoever did it.
      *
      * @param acceptor
      *   the player whose seat has opened up. The one thing that cannot be read here: their acceptance row is already
      *   gone, so the caller holds the last thing that knows their nickname.
      * @param removedBy
      *   whoever did it, which is usually the acceptor and may be the challenger — a challenger may remove another
      *   player's acceptance, and should no more be told about that than about their own.
      */
    def acceptanceWithdrawn(
        session: Session[IO],
        gameId: GameId,
        challengeId: ChallengeId,
        acceptor: Player,
        removedBy: Player
    ): IO[Unit] =
        rosterChanged(session, gameId, challengeId, acceptor, joined = false, except = Some(removedBy.playerId))

    // -------------------------------------------------------------------------
    // Things that happen to a match
    // -------------------------------------------------------------------------

    /** A match has begun. Told to everyone in it but the challenger.
      *
      * The mail introduces the match — who else is in it, whether this player moves first and by when — so it is the
      * one event whose news differs per recipient in more than its deadline.
      *
      * @param startedBy
      *   the challenger, and the one person not written to: they pressed Start and are reading the answer to their own
      *   click. Passed rather than read, for the same reason `matchEnded` is told who cancelled — who caused an event
      *   is something its caller knows and nothing here could work out.
      */
    def matchStarted(session: Session[IO], started: Match, startedBy: PlayerId): IO[Unit] =
        aboutMatch(session, started, s"start of match ${started.matchId.value}") {
            (from, uiBaseUrl, notice, seats, participants) =>
                val byId = participants.map(participant => participant.participantId -> participant).toMap

                seats.filter(_.player.playerId != startedBy).flatMap { seat =>
                    val participant = byId.get(seat.participantId)
                    NotificationPolicy
                        .choose(Seq(NotificationType.MatchStarted), seat.preferences)
                        .flatMap { kind =>
                            MatchMail.compose(
                              from,
                              uiBaseUrl,
                              seat.player,
                              kind,
                              MatchNews(
                                gameName = notice.name,
                                description = started.description,
                                due = participant.flatMap(_.due),
                                // In seat order, which is the order the game was dealt in.
                                others = seats
                                    .filter(_.player.playerId != seat.player.playerId)
                                    .map(_.player.nickname),
                                // Whose turn it is first is the engine's to say, and `start` has already
                                // asked and written the answer down. Read back rather than worked out
                                // again, which is how the mail comes to disagree with the screen.
                                yourTurn = participant.exists(_.pending),
                                playUrl = started.playUrl
                              )
                            )
                        }
                }
        }

    /** Somebody has moved. Told to whoever it is now the turn of, and to everyone else in the match except the mover.
      *
      * Two kinds, and everyone gets at most one: whoever is up next is told that, because it is the one notification in
      * the whole set that asks the player to do something, and everyone else is told only that a move happened. A
      * player who has turned "it is my turn" off but left "someone takes a turn" on still gets the plainer mail.
      *
      * Whose turn it is now is read from the rows rather than taken from the engine's `next` list, because the rows are
      * what the rest of matchmaker answers with — and a game in which the mover moves again is then not a special case.
      */
    def turnTaken(session: Session[IO], played: Match, moved: ParticipantId): IO[Unit] =
        aboutMatch(session, played, s"move in match ${played.matchId.value}") {
            (from, uiBaseUrl, notice, seats, participants) =>
                val mover = seats.find(_.participantId == moved).map(_.player.nickname)
                val pending = participants.filter(_.pending).map(_.participantId).toSet
                val nextUp = seats.filter(seat => pending.contains(seat.participantId)).map(_.player.nickname)

                seats.filter(_.participantId != moved).flatMap { seat =>
                    val yours = pending.contains(seat.participantId)
                    val kinds =
                        if (yours) Seq(NotificationType.YourTurn, NotificationType.TurnTaken)
                        else Seq(NotificationType.TurnTaken)

                    NotificationPolicy
                        .choose(kinds, seat.preferences)
                        .flatMap { kind =>
                            MatchMail.compose(
                              from,
                              uiBaseUrl,
                              seat.player,
                              kind,
                              MatchNews(
                                gameName = notice.name,
                                description = played.description,
                                mover = mover,
                                nextUp = nextUp,
                                // The recipient's own deadline, which is why the news is built per seat:
                                // one move leaves one player with a clock running and everybody else
                                // without.
                                due =
                                    if (yours) participants.find(_.participantId == seat.participantId).flatMap(_.due)
                                    else None,
                                playUrl = played.playUrl
                              )
                            )
                        }
                }
        }

    /** A match is over, however it ended. Told to everyone in it.
      *
      * Everyone, because in two of the three cases nobody in it did this: the engine finished the game, or a clock ran
      * out. The exception is a cancellation, where `except` names the creator who called it off and is looking at the
      * answer to their own click.
      *
      * No play link, unlike every other mail about a match. The board may well still be there — a cancel does not tell
      * the engine anything — but a "Play" line on a match that is over invites a turn nobody can take.
      */
    def matchEnded(
        session: Session[IO],
        played: Match,
        ending: MatchEnding,
        except: Option[PlayerId] = None
    ): IO[Unit] =
        aboutMatch(session, played, s"end of match ${played.matchId.value}") { (from, uiBaseUrl, notice, seats, _) =>
            seats.filterNot(seat => except.contains(seat.player.playerId)).flatMap { seat =>
                NotificationPolicy
                    .choose(Seq(NotificationType.MatchEnded), seat.preferences)
                    .flatMap { kind =>
                        MatchMail.compose(
                          from,
                          uiBaseUrl,
                          seat.player,
                          kind,
                          MatchNews(
                            gameName = notice.name,
                            description = played.description,
                            ending = Some(ending)
                          )
                        )
                    }
            }
        }

    // -------------------------------------------------------------------------
    // The two shapes those five events come in
    // -------------------------------------------------------------------------

    /* An acceptance and a withdrawal have the same audience and the same shape, and differ in one
     * boolean and one sentence -- so they are one method with two entry points rather than the same
     * thirty lines twice.
     *
     * Two audiences, from one query:
     *
     *   - the challenger, whose challenge this is. They are the only one who can start it, so when
     *     the last required role has just been filled that is the news; otherwise they get the
     *     plainer "somebody accepted".
     *   - everyone else who has accepted. Nothing is being asked of them, so their pair of kinds is
     *     the other one: "a challenge you accepted is ready" over "somebody else joined it".
     *
     * At most one mail each, which is why the kinds go to `choose` as a pair rather than being tested
     * one at a time: an acceptance that completes a roster is two reasons to write to the challenger,
     * and they are owed one email. */
    private def rosterChanged(
        session: Session[IO],
        gameId: GameId,
        challengeId: ChallengeId,
        actor: Player,
        joined: Boolean,
        except: Option[PlayerId]
    ): IO[Unit] =
        dispatch(s"challenge ${challengeId.value} of game ${gameId.value}") { (from, uiBaseUrl) =>
            val notificationRepo = new NotificationRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val challengeRepo = new OpenChallengeRepo(session)

            notificationRepo.gameNotice(gameId).flatMap {
                // A game that is not there is not a thing to write about, and this is the reporting
                // path: it sends nothing rather than failing the accept that got here.
                case None => IO.pure(Seq.empty[MailMessage])
                case Some(notice) =>
                    for {
                        // Read rather than passed: it is where the challenger and their own description
                        // of the challenge are. Absent if the challenge has been deleted since the
                        // commit, which is an ordinary race outside the lock.
                        challenge <- challengeRepo.read(gameId, challengeId)
                        waitingFor <- acceptanceRepo.unclaimedRoles(gameId, challengeId)
                        audience <- notificationRepo.levelsForChallenge(gameId, challengeId, notice.notifications)
                    } yield challenge.toSeq.flatMap { offered =>
                        val news = ChallengeNews(
                          gameName = notice.name,
                          description = offered.message,
                          actor = actor.nickname,
                          // The role they took, for an acceptance. Taken from their own row in the
                          // audience, which is also why a withdrawal says nothing about the role it
                          // freed: that row is gone, and the roster line names the role anyway
                          // whenever it is one a start waits for.
                          role = audience.find(_.player.playerId == actor.playerId).map(_.roleName),
                          joined = joined,
                          // The challenger is in the audience: creating a challenge inserts their own
                          // acceptance. The fallback is unreachable by anything that can happen — that
                          // acceptance is written in the same transaction as the challenge — and is a
                          // phrase rather than a crash because a notification is not the place to
                          // discover a broken row.
                          challenger = audience
                              .find(_.player.playerId == offered.challenger)
                              .map(_.player.nickname)
                              .getOrElse("Whoever offered it"),
                          waitingFor = waitingFor
                        )

                        audience
                            .filter(recipient =>
                                recipient.player.playerId != actor.playerId &&
                                    !except.contains(recipient.player.playerId)
                            )
                            .flatMap(recipient =>
                                NotificationPolicy
                                    .choose(
                                      kindsFor(recipient, offered.challenger, joined, waitingFor),
                                      recipient.levels.resolve
                                    )
                                    .flatMap(ChallengeMail.compose(from, uiBaseUrl, recipient.player, _, news))
                            )
                    }
            }
        }

    /* What this recipient could be told about a roster change, most informative first.
     *
     * The "ready" kinds are offered only for an acceptance. A withdrawal can leave a roster still
     * full -- an optional role freed -- but "you can start it now" is not the news when somebody has
     * just left, and telling a challenger their challenge is ready because a player walked out of it
     * would be actively misleading. */
    private def kindsFor(
        recipient: AcceptorNotifications,
        challenger: PlayerId,
        joined: Boolean,
        waitingFor: Seq[String]
    ): Seq[NotificationType] = {
        val ready = joined && waitingFor.isEmpty
        if (recipient.player.playerId == challenger)
            if (ready) Seq(NotificationType.ChallengeReady, NotificationType.ChallengeAccepted)
            else Seq(NotificationType.ChallengeAccepted)
        else if (ready) Seq(NotificationType.AcceptedChallengeReady, NotificationType.AcceptanceChanged)
        else Seq(NotificationType.AcceptanceChanged)
    }

    /* The reads every event about a match needs, done once: what the game is called and what it asks
     * for, who is in the match with what they want to hear about it, and the seats as they now stand.
     *
     * The seats carry their players, so there is no second list to keep in step with this one -- what
     * `matchStarted` used to do by zipping two queries that happened to be ordered the same way.
     *
     * A match whose game has gone is a broken row rather than a thing to write about, and this is the
     * reporting path -- so it sends nothing rather than failing the action that got here. */
    private def aboutMatch(session: Session[IO], played: Match, about: String)(
        compose: (String, String, GameNotice, List[SeatNotifications], List[Participant]) => Seq[MailMessage]
    ): IO[Unit] =
        dispatch(about) { (from, uiBaseUrl) =>
            val notificationRepo = new NotificationRepo(session)
            val participantRepo = new ParticipantRepo(session)
            notificationRepo.gameNotice(played.gameId).flatMap {
                case None => IO.pure(Seq.empty[MailMessage])
                case Some(notice) =>
                    for {
                        seats <- notificationRepo.preferencesForMatch(played.gameId, played.matchId)
                        participants <- participantRepo
                            .listForMatch(played.gameId, played.matchId)
                            .map(_.map((participant, _, _) => participant))
                    } yield compose(from, uiBaseUrl, notice, seats, participants)
            }
        }

    /* The three terms in the class comment, in the order they apply: no settings, no reads and no
     * mail; otherwise compose, enqueue, and swallow whatever comes back with a line saying what it
     * was about. */
    private def dispatch(about: String)(compose: (String, String) => IO[Seq[MailMessage]]): IO[Unit] =
        mail.sender
            .zip(mail.uiBaseUrl)
            .traverse_ { (sender, uiBaseUrl) =>
                compose(sender, uiBaseUrl).flatMap(_.traverse_(notifier.enqueue))
            }
            .handleError(error => System.err.println(s"could not queue notifications for $about: $error"))
}

object Notifications {

    /** Sends nothing, because it has nowhere to send from and nowhere to send people to.
      *
      * What a service gets when it is constructed without one, which is every spec that has no opinion about mail and
      * the local server that has no queue — the same thing `Notifier.disabled` is for, one layer up.
      */
    val disabled: Notifications = new Notifications(Notifier.disabled, MailSettings.none)
}
