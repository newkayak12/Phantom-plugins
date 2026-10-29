// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionEntry

data class WorktreeNode(
  val path: String,
  val title: String,
  val branch: String?,
  val isMain: Boolean,
  val sessions: List<SessionRow>,
  val prNumber: Int? = null,
  /** The worktree this one was made from (Orca's lineage), not its base branch; null at the top. */
  val parentPath: String? = null,
)

/** A worktree with the worktrees made from it. */
data class NestedWorktree(val node: WorktreeNode, val children: List<NestedWorktree>)

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
      parentOf: (String) -> String? = { null },
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
              parentOf(w.path),
            )
          },
        )
      }
      return SidebarModel(repoRoots.distinct().map(::repoNode))
    }

    /**
     * [worktrees] under the worktree each was made from. A parent outside the list, a parent of the main worktree, or a
     * parent that leads back to the worktree itself puts it at the top. The order of [worktrees] is kept at each level.
     */
    fun nest(worktrees: List<WorktreeNode>): List<NestedWorktree> {
      val byPath = worktrees.associateBy { norm(it.path) }
      fun parent(w: WorktreeNode): String? =
        w.parentPath?.let(::norm)?.takeIf { !w.isMain && it != norm(w.path) && it in byPath }
      fun inCycle(w: WorktreeNode): Boolean {
        var p = parent(w)
        repeat(byPath.size) {
          if (p == null) return false
          if (p == norm(w.path)) return true
          p = parent(byPath.getValue(p!!))
        }
        return false
      }
      val under = worktrees.groupBy { if (inCycle(it)) null else parent(it) }
      fun of(w: WorktreeNode): NestedWorktree = NestedWorktree(w, under[norm(w.path)].orEmpty().map(::of))
      return under[null].orEmpty().map(::of)
    }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')
  }
}
