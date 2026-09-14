package com.vivi.matchmaker.service

import cats.effect.IO
import skunk.Session
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.notify._
import com.vivi.matchmaker.persistence.{GameNotice, NotificationRepo, ParticipantRepo, SeatNotifications}

/** Who is told what while a match is being played, and after it stops.
  *
  * The counterpart of [[ChallengeNotifications]] for the three kinds that need a match to exist, shared for the same
  * reason: the services that end a match — the engine reporting a result, a clock running out, a creator calling it off
  * — are telling the same people the same thing.
  *
  * Like that class, everything here runs after the transaction that caused the event has committed, and reads the state
  * it left. A turn's deadlines in particular are read back rather than recomputed: the caller has just written them,
  * and working them out a second time is how the mail comes to disagree with the screen.
  */
class MatchNotifications(sender: NotificationSender) {

    /** Tells a match that somebody has moved in it.
      *
      * Two kinds, and everyone gets at most one: whoever it is now the turn of is told that, because it is the one
      * notification in the whole set that asks the player to do something, and everyone else is told only that a move
      * happened. A player who has turned "it is my turn" off but left "someone takes a turn" on still gets the plainer
      * mail — see `NotificationPolicy.choose`.
      *
      * Not the player who moved. They know: they moved.
      *
      * Whose turn it is now is read from the rows rather than taken from the engine's `next` list, because the rows are
      * what the rest of matchmaker answers with — and a game in which the mover moves again is then not a special case.
      */
    def turnTaken(session: Session[IO], played: Match, moved: ParticipantId): IO[Unit] =
        dispatch(session, played, s"move in match ${played.matchId.value}") {
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
                        .choose(kinds, seat.levels)
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
                                // The recipient's own deadline, which is why the news is built per seat: one
                                // move leaves one player with a clock running and everybody else without.
                                due =
                                    if (yours) participants.find(_.participantId == seat.participantId).flatMap(_.due)
                                    else None,
                                playUrl = played.playUrl
                              )
                            )
                        }
                }
        }

    /** Tells a match that it is over, however it ended.
      *
      * Everyone in it, because in two of the three cases nobody in it did this: the engine finished the game, or a
      * clock ran out. The exception is a cancellation, where `except` names the creator who called it off and is
      * looking at the answer to their own click.
      *
      * No play link, unlike every other mail about a match. The board may well still be there — a cancel does not tell
      * the engine anything — but a "Play" line on a match that is over is an invitation to a turn nobody can take.
      */
    def ended(
        session: Session[IO],
        played: Match,
        ending: MatchEnding,
        except: Option[PlayerId] = None
    ): IO[Unit] =
        dispatch(session, played, s"end of match ${played.matchId.value}") { (from, uiBaseUrl, notice, seats, _) =>
            seats.filterNot(seat => except.contains(seat.player.playerId)).flatMap { seat =>
                NotificationPolicy
                    .choose(Seq(NotificationType.MatchEnded), seat.levels)
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

    /* The reads every one of these needs, done once: what the game is called and what it asks for,
     * who is in the match with what they want to hear about it, and the seats as they now stand.
     *
     * A match whose game has gone is a broken row rather than a thing to write about, and this is the
     * reporting path -- so it sends nothing rather than failing the action that got here. */
    private def dispatch(session: Session[IO], played: Match, about: String)(
        compose: (String, String, GameNotice, List[SeatNotifications], List[Participant]) => Seq[MailMessage]
    ): IO[Unit] =
        sender.dispatch(about) { (from, uiBaseUrl) =>
            val notificationRepo = new NotificationRepo(session)
            val participantRepo = new ParticipantRepo(session)
            notificationRepo.gameNotice(played.gameId).flatMap {
                case None => IO.pure(Seq.empty[MailMessage])
                case Some(notice) =>
                    for {
                        seats <- notificationRepo.levelsForMatch(played.gameId, played.matchId, notice.notifications)
                        participants <- participantRepo
                            .listForMatch(played.gameId, played.matchId)
                            .map(_.map((participant, _, _) => participant))
                    } yield compose(from, uiBaseUrl, notice, seats, participants)
            }
        }
}
