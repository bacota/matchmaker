package com.vivi.matchmaker.flyway

import java.sql.{Connection, DriverManager}

/** Ensures the administrator's player row exists for an already-created Cognito user.
  *
  * Terraform creates the user; it cannot create the row, because a player lives in a database
  * inside the VPC and terraform has no resource for inserting one. So the two halves are joined
  * here, by `sub`: deploy.sh applies terraform, reads the `admin_external_id` output, and runs
  * this. It lives in the flyway module rather than the service layer because it needs exactly what
  * that module already has — a JDBC driver and nothing else — and because it runs in the same
  * place, from an operator's machine with a route into the VPC.
  *
  * Idempotent, and run on every deploy: an existing row is granted admin rather than duplicated,
  * and a run that changes nothing says so.
  *
  * Configuration is read from environment variables:
  *   FLYWAY_URL        jdbc:postgresql://host:port/database (default: jdbc:postgresql://localhost:5432/matchmaker)
  *   FLYWAY_USER       database user (default: matchmaker)
  *   FLYWAY_PASSWORD   database password (default: empty)
  *   ADMIN_EXTERNAL_ID the Cognito `sub` of the admin user (required)
  *   ADMIN_NICKNAME    nickname for a newly created row (default: admin)
  *
  * The same variables as Migrate, so deploy.sh configures both steps identically.
  */
object SeedAdmin {

  def main(args: Array[String]): Unit = {
    val url = sys.env.getOrElse("FLYWAY_URL", "jdbc:postgresql://localhost:5432/matchmaker")
    val user = sys.env.getOrElse("FLYWAY_USER", "matchmaker")
    val password = sys.env.getOrElse("FLYWAY_PASSWORD", "")
    val nickname = sys.env.getOrElse("ADMIN_NICKNAME", "admin").trim

    val externalId = sys.env.getOrElse("ADMIN_EXTERNAL_ID", "").trim
    if (externalId.isEmpty) {
      Console.err.println("ADMIN_EXTERNAL_ID is not set; expected the admin Cognito user's sub")
      sys.exit(2)
    }

    val connection = DriverManager.getConnection(url, user, password)
    try println(seed(connection, externalId, nickname))
    finally connection.close()
  }

  /** Returns a description of what it did, for the deploy log. Separated from `main` so it can be
    * driven against a test database without going through environment variables.
    */
  def seed(connection: Connection, externalId: String, nickname: String): String =
    existingNickname(connection, externalId) match {
      case Some(existing) => grantAdmin(connection, externalId, existing)
      case None           => insert(connection, externalId, nickname)
    }

  private def existingNickname(connection: Connection, externalId: String): Option[String] = {
    val statement = connection.prepareStatement("SELECT nickname FROM player WHERE external_id = ?")
    try {
      statement.setString(1, externalId)
      val rows = statement.executeQuery()
      if (rows.next()) Some(rows.getString(1)) else None
    } finally statement.close()
  }

  /* The row is not replaced when it already exists: by then it is a real player with matches and
   * acceptances hanging off its id, and the nickname is theirs to have changed. Only the one bit
   * this is responsible for is asserted.
   */
  private def grantAdmin(connection: Connection, externalId: String, nickname: String): String = {
    val statement = connection.prepareStatement(
      "UPDATE player SET is_admin = TRUE, update_date = now() WHERE external_id = ? AND NOT is_admin"
    )
    try {
      statement.setString(1, externalId)
      if (statement.executeUpdate() == 0) s"admin player '$nickname' already present"
      else s"granted admin to existing player '$nickname'"
    } finally statement.close()
  }

  /* ON CONFLICT (external_id) rather than a plain insert, because two deploys could race, and
   * because the row may have been created by a normal registration between the read above and
   * here. A conflict on *nickname* is a different matter and is deliberately left to fail: it
   * means someone else already answers to this name, and quietly picking another one would leave
   * an administrator nobody could identify.
   */
  private def insert(connection: Connection, externalId: String, nickname: String): String = {
    val statement = connection.prepareStatement(
      """INSERT INTO player (nickname, is_admin, external_id) VALUES (?, TRUE, ?)
        |ON CONFLICT (external_id) DO UPDATE SET is_admin = TRUE, update_date = now()""".stripMargin
    )
    try {
      statement.setString(1, nickname)
      statement.setString(2, externalId)
      statement.executeUpdate()
      s"created admin player '$nickname'"
    } finally statement.close()
  }
}
