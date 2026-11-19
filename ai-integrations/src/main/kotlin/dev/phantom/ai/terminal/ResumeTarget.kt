// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import dev.phantom.ai.core.AgentKind
import java.nio.file.Path

/** What Resume runs: the hook session_id first, then Claude's earliest-created unclaimed .jsonl, else nothing (Resume is disabled). */
sealed interface ResumeTarget {
  data class Id(val id: String, val fromHook: Boolean) : ResumeTarget
  data object None : ResumeTarget

  companion object {
    /** Files created up to this long before the session started still count (clock and file system granularity). */
    const val START_SLACK_MS = 2_000L

    /**
     * Pure: [files] is only asked when the hook gave no id and the agent is Claude. The fallback is the earliest-created file (not the newest-modified) that
     * no other session [claimed] and, with [startedAtMs], that was created no earlier than the session started.
     */
    fun resolve(
      kind: AgentKind,
      hookSessionId: String?,
      files: () -> List<SessionJsonl.JsonlFile>,
      claimed: Set<String>,
      startedAtMs: Long?,
    ): ResumeTarget {
      hookSessionId?.takeIf { it.isNotBlank() }?.let { return Id(it, fromHook = true) }
      if (kind != AgentKind.CLAUDE) return None
      return files()
        .filter {
          it.id !in claimed &&
            (startedAtMs == null || (it.createdMs >= startedAtMs - START_SLACK_MS && it.modifiedMs >= startedAtMs - START_SLACK_MS))
        }
        // the earliest-created file is the session's own: a later one belongs to a session that started after it
        .minWithOrNull(compareBy<SessionJsonl.JsonlFile> { it.createdMs }.thenBy { it.modifiedMs }.thenBy { it.id })
        ?.let { Id(it.id, fromHook = false) } ?: None
    }

    /** Pure: [jsonlIds] (newest first) is only asked when the hook gave no id and the agent is Claude. */
    fun resolve(kind: AgentKind, hookSessionId: String?, jsonlIds: () -> List<String>): ResumeTarget {
      hookSessionId?.takeIf { it.isNotBlank() }?.let { return Id(it, fromHook = true) }
      if (kind != AgentKind.CLAUDE) return None
      return jsonlIds().firstOrNull()?.let { Id(it, fromHook = false) } ?: None
    }

    fun forSession(
      kind: AgentKind,
      hookSessionId: String?,
      worktree: String,
      claudeConfigDir: Path,
      claimed: Set<String> = emptySet(),
      startedAtMs: Long? = null,
    ): ResumeTarget =
      resolve(kind, hookSessionId, { SessionJsonl.sessions(claudeConfigDir, worktree) }, claimed, startedAtMs)
  }
}
