// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Subagent

/** Applies [HookEvent]s to the [SessionStore] session of the matching tab id and remembers its session_id in [AiState]. */
object HookIngest {
  /** Returns false if no live session has [tabId]. */
  fun apply(tabId: String, kind: AgentKind, event: HookEvent, store: SessionStore = SessionStore.getInstance()): Boolean {
    val current = store.get(tabId) ?: return false
    if (current.kind != kind) return false
    val next = store.update(tabId) { s ->
      s.copy(
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

  private fun withSubagent(list: List<Subagent>, e: HookEvent): List<Subagent> = when (e.subagent) {
    SubagentEvent.START -> {
      val name = e.subagentName ?: "subagent"
      list + Subagent(e.subagentId ?: "$name#${list.size}", name)
    }
    SubagentEvent.STOP -> {
      val i = list.indexOfFirst { it.running && (e.subagentId == null || it.id == e.subagentId) }
      if (i < 0) list else list.toMutableList().also { it[i] = it[i].copy(running = false) }
    }
    null -> list
  }
}
