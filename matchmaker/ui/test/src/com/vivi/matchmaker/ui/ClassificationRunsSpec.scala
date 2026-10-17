package com.vivi.matchmaker.ui

import munit.FunSuite
import com.vivi.matchmaker.model._
import com.vivi.matchmaker.model.tournament.TournamentDesign

/** The create form's drop-down of formats: in display order, a header over each run of consecutive formats of one
  * classification (`Tournaments.classificationRuns`).
  */
class ClassificationRunsSpec extends FunSuite {

    private def model(name: String, order: Int, classification: Option[String]): TournamentModel =
        TournamentModel(
          tournamentModelId = TournamentModelId(name.toLowerCase),
          version = 1,
          name = name,
          description = "",
          classification = classification,
          poolSize = None,
          hasLateEntries = false,
          design = TournamentDesign(Nil),
          displayOrder = order
        )

    private def runs(models: TournamentModel*): List[(Option[String], List[String])] =
        Tournaments.classificationRuns(models).map((c, inRun) => c -> inRun.map(_.name).toList).toList

    test("consecutive models of one classification share a header, in display order") {
        assertEquals(
          runs(
            model(name = "Ladder", order = 30, classification = Some("Ladder")),
            model(name = "Single", order = 10, classification = Some("Elimination")),
            model(name = "Double", order = 20, classification = Some("Elimination"))
          ),
          List(Some("Elimination") -> List("Single", "Double"), Some("Ladder") -> List("Ladder"))
        )
    }

    test("a classification split by another's model heads each of its runs, and the order is kept") {
        assertEquals(
          runs(
            model(name = "Single", order = 10, classification = Some("Elimination")),
            model(name = "Heats", order = 20, classification = Some("Pools")),
            model(name = "Double", order = 30, classification = Some("Elimination"))
          ),
          List(
            Some("Elimination") -> List("Single"),
            Some("Pools") -> List("Heats"),
            Some("Elimination") -> List("Double")
          )
        )
    }

    test("models of the same order are taken by name, and one without a classification is a run of its own") {
        assertEquals(
          runs(
            model(name = "beta", order = 10, classification = Some("A")),
            model(name = "Alpha", order = 10, classification = Some("A")),
            model(name = "Loose", order = 20, classification = None)
          ),
          List(Some("A") -> List("Alpha", "beta"), None -> List("Loose"))
        )
    }
}
