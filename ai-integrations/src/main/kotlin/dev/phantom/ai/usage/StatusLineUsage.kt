// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonParser

/** Rate limits from a /statusline/claude payload, the only source of Claude usage. */
object StatusLineUsage {
  /** Null if the payload has no rate_limits windows. */
  fun parse(payload: String, nowMs: Long): ClaudeUsage? {
    val rl = bestEffort { JsonParser.parseString(payload) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
      ?.get("rate_limits")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val session = parseWindow(rl.get("five_hour"), SESSION_MINUTES)
    val weekly = parseWindow(rl.get("seven_day"), WEEK_MINUTES)
    if (session == null && weekly == null) return null
    return ClaudeUsage(session, weekly, nowMs)
  }
}
