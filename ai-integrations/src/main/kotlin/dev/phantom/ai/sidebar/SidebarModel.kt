// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.core.SessionEntry

data class WorktreeNode(val path: String, val title: String, val branch: String?, val isMain: Boolean, val sessions: List<SessionRow>, val prNumber: Int? = null)

data class RepoNode(val repo: Repo, val name: String, val worktrees: List<WorktreeNode>)

data class GroupNode(val group: Group, val groups: List<GroupNode>, val repos: List<RepoNode>)

/** The sidebar tree: nested groups (with their repos), then the ungrouped repos. Pure; no IDE access. */
data class SidebarModel(val groups: List<GroupNode>, val ungrouped: List<RepoNode>) {
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
      tree: GroupTree,
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
      fun repoNode(r: Repo): RepoNode {
        val trees = worktrees.list(r.path).ifEmpty { listOf(Worktree(r.path, null, true)) }
        return RepoNode(
          r,
          norm(r.path).substringAfterLast('/'),
          trees.map { w ->
            val pr = w.branch?.let { prTitles.infoFor(r.path, it) }
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
      fun groupNode(g: Group): GroupNode =
        GroupNode(g, tree.childrenOf(g.id).map(::groupNode), tree.reposOf(g.id).map(::repoNode))
      return SidebarModel(tree.childrenOf(null).map(::groupNode), tree.reposOf(null).map(::repoNode))
    }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')
  }
}
