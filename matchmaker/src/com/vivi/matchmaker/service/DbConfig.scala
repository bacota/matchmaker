package com.vivi.matchmaker.service

/** Connection details for the Postgres-compatible database backing all services. */
case class DbConfig(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: Option[String]
)
