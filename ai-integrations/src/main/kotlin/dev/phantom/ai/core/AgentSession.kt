// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

/** A subagent the session started; [running] until its stop event. */
data class Subagent(val id: String, val name: String, val running: Boolean = true)

/** One agent terminal tab. [tabId] is the key; a worktree may have many sessions. */
data class AgentSession(
  val tabId: String,
  val worktree: String,
  val kind: AgentKind,
  val status: AgentStatus? = null,
  val unread: Boolean = false,
  val sessionId: String? = null,
  val userTitle: String? = null,
  val firstPrompt: String? = null,
  val lastPrompt: String? = null,
  val lastAnswer: String? = null,
  val startedAt: Long = System.currentTimeMillis(),
  /** Id of the account the session ran under; null = System default. Resume uses this one, not the active account. */
  val accountId: String? = null,
  val subagents: List<Subagent> = emptyList(),
)
