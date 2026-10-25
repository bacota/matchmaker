package com.vivi.boxing

import com.vivi.engine.ErrorText
import java.time.Instant
import scala.util.control.NonFatal
import com.vivi.engine.{GameEngine, Log, MatchStore, MatchmakerRefusal, Matchmaker, MoveApplied, Refusal, TurnClock}
import Protocol._

/** Boxing: the four exchanges of `interaction-design.txt`, which [[GameEngine]] makes for any game, and what is this
  * game's own — building a fighter, what a round plan is, and what a player is shown. How it tells matchmaker who is
  * pending, round by round, is [[Bout$]]'s.
  *
  * @param announce
  *   called once with each new bout, which is how the local server prints the play url and who is in which corner.
  * @param matchmakerUrl
  *   matchmaker's API, which a fighter is reported to once it is built. `None` refuses every build: a fighter
  *   matchmaker never heard of could never be put in a bout.
  */
class Engine(
    store: MatchStore[Bout],
    matchmaker: Matchmaker,
    baseUrl: String,
    now: () => Instant = () => Instant.now(),
    announce: Bout => Unit = _ => (),
    matchmakerUrl: Option[String] = None
) {

    /** The calls every engine makes, which this one exports, and which the shared routes are served from. */
    val core = GameEngine(Bout, store, matchmaker, baseUrl, now, announce)

    export core.{createGame, playUrl, read, resultsOf, seatOf, status}

    /** A player building a new fighter, which is made here and then reported to matchmaker as a character.
      *
      * A fighter is made in this engine, because this engine is what knows what a fighter is: the five characteristics,
      * the budget they are built from, and the range each must be in. Matchmaker only learns that one exists, with its
      * characteristics as the character's state, and from then on offers it in challenges and seats it in bouts — where
      * that state comes back to [[Bout.seat]].
      *
      * Nothing is kept here: matchmaker's answer is the fighter's only record, so a build matchmaker refuses or never
      * answers has made nothing, and the player can simply try again. The gap is the other way round — matchmaker
      * recording it and the answer being lost — which leaves the player a fighter they were told did not take, and
      * which they will find in matchmaker.
      */
    def buildFighter(cognitoId: String, request: BuildRequest): Either[Refusal, BuiltFighter] = {
        val fighter = Fighter(
          strength = request.strength,
          speed = request.speed,
          agility = request.agility,
          workrate = request.workrate,
          chin = request.chin
        )
        for {
            name <- named(request.name)
            valid <- Fighter.validate(fighter).left.map(Refusal.Invalid(_))
            url <- matchmakerUrl.toRight(noMatchmaker)
            characterId <- ask("registering a fighter", Unknown)(
              matchmaker.registerCharacter(
                url,
                RegisterCharacterRequest(
                  name = name,
                  description = request.description.trim,
                  ownerExternalId = cognitoId,
                  state = Fighter.toState(valid)
                )
              )
            )
        } yield BuiltFighter(characterId = characterId, name = name, fighter = view(valid))
    }

    /** The player's fighters, as matchmaker has them — this engine keeps none. A fighter whose state is not one this
      * engine could have built (a character made before fighters were built here) is listed without characteristics, as
      * it would be refused a bout.
      */
    def fightersOf(cognitoId: String): Either[Refusal, List[MyFighter]] =
        for {
            url <- matchmakerUrl.toRight(noMatchmaker)
            owned <- ask("listing fighters", Unknown)(matchmaker.listCharacters(url, cognitoId))
        } yield owned.map(c =>
            MyFighter(
              characterId = c.characterId,
              name = c.name,
              description = c.description,
              fighter = Fighter.fromState(c.state).map(view)
            )
        )

    /** A player changing one of their fighters' name and description. Edits are made here, as fighters are built here,
      * and matchmaker is told; it checks that the fighter is the player's, since it is what knows who owns what.
      */
    def editFighter(
        cognitoId: String,
        characterId: Long,
        newName: String,
        description: String
    ): Either[Refusal, Edited] =
        for {
            name <- named(newName)
            url <- matchmakerUrl.toRight(noMatchmaker)
            _ <- ask("editing a fighter", notYours)(
              matchmaker.editCharacter(
                matchmakerUrl = url,
                characterId = characterId,
                request = EditCharacterRequest(name = name, description = description.trim, ownerExternalId = cognitoId)
              )
            )
        } yield Edited(characterId = characterId, name = name, description = description.trim)

    /** A player giving one of their fighters to another player, named by their matchmaker nickname. Checked by
      * matchmaker as an edit is; a nickname nobody has is matchmaker's to say, and is passed on.
      */
    def giveFighter(cognitoId: String, characterId: Long, toNickname: String): Either[Refusal, Given] =
        for {
            to <- Option(toNickname.trim).filter(_.nonEmpty).toRight(Refusal.Invalid(ErrorText.boxing.giveToWhom))
            url <- matchmakerUrl.toRight(noMatchmaker)
            _ <- ask("giving a fighter away", notYours)(
              matchmaker.transferCharacter(
                matchmakerUrl = url,
                characterId = characterId,
                request = TransferCharacterRequest(to, cognitoId)
              )
            )
        } yield Given(characterId, to)

    private val notYours = Refusal.NotFound(ErrorText.boxing.noSuchFighter)

    private def named(name: String): Either[Refusal, String] =
        Option(name.trim).filter(_.nonEmpty).toRight(Refusal.Invalid(ErrorText.boxing.noName))

    private def view(f: Fighter): FighterView =
        FighterView(strength = f.strength, speed = f.speed, agility = f.agility, workrate = f.workrate, chin = f.chin)

    private val noMatchmaker = Refusal.Unavailable(ErrorText.boxing.noMatchmaker)

    /** What a 404 from matchmaker means to a player who has never signed in there: it has no player for them. */
    private val Unknown =
        Refusal.Invalid(ErrorText.boxing.unregistered)

    /** A call to matchmaker, with its failures put the way the player is told them. `notFound` is what a 404 means for
      * this call, and a 400 is passed on as matchmaker said it: those are the refusals that are the player's own.
      * Anything else matchmaker refuses is how this engine is set up, and anything else at all is matchmaker not
      * answering, which the player can retry.
      */
    private def ask[A](what: String, notFound: Refusal)(call: => A): Either[Refusal, A] =
        try Right(call)
        catch {
            case MatchmakerRefusal(404, _) => Left(notFound)
            // Matchmaker's own word on what the player asked for — a nickname nobody has, a fighter
            // that is already theirs — which is written to be read by them.
            case MatchmakerRefusal(400, reason) => Left(Refusal.Invalid(reason))
            case e: MatchmakerRefusal =>
                Log.failure(e, what)
                Left(Refusal.Unavailable(ErrorText.boxing.matchmakerRefused(e.reason)))
            case NonFatal(e) =>
                Log.failure(e, what)
                Left(Refusal.Unavailable(ErrorText.boxing.matchmakerUnreachable))
        }

    /** A player's plan for the current round. Both corners planning at the same moment is the ordinary case here, and
      * both plans must land.
      */
    def plan(
        matchId: String,
        cognitoId: String,
        allocation: Allocation
    ): Either[Refusal, MoveApplied[Bout, Corner, Plan]] =
        core.applyMove(matchId, cognitoId) { (current, corner, at) =>
            for {
                _ <- Either.cond(!current.isOver, (), Refusal.Invalid(ErrorText.boxing.over))
                // Every corner is created with a built fighter; only a bout stored before that rule could lack one.
                fighter <- corner.fighter.toRight(Refusal.Invalid(ErrorText.boxing.unbuilt))
                valid <- Allocation.validate(allocation, fighter).left.map(Refusal.Invalid(_))
                round = current.currentRound
                // No "not your turn": every round is both corners' turn. What a player may not do is
                // change a plan once it is in — whoever planned last would then always win.
                _ <- Either.cond(
                  current.planOf(corner, round).isEmpty,
                  (),
                  Refusal.Invalid(ErrorText.boxing.alreadyPlanned(round.toString))
                )
            } yield {
                // The round's start, or, in a live bout, this player's opening the board if that was later.
                val started = TurnClock.turnStart(
                  clock = current.clock,
                  participantId = corner.participantId,
                  turnStartedAt = current.roundStartedAt
                )
                val record = Plan(
                  participantId = corner.participantId,
                  round = round,
                  allocation = valid,
                  takenAt = at,
                  startedAt = started
                )
                (current.copy(plans = current.plans :+ record), record)
            }
        }

    /** The state a play page renders. `corner` is the viewer's own, absent on the public board.
      *
      * A fighter's characteristics are its own corner's to see, and nobody else's: the other corner gets an impression
      * of it instead. Each resolved round's totals are everyone's, but a corner's plans are its own, before the round
      * resolves and after: its totals less its plan would be its characteristics.
      *
      * So the other corner's plan for the round being fought is in nobody's answer: a viewer learns only that it has
      * been made.
      */
    def stateOf(m: Bout, corner: Option[Corner]): StateResponse = {
        val over = m.isOver
        def planView(a: Allocation) = PlanRequest(offense = a.offense, defense = a.defense, power = a.power)
        def numbers(e: Effective) = Numbers(offense = e.offense, defense = e.defense, power = e.power)
        // The viewer's own corner, whose characteristics they may see; nobody's on the public board.
        def yoursIs(side: Side) = corner.exists(_.side == side)
        def yours(c: Corner) = yoursIs(c.side)

        StateResponse(
          matchId = m.matchId,
          scheduledRounds = m.scheduledRounds,
          round = if (over) m.rounds.size else m.currentRound,
          hasExtraRound = (if (over) m.rounds.size else m.currentRound) > m.scheduledRounds,
          waitingFor = m.pending.map(_.side.toString),
          you = corner.map(_.side.toString),
          yourPlan =
              corner.flatMap(c => m.planOf(c, m.currentRound)).filterNot(_ => over).map(p => planView(p.allocation)),
          isCompleted = over,
          winner = m.winner.map(_.side.toString),
          isDrawn = m.isDrawn,
          method = m.method,
          corners = m.corners.map(c =>
              CornerView(
                side = c.side.toString,
                cognitoId = c.cognitoId,
                participantId = c.participantId,
                characterId = c.characterId,
                fighter = c.fighter.filter(_ => yours(c)).map(view),
                impression = if (yours(c)) Nil else c.fighter.map(_.impression).getOrElse(Nil),
                isPlanned = !over && m.planOf(c, m.currentRound).isDefined,
                points = m.points(c.side),
                nickname = c.nickname,
                fighterName = c.fighterName
              )
          ),
          rounds = m.rounds.map(r =>
              RoundView(
                number = r.number,
                // Only the viewer's own plan: the totals are everyone's, and a plan beside them would give
                // that corner's characteristics away.
                red = Option.when(yoursIs(Side.Red))(planView(r.red.allocation)),
                blue = Option.when(yoursIs(Side.Blue))(planView(r.blue.allocation)),
                redNumbers = numbers(r.redEffective),
                blueNumbers = numbers(r.blueEffective),
                decision = r.outcome.decision.label,
                winner = r.outcome.winner.map(_.toString),
                redPoints = r.outcome.score.map(_.red),
                bluePoints = r.outcome.score.map(_.blue)
              )
          ),
          clock = core.clockView(m)
        )
    }
}
