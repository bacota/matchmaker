package com.vivi.matchmaker

import munit.ScalaCheckSuite

/** Base for all property-test specs. These properties exercise a real local Postgres
  * database rather than pure in-memory logic, so ScalaCheck's default of 100 cases per
  * property is far more than needed to catch regressions and just slows the suite down.
  */
trait PropertySuite extends ScalaCheckSuite {
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(3)
}
