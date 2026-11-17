// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

data class UsageWindow(val usedPercent: Double, val windowMinutes: Int, val resetsAtMs: Long?)

data class ClaudeUsage(
  val session: UsageWindow?,
  val weekly: UsageWindow?,
  val fableWeekly: UsageWindow?,
  val updatedAtMs: Long,
)

internal const val EPOCH_MS_THRESHOLD = 10_000_000_000.0

/** Epoch seconds or ms, numeric or ISO-8601 string; null if unparseable. */
internal fun parseResetTimestamp(e: JsonElement?): Long? {
  if (e == null || !e.isJsonPrimitive) return null
  val p = e.asJsonPrimitive
  if (p.isNumber) return numberToMs(p.asDouble)
  val s = p.asString.trim()
  if (s.isEmpty()) return null
  s.toDoubleOrNull()?.let { return numberToMs(it) }
  return bestEffort { Instant.parse(s).toEpochMilli() }.getOrNull()
}

private fun numberToMs(d: Double): Long? = if (d.isFinite()) (if (d > EPOCH_MS_THRESHOLD) d else d * 1000).toLong() else null

/** Window from {utilization|used_percentage, resets_at}; percent clamped to 0..100. */
internal fun parseWindow(e: JsonElement?, minutes: Int): UsageWindow? {
  val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
  val pct = number(o, "utilization") ?: number(o, "used_percentage") ?: return null
  return UsageWindow(pct.coerceIn(0.0, 100.0), minutes, parseResetTimestamp(o.get("resets_at")))
}

internal fun number(o: JsonObject, key: String): Double? =
  o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }

internal const val SESSION_MINUTES = 300
internal const val WEEK_MINUTES = 10080
