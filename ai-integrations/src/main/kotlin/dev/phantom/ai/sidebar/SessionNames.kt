// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore

/** The user's name for an agent session: on its live tab and on its remembered entry, so a resume keeps it. */
object SessionNames {
  /** Names [session] [name] (blank = back to its first prompt or agent name). A "saved:" row has only the entry. */
  @RequiresEdt
  fun rename(session: AgentSession, name: String?) {
    val title = name?.trim()?.ifEmpty { null }
    if (!session.tabId.startsWith("saved:")) SessionStore.getInstance().update(session.tabId) { it.copy(userTitle = title) }
    val state = AiState.getInstance()
    session.sessionId?.let { id ->
      state.state.sessions.filter { it.sessionId == id && it.provider == session.kind.name }.forEach { it.userTitle = title }
    }
    state.changed()
  }
}
