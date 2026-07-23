package com.vivi.matchmaker

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
    ()
  }

  def ensure(): Unit = migrated
}
