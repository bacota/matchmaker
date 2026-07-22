package com.vivi.matchmaker.flyway

import org.flywaydb.core.Flyway

/** Runs pending Flyway migrations against a Postgres-compatible database.
  *
  * Configuration is read from environment variables:
  *   FLYWAY_URL      jdbc:postgresql://host:port/database (default: jdbc:postgresql://localhost:5432/matchmaker)
  *   FLYWAY_USER     database user (default: matchmaker)
  *   FLYWAY_PASSWORD database password (default: empty)
  *   MIGRATION_DIR   filesystem path to migration scripts
  *                   (default: matchmaker/resources/db/migration)
  */
object Migrate {
  def main(args: Array[String]): Unit = {
    val url = sys.env.getOrElse("FLYWAY_URL", "jdbc:postgresql://localhost:5432/matchmaker")
    val user = sys.env.getOrElse("FLYWAY_USER", "matchmaker")
    val password = sys.env.getOrElse("FLYWAY_PASSWORD", "")
    val migrationDir = sys.env.getOrElse("MIGRATION_DIR", "matchmaker/resources/db/migration")

    val flyway = Flyway.configure()
      .dataSource(url, user, password)
      .locations(s"filesystem:$migrationDir")
      .load()

    val result = flyway.migrate()
    println(s"Applied ${result.migrationsExecuted} migration(s); schema now at version ${result.targetSchemaVersion}")
  }
}
