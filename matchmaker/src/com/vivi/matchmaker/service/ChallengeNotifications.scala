package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify.{ChallengeMail, ChallengeNews, MailMessage, NotificationSender}
import com.vivi.matchmaker.persistence.{AcceptanceRepo, AcceptorNotifications, NotificationRepo, OpenChallengeRepo}

/** Who is told when a challenge's roster changes, and which of the competing things to tell them.
  *
  * Shared by the two services that change a roster — `OpenChallengeService.accept` and `AcceptanceService.delete` —
  * because an acceptance and a withdrawal have the same audience and the same shape, and differ in one boolean and one
  * sentence. The alternative was these thirty lines in both, drifting apart at the first edit.
  *
  * Everything here runs after the transaction that changed the roster has committed. It reads the state that
  * transaction left and reports it; it decides nothing, holds no locks, and cannot fail its caller.
  */
class ChallengeNotifications(sender: NotificationSender) {

    /** Tells everyone with a stake in a challenge that somebody has joined it or left it.
      *
      * Two audiences, from one query:
      *
      *   - the challenger, whose challenge this is. They are the only one who can start it, so when the last required
      *     role has just been filled that is the news; otherwise they get the plainer "somebody accepted".
      *   - everyone else who has accepted. Nothing is being asked of them, so their pair of kinds is the other one: "a
      *     challenge you accepted is ready" over "somebody else joined it".
      *
      * Never the player who did it. They pressed the button and are looking at the answer — the same rule
      * `GameEngineService` applies to the challenger who presses Start.
      *
      * At most one mail each, which is why the kinds are handed to `NotificationPolicy.choose` as a pair rather than
      * tested one at a time: an acceptance that completes a roster is two reasons to write to the challenger, and they
      * are owed one email.
      *
      * Everything but the actor is read here, so that neither caller has to carry facts out of its transaction for the
      * sake of a mail: the challenge says who offered it and what they called it, and the roster says who is in it,
      * what each of them plays, and what each of them wants to hear about.
      *
      * @param actor
      *   whoever's seat has just appeared or disappeared. The one thing that cannot be read here — in the withdrawal
      *   case their acceptance row is already gone, so the caller holds the last thing that knows their nickname.
      * @param except
      *   whoever did it, when that is somebody other than `actor`. A challenger may remove another player's acceptance,
      *   and they should no more be told about that than about their own.
      */
    def rosterChanged(
        session: Session[IO],
        gameId: GameId,
        challengeId: ChallengeId,
        actor: Player,
        joined: Boolean,
        except: Option[PlayerId] = None
    ): IO[Unit] =
        sender.dispatch(s"challenge ${challengeId.value} of game ${gameId.value}") { (from, uiBaseUrl) =>
            val notificationRepo = new NotificationRepo(session)
            val acceptanceRepo = new AcceptanceRepo(session)
            val challengeRepo = new OpenChallengeRepo(session)

            notificationRepo.gameNotice(gameId).flatMap {
                // A game that is not there is not a thing to write about, and this is the reporting
                // path: it sends nothing rather than failing the accept that got here.
                case None => IO.pure(Seq.empty[MailMessage])
                case Some(notice) =>
                    for {
                        // Read rather than passed for the same reason: it is where the challenger and
                        // their own description of the challenge are. Absent if the challenge has been
                        // deleted since the commit, which is an ordinary race outside the lock.
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
                          // freed: that row is gone, and the roster line below names the role anyway
                          // whenever it is one a start waits for.
                          role = audience.find(_.player.playerId == actor.playerId).map(_.roleName),
                          joined = joined,
                          // The challenger is in the audience: creating a challenge inserts their own
                          // acceptance. The fallback is unreachable by anything that can happen — their
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
                                      recipient.levels
                                    )
                                    .flatMap(ChallengeMail.compose(from, uiBaseUrl, recipient.player, _, news))
                            )
                    }
            }
        }

    /* What this recipient could be told, most informative first.
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
}
