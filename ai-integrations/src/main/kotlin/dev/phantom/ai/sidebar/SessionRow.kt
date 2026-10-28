// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.AgentSession

/** A session line of the sidebar: title (user title > first prompt > agent name), icon keys and elapsed time. */
data class SessionRow(
  val session: AgentSession,
  val title: String,
  /** working | waiting | done | launched (no event yet). */
  val statusKey: String,
  /** claude | codex. */
  val agentIconKey: String,
  val elapsed: String?,
) {
  companion object {
    fun of(session: AgentSession, now: Long): SessionRow = SessionRow(
      session,
      title(session),
      session.status?.name?.lowercase() ?: "launched",
      session.kind.name.lowercase(),
      elapsed(session.startedAt, now),
    )

    fun title(s: AgentSession): String =
      s.userTitle?.trim()?.takeIf { it.isNotEmpty() }
        ?: s.firstPrompt?.trim()?.takeIf { it.isNotEmpty() }?.lineSequence()?.first()
        ?: s.kind.displayName

    /** 45s, 12m, 3h 5m, 2d; null when the start is unknown (<= 0). */
    fun elapsed(startedAt: Long, now: Long): String? {
      if (startedAt <= 0) return null
      val sec = ((now - startedAt) / 1000).coerceAtLeast(0)
      return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        sec < 86400 -> "${sec / 3600}h ${sec % 3600 / 60}m"
        else -> "${sec / 86400}d"
      }
    }
  }
}
