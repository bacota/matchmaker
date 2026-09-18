package com.vivi.matchmaker.service

/** Connection details for the Postgres-compatible database backing all services. */
case class DbConfig(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: Option[String]
)

object DbConfig {

    /** The five variables every deployed function is given, read the same way by each of them.
      *
      * Here rather than in one handler because there are now two functions built from the same jar — the API and the
      * bounce consumer — and they must agree about what `DB_HOST` means. A missing variable throws: a function that
      * guessed `localhost` would start, pass its health check and fail every query.
      *
      * The credentials arrive as plainly as the host does. That keeps these functions free of any AWS dependency — no
      * SDK, no extension layer, no network call before the first query — at the cost of the password being readable
      * from the function's configuration by anyone holding `lambda:GetFunction`.
      */
    def fromEnvironment(env: String => Option[String] = key => Option(System.getenv(key))): DbConfig = {
        def required(name: String): String =
            env(name).getOrElse(throw new IllegalStateException(s"$name is not set"))

        DbConfig(
          host = required("DB_HOST"),
          port = env("DB_PORT").flatMap(_.toIntOption).getOrElse(5432),
          database = required("DB_NAME"),
          user = required("DB_USER"),
          password = Some(required("DB_PASSWORD"))
        )
    }
}
