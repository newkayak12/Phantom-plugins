// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The grid formats and parses `java.sql` dates and times as UTC wall clock (DataGrip reads them with a UTC calendar),
 * while JDBC gives them in the JVM's zone: shown as they are, a TIMESTAMP was off by the zone's offset and a DATE east
 * of UTC showed the day before. Values going into the grid model are turned into the grid's form ([toGrid]), values
 * taken out of it back into the JDBC form ([fromGrid]). A `LocalDateTime` (MySQL's DATETIME) goes in as a TIMESTAMP, so
 * it shows, edits and opens the calendar like one; it comes out as a `Timestamp`, which JDBC writes to the same column.
 */
object DbGridTime {
  private const val DAY_MS = 86_400_000L

  fun toGrid(value: Any?): Any? = when (value) {
    is Timestamp -> Timestamp.from(value.toLocalDateTime().toInstant(ZoneOffset.UTC))
    is LocalDateTime -> Timestamp.from(value.toInstant(ZoneOffset.UTC))
    is java.sql.Date -> java.sql.Date(value.toLocalDate().toEpochDay() * DAY_MS)
    is Time -> Time(Instant.ofEpochMilli(value.time).atZone(ZoneId.systemDefault()).toLocalTime().toNanoOfDay() / 1_000_000)
    else -> value
  }

  fun fromGrid(value: Any?): Any? = when (value) {
    is Timestamp -> Timestamp.valueOf(LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC))
    is java.sql.Date -> java.sql.Date.valueOf(LocalDate.ofEpochDay(Math.floorDiv(value.time, DAY_MS)))
    is Time -> {
      val time = LocalTime.ofNanoOfDay(Math.floorMod(value.time, DAY_MS) * 1_000_000)
      Time(LocalDate.EPOCH.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
    else -> value
  }

  fun toGrid(row: Array<Any?>): Array<Any?> = Array(row.size) { toGrid(row[it]) }
}
