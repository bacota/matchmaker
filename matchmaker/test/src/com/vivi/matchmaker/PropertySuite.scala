package com.vivi.matchmaker

import munit.ScalaCheckSuite

/** Base for all property-test specs. These properties exercise a real local Postgres
  * database rather than pure in-memory logic, so ScalaCheck's default of 100 cases per
  * property is far more than needed to catch regressions and just slows the suite down.
  */
trait PropertySuite extends ScalaCheckSuite {
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(3)

  /** No shrinking, for the same reason.
    *
    * When a property fails, ScalaCheck's default is to keep re-running it on smaller inputs to
    * find a minimal counterexample — and every one of those runs builds a whole fixture in
    * Postgres. A failure that should take a second takes half an hour, which is long enough that
    * the failure looks like a hang. The generated values here are opaque unique ids anyway, so a
    * shrunk one says nothing a full one does not.
    */
  given [T]: org.scalacheck.Shrink[T] = org.scalacheck.Shrink.shrinkAny
}
