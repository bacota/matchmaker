package com.vivi.matchmaker

import java.sql.DriverManager
import org.flywaydb.core.Flyway

/** Applies pending Flyway migrations to the local test database before any spec runs,
  * so specs never depend on migrations having been run manually beforehand.
  */
object TestMigration {
  private val host = "localhost"
  private val port = 5432
  private val database = "matchmaker"
  private val user = "matchmaker"
  private val password = "matchmaker"

  // Runs once per forked test JVM (each spec class is forked by mill), guarded by Flyway's
  // own advisory locking so concurrently-started JVMs don't race applying the same migration.
  private lazy val migrated: Unit = {
    Flyway
      .configure()
      .dataSource(s"jdbc:postgresql://$host:$port/$database", user, password)
      .locations("classpath:db/migration")
      .load()
      .migrate()
    installTestTriggers()
    ()
  }

  /* Test-only database objects, installed once beside the migrations.
   *
   * `fail_match_update_trigger` is how `GameEngineServiceSpec` makes a write fail after the
   * engine call has succeeded. It used to be created and dropped around each run of that
   * property, which was two problems: CREATE TRIGGER takes an ACCESS EXCLUSIVE lock on `match`,
   * so it queued behind — and then blocked — every other suite's work on that table, on a
   * four-connection pool shared by all of them; and a run killed before the drop left the
   * trigger behind, so the next run failed on "trigger already exists".
   *
   * Installed permanently instead, and harmless that way: it fires only on a match whose
   * description is 'explode', which is a value no other spec uses and no application code can
   * produce by itself.
   *
   * Under an advisory lock, for the same reason Flyway holds one over the migrations: `migrated`
   * is one lazy val per JVM and mill runs several of them, so without it two `CREATE OR REPLACE
   * FUNCTION` statements reach the same pg_proc row at once and one of them fails with "tuple
   * concurrently updated" — in whichever unrelated spec happened to force this first.
   */
  private def installTestTriggers(): Unit = {
    val connection = DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", user, password)
    try {
      val statement = connection.createStatement()
      try {
        // An arbitrary constant, shared only with other JVMs running this same code.
        statement.execute(s"SELECT pg_advisory_lock($installLockKey)")
        try {
          statement.execute(
            """CREATE OR REPLACE FUNCTION fail_match_update() RETURNS trigger LANGUAGE plpgsql
               AS 'BEGIN RAISE EXCEPTION ''match update refused by test''; END'"""
          )
          statement.execute(
            """CREATE OR REPLACE TRIGGER fail_match_update_trigger BEFORE UPDATE ON match
               FOR EACH ROW WHEN (NEW.description = 'explode') EXECUTE FUNCTION fail_match_update()"""
          )
        } finally statement.execute(s"SELECT pg_advisory_unlock($installLockKey)")
      } finally statement.close()
    } finally connection.close()
  }

  private val installLockKey = 4711147114L

  def ensure(): Unit = migrated
}
