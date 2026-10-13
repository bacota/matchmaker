package com.vivi.matchmaker.model.tournament
package standard

/** A tournament model as the code defines it: what a site admin's sync writes to `tournament_model`, for any game to
  * offer. Each is an object in a file of its own in this package, which spells out its whole design and shares nothing
  * with another's, so that changing one cannot change another without its version.
  *
  * `id` names it for good -- a tournament made from it refers to it by that -- so it is never reused for a different
  * format. `version` is bumped whenever anything else in its file changes, which is what tells the sync to rewrite the
  * stored model; tournaments already made from it keep the stages they copied. A model the code stops defining is
  * removed by the sync, or only deactivated while a tournament made from it has entrants.
  *
  * The designs are built for a game of two players. Where a format's pool size follows the game, a stage leaves it
  * unset, to be the tournament's default pool size -- the game's R unless whoever creates it says otherwise -- and a
  * game whose R does not suit a format's fixed sizes is one whose admins leave it unoffered.
  */
abstract class StandardModel(
    val id: String,
    val version: Int,
    val name: String,
    val description: String,
    val classification: String,
    val poolSize: Option[Int] = None,
    val lateEntries: Boolean = false
) {
    def design: TournamentDesign

    /** The model as it is stored. */
    def model: com.vivi.matchmaker.model.TournamentModel =
        com.vivi.matchmaker.model.TournamentModel(
          com.vivi.matchmaker.model.TournamentModelId(id),
          version,
          name,
          description,
          Some(classification),
          poolSize,
          lateEntries,
          design
        )
}

object StandardModels {

    /** Every model the code defines: what the sync makes the stored models. A new model's object is added here. */
    val all: List[StandardModel] = List(
      SingleElimination,
      SingleEliminationConsolation,
      ModifiedDoubleElimination,
      DoubleElimination,
      BrazilianRepechage,
      Heats,
      WorldCup,
      Fencing,
      Ncaa,
      Ladder
    )

    def find(id: String): Option[StandardModel] = all.find(_.id == id)
}
