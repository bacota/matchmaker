package com.vivi.matchmaker.model.tournament

import upickle.default.{ReadWriter, macroRW, readwriter}

/** How designs and tournament states are written as JSON: a design as the tournament's creator sends it and the
  * `tournament.design` column holds it, a state as the `tournament.state` column holds it and the UI draws it.
  *
  * Here rather than in `api/Json` because the server's services read and write the state column, and `Json` is not
  * compiled into them. Every given is named, and `Json` re-exports the two a client needs, so that importing both
  * cannot make a codec ambiguous. Defaults are left out when written, as everywhere upickle writes, so a stored design
  * holds only what differs from them.
  */
object TournamentCodecs {

    /** `{"until": 1}`, `{"times": 2}` or `"stopped"`: which kind of repetition, by which field is there. */
    given repetitionRW: ReadWriter[Repetition] = readwriter[ujson.Value].bimap(
      {
          case Repetition.Fixed(k)          => ujson.Obj("times" -> k)
          case Repetition.UntilRemaining(c) => ujson.Obj("until" -> c)
          case Repetition.UntilStopped      => ujson.Str("stopped")
      },
      {
          case ujson.Str("stopped")                      => Repetition.UntilStopped
          case o: ujson.Obj if o.value.contains("times") => Repetition.Fixed(o("times").num.toInt)
          case o: ujson.Obj if o.value.contains("until") => Repetition.UntilRemaining(o("until").num.toInt)
          case other => throw new IllegalArgumentException(s"not a repetition: $other")
      }
    )

    given seedPolicyRW: ReadWriter[SeedPolicy] = readwriter[String].bimap(_.code, SeedPolicy.fromCode)

    // A design nests through its stages, so the two codecs refer to each other; lazy, so neither is
    // needed before both exist.
    given stageRW: ReadWriter[StageDesign] = macroRW
    given designRW: ReadWriter[TournamentDesign] = macroRW

    given seatPlanRW: ReadWriter[SeatPlan] = macroRW
    given plannedMatchRW: ReadWriter[PlannedMatch] = macroRW
    given placingRW: ReadWriter[Placing] = macroRW
    given poolMatchRW: ReadWriter[PoolMatch] = macroRW
    given poolMemberRW: ReadWriter[PoolMember] = macroRW
    given memberRW: ReadWriter[Member] = macroRW

    /** As a list of pairs, since its keys are numbers. */
    given rankingRW: ReadWriter[Map[Long, Int]] =
        readwriter[List[(Long, Int)]].bimap(_.toList.sortBy(_._2), _.toMap)

    given poolRW: ReadWriter[Pool] = macroRW
    given nodeRW: ReadWriter[Node] = macroRW
    given runRW: ReadWriter[StageRun] = macroRW
    given stateRW: ReadWriter[TournamentState] = macroRW
}
