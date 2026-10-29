// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Subagent

/** Applies [HookEvent]s to the [SessionStore] session of the matching tab id and remembers its session_id in [AiState]. */
object HookIngest {
  /** Returns false if no live session has [tabId] or the event belongs to another session ([isOtherSession]). */
  fun apply(tabId: String, kind: AgentKind, event: HookEvent, store: SessionStore = SessionStore.getInstance()): Boolean {
    val current = store.get(tabId) ?: return false
    if (current.kind != kind) return false
    if (isOtherSession(current.sessionId, event)) return false
    val next = store.update(tabId) { s ->
      val fromSubagent = isSubagentTool(event)
      s.copy(
        // a subagent's tool calls are its own activity; one after a permission prompt means the prompt was answered
        activity = when {
          event.status == AgentStatus.DONE || event.name == "SessionStart" -> null
          fromSubagent -> if (s.status == AgentStatus.WAITING) null else s.activity
          else -> event.activity ?: s.activity
        },
        status = event.status ?: s.status,
        unread = if (event.status == AgentStatus.DONE) true else s.unread,
        sessionId = event.sessionId ?: s.sessionId,
        firstPrompt = s.firstPrompt ?: event.prompt,
        lastPrompt = event.prompt ?: s.lastPrompt,
        lastAnswer = event.lastAnswer ?: s.lastAnswer,
        subagents = withSubagent(s.subagents, event),
      )
    } ?: return false
    next.sessionId?.let { AiState.getInstance().rememberSession(next.worktree, kind, it, next.firstPrompt, next.accountId) }
    return true
  }

  /**
   * A headless `claude -p` / `codex exec` run inside the tab inherits its hook env and posts here with its own session id.
   * Once the tab has a session, only a SessionStart that is not a fresh startup (/clear, resume) may move it to another.
   */
  private fun isOtherSession(current: String?, e: HookEvent): Boolean {
    if (current == null || e.sessionId == null || e.sessionId == current) return false
    return !(e.name == "SessionStart" && e.source != null && e.source != "startup")
  }

  /** A tool call Claude made inside a subagent: it carries the subagent's agent_id. Permission prompts still go to the session. */
  private fun isSubagentTool(e: HookEvent): Boolean =
    e.subagent == null && e.subagentId != null && e.name in setOf("PreToolUse", "PostToolUse", "PostToolUseFailure")

  private fun withSubagent(list: List<Subagent>, e: HookEvent): List<Subagent> = when (e.subagent) {
    SubagentEvent.START -> {
      val name = e.subagentName ?: "subagent"
      list + Subagent(e.subagentId ?: "$name#${list.size}", name)
    }
    SubagentEvent.STOP -> {
      val i = list.indexOfFirst { it.running && (e.subagentId == null || it.id == e.subagentId) }
      if (i < 0) list else list.toMutableList().also { it[i] = it[i].copy(running = false, activity = null) }
    }
    null -> {
      // a teammate's id carries the AgentTeams prefix; an unknown subagent's activity is dropped, not given to the session
      val i = if (isSubagentTool(e) && e.activity != null) {
        list.indexOfFirst { it.running && (it.id == e.subagentId || it.id == AgentTeams.PREFIX + e.subagentId) }
      } else -1
      if (i < 0) list else list.toMutableList().also { it[i] = it[i].copy(activity = e.activity) }
    }
  }
}
