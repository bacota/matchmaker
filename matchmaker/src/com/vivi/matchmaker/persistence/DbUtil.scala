package com.vivi.matchmaker.persistence

import java.sql.{PreparedStatement, ResultSet, Timestamp, Types}
import java.time.{Duration, Instant}
import org.postgresql.util.{PGInterval, PGobject}

private[persistence] object DbUtil {

  def setLongOpt(stmt: PreparedStatement, idx: Int, value: Option[Long]): Unit =
    value match {
      case Some(v) => stmt.setLong(idx, v)
      case None    => stmt.setNull(idx, Types.BIGINT)
    }

  def getLongOpt(rs: ResultSet, column: String): Option[Long] = {
    val v = rs.getLong(column)
    if (rs.wasNull()) None else Some(v)
  }

  def setInstant(stmt: PreparedStatement, idx: Int, value: Instant): Unit =
    stmt.setTimestamp(idx, Timestamp.from(value))

  def setInstantOpt(stmt: PreparedStatement, idx: Int, value: Option[Instant]): Unit =
    value match {
      case Some(v) => stmt.setTimestamp(idx, Timestamp.from(v))
      case None    => stmt.setNull(idx, Types.TIMESTAMP)
    }

  def getInstant(rs: ResultSet, column: String): Instant =
    rs.getTimestamp(column).toInstant

  def getInstantOpt(rs: ResultSet, column: String): Option[Instant] =
    Option(rs.getTimestamp(column)).map(_.toInstant)

  def setDurationOpt(stmt: PreparedStatement, idx: Int, value: Option[Duration]): Unit =
    value match {
      case Some(d) => stmt.setObject(idx, toPGInterval(d))
      case None    => stmt.setNull(idx, Types.OTHER)
    }

  def getDurationOpt(rs: ResultSet, column: String): Option[Duration] =
    Option(rs.getObject(column, classOf[PGInterval])).map(fromPGInterval)

  def toPGInterval(d: Duration): PGInterval =
    new PGInterval(0, 0, 0, 0, 0, d.getSeconds.toDouble)

  def fromPGInterval(pgi: PGInterval): Duration =
    Duration
      .ofDays(365L * pgi.getYears + 30L * pgi.getMonths + pgi.getDays)
      .plusHours(pgi.getHours.toLong)
      .plusMinutes(pgi.getMinutes.toLong)
      .plusMillis((pgi.getSeconds * 1000).toLong)

  def jsonb(value: String): PGobject = {
    val obj = new PGobject()
    obj.setType("jsonb")
    obj.setValue(value)
    obj
  }
}
