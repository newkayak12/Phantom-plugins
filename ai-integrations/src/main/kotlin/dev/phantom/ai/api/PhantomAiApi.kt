// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/** The agent that runs a session. */
enum class AiAgent { CLAUDE, CODEX }

/** What a session is doing; [NONE] when it was launched and no hook event has arrived yet. */
enum class AiSessionStatus { WORKING, WAITING, DONE, NONE }

/** A subagent the session's agent started (Claude's Task tool, agent teams), as its hooks reported it; [running] until it stops. */
data class AiSubagent(val id: String, val name: String, val running: Boolean)

/**
 * An immutable view of one live agent session (one terminal tab). [title] is the user title, else the first prompt line,
 * else the agent name; [startedAt] is epoch milliseconds. [subagents] are the ones it started, oldest first (since 0.3.1).
 */
data class AiSession(
  val tabId: String,
  val worktreePath: String,
  val agent: AiAgent,
  val status: AiSessionStatus,
  val title: String,
  val startedAt: Long,
  val subagents: List<AiSubagent> = emptyList(),
)

/**
 * An immutable view of one worktree of a project. [title] is the name to show (user name, else branch, else folder).
 * [prNumber] is always null in 0.3.0: the API never makes a network call to look up pull requests.
 */
data class AiWorktree(
  val path: String,
  val title: String,
  val branch: String?,
  val prNumber: Int?,
  val isMain: Boolean,
)

/** Called, on an arbitrary thread, after any change of sessions, worktrees or worktree names. Re-read the state to see it. */
fun interface AiChangeListener {
  fun changed()
}

/**
 * The public API of Phantom AI Integration (`dev.phantom.ai`): the only package other plugins may use. Read-only state plus
 * a few actions. Everything outside `dev.phantom.ai.api` is internal and may change without notice.
 */
interface PhantomAiApi {
  /** Every live session of the IDE, oldest first. Cheap, no I/O. */
  fun sessions(): List<AiSession>

  /** The live sessions in the worktrees of [project]. Blocking (lists worktrees through git): call off the EDT. */
  fun sessions(project: Project): List<AiSession>

  /** The worktrees of [project]'s repositories, without pull request numbers. Blocking (runs git): call off the EDT. */
  fun worktrees(project: Project): List<AiWorktree>

  /**
   * Calls [listener] on every change: a session added, removed or updated, a worktree name changed, or (polled every 30 seconds
   * while any listener exists) a worktree added, removed or switched branch. Removed when [parent] is disposed; a no-op when it already is.
   */
  fun addChangeListener(parent: Disposable, listener: AiChangeListener)

  /** Selects the terminal tab of [tabId] in [project] and activates its tool window; false when there is no such tab.
   * Runs on the EDT: called from another thread it is queued and returns false at once. */
  fun focusSession(project: Project, tabId: String): Boolean

  /** Starts a new [agent] session in [worktreePath]; the new tab id, or null when it did not start.
   * Runs on the EDT: called from another thread it is queued and returns null at once. */
  fun startSession(project: Project, worktreePath: String, agent: AiAgent): String?

  companion object {
    /** The IDE-wide instance. */
    fun getInstance(): PhantomAiApi = PhantomAiApiImpl.instance()
  }
}
