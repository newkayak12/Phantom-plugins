// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.stringhelpers.cron

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.BitSet

/** The two cron dialects found in Java code. They differ in day-of-week numbers, `?` and the optional year. */
enum class CronDialect { SPRING, QUARTZ }

/** Why an expression does not parse: a bundle key, the bundle key of the field it is about, and the arguments after it. */
class CronProblem(val key: String, val field: String?, val args: List<String>) {
  /** A wrong field count or an unknown macro: the check the Spring plugin already makes on `@Scheduled`. */
  val shape: Boolean get() = key == "cron.error.fields.spring" || key == "cron.error.macro"
}

class CronResult(val schedule: CronSchedule?, val problem: CronProblem?)

/** A parsed expression; [next] lists its runs. Only local date-times: the caller picks the time zone. */
class CronSchedule internal constructor(
  private val seconds: BitSet,
  private val minutes: BitSet,
  private val hours: BitSet,
  private val months: BitSet,
  private val years: BitSet?,
  private val dayOfMonth: (LocalDate) -> Boolean,
  private val dayOfWeek: (LocalDate) -> Boolean,
) {
  /** The first [count] runs strictly after [after], looking at most [horizonYears] ahead. */
  fun next(after: LocalDateTime, count: Int, horizonYears: Long = HORIZON_YEARS): List<LocalDateTime> {
    val runs = ArrayList<LocalDateTime>(count)
    val start = after.withNano(0).plusSeconds(1)
    var date = start.toLocalDate()
    var from: LocalTime? = start.toLocalTime()
    val end = date.plusYears(horizonYears)
    while (runs.size < count && !date.isAfter(end)) {
      if (matches(date)) {
        var time = from?.let(::firstTime)
        while (time != null && runs.size < count) {
          runs += date.atTime(time)
          time = if (time == LocalTime.MAX.withNano(0)) null else firstTime(time.plusSeconds(1))
        }
      }
      date = date.plusDays(1)
      from = LocalTime.MIDNIGHT
    }
    return runs
  }

  private fun matches(date: LocalDate): Boolean =
    months[date.monthValue] && (years == null || years[date.year]) && dayOfMonth(date) && dayOfWeek(date)

  private fun firstTime(from: LocalTime): LocalTime? {
    var h = hours.nextSetBit(from.hour)
    while (h >= 0) {
      var m = minutes.nextSetBit(if (h == from.hour) from.minute else 0)
      while (m >= 0) {
        val s = seconds.nextSetBit(if (h == from.hour && m == from.minute) from.second else 0)
        if (s >= 0) return LocalTime.of(h, m, s)
        m = minutes.nextSetBit(m + 1)
      }
      h = hours.nextSetBit(h + 1)
    }
    return null
  }

  companion object {
    const val HORIZON_YEARS: Long = 30
  }
}

/**
 * Parses cron expressions the way Spring's `CronExpression.parse` and Quartz's `CronExpression` do, so the editor can
 * report what either would reject at runtime and list the next runs. Written for this plugin; neither library is bundled.
 */
object Cron {
  private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
  private val SPRING_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
  private val QUARTZ_DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

  /** Spring's macros (`CronExpression.MACROS`), matched ignoring case. */
  private val MACROS = mapOf(
    "@yearly" to "0 0 0 1 1 *",
    "@annually" to "0 0 0 1 1 *",
    "@monthly" to "0 0 0 1 * *",
    "@weekly" to "0 0 0 * * 0",
    "@daily" to "0 0 0 * * *",
    "@midnight" to "0 0 0 * * *",
    "@hourly" to "0 0 * * * *",
  )

  private class Field(val key: String, val min: Int, val max: Int, val names: List<String>? = null)

  private val SECOND = Field("cron.field.second", 0, 59)
  private val MINUTE = Field("cron.field.minute", 0, 59)
  private val HOUR = Field("cron.field.hour", 0, 23)
  private val DAY_OF_MONTH = Field("cron.field.dayOfMonth", 1, 31)
  private val MONTH = Field("cron.field.month", 1, 12, MONTHS)
  private val SPRING_DAY_OF_WEEK = Field("cron.field.dayOfWeek", 0, 7, SPRING_DAYS)
  private val QUARTZ_DAY_OF_WEEK = Field("cron.field.dayOfWeek", 1, 7, QUARTZ_DAYS)
  private val YEAR = Field("cron.field.year", 1970, 2099)

  private class Invalid(val problem: CronProblem) : RuntimeException(null, null, false, false)

  fun parse(expression: String, dialect: CronDialect): CronResult = try {
    CronResult(build(expression.trim(), dialect), null)
  } catch (e: Invalid) {
    CronResult(null, e.problem)
  }

  private fun build(expression: String, dialect: CronDialect): CronSchedule {
    val text = if (dialect == CronDialect.SPRING && expression.startsWith("@")) {
      MACROS[expression.lowercase()] ?: fail("cron.error.macro", null, expression)
    } else {
      expression
    }
    val f = text.uppercase().split(Regex("\\s+"))
    checkFieldCount(f.size, dialect)
    val quartz = dialect == CronDialect.QUARTZ
    if (quartz && (f[3] == "?") == (f[5] == "?")) fail("cron.error.question", null)
    return CronSchedule(
      seconds = set(f[0], SECOND, dialect),
      minutes = set(f[1], MINUTE, dialect),
      hours = set(f[2], HOUR, dialect),
      months = set(f[4], MONTH, dialect),
      years = if (f.size == 7) set(f[6], YEAR, dialect) else null,
      dayOfMonth = dayOfMonth(f[3], dialect),
      dayOfWeek = dayOfWeek(f[5], dialect),
    )
  }

