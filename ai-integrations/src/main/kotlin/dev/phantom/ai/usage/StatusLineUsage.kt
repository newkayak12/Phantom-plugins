// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonParser

/** Rate limits from a /statusline/claude payload; newest of statusLine vs. poll wins. */
object StatusLineUsage {
  /** Null if the payload has no rate_limits windows. */
  fun parse(payload: String, nowMs: Long): ClaudeUsage? {
    val rl = bestEffort { JsonParser.parseString(payload) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
      ?.get("rate_limits")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val session = parseWindow(rl.get("five_hour"), SESSION_MINUTES)
    val weekly = parseWindow(rl.get("seven_day"), WEEK_MINUTES)
    if (session == null && weekly == null) return null
    return ClaudeUsage(session, weekly, null, nowMs)
  }

  /** The newer by updatedAtMs; a tie goes to the poll (it carries the Fable window). A statusLine result lacking Fable inherits the poll's. */
  fun merge(statusLine: ClaudeUsage?, poll: ClaudeUsage?): ClaudeUsage? {
    if (statusLine == null) return poll
    if (poll == null) return statusLine
    return if (statusLine.updatedAtMs > poll.updatedAtMs) statusLine.copy(fableWeekly = statusLine.fableWeekly ?: poll.fableWeekly) else poll
  }
}
