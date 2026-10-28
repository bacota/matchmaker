package com.vivi.matchmaker.persistence

import cats.effect.IO
import skunk.Session

/** Every repo over one session, each made the first time it is asked for: `repos.matches`, `repos.tournaments`, in
  * place of a `new MatchRepo(session)` and a `new TournamentRepo(session)` at the top of each method.
  *
  * `games` and `characters` are made on each call: their type parameter is the caller's, so there is no one instance to
  * keep. A repo holds nothing but its session and its codecs, so making one costs next to nothing.
  */
class Repos(val session: Session[IO]) {
    lazy val archives: ArchiveRepo = new ArchiveRepo(session)
    lazy val characterInvitations: CharacterInvitationRepo = new CharacterInvitationRepo(session)
    lazy val eloRatings: EloRatingRepo = new EloRatingRepo(session)
    lazy val entrants: EntrantRepo = new EntrantRepo(session)
    lazy val gameAdmins: GameAdminRepo = new GameAdminRepo(session)
    lazy val gameApiKeys: GameApiKeyRepo = new GameApiKeyRepo(session)
    lazy val invitations: InvitationRepo = new InvitationRepo(session)
    lazy val matches: MatchRepo = new MatchRepo(session)
    lazy val notifications: NotificationRepo = new NotificationRepo(session)
    lazy val participants: ParticipantRepo = new ParticipantRepo(session)
    lazy val players: PlayerRepo = new PlayerRepo(session)
    lazy val pools: PoolRepo = new PoolRepo(session)
    lazy val results: ResultRepo = new ResultRepo(session)
    lazy val stages: StageRepo = new StageRepo(session)
    lazy val suppressions: SuppressionRepo = new SuppressionRepo(session)
    lazy val tournamentMatches: TournamentMatchRepo = new TournamentMatchRepo(session)
    lazy val tournamentModels: TournamentModelRepo = new TournamentModelRepo(session)
    lazy val tournaments: TournamentRepo = new TournamentRepo(session)
    lazy val tournamentStates: TournamentStateRepo = new TournamentStateRepo(session)
    lazy val turns: TurnRepo = new TurnRepo(session)

    def games[T](using TextCodec[T]): GameRepo[T] = new GameRepo[T](session)
    def characters[T](using TextCodec[T]): CharacterRepo[T] = new CharacterRepo[T](session)
}