  private fun checkFieldCount(count: Int, dialect: CronDialect) {
    when (dialect) {
      CronDialect.SPRING -> if (count != 6) fail("cron.error.fields.spring", null, count.toString())
      CronDialect.QUARTZ -> if (count != 6 && count != 7) fail("cron.error.fields.quartz", null, count.toString())
    }
  }

  private fun fail(key: String, field: String?, vararg args: String): Nothing = throw Invalid(CronProblem(key, field, args.toList()))

  /** A list of values, ranges and steps (`*`, `5`, `1-5`, `*\/15`, `10/5`, `MON-FRI`) as a set of the field's values. */
  private fun set(text: String, field: Field, dialect: CronDialect): BitSet {
    val bits = BitSet()
    for (part in text.split(',')) {
      val slash = part.split('/')
      if (slash.size > 2 || part.isEmpty()) fail("cron.error.syntax", field.key, part)
      val step = if (slash.size == 2) slash[1].toIntOrNull()?.takeIf { it > 0 } ?: fail("cron.error.step", field.key, part) else 1
      val (from, to) = bounds(slash[0], field, dialect, part, slash.size == 2)
      val span = field.max - field.min + 1
      var v = from
      // Quartz accepts a range that wraps around (FRI-MON, 22-2); Spring rejects it in bounds().
      val last = if (to < from) to + span else to
      while (v <= last) {
        bits.set(if (v > field.max) v - span else v)
        v += step
      }
    }
    return bits
  }

  private fun bounds(text: String, field: Field, dialect: CronDialect, part: String, stepped: Boolean): Pair<Int, Int> {
    if (text == "*") return field.min to field.max
    val dash = text.indexOf('-')
    if (dash < 0) {
      val v = value(text, field, part)
      return v to if (stepped) field.max else v
    }
    var from = value(text.substring(0, dash), field, part)
    val to = value(text.substring(dash + 1), field, part)
    // Spring's BitsCronField: "If used as a minimum in a range, Sunday means 0 (not 7)".
    if (dialect == CronDialect.SPRING && field === SPRING_DAY_OF_WEEK && from == 7) from = 0
    if (from > to && dialect == CronDialect.SPRING) fail("cron.error.range", field.key, part)
    return from to to
  }

  private fun value(text: String, field: Field, part: String): Int {
    val named = field.names?.indexOf(text)?.takeIf { it >= 0 }?.plus(1)
    val v = named ?: text.toIntOrNull() ?: fail("cron.error.syntax", field.key, part)
    if (v !in field.min..field.max) fail("cron.error.value", field.key, part, field.min.toString(), field.max.toString())
    return v
  }

  private fun dayOfMonth(text: String, dialect: CronDialect): (LocalDate) -> Boolean {
    val key = DAY_OF_MONTH.key
    return when {
      text == "?" -> { _ -> true }
      text == "L" -> { d -> d.dayOfMonth == d.lengthOfMonth() }
      text == "LW" -> { d -> d == weekdayNear(d.with(TemporalAdjusters.lastDayOfMonth())) }
      text.startsWith("L-") -> {
        val offset = text.substring(2).toIntOrNull()?.takeIf { it in 0..30 } ?: fail("cron.error.offset", key, text)
        return { d -> d.dayOfMonth == d.lengthOfMonth() - offset }
      }
      text.endsWith("W") -> {
        val day = text.dropLast(1).toIntOrNull()?.takeIf { it in 1..31 } ?: fail("cron.error.syntax", key, text)
        return { d -> day <= d.lengthOfMonth() && d == weekdayNear(d.withDayOfMonth(day)) }
      }
      else -> set(text, DAY_OF_MONTH, dialect).let { days -> { d -> days[d.dayOfMonth] } }
    }
  }

  /** The weekday Quartz and Spring pick for `W`: Saturday moves to Friday and Sunday to Monday, within the month. */
  private fun weekdayNear(d: LocalDate): LocalDate = when (d.dayOfWeek) {
    DayOfWeek.SATURDAY -> if (d.dayOfMonth == 1) d.plusDays(2) else d.minusDays(1)
    DayOfWeek.SUNDAY -> if (d.dayOfMonth == d.lengthOfMonth()) d.minusDays(2) else d.plusDays(1)
    else -> d
  }

  private fun dayOfWeek(text: String, dialect: CronDialect): (LocalDate) -> Boolean {
    val field = if (dialect == CronDialect.SPRING) SPRING_DAY_OF_WEEK else QUARTZ_DAY_OF_WEEK
    if (text == "?") return { _ -> true }
    if (text == "L" && dialect == CronDialect.QUARTZ) return { d -> d.dayOfWeek == DayOfWeek.SATURDAY }
    if (text.length > 1 && text.endsWith("L")) {
      val day = day(value(text.dropLast(1), field, text), dialect)
      return { d -> d.dayOfWeek == day && d.dayOfMonth + 7 > d.lengthOfMonth() }
    }
    val hash = text.indexOf('#')
    if (hash >= 0) {
      val day = day(value(text.substring(0, hash), field, text), dialect)
      val nth = text.substring(hash + 1).toIntOrNull()?.takeIf { it in 1..5 } ?: fail("cron.error.nth", field.key, text)
      return { d -> d.dayOfWeek == day && (d.dayOfMonth - 1) / 7 + 1 == nth }
    }
    val values = set(text, field, dialect)
    val days = (field.min..field.max).filter { values[it] }.map { day(it, dialect) }.toSet()
    return { d -> d.dayOfWeek in days }
  }

  private fun day(value: Int, dialect: CronDialect): DayOfWeek = when (dialect) {
    CronDialect.SPRING -> if (value == 0) DayOfWeek.SUNDAY else DayOfWeek.of(value)
    CronDialect.QUARTZ -> if (value == 1) DayOfWeek.SUNDAY else DayOfWeek.of(value - 1)
  }
}
