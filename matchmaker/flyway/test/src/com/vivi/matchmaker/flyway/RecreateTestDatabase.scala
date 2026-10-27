package com.vivi.matchmaker.flyway

import java.sql.DriverManager

/** Drops the local test database and creates it again, empty, for `Migrate` to bring up to date.
  *
  * `matchmaker.test`'s `migrated` task runs this before every test run. The specs never clean up after themselves —
  * every case makes its own players, games and tournaments under fresh random names — so a database kept between runs
  * only grows, and anything that reads a whole table reads every row every earlier run left: tens of thousands of games
  * after a few weeks, and a test of `GameRepo.list` that took nine seconds.
  *
  * Its own database rather than `matchmaker`, which the local stack runs on and which nothing here may wipe. Everything
  * is fixed — localhost, the `matchmaker` role, `matchmaker_test` — and nothing is read from the environment, so no
  * setting can point this at a database that matters. Test code, so it is never part of anything deployed.
  */
object RecreateTestDatabase {
    val database = "matchmaker_test"

    def main(args: Array[String]): Unit = {
        // Connected to `postgres`, since a database cannot be dropped from a session on it.
        val connection =
            DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", "matchmaker", "matchmaker")
        try {
            val statement = connection.createStatement()
            try {
                // FORCE ends any session still on it: a psql left open, or a test JVM killed mid-run.
                statement.execute(s"DROP DATABASE IF EXISTS $database WITH (FORCE)")
                statement.execute(s"CREATE DATABASE $database")
            } finally statement.close()
        } finally connection.close()
        println(s"Recreated $database")
    }
}
