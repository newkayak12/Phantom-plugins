// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.initializr

/**
 * A Spring Boot version as start.spring.io writes it: `4.1.1`, `4.2.0-M2`, `4.2.0-RC1`, `4.1.2-SNAPSHOT`,
 * and the older `2.7.0.RELEASE` / `2.7.1.BUILD-SNAPSHOT`. Pre-releases sort below the release:
 * milestone < release candidate < snapshot < release.
 */
internal data class BootVersion(val numbers: List<Int>, val qualifierRank: Int, val qualifierNumber: Int) : Comparable<BootVersion> {
  override fun compareTo(other: BootVersion): Int {
    for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
      val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
      if (c != 0) return c
    }
    return compareValuesBy(this, other, BootVersion::qualifierRank, BootVersion::qualifierNumber)
  }

  companion object {
    private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:[.-]([A-Za-z-]+?)-?(\d*))?$""")

    fun parse(text: String): BootVersion? {
      val m = PATTERN.matchEntire(text.trim()) ?: return null
      val numbers = listOfNotNull(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null }).map(String::toInt)
      val rank = when (m.groupValues[4].uppercase()) {
        "M" -> 1
        "RC" -> 2
        "SNAPSHOT", "BUILD-SNAPSHOT" -> 3
        "", "RELEASE" -> 4
        else -> return null
      }
      return BootVersion(numbers, rank, m.groupValues[5].toIntOrNull() ?: 0)
    }
  }
}

/**
 * A dependency's `versionRange` from the Initializr metadata, in Maven range syntax: `[4.0.0,4.3.0-M1)`, `(3.5.0,4.0.0]`,
 * or a bare version meaning "this version or later".
 */
internal class BootVersionRange private constructor(
  private val lower: BootVersion?, private val lowerInclusive: Boolean,
  private val upper: BootVersion?, private val upperInclusive: Boolean,
  private val text: String,
) {
  operator fun contains(version: BootVersion): Boolean {
    if (lower != null) {
      val c = version.compareTo(lower)
      if (c < 0 || c == 0 && !lowerInclusive) return false
    }
    if (upper != null) {
      val c = version.compareTo(upper)
      if (c > 0 || c == 0 && !upperInclusive) return false
    }
    return true
  }

  /** The range as a reader would say it, e.g. `>= 4.0.0 and < 4.3.0-M1`. */
  fun describe(): String {
    val lowerText = lower?.let { (if (lowerInclusive) ">= " else "> ") + bound(0) }
    val upperText = upper?.let { (if (upperInclusive) "<= " else "< ") + bound(1) }
    return listOfNotNull(lowerText, upperText).joinToString(" and ")
  }

  private fun bound(index: Int): String =
    if (text.first() == '[' || text.first() == '(') text.substring(1, text.length - 1).split(',')[index].trim() else text.trim()

  companion object {
    fun parse(text: String): BootVersionRange? {
      val range = text.trim()
      if (range.isEmpty()) return null
      if (range.first() != '[' && range.first() != '(') {
        return BootVersionRange(BootVersion.parse(range) ?: return null, true, null, false, range)
      }
      val parts = range.substring(1, range.length - 1).split(',')
      if (parts.size != 2) return null
      val lower = parts[0].trim().ifEmpty { null }?.let { BootVersion.parse(it) ?: return null }
      val upper = parts[1].trim().ifEmpty { null }?.let { BootVersion.parse(it) ?: return null }
      return BootVersionRange(lower, range.first() == '[', upper, range.last() == ']', range)
    }
  }
}
