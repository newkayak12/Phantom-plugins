// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class CronTest {
  private val now = LocalDateTime.of(2026, 11, 27, 10, 0) // a Friday

  private fun problem(expression: String, dialect: CronDialect = CronDialect.SPRING): String? =
    Cron.parse(expression, dialect).problem?.let { (listOf(it.key, it.field) + it.args).joinToString(" ") }

  private fun next(expression: String, dialect: CronDialect = CronDialect.SPRING, count: Int = 3): List<String> =
    Cron.parse(expression, dialect).schedule!!.next(now, count).map { it.toString() }

  @Test
  fun `valid spring expressions`() {
    listOf(
      "0 0 * * * *", "*/10 * * * * *", "0 0 8-10 * * *", "0 0 6,19 * * *", "0 0/30 8-10 * * *", "0 0 9-17 * * MON-FRI",
      "0 0 0 25 12 ?", "0 0 0 L * *", "0 0 0 L-3 * *", "0 0 0 1W * *", "0 0 0 LW * *", "0 0 0 * * 5L", "0 0 0 ? * MON#1",
      "0 0 0 * * SUN-SAT", "0 0 0 * * 0-7", "@daily", "@HOURLY", "  0 0 * * * *  ", "0 0 0 * jan-mar *",
    ).forEach { assertNull(it, problem(it)) }
  }

  @Test
  fun `invalid spring expressions`() {
    assertEquals("cron.error.fields.spring null 5", problem("0 * * * *"))
    assertEquals("cron.error.macro null @often", problem("@often"))
    assertEquals("cron.error.value cron.field.hour 24 0 23", problem("0 0 24 * * *"))
    assertEquals("cron.error.value cron.field.month 13 1 12", problem("0 0 0 1 13 *"))
    assertEquals("cron.error.syntax cron.field.month FOO", problem("0 0 0 1 FOO *"))
    assertEquals("cron.error.step cron.field.second */0", problem("*/0 * * * * *"))
    assertEquals("cron.error.range cron.field.hour 10-8", problem("0 0 10-8 * * *"))
    assertEquals("cron.error.nth cron.field.dayOfWeek MON#6", problem("0 0 0 * * MON#6"))
    assertEquals("cron.error.offset cron.field.dayOfMonth L-31", problem("0 0 0 L-31 * *"))
    assertEquals("cron.error.syntax cron.field.dayOfMonth 0W", problem("0 0 0 0W * *"))
    assertEquals("cron.error.syntax cron.field.dayOfWeek L", problem("0 0 0 * * L"))
    assertEquals("cron.error.syntax cron.field.minute ", problem("0 1,,2 * * * *"))
  }

  @Test
  fun `quartz rules`() {
    listOf("0 0 12 * * ?", "0 15 10 ? * MON-FRI", "0 15 10 ? * 6L 2026-2030", "0 0 22-2 ? * *", "0 0 0 ? * L", "0 0 0 ? * FRI-MON")
      .forEach { assertNull(it, problem(it, CronDialect.QUARTZ)) }
    assertEquals("cron.error.question null", problem("0 0 12 * * *", CronDialect.QUARTZ))
    assertEquals("cron.error.question null", problem("0 0 12 ? * ?", CronDialect.QUARTZ))
    assertEquals("cron.error.fields.quartz null 8", problem("0 0 12 ? * * 2026 1", CronDialect.QUARTZ))
    assertEquals("cron.error.value cron.field.dayOfWeek 0 1 7", problem("0 0 12 ? * 0", CronDialect.QUARTZ))
    assertEquals("cron.error.value cron.field.year 1969 1970 2099", problem("0 0 12 ? * * 1969", CronDialect.QUARTZ))
  }

  @Test
  fun `next spring runs`() {
    assertEquals(listOf("2026-11-27T10:00:10", "2026-11-27T10:00:20", "2026-11-27T10:00:30"), next("*/10 * * * * *"))
    assertEquals(listOf("2026-11-30T09:00", "2026-12-01T09:00", "2026-12-02T09:00"), next("0 0 9 * * MON-FRI"))
    assertEquals(listOf("2026-11-29T00:00", "2026-12-06T00:00"), next("0 0 0 * * 0", count = 2))
    assertEquals(listOf("2026-11-29T00:00", "2026-12-06T00:00"), next("@weekly", count = 2))
    assertEquals(listOf("2026-11-30T00:00", "2027-01-29T00:00"), next("0 0 0 LW 1,11 *", count = 2))
    assertEquals(listOf("2026-11-16T00:00"), Cron.parse("0 0 0 15W * *", CronDialect.SPRING).schedule!!.next(now.minusDays(20), 1).map { it.toString() })
    assertEquals(listOf("2027-01-01T00:00"), next("@yearly", count = 1))
    // Spring requires both day fields to match: the 13th that is a Friday.
    assertEquals(listOf("2027-08-13T00:00"), next("0 0 0 13 * FRI", count = 1))
  }

  @Test
  fun `next quartz runs`() {
    assertEquals(listOf("2026-11-30T00:00", "2026-12-31T00:00"), next("0 0 0 L * ?", CronDialect.QUARTZ, 2))
    assertEquals(listOf("2026-12-18T12:00", "2027-01-15T12:00"), next("0 0 12 ? * 6#3", CronDialect.QUARTZ, 2))
    assertEquals(listOf("2026-11-28T00:00", "2026-11-29T00:00", "2026-11-30T00:00"), next("0 0 0 ? * FRI-MON", CronDialect.QUARTZ))
    assertEquals(listOf("2028-01-01T00:00"), next("0 0 0 1 1 ? 2028", CronDialect.QUARTZ, 1))
    assertEquals(emptyList<String>(), next("0 0 0 1 1 ? 2020", CronDialect.QUARTZ, 1))
  }
}
