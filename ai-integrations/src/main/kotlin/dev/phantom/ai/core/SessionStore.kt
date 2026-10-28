// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.messages.Topic
import java.util.concurrent.ConcurrentHashMap

fun interface SessionListener {
  /** [session] is the new state, or null when [tabId] was removed. */
  fun changed(tabId: String, session: AgentSession?)
}

/** Live agent sessions, many per worktree, keyed by tab id. Changes are published on [TOPIC]. */
@Service(Service.Level.APP)
class SessionStore {
  private val sessions = ConcurrentHashMap<String, AgentSession>()

  fun all(): List<AgentSession> = sessions.values.sortedBy { it.startedAt }

  fun get(tabId: String): AgentSession? = sessions[tabId]

  fun forWorktree(worktree: String): List<AgentSession> = all().filter { it.worktree == worktree }

  fun put(session: AgentSession) {
    sessions[session.tabId] = session
    publish(session.tabId, session)
  }

  /** Applies [change] to the session of [tabId]; null if there is none. */
  fun update(tabId: String, change: (AgentSession) -> AgentSession): AgentSession? {
    val next = sessions.computeIfPresent(tabId) { _, old -> change(old) } ?: return null
    publish(tabId, next)
    return next
  }

  fun remove(tabId: String) {
    if (sessions.remove(tabId) != null) publish(tabId, null)
  }

  private fun publish(tabId: String, session: AgentSession?) {
    val app = ApplicationManager.getApplication() ?: return
    app.messageBus.syncPublisher(TOPIC).changed(tabId, session)
  }

  companion object {
    @JvmField
    val TOPIC: Topic<SessionListener> = Topic.create("Phantom AI sessions", SessionListener::class.java)

    fun getInstance(): SessionStore = ApplicationManager.getApplication().getService(SessionStore::class.java)
  }
}
