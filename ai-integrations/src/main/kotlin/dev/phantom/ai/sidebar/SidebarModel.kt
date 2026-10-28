// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionEntry

data class WorktreeNode(val path: String, val title: String, val branch: String?, val isMain: Boolean, val sessions: List<SessionRow>, val prNumber: Int? = null)

data class RepoNode(val path: String, val name: String, val worktrees: List<WorktreeNode>)

/**
 * The sidebar tree of the current project: its repositories, each with worktrees and their sessions. With exactly one
 * repository the view shows its worktrees as top-level rows; with several, a repository row sits above each. Pure.
 */
data class SidebarModel(val repos: List<RepoNode>) {
  /** True when the worktrees are the top-level rows (one repository). */
  val flat: Boolean get() = repos.size == 1


  companion object {
    /** A remembered session of AiState that has no live tab; its tab id is "saved:<session id>". */
    fun saved(e: SessionEntry): AgentSession? {
      val kind = bestEffort { AgentKind.valueOf(e.provider.orEmpty()) }.getOrNull() ?: return null
      val wt = e.worktree ?: return null
      val id = e.sessionId ?: return null
      return AgentSession(
        "saved:$id", wt, kind, sessionId = id, accountId = e.accountId,
        userTitle = e.userTitle, firstPrompt = e.firstPrompt, startedAt = 0,
      )
    }

    fun build(
      repoRoots: List<String>,
      worktrees: WorktreeSource,
      sessions: List<AgentSession>,
      saved: List<AgentSession>,
      prTitles: PrTitles,
      userName: (String) -> String?,
      now: Long,
    ): SidebarModel {
      val liveIds = sessions.mapNotNull { it.sessionId }.toSet()
      val all = sessions + saved.filter { it.sessionId !in liveIds }
      val byWorktree = all.groupBy { norm(it.worktree) }
      fun repoNode(path: String): RepoNode {
        val trees = worktrees.list(path).ifEmpty { listOf(Worktree(path, null, true)) }
        return RepoNode(
          path,
          norm(path).substringAfterLast('/'),
          trees.map { w ->
            val pr = w.branch?.let { prTitles.infoFor(path, it) }
            WorktreeNode(
              w.path,
              WorktreeTitle.resolve(userName(w.path), pr?.title, w.branch, w.path),
              w.branch,
              w.isMain,
              byWorktree[norm(w.path)].orEmpty().sortedByDescending { it.startedAt }.map { SessionRow.of(it, now) },
              pr?.number,
            )
          },
        )
      }
      return SidebarModel(repoRoots.distinct().map(::repoNode))
    }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')
  }
}
