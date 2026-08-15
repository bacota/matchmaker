package com.vivi.matchmaker.flyway

import java.sql.{Connection, DriverManager}
import java.util.UUID
import munit.FunSuite

/** Against the same local Postgres the repository specs use: user, database and password all
  * "matchmaker". Migrations are assumed to have run — `mill matchmaker.test` does that, and this
  * module has no cats-effect runtime to reach TestMigration through.
  *
  * Every case uses a fresh random external id, so runs do not interfere with each other or with
  * whatever else is in the table.
  */
class SeedAdminSpec extends FunSuite {

  private val connection: Connection =
    DriverManager.getConnection("jdbc:postgresql://localhost:5432/matchmaker", "matchmaker", "matchmaker")

  override def afterAll(): Unit = connection.close()

  private def externalId(): String = s"seed-admin-spec-${UUID.randomUUID()}"

  private def nickname(): String = s"admin-${UUID.randomUUID()}"

  private def isAdmin(external: String): Option[Boolean] = {
    val statement = connection.prepareStatement("SELECT is_admin FROM player WHERE external_id = ?")
    try {
      statement.setString(1, external)
      val rows = statement.executeQuery()
      if (rows.next()) Some(rows.getBoolean(1)) else None
    } finally statement.close()
  }

  private def insertPlayer(external: String, nick: String, admin: Boolean): Unit = {
    val statement = connection.prepareStatement("INSERT INTO player (nickname, is_admin, external_id) VALUES (?, ?, ?)")
    try {
      statement.setString(1, nick)
      statement.setBoolean(2, admin)
      statement.setString(3, external)
      statement.executeUpdate()
    } finally statement.close()
  }

  test("an unknown external id gets a new admin player") {
    val external = externalId()

    SeedAdmin.seed(connection, external, nickname())

    assertEquals(isAdmin(external), Some(true))
  }

  test("seeding twice leaves one row and reports that nothing changed") {
    val external = externalId()
    val nick = nickname()

    SeedAdmin.seed(connection, external, nick)
    val second = SeedAdmin.seed(connection, external, nick)

    assert(second.contains("already present"), second)
    assertEquals(isAdmin(external), Some(true))
  }

  // The case that matters on a pool that already has players: the administrator signed up through
  // hosted login before the seeder ever ran, so there is a row and it is not an admin.
  test("an existing non-admin player is granted admin rather than duplicated") {
    val external = externalId()
    val nick = nickname()
    insertPlayer(external, nick, admin = false)

    val message = SeedAdmin.seed(connection, external, "some-other-name")

    assert(message.contains("granted admin"), message)
    assertEquals(isAdmin(external), Some(true))
  }

  test("an existing player keeps the nickname it already has") {
    val external = externalId()
    val nick = nickname()
    insertPlayer(external, nick, admin = false)

    SeedAdmin.seed(connection, external, "some-other-name")

    val statement = connection.prepareStatement("SELECT nickname FROM player WHERE external_id = ?")
    try {
      statement.setString(1, external)
      val rows = statement.executeQuery()
      assert(rows.next())
      assertEquals(rows.getString(1), nick)
    } finally statement.close()
  }

  // Rather than silently renaming the administrator to something nobody would recognize.
  test("a nickname already taken by someone else fails") {
    val taken = nickname()
    insertPlayer(externalId(), taken, admin = false)

    intercept[org.postgresql.util.PSQLException](SeedAdmin.seed(connection, externalId(), taken))
  }
}
